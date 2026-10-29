package com.dvarahq.agentworks.controlplane.deployment;

import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleConflictException;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleJson;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleNotFoundException;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleValidation;
import com.dvarahq.agentworks.controlplane.evidence.ReleaseAdmissionPolicy;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.Blocker;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.CohortCommand;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.CohortResult;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.DeploymentConfiguration;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.GateResult;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.GateType;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.ContinuityResult;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.OverrideRequest;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.PromotionEvidence;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.Idempotent;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.PromotionCommand;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.PromotionGate;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.PromotionPolicy;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.PromotionResult;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.QuiescenceReport;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.RetirementResult;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.RollbackCommand;
import com.dvarahq.agentworks.controlplane.mutation.ControlPlaneMutationAuthority;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import com.dvarahq.agentworks.storage.TenantTransactionTemplate;
import com.dvarahq.agentworks.storage.lifecycle.DatabaseClock;
import com.dvarahq.agentworks.storage.lifecycle.Digests;
import com.dvarahq.agentworks.storage.lifecycle.IdempotencyDigests;
import com.dvarahq.agentworks.storage.lifecycle.JdbcIdempotencyRepository;
import com.dvarahq.agentworks.storage.lifecycle.JdbcLifecycleRepository;
import com.dvarahq.agentworks.storage.lifecycle.LifecycleRetry;
import com.dvarahq.agentworks.domain.lifecycle.state.LifecycleRows.CohortRow;
import com.dvarahq.agentworks.domain.lifecycle.state.LifecycleRows.DecisionRow;
import com.dvarahq.agentworks.domain.lifecycle.state.LifecycleRows.DeploymentRow;
import com.dvarahq.agentworks.domain.lifecycle.state.LifecycleRows.EnvironmentRow;
import com.dvarahq.agentworks.domain.lifecycle.state.LifecycleRows.QuiescenceBlocker;
import com.dvarahq.agentworks.domain.lifecycle.state.LifecycleRows.ReleaseRow;
import com.dvarahq.agentworks.domain.lifecycle.state.LifecycleRows.RevisionRow;
import com.dvarahq.agentworks.domain.lifecycle.state.LifecycleRows.RoutingGenerationRow;
import com.dvarahq.agentworks.domain.lifecycle.state.LifecycleRows.RoutingTargetRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Control Plane authority for desired deployment state (ADR-005, ADR-007, ADR-008, ADR-027).
 *
 * <p>Every command runs as one PostgreSQL transaction that acquires rows in a fixed order: idempotency namespace,
 * idempotency key, environment, deployment, then cohorts by revision generation. Deadlock, serialization, and lock-timeout failures
 * retry the whole transaction a bounded number of times; exhaustion surfaces a typed failure with nothing committed.
 * Timestamps come from the database. Promotion atomically records the decision, immutable revision, new cohort,
 * immutable routing generation and target, prior-cohort drain transitions, desired-state advance, and outbox event.
 */
public final class DeploymentLifecycleService implements ControlPlaneMutationAuthority {
    private static final Logger log = LoggerFactory.getLogger(DeploymentLifecycleService.class);
    private static final List<String> PLACEMENTS = List.of("SHARED", "ISOLATED", "DEDICATED");
    private static final Pattern DEPLOYMENT_NAME = Pattern.compile("^[a-z0-9]([-a-z0-9]*[a-z0-9])?$");
    private static final Pattern CREDENTIAL_NAME = Pattern.compile("^[A-Za-z][A-Za-z0-9_.-]{0,63}$");
    private static final Pattern CREDENTIAL_REFERENCE = Pattern.compile("^[a-z][a-z0-9+.-]{1,31}://[A-Za-z0-9._~/:@-]{1,240}$");
    private static final Pattern POLICY_IDENTITY = Pattern.compile("^[a-z0-9][a-z0-9._-]{0,127}@[0-9A-Za-z._+-]{1,64}$");
    private static final String SINGLE_TARGET = "SINGLE_TARGET";

    private final TenantTransactionTemplate tenants;
    private final JdbcLifecycleRepository lifecycle;
    private final DatabaseClock clock;
    private final LifecycleIdempotency idempotency;
    private final ReleaseAdmissionPolicy releaseAdmission;
    private final PromotionEvidenceProvider evidenceProvider;
    private final DependencyContinuityProvider continuityProvider;

    public DeploymentLifecycleService(TenantTransactionTemplate tenants, JdbcLifecycleRepository lifecycle,
            JdbcIdempotencyRepository idempotency, DatabaseClock clock, LifecycleRetry retry, Duration replayWindow,
            IdempotencyDigests digests) {
        this(tenants, lifecycle, idempotency, clock, retry, replayWindow, digests,
                ReleaseAdmissionPolicy.permissive(), PromotionEvidenceProvider.NONE, DependencyContinuityProvider.MANIFEST);
    }

    public DeploymentLifecycleService(TenantTransactionTemplate tenants, JdbcLifecycleRepository lifecycle,
            JdbcIdempotencyRepository idempotency, DatabaseClock clock, LifecycleRetry retry, Duration replayWindow,
            IdempotencyDigests digests, ReleaseAdmissionPolicy releaseAdmission) {
        this(tenants, lifecycle, idempotency, clock, retry, replayWindow, digests, releaseAdmission,
                PromotionEvidenceProvider.NONE, DependencyContinuityProvider.MANIFEST);
    }

    public DeploymentLifecycleService(TenantTransactionTemplate tenants, JdbcLifecycleRepository lifecycle,
            JdbcIdempotencyRepository idempotency, DatabaseClock clock, LifecycleRetry retry, Duration replayWindow,
            IdempotencyDigests digests, PromotionEvidenceProvider evidenceProvider) {
        this(tenants, lifecycle, idempotency, clock, retry, replayWindow, digests,
                ReleaseAdmissionPolicy.permissive(), evidenceProvider, DependencyContinuityProvider.MANIFEST);
    }

