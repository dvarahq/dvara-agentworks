package com.dvarahq.agentworks.artifact;

import java.util.Objects;

/** Typed, content-free failure from a bounded artifact transform. */
public final class ArtifactComputeException extends RuntimeException {
    public enum Reason {
        INPUT_LIMIT_EXCEEDED,
        OUTPUT_LIMIT_EXCEEDED,
        TIME_LIMIT_EXCEEDED,
        INVALID_INPUT,
        UNAVAILABLE
    }

    private final Reason reason;

    public ArtifactComputeException(Reason reason) {
        super("artifact compute failed: " + reason);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public ArtifactComputeException(Reason reason, Throwable cause) {
        super("artifact compute failed: " + reason, cause);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public Reason reason() {
        return reason;
    }
}
