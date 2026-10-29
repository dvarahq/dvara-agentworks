package com.dvarahq.agentworks.sdk.stream;

/** Stable, secret-free stream failure. */
public final class RunStreamException extends RuntimeException {
    private final String code;
    public RunStreamException(String code, String message) { super(message); this.code = code; }
    public RunStreamException(String code, String message, Throwable cause) { super(message, cause); this.code = code; }
    public String code() { return code; }
}
