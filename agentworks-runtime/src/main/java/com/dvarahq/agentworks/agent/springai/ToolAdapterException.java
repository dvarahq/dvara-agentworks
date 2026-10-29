package com.dvarahq.agentworks.agent.springai;

/** Safe adapter refusal; no underlying exception, request, path, response, or credential is retained. */
public final class ToolAdapterException extends RuntimeException {
    private final String code;
    public ToolAdapterException(String code) {
        super(code, null, false, false);
        if (code == null || !code.matches("[A-Z][A-Z0-9_]{1,63}")) throw new IllegalArgumentException("invalid code");
        this.code = code;
    }
    public String code() { return code; }
}
