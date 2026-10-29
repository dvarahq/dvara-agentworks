package com.dvarahq.agentworks.controlplane.deployment;

import com.dvarahq.agentworks.controlplane.evidence.ReleaseAdmissionPolicy;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleConflictException;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleJson;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.Idempotent;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.ReleaseLifecycleCommand;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.ReleaseLifecycleResult;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.ReleaseLifecycleView;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.ReleaseRevocationView;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleNotFoundException;
import com.dvarahq.agentworks.domain.lifecycle.state.LifecycleRows.DecisionRow;
import com.dvarahq.agentworks.domain.lifecycle.state.LifecycleRows.ReleaseLifecycleRow;
import com.dvarahq.agentworks.domain.lifecycle.state.LifecycleRows.ReleaseRevocationRow;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import com.dvarahq.agentworks.storage.TenantTransactionTemplate;
import com.dvarahq.agentworks.storage.lifecycle.DatabaseClock;
import com.dvarahq.agentworks.storage.lifecycle.IdempotencyDigests;
import com.dvarahq.agentworks.storage.lifecycle.JdbcIdempotencyRepository;
import com.dvarahq.agentworks.storage.lifecycle.JdbcLifecycleRepository;
import com.dvarahq.agentworks.storage.lifecycle.LifecycleRetry;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** PostgreSQL-authoritative release suspension/revocation and cohort propagation (issue #79). */
public final class ReleaseLifecycleService implements ReleaseAdmissionPolicy {
    private static final Set<String> SUBJECT_KINDS = Set.of("RELEASE", "SIGNING_KEY", "DEPENDENCY");
    private final TenantTransactionTemplate tenants;
    private final JdbcLifecycleRepository lifecycle;
    private final DatabaseClock clock;
    private final LifecycleIdempotency idempotency;

    public ReleaseLifecycleService(TenantTransactionTemplate tenants, JdbcLifecycleRepository lifecycle,
            JdbcIdempotencyRepository idempotency, DatabaseClock clock, LifecycleRetry retry, Duration replayWindow,
            IdempotencyDigests digests) {
        this.tenants = Objects.requireNonNull(tenants, "tenants");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.idempotency = new LifecycleIdempotency(tenants, idempotency, clock, retry, replayWindow, digests);
    }

    public ReleaseLifecycleView get(WorkspaceId workspace, UUID releaseId) {
        return tenants.execute(workspace, () -> view(lifecycle.findReleaseLifecycle(releaseId)
                .orElseThrow(LifecycleNotFoundException::new)));
    }

    public List<ReleaseRevocationView> revocations(WorkspaceId workspace, UUID releaseId, String subjectKind,
            Instant beforeOccurredAt, UUID beforeId, int limit) {
        return tenants.execute(workspace, () -> lifecycle.listReleaseRevocations(releaseId, subjectKind,
                beforeOccurredAt, beforeId, limit).stream().map(ReleaseLifecycleService::view).toList());
    }

    public Idempotent<ReleaseLifecycleResult> transition(WorkspaceId workspace, String action,
            ReleaseLifecycleCommand command) {
        String normalized = Objects.requireNonNull(action, "action").toUpperCase(Locale.ROOT);
        validate(command, normalized);
        var fingerprint = new ReleaseLifecycleCommand(command.releaseId(), command.expectedVersion(), null,
                command.actor(), command.reason(), command.subjectKind(), command.subjectIdentity(),
                command.evidenceDigest());
        return idempotency.execute(workspace, "release." + action.toLowerCase(Locale.ROOT), command.idempotencyKey(),
                fingerprint, ReleaseLifecycleResult.class, ReleaseLifecycleResult::decisionId,
                result -> result.lifecycle().state(), () -> mutate(workspace, normalized, command));
    }

    /** Called inside promotion/rollback transactions. */
    @Override public void requireAdmissible(com.dvarahq.agentworks.domain.lifecycle.state.LifecycleRows.ReleaseRow release) {
        ReleaseLifecycleRow state = lifecycle.findReleaseLifecycle(release.id())
                .orElseThrow(LifecycleNotFoundException::new);
        if (!"AVAILABLE".equals(state.state())) {
            throw new LifecycleConflictException("RELEASE_" + state.state(),
                    "the release is not available for admission");
        }
    }

    private ReleaseLifecycleResult mutate(WorkspaceId workspace, String action, ReleaseLifecycleCommand command) {
        lifecycle.findRelease(command.releaseId()).orElseThrow(LifecycleNotFoundException::new);
        ReleaseLifecycleRow current = lifecycle.lockReleaseLifecycle(command.releaseId())
                .orElseThrow(LifecycleNotFoundException::new);
        if (current.lifecycleVersion() != command.expectedVersion()) throw LifecycleConflictException.stale("release");
        String next = switch (action) {
            case "SUSPEND" -> "SUSPENDED";
            case "RESUME" -> "AVAILABLE";
            case "REVOKE" -> "REVOKED";
            case "RETIRE" -> "RETIRED";
            default -> throw new LifecycleConflictException("INVALID_RELEASE_ACTION", "release action is invalid");
        };
        legal(current.state(), next);
        Instant now = clock.now();
        UUID decisionId = UUID.randomUUID();
        ReleaseRevocationRow revocation = null;
        if ("REVOKED".equals(next)) {
            revocation = new ReleaseRevocationRow(UUID.randomUUID(), command.releaseId(), command.subjectKind(),
                    command.subjectIdentity(), command.reason(), command.evidenceDigest(), command.actor(), now);
            lifecycle.insertReleaseRevocation(revocation);
        }
        lifecycle.insertDecision(new DecisionRow(decisionId, workspace.value(), "RELEASE_" + action, "ALLOWED",
                null, null, null, command.releaseId(), null, null, null, "[]", command.actor(), command.reason(),
                null, null, now));
        List<UUID> affected = propagate(command.releaseId(), action, command.actor(), command.reason(), decisionId, now);
        if ("RETIRED".equals(next) && !affected.isEmpty()) {
            throw new LifecycleConflictException("RELEASE_RETIREMENT_BLOCKED",
                    "every cohort must be retired before the release can retire");
        }
        if (!lifecycle.transitionRelease(current, next, command.reason(), command.actor(),
                revocation == null ? null : revocation.id(), now)) throw LifecycleConflictException.stale("release");
        lifecycle.appendOutbox("RELEASE_" + next, decisionId, LifecycleJson.write(Map.of(
                "workspaceId", workspace.value(), "releaseId", command.releaseId(), "state", next,
                "lifecycleVersion", current.lifecycleVersion() + 1)), now);
        ReleaseLifecycleView result = new ReleaseLifecycleView(command.releaseId(), next,
                current.lifecycleVersion() + 1, command.reason(), command.actor(), now);
        return new ReleaseLifecycleResult(decisionId, result, revocation == null ? null : view(revocation), affected);
    }

    private List<UUID> propagate(UUID releaseId, String action, String actor, String reason, UUID decisionId,
            Instant now) {
        List<UUID> affected = new ArrayList<>();
        for (UUID deploymentId : lifecycle.deploymentIdsForRelease(releaseId)) {
            var deployment = lifecycle.lockDeployment(deploymentId).orElseThrow();
            for (var cohort : lifecycle.lockCohortsForRelease(deploymentId, releaseId)) {
                String target = switch (action) {
                    case "SUSPEND" -> "ROUTABLE".equals(cohort.state()) ? "SUSPENDED" : null;
                    case "RESUME" -> "SUSPENDED".equals(cohort.state()) ? "ROUTABLE" : null;
                    case "REVOKE" -> Set.of("PENDING", "ROUTABLE", "SUSPENDED", "DRAINING", "QUIESCENT")
                            .contains(cohort.state()) ? "REVOKED" : null;
                    case "RETIRE" -> "RETIRED".equals(cohort.state()) ? null : "BLOCKED";
                    default -> null;
                };
                if ("BLOCKED".equals(target)) { affected.add(cohort.id()); continue; }
                if (target == null) continue;
                if (!lifecycle.transitionCohort(cohort, target, "RELEASE_" + action, decisionId, actor, reason, now,
                        null)) throw LifecycleConflictException.stale("cohort");
                affected.add(cohort.id());
                lifecycle.appendOutbox("COHORT_TRANSITIONED", decisionId, LifecycleJson.write(Map.of(
                        "workspaceId", cohort.workspaceId(), "deploymentId", deployment.id(), "releaseId", releaseId,
                        "cohortId", cohort.id(), "revisionId", cohort.revisionId(), "state", target,
                        "transitionVersion", cohort.transitionVersion() + 1)), now);
            }
        }
        return List.copyOf(affected);
    }

    private static void validate(ReleaseLifecycleCommand command, String action) {
        Objects.requireNonNull(command.releaseId(), "releaseId");
        if (command.expectedVersion() < 1) throw new LifecycleConflictException("INVALID_RELEASE_VERSION", "release version must be positive");
        if (command.actor() == null || command.actor().isBlank() || command.actor().length() > 128)
            throw new LifecycleConflictException("ACTOR_REQUIRED", "a bounded actor is required");
        if (command.reason() == null || command.reason().isBlank() || command.reason().length() > 1024)
            throw new LifecycleConflictException("REASON_REQUIRED", "a bounded reason is required");
        if ("REVOKE".equals(action)) {
            if (!SUBJECT_KINDS.contains(command.subjectKind())) throw new LifecycleConflictException("INVALID_REVOCATION_SUBJECT", "revocation subject kind is invalid");
            if (command.subjectIdentity() == null || command.subjectIdentity().isBlank() || command.subjectIdentity().length() > 512)
                throw new LifecycleConflictException("INVALID_REVOCATION_SUBJECT", "revocation subject identity is required");
            if (command.evidenceDigest() != null && !command.evidenceDigest().matches("sha256:[0-9a-f]{64}"))
                throw new LifecycleConflictException("INVALID_EVIDENCE_DIGEST", "evidence digest must be SHA-256");
        }
    }

    private static void legal(String current, String next) {
        boolean permitted = switch (current) {
            case "PENDING" -> Set.of("AVAILABLE", "SUSPENDED", "REVOKED").contains(next);
            case "AVAILABLE" -> Set.of("SUSPENDED", "REVOKED", "RETIRED").contains(next);
            case "SUSPENDED" -> Set.of("AVAILABLE", "REVOKED", "RETIRED").contains(next);
            default -> false;
        };
        if (!permitted) throw new LifecycleConflictException("INVALID_RELEASE_TRANSITION",
                "release cannot transition from " + current + " to " + next);
    }

    private static ReleaseLifecycleView view(ReleaseLifecycleRow row) {
        return new ReleaseLifecycleView(row.releaseId(), row.state(), row.lifecycleVersion(), row.reason(),
                row.actor(), row.changedAt());
    }
    private static ReleaseRevocationView view(ReleaseRevocationRow row) {
        return new ReleaseRevocationView(row.id(), row.releaseId(), row.subjectKind(), row.subjectIdentity(),
                row.reason(), row.evidenceDigest(), row.actor(), row.occurredAt());
    }
}