    public DeploymentLifecycleService(TenantTransactionTemplate tenants, JdbcLifecycleRepository lifecycle,
            JdbcIdempotencyRepository idempotency, DatabaseClock clock, LifecycleRetry retry, Duration replayWindow,
            IdempotencyDigests digests, ReleaseAdmissionPolicy releaseAdmission,
            PromotionEvidenceProvider evidenceProvider) {
        this(tenants, lifecycle, idempotency, clock, retry, replayWindow, digests, releaseAdmission, evidenceProvider,
                DependencyContinuityProvider.MANIFEST);
    }

    public DeploymentLifecycleService(TenantTransactionTemplate tenants, JdbcLifecycleRepository lifecycle,
            JdbcIdempotencyRepository idempotency, DatabaseClock clock, LifecycleRetry retry, Duration replayWindow,
            IdempotencyDigests digests, ReleaseAdmissionPolicy releaseAdmission,
            PromotionEvidenceProvider evidenceProvider, DependencyContinuityProvider continuityProvider) {
        this.tenants = Objects.requireNonNull(tenants, "tenants");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.idempotency = new LifecycleIdempotency(tenants, idempotency, clock, retry, replayWindow, digests);
        this.releaseAdmission = Objects.requireNonNull(releaseAdmission, "releaseAdmission");
        this.evidenceProvider = Objects.requireNonNull(evidenceProvider, "evidenceProvider");
        this.continuityProvider = Objects.requireNonNull(continuityProvider, "continuityProvider");
    }

    /** Promotes a release into an environment, creating the logical deployment on first promotion. */
    public Idempotent<PromotionResult> promote(WorkspaceId workspace, PromotionCommand command) {
        validateActor(command.actor());
        validate(command);
        return idempotency.execute(workspace, "deployment.promote", command.idempotencyKey(),
                withoutKey(command), PromotionResult.class, PromotionResult::decisionId, PromotionResult::outcome,
                () -> promoteInTransaction(workspace, command));
    }

    /** Selects a previously deployed release for new roots through a fresh revision and cohort. */
    public Idempotent<PromotionResult> rollback(WorkspaceId workspace, RollbackCommand command) {
        validateActor(command.actor());
        Objects.requireNonNull(command.deploymentId(), "deploymentId");
        if (command.releaseId() == null) throw LifecycleValidation.invalid("/releaseId", "is required");
        validateReferences(command.evidence());
        return idempotency.execute(workspace, "deployment.rollback", command.idempotencyKey(),
                withoutKey(command),
                PromotionResult.class, PromotionResult::decisionId, PromotionResult::outcome,
                () -> rollbackInTransaction(workspace, command));
    }

    public Idempotent<CohortResult> suspend(WorkspaceId workspace, CohortCommand command) {
        return cohortCommand(workspace, "cohort.suspend", command, "SUSPEND", (deployment, cohort) -> {
            if (!"ROUTABLE".equals(cohort.state())) throw invalidTransition(cohort, "SUSPENDED");
            return "SUSPENDED";
        });
    }

    /** Resumes a suspended cohort as the same immutable revision, only while it is still the current route. */
    public Idempotent<CohortResult> resume(WorkspaceId workspace, CohortCommand command) {
        return cohortCommand(workspace, "cohort.resume", command, "RESUME", (deployment, cohort) -> {
            if (!"SUSPENDED".equals(cohort.state())) throw invalidTransition(cohort, "ROUTABLE");
            if (!cohort.revisionId().equals(deployment.currentRevisionId())) {
                throw new LifecycleConflictException("NOT_CURRENT_ROUTE",
                        "only the current deployment revision can return to ROUTABLE");
            }
            return "ROUTABLE";
        });
    }

    /** Terminal security/operational fence; it never retires or claims to roll back unfinished work. */
    public Idempotent<CohortResult> revoke(WorkspaceId workspace, CohortCommand command) {
        return cohortCommand(workspace, "cohort.revoke", command, "REVOKE", (deployment, cohort) -> {
            if (List.of("RETIRED", "REVOKED").contains(cohort.state())) throw invalidTransition(cohort, "REVOKED");
            return "REVOKED";
        });
    }

    /**
     * Retires a draining or quiescent cohort after a transactional recheck under the cohort fence. Outstanding
     * durable work returns a typed {@code BLOCKED} result (and invalidates a stale quiescence report) instead of
     * retiring.
     */
    public Idempotent<RetirementResult> retire(WorkspaceId workspace, CohortCommand command) {
        validateActor(command.actor());
        Objects.requireNonNull(command.cohortId(), "cohortId");
        return idempotency.execute(workspace, "cohort.retire", command.idempotencyKey(), withoutKey(command),
                RetirementResult.class, RetirementResult::cohortId, RetirementResult::outcome,
                () -> retireInTransaction(workspace, command));
    }

    /** Rebuilds the quiescence observation from authoritative durable state. */
    public QuiescenceReport observeQuiescence(WorkspaceId workspace, UUID cohortId, String actor) {
        validateActor(actor);
        return idempotency.execute(workspace, "cohort.observe", null, null, QuiescenceReport.class,
                QuiescenceReport::cohortId, QuiescenceReport::state, () -> {
                    CohortRow unlocked = lifecycle.findCohort(cohortId).orElseThrow(LifecycleNotFoundException::new);
                    lifecycle.lockDeployment(unlocked.deploymentId()).orElseThrow(LifecycleNotFoundException::new);
                    CohortRow cohort = lifecycle.lockCohort(cohortId).orElseThrow(LifecycleNotFoundException::new);
                    Instant now = clock.now();
                    List<Blocker> blockers = blockers(cohortId);
                    String next = cohort.state();
                    if ("DRAINING".equals(cohort.state()) && blockers.isEmpty()) next = "QUIESCENT";
                    if ("QUIESCENT".equals(cohort.state()) && !blockers.isEmpty()) next = "DRAINING";
                    if (!next.equals(cohort.state())) {
                        transition(cohort, next, "QUIESCENCE_OBSERVED", null, actor, null, now, null);
                        cohort = lifecycle.findCohort(cohortId).orElseThrow();
                    }
                    return report(cohort, blockers, now);
                }).value();
    }

