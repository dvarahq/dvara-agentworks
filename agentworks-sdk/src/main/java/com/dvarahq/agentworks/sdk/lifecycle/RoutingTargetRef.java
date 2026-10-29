package com.dvarahq.agentworks.sdk.lifecycle;

import java.util.Objects;
import java.util.UUID;

/** Immutable root-admission routing generation and selected target. */
public record RoutingTargetRef(UUID routingGenerationId, UUID targetId, UUID deploymentRevisionId,
                               int weightBasisPoints) {
    public RoutingTargetRef {
        Objects.requireNonNull(routingGenerationId); Objects.requireNonNull(targetId);
        Objects.requireNonNull(deploymentRevisionId);
        if (weightBasisPoints < 0 || weightBasisPoints > 10_000) throw new IllegalArgumentException("weightBasisPoints");
    }
    public boolean ordinaryCoreTarget() { return weightBasisPoints == 10_000; }
}
