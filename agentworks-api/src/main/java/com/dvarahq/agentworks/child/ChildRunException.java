package com.dvarahq.agentworks.child;

import java.util.Objects;

/** Stable typed refusal from child/wakeup authority. */
public final class ChildRunException extends RuntimeException {
    public enum Code {
        NOT_FOUND, IDEMPOTENCY_CONFLICT, DEPTH_LIMIT, FAN_OUT_LIMIT, CHILD_LIMIT, BUDGET_EXCEEDED,
        RELEASE_MISMATCH, PARENT_NOT_ACTIVE, PARENT_TERMINAL, CHILD_NOT_TERMINAL, ALREADY_TERMINAL,
        RUN_PINS_MISMATCH, COHORT_NOT_ADMITTING, WAKEUP_NOT_DUE, WAKEUP_ALREADY_TERMINAL,
        SANDBOX_RESTORE_UNSUPPORTED
    }
    private final Code code;
    public ChildRunException(Code code, String message) { super(message); this.code = Objects.requireNonNull(code); }
    public ChildRunException(Code code, String message, Throwable cause) { super(message, cause); this.code = Objects.requireNonNull(code); }
    public Code code() { return code; }
}
