package com.dvarahq.agentworks.controlplane.deployment;

import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleConflictException;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleJson;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleNotFoundException;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleValidation;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.EnvironmentView;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.GateType;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.PromotionGate;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.PromotionPolicy;
import com.dvarahq.agentworks.controlplane.mutation.ControlPlaneMutationAuthority;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import com.dvarahq.agentworks.storage.TenantTransactionTemplate;
import com.dvarahq.agentworks.storage.lifecycle.ConcurrentInsertException;
import com.dvarahq.agentworks.storage.lifecycle.DatabaseClock;
import com.dvarahq.agentworks.storage.lifecycle.Digests;
import com.dvarahq.agentworks.storage.lifecycle.JdbcLifecycleRepository;
import com.dvarahq.agentworks.storage.lifecycle.LifecycleRetry;
import com.dvarahq.agentworks.domain.lifecycle.state.LifecycleRows.EnvironmentRow;
import org.springframework.dao.DuplicateKeyException;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Customer-defined environments and their versioned promotion policies. No environment names are built in. */
public final class EnvironmentService implements ControlPlaneMutationAuthority {
    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9 ._-]{0,127}$");

    private final TenantTransactionTemplate tenants;
    private final JdbcLifecycleRepository lifecycle;
    private final DatabaseClock clock;
    private final LifecycleRetry retry;

    public EnvironmentService(TenantTransactionTemplate tenants, JdbcLifecycleRepository lifecycle, DatabaseClock clock,
            LifecycleRetry retry) {
        this.tenants = Objects.requireNonNull(tenants, "tenants");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.retry = Objects.requireNonNull(retry, "retry");
    }

    public EnvironmentView create(WorkspaceId workspace, String name, String description, PromotionPolicy policy) {
        if (name == null || !NAME.matcher(name).matches()) throw LifecycleValidation.invalid("/name", "must match " + NAME.pattern());
        if (description != null && description.length() > 1024) throw LifecycleValidation.invalid("/description", "length must be <= 1024");
        PromotionPolicy validated = validate(policy == null ? PromotionPolicy.open() : policy);
        String json = LifecycleJson.write(validated);
        try {
            return retry.execute("environment.create", () -> tenants.execute(workspace, () -> {
                if (lifecycle.findEnvironmentByName(name).isPresent()) throw exists();
                EnvironmentRow row = new EnvironmentRow(UUID.randomUUID(), workspace.value(), name, description, 1,
                        json, Digests.prefixed(json), clock.now());
                lifecycle.insertEnvironment(row);
                return view(row);
            }));
        } catch (DuplicateKeyException | ConcurrentInsertException concurrent) {
            throw exists();
        }
    }

    public EnvironmentView get(WorkspaceId workspace, UUID id) {
        return tenants.execute(workspace, () -> lifecycle.findEnvironment(id).map(EnvironmentService::view)
                .orElseThrow(LifecycleNotFoundException::new));
    }

    public List<EnvironmentView> list(WorkspaceId workspace) {
        return tenants.execute(workspace, () -> lifecycle.listEnvironments().stream().map(EnvironmentService::view).toList());
    }

    /** One keyset page of environments by name, then id (issue #35). */
    public List<EnvironmentView> list(WorkspaceId workspace, String afterName, UUID afterId, int limit) {
        return tenants.execute(workspace, () -> lifecycle.listEnvironments(afterName, afterId, limit).stream()
                .map(EnvironmentService::view).toList());
    }

    /** Replaces the policy with optimistic concurrency; every version remains in immutable history. */
    public EnvironmentView updatePolicy(WorkspaceId workspace, UUID id, long expectedVersion, PromotionPolicy policy) {
        String json = LifecycleJson.write(validate(Objects.requireNonNull(policy, "policy")));
        return retry.execute("environment.policy", () -> tenants.execute(workspace, () -> {
            lifecycle.lockEnvironmentForPromotion(id).orElseThrow(LifecycleNotFoundException::new);
            if (!lifecycle.updatePolicy(id, expectedVersion, json, Digests.prefixed(json), clock.now())) {
                throw LifecycleConflictException.stale("promotion policy");
            }
            return view(lifecycle.findEnvironment(id).orElseThrow());
        }));
    }

    static PromotionPolicy validate(PromotionPolicy policy) {
        if (policy.gates().size() > 64) {
            throw LifecycleValidation.invalid("/promotionPolicy/gates", "must contain at most 64 gates");
        }
        for (int index = 0; index < policy.gates().size(); index++) {
            PromotionGate gate = policy.gates().get(index);
            if (gate == null || gate.type() == null) throw LifecycleValidation.invalid("/promotionPolicy/gates/" + index + "/type", "is required");
            boolean needsEnvironment = gate.type() == GateType.REQUIRE_PRIOR_ENVIRONMENT;
            if (needsEnvironment != (gate.environment() != null)) {
                throw LifecycleValidation.invalid("/promotionPolicy/gates/" + index + "/environment",
                        needsEnvironment ? "is required for REQUIRE_PRIOR_ENVIRONMENT" : "applies only to REQUIRE_PRIOR_ENVIRONMENT");
            }
            boolean evidenceGate = gate.type() != GateType.REQUIRE_PRIOR_ENVIRONMENT
                    && gate.type() != GateType.CHANGE_FREEZE;
            if (!evidenceGate && (gate.evidenceKind() != null || gate.requiredStatus() != null
                    || gate.metric() != null || gate.comparison() != null || gate.threshold() != null)) {
                throw LifecycleValidation.invalid("/promotionPolicy/gates/" + index,
                        "prior-environment and change-freeze gates cannot declare evidence constraints");
            }
            if (evidenceGate) {
                var expectedKind = gate.type() == GateType.SEPARATION_OF_DUTIES
                        ? com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.EvidenceKind.HUMAN_APPROVAL
                        : com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.EvidenceKind
                                .valueOf(gate.type().name());
                if (gate.evidenceKind() != null && gate.evidenceKind() != expectedKind) {
                    throw LifecycleValidation.invalid("/promotionPolicy/gates/" + index + "/evidenceKind",
                            "must match the gate type");
                }
                if (gate.requiredStatus() == null || !gate.requiredStatus().matches("^[A-Z][A-Z0-9_]{0,31}$")) {
                    throw LifecycleValidation.invalid("/promotionPolicy/gates/" + index + "/requiredStatus",
                            "must be an explicit uppercase captured status");
                }
            }
            boolean numeric = gate.metric() != null || gate.comparison() != null || gate.threshold() != null;
            if (numeric && (gate.metric() == null || gate.comparison() == null || gate.threshold() == null)) {
                throw LifecycleValidation.invalid("/promotionPolicy/gates/" + index,
                        "metric, comparison, and threshold must be declared together");
            }
            if (numeric && gate.type() != GateType.COST && gate.type() != GateType.LATENCY) {
                throw LifecycleValidation.invalid("/promotionPolicy/gates/" + index + "/metric",
                        "numeric thresholds apply only to COST or LATENCY gates");
            }
            if (gate.metric() != null && !gate.metric().matches("^[A-Za-z][A-Za-z0-9_.-]{0,63}$")) {
                throw LifecycleValidation.invalid("/promotionPolicy/gates/" + index + "/metric",
                        "must be a bounded metric identity");
            }
            if (gate.threshold() != null && gate.threshold().scale() > 8) {
                throw LifecycleValidation.invalid("/promotionPolicy/gates/" + index + "/threshold",
                        "must have at most 8 decimal places");
            }
        }
        if (policy.drainDeadline() != null) {
            try {
                if (Duration.parse(policy.drainDeadline()).isNegative()) throw new DateTimeParseException("negative", "", 0);
            } catch (DateTimeParseException exception) {
                throw LifecycleValidation.invalid("/promotionPolicy/drainDeadline", "must be a non-negative ISO-8601 duration");
            }
        }
        return policy;
    }

    static EnvironmentView view(EnvironmentRow row) {
        return new EnvironmentView(row.id(), row.workspaceId(), row.name(), row.description(), row.policyVersion(),
                LifecycleJson.read(row.policyJson(), PromotionPolicy.class), row.policyDigest(), row.createdAt());
    }

    private static LifecycleConflictException exists() {
        return new LifecycleConflictException("ENVIRONMENT_EXISTS", "an environment with this name already exists");
    }
}
