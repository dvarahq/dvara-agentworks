package com.dvarahq.agentworks.domain.artifact;

import java.util.Objects;

/** Content-free evidence returned after a compute output has been atomically published. */
public record ArtifactComputeResult(ArtifactRef output, ArtifactComputeOperator operator,
        int inputCount, long inputBytes, long outputBytes) {
    public ArtifactComputeResult {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(operator, "operator");
        if (inputCount < 1 || inputBytes < 0 || outputBytes < 0) {
            throw new IllegalArgumentException("artifact compute counts cannot be negative or empty");
        }
    }
}