    public int compactIdempotency(WorkspaceId workspace) {
        return idempotency.compact(workspace);
    }

    public boolean retireIdempotencyNamespace(WorkspaceId workspace, String namespace) {
        return idempotency.retireNamespace(workspace, namespace);
    }

    public int purgeIdempotencyNamespace(WorkspaceId workspace, String namespace) {
        return idempotency.purgeRetiredNamespace(workspace, namespace);
    }

    public DeploymentRow deployment(WorkspaceId workspace, UUID id) {
        return tenants.execute(workspace, () -> lifecycle.findDeployment(id).orElseThrow(LifecycleNotFoundException::new));
    }

    public List<DeploymentRow> deployments(WorkspaceId workspace) {
        return tenants.execute(workspace, lifecycle::listDeployments);
    }

    /** One keyset page of deployments by creation time, then id, optionally of one environment (issue #35). */
    public List<DeploymentRow> deployments(WorkspaceId workspace, UUID environmentId, java.time.Instant afterCreatedAt,
            UUID afterId, int limit) {
        return tenants.execute(workspace, () -> lifecycle.listDeployments(environmentId, afterCreatedAt, afterId, limit));
    }

    public List<RevisionRow> revisions(WorkspaceId workspace, UUID deploymentId) {
        return tenants.execute(workspace, () -> {
            lifecycle.findDeployment(deploymentId).orElseThrow(LifecycleNotFoundException::new);
            return lifecycle.listRevisions(deploymentId);
        });
    }

    public List<CohortRow> cohorts(WorkspaceId workspace, UUID deploymentId) {
        return tenants.execute(workspace, () -> {
            lifecycle.findDeployment(deploymentId).orElseThrow(LifecycleNotFoundException::new);
            return lifecycle.listCohorts(deploymentId);
        });
    }

    public CohortRow cohort(WorkspaceId workspace, UUID cohortId) {
        return tenants.execute(workspace, () -> lifecycle.findCohort(cohortId).orElseThrow(LifecycleNotFoundException::new));
    }

    public List<RoutingGenerationRow> routingGenerations(WorkspaceId workspace, UUID deploymentId) {
        return tenants.execute(workspace, () -> {
            lifecycle.findDeployment(deploymentId).orElseThrow(LifecycleNotFoundException::new);
            return lifecycle.listRoutingGenerations(deploymentId);
        });
    }

    public List<RoutingTargetRow> routingTargets(WorkspaceId workspace, UUID generationId) {
        return tenants.execute(workspace, () -> {
            lifecycle.findRoutingGeneration(generationId).orElseThrow(LifecycleNotFoundException::new);
            return lifecycle.routingTargets(generationId);
        });
    }

    public DecisionRow decision(WorkspaceId workspace, UUID id) {
        return tenants.execute(workspace, () -> lifecycle.findDecision(id).orElseThrow(LifecycleNotFoundException::new));
    }

    private PromotionResult promoteInTransaction(WorkspaceId workspace, PromotionCommand command) {
        EnvironmentRow environment = lifecycle.lockEnvironmentForPromotion(command.environmentId())
                .orElseThrow(LifecycleNotFoundException::new);
        ReleaseRow release = lifecycle.findRelease(command.releaseId()).orElseThrow(LifecycleNotFoundException::new);
        releaseAdmission.requireAdmissible(release);
        DeploymentRow deployment = lifecycle.lockDeploymentByName(environment.id(), command.deploymentName()).orElse(null);
        long currentGeneration = deployment == null ? 0 : deployment.generation();
        if (command.expectedGeneration() != null && command.expectedGeneration() != currentGeneration) {
            throw LifecycleConflictException.stale("deployment");
        }
        if (deployment != null && !deployment.definitionId().equals(release.definitionId())) {
            throw LifecycleValidation.invalid("/releaseId", "must be a release of the deployed agent definition");
        }
        Instant now = clock.now();
        PromotionPolicy policy = LifecycleJson.read(environment.policyJson(), PromotionPolicy.class);
        List<PromotionEvidence> evidence = canonicalEvidence(
                evidenceProvider.resolve(workspace, environment.id(), environment.policyDigest(), release.id(),
                        command.evidence()));
        validateEvidenceSelection(command, evidence);
        List<GateResult> gates = PromotionPolicyEvaluator.evaluate(policy, release.id(), command.actor(), evidence,
                name -> lifecycle.findEnvironmentByName(name).map(prior ->
                        lifecycle.releaseDeployedInEnvironment(release.id(), prior.id())).orElse(false));
        String priorManifest = deployment == null ? null
                : lifecycle.findRelease(deployment.desiredReleaseId()).orElseThrow().manifestJson();
        ContinuityResult continuity = PromotionPolicyEvaluator.continuity(policy, priorManifest,
                release.manifestJson(), continuityProvider.capture(workspace, release.manifestJson()));
        if ("FAILED".equals(continuity.state())) {
            gates = new ArrayList<>(gates);
            gates.add(new GateResult(new PromotionGate(GateType.RELEASE_EVIDENCE, null), "FAILED",
                    "dependency continuity policy refuses " + continuity.drift().size() + " changed dependency classes"));
            gates = List.copyOf(gates);
        }
        gates = applyOverride(policy, command.override(), command.overrideAuthorized(), gates);
        String inputDigest = decisionInputDigest(environment, release, command, gates, evidence, continuity);
        UUID decisionId = UUID.randomUUID();
        if (gates.stream().anyMatch(gate -> "FAILED".equals(gate.result()))) {
            lifecycle.insertDecision(new DecisionRow(decisionId, workspace.value(), "PROMOTION", LifecycleModel.DENIED,
                    environment.id(), release.definitionId(), deployment == null ? null : deployment.id(), release.id(),
                    null, environment.policyVersion(), environment.policyDigest(), decisionEvidence(inputDigest, gates,
                            evidence, command.override(), continuity),
                    actor(command.actor()), command.reason(), null, null, now));
            appendDeniedDrift(workspace, decisionId, deployment == null ? null : deployment.id(), release.id(),
                    continuity, now);
            log.info("promotion denied workspace={} environment={} release={} decision={}", workspace.value(),
                    environment.id(), release.id(), decisionId);
            return new PromotionResult(decisionId, "PROMOTION", LifecycleModel.DENIED, gates,
                    deployment == null ? null : deployment.id(), deployment == null ? null : currentGeneration,
                    null, null, null, List.of(), inputDigest, command.override(), continuity);
        }
        if (deployment == null) {
            UUID id = UUID.randomUUID();
            lifecycle.insertDeployment(new DeploymentRow(id, workspace.value(), environment.id(), release.definitionId(),
                    command.deploymentName(), release.id(), 0, null, null, now));
            deployment = lifecycle.lockDeployment(id).orElseThrow();
        }
        String cause = release.id().equals(deployment.desiredReleaseId()) && deployment.currentRevisionId() != null
                ? "REDEPLOYMENT" : "PROMOTION";
        DeploymentConfiguration configuration = command.configuration() == null ? DeploymentConfiguration.shared()
                : command.configuration();
        return commit(workspace, environment, deployment, release, configuration, cause, "PROMOTION",
                decisionId, gates, drainDeadline(policy, now), command.actor(), command.reason(), now, inputDigest,
                command.override(), evidence, continuity);
    }

