package com.dvarahq.agentworks.gateway.dvarahq;

import com.dvarahq.agentworks.gateway.GatewayEvent;

import java.time.Instant;
import java.util.Objects;

/** Content-free adapter health, routing and continuity evidence. Model observations are release/run scoped; A2A
 * observations use their separate external-invocation contract and therefore have a {@code null} model scope. */
public record DvaraHqObservation(Type type, String gatewayId, String operation, String code,
        GatewayEvent.Scope scope, String providerModel, String servedModel, String fingerprint, Instant occurredAt) {
    public DvaraHqObservation {
        Objects.requireNonNull(type); Objects.requireNonNull(gatewayId); Objects.requireNonNull(operation);
        Objects.requireNonNull(code); Objects.requireNonNull(occurredAt);
    }
    public enum Type { OPERATION_SUCCEEDED, OPERATION_FAILED, MODEL_CONTINUITY_OBSERVED, MODEL_CONTINUITY_CHANGED }
}
