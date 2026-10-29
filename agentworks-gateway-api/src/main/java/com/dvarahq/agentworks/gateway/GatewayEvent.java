package com.dvarahq.agentworks.gateway;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Secret-free gateway evidence: identifiers, capabilities, usage, and outcomes only. No event carries prompt or
 * response content, tool arguments, endpoint URLs, credential references, or credential material. The durable run
 * event store (#32) persists these once the runtime integrates the gateway (#34).
 */
public sealed interface GatewayEvent {

    String type();

    Scope scope();

    Instant occurredAt();

    /**
     * Who invoked what: tenant, release, run, and the pinned identity.
     *
     * @param runId the run, or {@code null} outside a run
     */
    record Scope(UUID workspaceId, UUID releaseId, UUID runId, String traceId, ModelRole role, String gatewayId,
            String gatewayType, String providerModel) {
        public Scope {
            Objects.requireNonNull(workspaceId, "workspaceId");
            Objects.requireNonNull(releaseId, "releaseId");
            Objects.requireNonNull(traceId, "traceId");
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(gatewayId, "gatewayId");
            Objects.requireNonNull(gatewayType, "gatewayType");
            Objects.requireNonNull(providerModel, "providerModel");
        }

        static Scope of(ModelInvocation invocation) {
            ResolvedModelIdentity identity = invocation.model().identity();
            return new Scope(invocation.model().workspace().value(), invocation.model().release().value(),
                    invocation.correlation().runId() == null ? null : invocation.correlation().runId().value(),
                    invocation.correlation().traceId(), identity.role(), identity.gatewayId(), identity.gatewayType(),
                    identity.providerModel());
        }
    }

    /** Policy permitted degrading a capability the invocation needed; emitted before provider contact. */
    record CapabilityDegraded(Scope scope, GatewayCapability capability, DegradedBehavior behavior, Instant occurredAt)
            implements GatewayEvent {
        @Override
        public String type() {
            return "MODEL_CAPABILITY_DEGRADED";
        }
    }

    /** The invocation completed; this is the run-provenance record of the served identity and usage. */
    record ModelInvocationCompleted(Scope scope, String adapterVersion, ServedModel served, ModelUsage usage,
            FinishReason finishReason, long chunks, List<DegradedBehavior> degradations, Instant occurredAt)
            implements GatewayEvent {
        public ModelInvocationCompleted {
            degradations = List.copyOf(degradations);
        }

        @Override
        public String type() {
            return "MODEL_INVOCATION_COMPLETED";
        }
    }

    /**
     * The invocation failed with a typed code. {@code providerContacted} is true once the adapter marked the request as
     * sent, unless the adapter reported that it never left (for example a connection that never opened).
     */
    record ModelInvocationFailed(Scope scope, GatewayErrorCode code, boolean providerContacted, ModelUsage usage,
            Instant occurredAt)
            implements GatewayEvent {
        public ModelInvocationFailed {
            Objects.requireNonNull(usage, "usage");
        }

        /** Compatibility constructor for failures without partial usage evidence. */
        public ModelInvocationFailed(Scope scope, GatewayErrorCode code, boolean providerContacted,
                Instant occurredAt) {
            this(scope, code, providerContacted, ModelUsage.UNREPORTED, occurredAt);
        }

        @Override
        public String type() {
            return "MODEL_INVOCATION_FAILED";
        }
    }

    /** One provider attempt, including retries and fallback substitutions, without prompt or response content. */
    record ModelInvocationAttempted(Scope scope, int route, int attempt, boolean fallback, Instant occurredAt)
            implements GatewayEvent {
        @Override public String type() { return "MODEL_INVOCATION_ATTEMPTED"; }
    }

    /** A release-declared fallback replaced the preceding unavailable route. */
    record ModelFallbackSelected(Scope scope, String failedGatewayId, String failedProviderModel, Instant occurredAt)
            implements GatewayEvent {
        @Override public String type() { return "MODEL_FALLBACK_SELECTED"; }
    }

    /** Breaker state changed for this isolated route. */
    record ModelRouteStateChanged(Scope scope, String state, Instant occurredAt) implements GatewayEvent {
        @Override public String type() { return "MODEL_ROUTE_STATE_CHANGED"; }
    }

    /** A delayed authoritative usage record replaced an estimate; discrepancy prevents silent double accounting. */
    record ModelUsageReconciled(Scope scope, ModelUsage original, ModelUsage reconciled,
            ModelUsage.TokenDiscrepancy discrepancy, Instant occurredAt) implements GatewayEvent {
        public ModelUsageReconciled {
            Objects.requireNonNull(original, "original");
            Objects.requireNonNull(reconciled, "reconciled");
            Objects.requireNonNull(discrepancy, "discrepancy");
        }

        @Override public String type() { return "MODEL_USAGE_RECONCILED"; }
    }
}