    private PromotionResult rollbackInTransaction(WorkspaceId workspace, RollbackCommand command) {
        DeploymentRow unlocked = lifecycle.findDeployment(command.deploymentId()).orElseThrow(LifecycleNotFoundException::new);
        EnvironmentRow environment = lifecycle.lockEnvironmentForPromotion(unlocked.environmentId()).orElseThrow();
        DeploymentRow deployment = lifecycle.lockDeployment(command.deploymentId()).orElseThrow();
        if (deployment.generation() != command.expectedGeneration()) throw LifecycleConflictException.stale("deployment");
        ReleaseRow release = lifecycle.findRelease(command.releaseId()).orElseThrow(LifecycleNotFoundException::new);
        releaseAdmission.requireAdmissible(release);
        if (release.id().equals(deployment.desiredReleaseId()) || !lifecycle.releaseEverDeployed(deployment.id(), release.id())) {
            throw LifecycleValidation.invalid("/releaseId", "rollback must select a different, previously deployed release");
        }
        RevisionRow prior = lifecycle.listRevisions(deployment.id()).stream()
                .filter(revision -> revision.releaseId().equals(release.id())).reduce((first, second) -> second)
                .orElseThrow();
        Instant now = clock.now();
        PromotionPolicy policy = LifecycleJson.read(environment.policyJson(), PromotionPolicy.class);
        List<PromotionEvidence> evidence = canonicalEvidence(
                evidenceProvider.resolve(workspace, environment.id(), environment.policyDigest(), release.id(),
                        command.evidence()));
        validateEvidenceSelection(command.evidence(), evidence);
        List<GateResult> gates = PromotionPolicyEvaluator.evaluate(policy, release.id(), command.actor(), evidence,
                name -> lifecycle.findEnvironmentByName(name).map(priorEnvironment ->
                        lifecycle.releaseDeployedInEnvironment(release.id(), priorEnvironment.id())).orElse(false));
        gates = applyOverride(policy, command.override(), command.overrideAuthorized(), gates);
        ContinuityResult continuity = PromotionPolicyEvaluator.continuity(policy,
                lifecycle.findRelease(deployment.desiredReleaseId()).orElseThrow().manifestJson(), release.manifestJson(),
                continuityProvider.capture(workspace, release.manifestJson()));
        if ("FAILED".equals(continuity.state())) {
            gates = new ArrayList<>(gates);
            gates.add(new GateResult(new PromotionGate(GateType.RELEASE_EVIDENCE, null), "FAILED",
                    "dependency continuity policy refuses " + continuity.drift().size() + " changed dependency classes"));
            gates = applyOverride(policy, command.override(), command.overrideAuthorized(), List.copyOf(gates));
        }
        String inputDigest = Digests.prefixed(LifecycleJson.write(Map.of("kind", "ROLLBACK", "policyDigest",
                environment.policyDigest(), "releaseId", release.id(), "gates", gates, "evidence", evidence,
                "continuity", continuity)));
        if (gates.stream().anyMatch(gate -> "FAILED".equals(gate.result()))) {
            UUID decisionId = UUID.randomUUID();
            lifecycle.insertDecision(new DecisionRow(decisionId, workspace.value(), "ROLLBACK", LifecycleModel.DENIED,
                    environment.id(), deployment.definitionId(), deployment.id(), release.id(), null,
                    environment.policyVersion(), environment.policyDigest(), decisionEvidence(inputDigest, gates,
                            evidence, command.override(), continuity),
                    actor(command.actor()), command.reason(), null, null, now));
            appendDeniedDrift(workspace, decisionId, deployment.id(), release.id(), continuity, now);
            return new PromotionResult(decisionId, "ROLLBACK", LifecycleModel.DENIED, gates, deployment.id(),
                    deployment.generation(), null, null, null, List.of(), inputDigest, command.override(), continuity);
        }
        DeploymentConfiguration configuration = LifecycleJson.read(prior.configurationJson(), DeploymentConfiguration.class);
        return commit(workspace, environment, deployment, release, configuration, "ROLLBACK", "ROLLBACK",
                UUID.randomUUID(), gates, drainDeadline(policy, now), command.actor(), command.reason(), now,
                inputDigest, command.override(), evidence, continuity);
    }

