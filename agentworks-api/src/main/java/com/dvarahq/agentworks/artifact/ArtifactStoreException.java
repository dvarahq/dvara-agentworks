package com.dvarahq.agentworks.artifact;

/** Typed, content-free artifact failure. NOT_FOUND_OR_DENIED deliberately hides tenant existence. */
public final class ArtifactStoreException extends RuntimeException {
    public enum Reason {
        NOT_FOUND_OR_DENIED,
        INVALID_RANGE,
        INTEGRITY_FAILED,
        LIMIT_EXCEEDED,
        QUARANTINED,
        PINNED,
        INVALID_STATE,
        UNSUPPORTED_MEDIA_TYPE,
        UNAVAILABLE
    }

    private final Reason reason;

    public ArtifactStoreException(Reason reason) {
        super("artifact operation failed: " + reason);
        this.reason = java.util.Objects.requireNonNull(reason, "reason");
    }

    public Reason reason() { return reason; }
}
