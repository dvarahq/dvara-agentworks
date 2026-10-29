package com.dvarahq.agentworks.artifact.compute;

import java.time.Duration;
import java.util.Objects;

/** Node-local hard ceilings for the built-in Artifact Compute operators. */
public record ArtifactComputeLimits(int maximumInputs, long maximumInputBytes,
        int maximumOutputBytes, Duration timeout) {
    public static final ArtifactComputeLimits DEFAULTS = new ArtifactComputeLimits(
            32, 64L * 1024 * 1024, 16 * 1024 * 1024, Duration.ofSeconds(30));

    public ArtifactComputeLimits {
        if (maximumInputs < 1 || maximumInputs > 256) {
            throw new IllegalArgumentException("maximumInputs must be between 1 and 256");
        }
        if (maximumInputBytes < 1 || maximumInputBytes > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("maximumInputBytes must be positive and fit in memory");
        }
        if (maximumOutputBytes < 1) throw new IllegalArgumentException("maximumOutputBytes must be positive");
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("timeout must be positive and at most five minutes");
        }
    }
}
