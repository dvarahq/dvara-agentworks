package com.dvarahq.agentworks.session;

/** Stable failure vocabulary for session and inbox authority. */
public final class SessionAuthorityException extends RuntimeException {
    public enum Code {
        NOT_FOUND,
        RUN_NOT_IN_SESSION,
        RUN_PINS_MISMATCH,
        COHORT_NOT_ADMITTING,
        IDEMPOTENCY_CONFLICT,
        ALREADY_TERMINAL,
        ALREADY_CLAIMED,
        STALE_CLAIM,
        STALE_VERSION,
        INVALID_TRANSITION
    }

    private final Code code;

    public SessionAuthorityException(Code code, String message) {
        super(message);
        this.code = java.util.Objects.requireNonNull(code, "code");
    }

    public SessionAuthorityException(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = java.util.Objects.requireNonNull(code, "code");
    }

    public Code code() { return code; }
}
