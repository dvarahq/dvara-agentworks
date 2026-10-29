package com.dvarahq.agentworks.operation;

/** Stable operation-authority failures used by runtime and REST adapters. */
public final class DurableOperationAuthorityException extends RuntimeException {
    public enum Code {
        NOT_FOUND,
        PROVIDER_NOT_FOUND,
        RUN_PINS_MISMATCH,
        RUN_NOT_IN_SESSION,
        COHORT_NOT_ADMITTING,
        IDEMPOTENCY_CONFLICT,
        CALLBACK_UNAUTHORIZED,
        ALREADY_TERMINAL,
        RESULT_NOT_READY,
        INVALID_TRANSITION,
        PROVIDER_UNAVAILABLE
    }

    private final Code code;

    public DurableOperationAuthorityException(Code code, String message) {
        super(message);
        this.code = java.util.Objects.requireNonNull(code, "code");
    }

    public DurableOperationAuthorityException(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = java.util.Objects.requireNonNull(code, "code");
    }

    public Code code() { return code; }
}
