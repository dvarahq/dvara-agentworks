package com.dvarahq.agentworks.context;

public final class ContextCompilationException extends RuntimeException {
    private final String code;
    public ContextCompilationException(String code, String message) {
        super(message);
        this.code = code;
    }
    public String code() { return code; }
}
