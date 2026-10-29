package com.dvarahq.agentworks.testing;
public final class ForbiddenEffectException extends RuntimeException {
    private final String code;
    public ForbiddenEffectException(String code) { super(code); this.code = code; }
    public String code() { return code; }
}
