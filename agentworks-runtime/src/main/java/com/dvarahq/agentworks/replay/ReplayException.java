package com.dvarahq.agentworks.replay;

/** A typed, safe replay refusal. */
public final class ReplayException extends RuntimeException {
    private final String code;

    public ReplayException(String code, String message) {
        super(message, null, false, false);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
