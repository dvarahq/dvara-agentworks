package com.dvarahq.agentworks.interaction;

import java.util.Objects;

/** Typed refusal from the durable interaction authority. */
public final class HumanInteractionAuthorityException extends RuntimeException {
    public enum Code {
        NOT_FOUND, UNAUTHORIZED_RESPONDER, INVALID_RESPONSE, INVALID_TRANSITION, ALREADY_TERMINAL, DEADLINE_PASSED,
        DEADLINE_NOT_REACHED, IDEMPOTENCY_CONFLICT, RUN_PINS_MISMATCH, COHORT_NOT_ADMITTING, CREDENTIAL_UNAVAILABLE
    }

    private final Code code;

    public HumanInteractionAuthorityException(Code code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
    }

    public HumanInteractionAuthorityException(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code");
    }

    public Code code() {
        return code;
    }
}
