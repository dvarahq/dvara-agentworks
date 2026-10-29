package com.dvarahq.agentworks.memory;

public final class KnowledgeRetrievalException extends RuntimeException {
    private final String code;

    public KnowledgeRetrievalException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() { return code; }
}