    private void appendDeniedDrift(WorkspaceId workspace, UUID decisionId, UUID deploymentId, UUID releaseId,
            ContinuityResult continuity, Instant now) {
        if (!"FAILED".equals(continuity.state()) || continuity.drift().isEmpty()) return;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("workspaceId", workspace.value());
        payload.put("decisionId", decisionId);
        payload.put("deploymentId", deploymentId);
        payload.put("releaseId", releaseId);
        payload.put("state", continuity.state());
        payload.put("action", continuity.action());
        payload.put("drift", continuity.drift());
        lifecycle.appendOutbox("DEPENDENCY_DRIFT_DETECTED", decisionId, LifecycleJson.write(payload), now);
    }

    private PromotionResult commit(WorkspaceId workspace, EnvironmentRow environment, DeploymentRow deployment,
            ReleaseRow release, DeploymentConfiguration configuration, String cause, String kind, UUID decisionId,
            List<GateResult> gates, Instant drainDeadline, String actor, String reason, Instant now) {
        return commit(workspace, environment, deployment, release, configuration, cause, kind, decisionId, gates,
                drainDeadline, actor, reason, now, null, null, List.of(),
                new ContinuityResult("UNCHANGED", LifecycleModel.ContinuityAction.FAIL, List.of()));
    }

