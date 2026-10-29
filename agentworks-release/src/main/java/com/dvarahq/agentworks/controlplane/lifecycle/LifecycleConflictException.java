package com.dvarahq.agentworks.controlplane.lifecycle;

import java.util.UUID;

/** A command conflicts with authoritative state and changed nothing. {@code code} is a stable machine value. */
public class LifecycleConflictException extends RuntimeException {
    private final String code;
    private final UUID resultId;

    public LifecycleConflictException(String code, String message) {
        this(code, message, null);
    }

    public LifecycleConflictException(String code, String message, UUID resultId) {
        super(message);
        this.code = code;
        this.resultId = resultId;
    }

    public String code() {
        return code;
    }

    /** For {@code IDEMPOTENCY_RESULT_EXPIRED}, the immutable identity of the original result. */
    public UUID resultId() {
        return resultId;
    }

    public static LifecycleConflictException stale(String what) {
        return new LifecycleConflictException("STALE_VERSION", what + " changed since the supplied version");
    }
}
