package com.dvarahq.agentworks.evaluation;

public final class EvaluationException extends RuntimeException {
    private final String code;
    public EvaluationException(String code, String message) { super(message); this.code = code; }
    public String code() { return code; }
}