    private PromotionResult commit(WorkspaceId workspace, EnvironmentRow environment, DeploymentRow deployment,
            ReleaseRow release, DeploymentConfiguration configuration, String cause, String kind, UUID decisionId,
            List<GateResult> gates, Instant drainDeadline, String actor, String reason, Instant now, String inputDigest,
            OverrideRequest override, List<PromotionEvidence> evidence, ContinuityResult continuity) {
        UUID revisionId = UUID.randomUUID();
        UUID cohortId = UUID.randomUUID();
        UUID routingGenerationId = UUID.randomUUID();
        long generation = deployment.generation() + 1;
        lifecycle.insertDecision(new DecisionRow(decisionId, workspace.value(), kind, LifecycleModel.ALLOWED,
                environment.id(), release.definitionId(), deployment.id(), release.id(), cohortId,
                environment.policyVersion(), environment.policyDigest(), decisionEvidence(inputDigest, gates, evidence,
                        override, continuity), actor(actor),
                reason, revisionId, routingGenerationId, now));

        String content = LifecycleJson.write(configuration);
        String contentDigest = Digests.prefixed(LifecycleJson.write(Map.of("releaseId", release.id().toString(),
                "environmentId", environment.id().toString(), "configuration", configuration)));
        lifecycle.insertRevision(new RevisionRow(revisionId, workspace.value(), deployment.id(), generation, release.id(),
                release.definitionId(), environment.id(), configuration.placement(), content, contentDigest, cause,
                decisionId, deployment.currentRevisionId(), now));

        lifecycle.insertCohort(cohortId, deployment.id(), revisionId, now);
        lifecycle.recordInitialTransition(cohortId, actor(actor), decisionId, now);
        List<UUID> drained = new ArrayList<>();
        for (CohortRow previous : lifecycle.lockCohorts(deployment.id(), List.of("ROUTABLE", "SUSPENDED"))) {
            transition(previous, "DRAINING", kind, decisionId, actor, reason, now, drainDeadline);
            drained.add(previous.id());
        }
        CohortRow pending = lifecycle.lockCohort(cohortId).orElseThrow();
        transition(pending, "ROUTABLE", kind, decisionId, actor, reason, now, null);

        RoutingGenerationRow routing = new RoutingGenerationRow(routingGenerationId, workspace.value(), deployment.id(),
                lifecycle.nextRoutingGeneration(deployment.id()), SINGLE_TARGET, decisionId,
                deployment.currentRoutingGenerationId(), now);
        lifecycle.insertRoutingGeneration(routing, List.of(new RoutingTargetRow(routingGenerationId, 0, cohortId,
                revisionId, 100)));
        if (!lifecycle.advanceDeployment(deployment.id(), deployment.generation(), release.id(), revisionId,
                routingGenerationId)) {
            throw LifecycleConflictException.stale("deployment");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("workspaceId", workspace.value().toString());
        payload.put("decisionId", decisionId.toString());
        payload.put("kind", kind);
        payload.put("deploymentId", deployment.id().toString());
        payload.put("generation", generation);
        payload.put("releaseId", release.id().toString());
        payload.put("revisionId", revisionId.toString());
        payload.put("cohortId", cohortId.toString());
        payload.put("routingGenerationId", routingGenerationId.toString());
        payload.put("drainedCohortIds", drained.stream().map(UUID::toString).toList());
        lifecycle.appendOutbox("DESIRED_DEPLOYMENT_CHANGED", decisionId, LifecycleJson.write(payload), now);
        if (!continuity.drift().isEmpty()) {
            lifecycle.appendOutbox("DEPENDENCY_DRIFT_DETECTED", decisionId, LifecycleJson.write(Map.of(
                    "workspaceId", workspace.value(), "decisionId", decisionId, "deploymentId", deployment.id(),
                    "releaseId", release.id(), "state", continuity.state(), "action", continuity.action(),
                    "drift", continuity.drift())), now);
        }
        log.info("deployment {} workspace={} deployment={} generation={} release={} revision={} cohort={} routing={}",
                kind.toLowerCase(java.util.Locale.ROOT), workspace.value(), deployment.id(), generation, release.id(),
                revisionId, cohortId, routingGenerationId);
        return new PromotionResult(decisionId, kind, LifecycleModel.ALLOWED, gates, deployment.id(), generation,
                revisionId, cohortId, routingGenerationId, List.copyOf(drained), inputDigest, override, continuity);
    }

    private Idempotent<CohortResult> cohortCommand(WorkspaceId workspace, String namespace, CohortCommand command,
            String kind, java.util.function.BiFunction<DeploymentRow, CohortRow, String> target) {
        validateActor(command.actor());
        Objects.requireNonNull(command.cohortId(), "cohortId");
        return idempotency.execute(workspace, namespace, command.idempotencyKey(), withoutKey(command),
                CohortResult.class, CohortResult::decisionId, CohortResult::state, () -> {
                    CohortRow unlocked = lifecycle.findCohort(command.cohortId()).orElseThrow(LifecycleNotFoundException::new);
                    DeploymentRow deployment = lifecycle.lockDeployment(unlocked.deploymentId()).orElseThrow();
                    CohortRow cohort = lifecycle.lockCohort(command.cohortId()).orElseThrow();
                    if (cohort.transitionVersion() != command.expectedVersion()) throw LifecycleConflictException.stale("cohort");
                    String next = target.apply(deployment, cohort);
                    Instant now = clock.now();
                    UUID decisionId = UUID.randomUUID();
                    RevisionRow revision = lifecycle.findRevision(cohort.revisionId()).orElseThrow();
                    lifecycle.insertDecision(new DecisionRow(decisionId, workspace.value(), kind, LifecycleModel.ALLOWED,
                            deployment.environmentId(), deployment.definitionId(), deployment.id(), revision.releaseId(),
                            cohort.id(), null, null, "[]", actor(command.actor()), command.reason(), cohort.revisionId(),
                            null, now));
                    transition(cohort, next, kind, decisionId, command.actor(), command.reason(), now, null);
                    lifecycle.appendOutbox("COHORT_TRANSITIONED", decisionId, LifecycleJson.write(Map.of(
                            "workspaceId", workspace.value().toString(), "decisionId", decisionId.toString(),
                            "deploymentId", deployment.id().toString(), "cohortId", cohort.id().toString(),
                            "revisionId", cohort.revisionId().toString(), "state", next,
                            "transitionVersion", cohort.transitionVersion() + 1)), now);
                    return new CohortResult(decisionId, cohort.id(), next, cohort.transitionVersion() + 1);
                });
    }

    private RetirementResult retireInTransaction(WorkspaceId workspace, CohortCommand command) {
        CohortRow unlocked = lifecycle.findCohort(command.cohortId()).orElseThrow(LifecycleNotFoundException::new);
        DeploymentRow deployment = lifecycle.lockDeployment(unlocked.deploymentId()).orElseThrow();
        // The exclusive cohort lock is the fence every resumable-work writer shares, so the recheck below sees
        // any writer that committed first, and any later writer observes RETIRED.
        CohortRow cohort = lifecycle.lockCohort(command.cohortId()).orElseThrow();
        if (cohort.transitionVersion() != command.expectedVersion()) throw LifecycleConflictException.stale("cohort");
        if (!List.of("DRAINING", "QUIESCENT").contains(cohort.state())) throw invalidTransition(cohort, "RETIRED");
        Instant now = clock.now();
        List<Blocker> blockers = blockers(cohort.id());
        UUID decisionId = UUID.randomUUID();
        RevisionRow revision = lifecycle.findRevision(cohort.revisionId()).orElseThrow();
        String outcome = blockers.isEmpty() ? LifecycleModel.ALLOWED : LifecycleModel.DENIED;
        lifecycle.insertDecision(new DecisionRow(decisionId, workspace.value(), "RETIRE", outcome,
                deployment.environmentId(), deployment.definitionId(), deployment.id(), revision.releaseId(), cohort.id(),
                null, null, LifecycleJson.write(Map.of("blockers", blockers)), actor(command.actor()), command.reason(),
                cohort.revisionId(), null, now));
        if (!blockers.isEmpty()) {
            if ("QUIESCENT".equals(cohort.state())) {
                transition(cohort, "DRAINING", "RETIREMENT_RECHECK", decisionId, command.actor(),
                        "retirement recheck found durable work", now, null);
                cohort = lifecycle.findCohort(cohort.id()).orElseThrow();
            }
            return new RetirementResult(decisionId, cohort.id(), "BLOCKED", cohort.state(), cohort.transitionVersion(),
                    blockers);
        }
        if ("DRAINING".equals(cohort.state())) {
            transition(cohort, "QUIESCENT", "RETIREMENT_RECHECK", decisionId, command.actor(), null, now, null);
            cohort = lifecycle.findCohort(cohort.id()).orElseThrow();
        }
        transition(cohort, "RETIRED", "RETIRE", decisionId, command.actor(), command.reason(), now, null);
        lifecycle.appendOutbox("COHORT_RETIRED", decisionId, LifecycleJson.write(Map.of(
                "workspaceId", workspace.value().toString(), "decisionId", decisionId.toString(),
                "deploymentId", deployment.id().toString(), "cohortId", cohort.id().toString(),
                "revisionId", cohort.revisionId().toString())), now);
        return new RetirementResult(decisionId, cohort.id(), "RETIRED", "RETIRED", cohort.transitionVersion() + 1,
                List.of());
    }

    private void transition(CohortRow cohort, String state, String cause, UUID decisionId, String actor, String reason,
            Instant now, Instant drainDeadline) {
        if (!lifecycle.transitionCohort(cohort, state, cause, decisionId, actor(actor), reason, now, drainDeadline)) {
            throw LifecycleConflictException.stale("cohort");
        }
    }

    private static List<GateResult> applyOverride(PromotionPolicy policy, OverrideRequest override,
            boolean authorized, List<GateResult> gates) {
        if (override == null) return gates;
        if (!policy.overridesAllowed()) {
            throw new LifecycleConflictException("OVERRIDE_NOT_ALLOWED", "the promotion policy does not allow overrides");
        }
        if (!authorized) {
            throw new LifecycleConflictException("OVERRIDE_NOT_AUTHORIZED",
                    "promotion override requires environment-administration authority");
        }
        if (override.reason() == null || override.reason().isBlank() || override.reason().length() > 1024) {
            throw LifecycleValidation.invalid("/override/reason", "must be present and at most 1024 characters");
        }
        if (override.ticket() == null || !override.ticket().matches("^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$")) {
            throw LifecycleValidation.invalid("/override/ticket", "must be a stable 1 to 128 character reference");
        }
        return gates.stream().map(gate -> "FAILED".equals(gate.result())
                ? new GateResult(gate.gate(), "OVERRIDDEN", "authorized override " + override.ticket(),
                        gate.evidenceId(), gate.evidenceDigest()) : gate).toList();
    }

    private static void validateEvidenceSelection(PromotionCommand command, List<PromotionEvidence> evidence) {
        validateEvidenceSelection(command.evidence(), evidence);
    }

    private static void validateEvidenceSelection(List<LifecycleModel.EvidenceReference> references,
            List<PromotionEvidence> evidence) {
        if (evidence == null) throw new IllegalStateException("promotion evidence provider returned null");
        Map<String, PromotionEvidence> resolved = new LinkedHashMap<>();
        java.util.EnumSet<LifecycleModel.EvidenceKind> kinds =
                java.util.EnumSet.noneOf(LifecycleModel.EvidenceKind.class);
        for (PromotionEvidence item : evidence) {
            if (item != null && item.id() != null && item.kind() != null
                    && resolved.put(evidenceKey(item.id(), item.kind()), item) != null) {
                throw new IllegalStateException("promotion evidence provider returned a duplicate identity");
            }
            if (item != null && item.kind() != null && !kinds.add(item.kind())) {
                throw new LifecycleConflictException("EVIDENCE_AMBIGUOUS",
                        "only one captured evidence record of each kind may be selected");
            }
        }
        for (var reference : references) {
            if (reference == null || reference.id() == null || reference.kind() == null) {
                throw LifecycleValidation.invalid("/evidence", "each reference requires id and kind");
            }
            PromotionEvidence item = resolved.get(evidenceKey(reference.id(), reference.kind()));
            if (item == null || item.kind() != reference.kind()) {
                throw new LifecycleConflictException("EVIDENCE_UNAVAILABLE",
                        "selected promotion evidence is unavailable or has another kind");
            }
        }
        if (resolved.size() != references.size()) {
            throw new IllegalStateException("promotion evidence provider returned evidence that was not selected");
        }
    }

    private static String evidenceKey(UUID id, LifecycleModel.EvidenceKind kind) {
        return kind.name() + ":" + id;
    }

    private static List<PromotionEvidence> canonicalEvidence(List<PromotionEvidence> evidence) {
        if (evidence == null) throw new IllegalStateException("promotion evidence provider returned null");
        return evidence.stream().sorted(java.util.Comparator
                .comparing((PromotionEvidence item) -> item.kind() == null ? "" : item.kind().name())
                .thenComparing(item -> item.id() == null ? new UUID(0, 0) : item.id())).toList();
    }

    private static String decisionInputDigest(EnvironmentRow environment, ReleaseRow release,
            PromotionCommand command, List<GateResult> gates, List<PromotionEvidence> evidence,
            ContinuityResult continuity) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("policyVersion", environment.policyVersion());
        input.put("policyDigest", environment.policyDigest());
        input.put("releaseId", release.id());
        input.put("releaseManifestDigest", release.manifestDigest());
        input.put("actor", command.actor());
        input.put("evidence", evidence);
        input.put("gates", gates);
        input.put("override", command.override());
        input.put("continuity", continuity);
        return Digests.prefixed(LifecycleJson.write(input));
    }

