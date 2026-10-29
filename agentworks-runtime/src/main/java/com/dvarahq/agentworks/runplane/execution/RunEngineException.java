package com.dvarahq.agentworks.runplane.execution;

import java.util.Objects;

/**
 * Typed run-engine failure; nothing from the failed operation committed. {@code code} is a stable machine value:
 * {@code NOT_FOUND}, {@code STALE_LEASE}, {@code ILLEGAL_TRANSITION}, {@code RUN_TERMINAL},
 * {@code COHORT_NOT_ADMITTING}, {@code INVALID_EVENT}, {@code INVALID_PAYLOAD}, {@code PAYLOAD_TOO_LARGE},
 * {@code UNKNOWN_EVENT_TYPE}, {@code UNSUPPORTED_EVENT_VERSION}, {@code CORRUPT_HISTORY},
 * {@code UNSUPPORTED_MANIFEST_VERSION}, {@code INVALID_MANIFEST}, {@code EFFECT_KEY_REUSED},
 * {@code EFFECT_IN_DOUBT}, {@code EFFECT_ABANDONED}, {@code EFFECT_FAILED}, {@code INVALID_EVENT_POSITION},
 * {@code EVENT_SEQUENCE_AHEAD}, {@code EVENT_HISTORY_EXPIRED}, or a database-authority code.
 */
public final class RunEngineException extends RuntimeException {
    private final String code;

    public RunEngineException(String code, String message) {
        this(code, message, null);
    }

    public RunEngineException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code");
    }

    public String code() {
        return code;
    }
}
