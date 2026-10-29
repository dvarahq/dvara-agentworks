package com.dvarahq.agentworks.agent.springai;

/**
 * A typed end of an exchange: the model output cannot be recorded or accepted ({@code OUTPUT_INVALID},
 * {@code OUTPUT_TOO_LARGE}, {@code TOOL_CALL_LIMIT_EXCEEDED}, {@code TOOL_POLICY_DENIED}), or a tool overflowed its
 * stack ({@code TOOL_FAILED}). Its message never carries output or tool text.
 */
final class OutputRejectedException extends RuntimeException {
    private final String code;

    OutputRejectedException(String code, String message) {
        super(message, null, false, false);
        this.code = code;
    }

    String code() {
        return code;
    }
}