    private static String decisionEvidence(String inputDigest, List<GateResult> gates,
            List<PromotionEvidence> evidence, OverrideRequest override, ContinuityResult continuity) {
        Map<String, Object> captured = new LinkedHashMap<>();
        captured.put("schemaVersion", 2);
        captured.put("inputDigest", inputDigest);
        captured.put("gates", gates);
        captured.put("evidence", evidence);
        captured.put("override", override);
        captured.put("continuity", continuity);
        return LifecycleJson.write(captured);
    }

    private List<Blocker> blockers(UUID cohortId) {
        return lifecycle.quiescenceBlockers(cohortId).stream().map(DeploymentLifecycleService::blocker).toList();
    }

    private static Blocker blocker(QuiescenceBlocker blocker) {
        return new Blocker(blocker.category(), blocker.state(), blocker.count(), blocker.oldestSince());
    }

    private static QuiescenceReport report(CohortRow cohort, List<Blocker> blockers, Instant now) {
        boolean exceeded = cohort.drainDeadline() != null && !now.isBefore(cohort.drainDeadline())
                && List.of("DRAINING", "QUIESCENT").contains(cohort.state());
        return new QuiescenceReport(cohort.id(), cohort.state(), cohort.transitionVersion(), blockers.isEmpty(),
                blockers, now, cohort.drainDeadline(), exceeded);
    }

    private static Instant drainDeadline(PromotionPolicy policy, Instant now) {
        return policy.drainDeadline() == null ? null : now.plus(Duration.parse(policy.drainDeadline()));
    }

    private static LifecycleConflictException invalidTransition(CohortRow cohort, String target) {
        return new LifecycleConflictException("INVALID_TRANSITION", cohort.state() + " cannot transition to " + target);
    }

