package com.dvarahq.agentworks.gateway;

import java.util.Objects;

/**
 * One ordered content delta. Core assigns {@code sequence} (0, 1, 2, ...) in provider order, so listeners never see a
 * gap, a duplicate, or a reordering. {@link #toString()} does not render the delta.
 */
public record ModelChunk(long sequence, String delta) {
    public ModelChunk {
        if (sequence < 0) {
            throw new IllegalArgumentException("sequence must not be negative");
        }
        Objects.requireNonNull(delta, "delta");
    }

    @Override
    public String toString() {
        return "ModelChunk[sequence=" + sequence + ", delta=<" + delta.length() + " chars redacted>]";
    }
}