    /**
     * Every lifecycle command names its actor; a missing one fails closed rather than being invented. It is one typed
     * validation error (422, {@code /actor}) wherever it is checked (#203): first before any idempotency record, lock,
     * gate, or insert (#177), and again at each write that records the actor.
     */
    private static void validateActor(String actor) {
        if (actor == null || actor.isBlank()) throw LifecycleValidation.invalid("/actor", "is required");
    }

    /** The validated actor a write records (see {@link #validateActor}). */
    private static String actor(String actor) {
        validateActor(actor);
        return actor;
    }

    private record LegacyPromotionFingerprint(UUID environmentId, String deploymentName, UUID releaseId,
            DeploymentConfiguration configuration, Long expectedGeneration, String idempotencyKey, String actor,
            String reason) {}
    private record LegacyRollbackFingerprint(UUID deploymentId, UUID releaseId, long expectedGeneration,
            String idempotencyKey, String actor, String reason) {}
    private record ExtendedPromotionFingerprint(Object legacy, List<LifecycleModel.EvidenceReference> evidence,
            OverrideRequest override) {}

    static Object withoutKey(PromotionCommand command) {
        var legacy = new LegacyPromotionFingerprint(command.environmentId(), command.deploymentName(), command.releaseId(),
                command.configuration(), command.expectedGeneration(), null, command.actor(), command.reason());
        if (command.evidence().isEmpty() && command.override() == null) return legacy;
        List<LifecycleModel.EvidenceReference> evidence = command.evidence().stream().sorted(java.util.Comparator
                .comparing((LifecycleModel.EvidenceReference item) -> item.kind().name())
                .thenComparing(LifecycleModel.EvidenceReference::id)).toList();
        return new ExtendedPromotionFingerprint(legacy, evidence, command.override());
    }

    private static CohortCommand withoutKey(CohortCommand command) {
        return new CohortCommand(command.cohortId(), command.expectedVersion(), null, command.actor(), command.reason());
    }

    static Object withoutKey(RollbackCommand command) {
        var legacy = new LegacyRollbackFingerprint(command.deploymentId(), command.releaseId(),
                command.expectedGeneration(), null, command.actor(), command.reason());
        if (command.evidence().isEmpty() && command.override() == null) return legacy;
        List<LifecycleModel.EvidenceReference> evidence = command.evidence().stream().sorted(java.util.Comparator
                .comparing((LifecycleModel.EvidenceReference item) -> item.kind().name())
                .thenComparing(LifecycleModel.EvidenceReference::id)).toList();
        return new ExtendedPromotionFingerprint(legacy, evidence, command.override());
    }

    private static void validate(PromotionCommand command) {
        Objects.requireNonNull(command.environmentId(), "environmentId");
        if (command.releaseId() == null) throw LifecycleValidation.invalid("/releaseId", "is required");
        validateReferences(command.evidence());
        if (command.deploymentName() == null || command.deploymentName().length() > 63
                || !DEPLOYMENT_NAME.matcher(command.deploymentName()).matches()) {
            throw LifecycleValidation.invalid("/deployment", "must match " + DEPLOYMENT_NAME.pattern());
        }
        DeploymentConfiguration configuration = command.configuration() == null ? DeploymentConfiguration.shared()
                : command.configuration();
        if (!PLACEMENTS.contains(configuration.placement())) {
            throw LifecycleValidation.invalid("/configuration/placement", "must be one of " + PLACEMENTS);
        }
        var constraints = configuration.placementConstraints();
        if (constraints != null) {
            validateConstraint("securityZone", constraints.securityZone());
            validateConstraint("dataResidency", constraints.dataResidency());
            validateConstraint("runtimeClass", constraints.runtimeClass());
            validateConstraint("imageClass", constraints.imageClass());
        }
        configuration.credentialReferences().forEach((name, reference) -> {
            if (!CREDENTIAL_NAME.matcher(name).matches()) {
                throw LifecycleValidation.invalid("/configuration/credentialReferences", "credential names must match "
                        + CREDENTIAL_NAME.pattern());
            }
            if (reference == null || !CREDENTIAL_REFERENCE.matcher(reference).matches()) {
                // Only CredentialRef identifiers are accepted; a resolved secret value is never stored.
                throw LifecycleValidation.invalid("/configuration/credentialReferences/" + name,
                        "must be a credential reference URI such as vault://path/to/secret, not a secret value");
            }
        });
        for (String identity : configuration.policyIdentities()) {
            if (identity == null || !POLICY_IDENTITY.matcher(identity).matches()) {
                throw LifecycleValidation.invalid("/configuration/policyIdentities", "must be versioned identities such as tool-policy@3");
            }
        }
        if (configuration.exposures().size() > 64
                || configuration.exposures().stream().map(com.dvarahq.agentworks.exposure.ExposureSpec::id)
                        .distinct().count() != configuration.exposures().size()) {
            throw LifecycleValidation.invalid("/configuration/exposures",
                    "must contain at most 64 uniquely named exposures");
        }
    }

    private static void validateReferences(List<LifecycleModel.EvidenceReference> references) {
        java.util.HashSet<String> ids = new java.util.HashSet<>();
        for (int index = 0; index < references.size(); index++) {
            var reference = references.get(index);
            if (reference == null || reference.id() == null || reference.kind() == null) {
                throw LifecycleValidation.invalid("/evidence/" + index, "requires id and kind");
            }
            if (!ids.add(evidenceKey(reference.id(), reference.kind()))) {
                throw LifecycleValidation.invalid("/evidence/" + index, "kind and id must be unique");
            }
        }
    }

    private static void validateConstraint(String field, String value) {
        if (value != null && !value.matches("^[A-Za-z0-9][A-Za-z0-9._/-]{0,63}$")) {
            throw LifecycleValidation.invalid("/configuration/placementConstraints/" + field,
                    "must be a 1 to 64 character placement identity");
        }
    }
}
