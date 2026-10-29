package com.dvarahq.agentworks.controlplane.agent;

/** A request conflicts with an existing definition name or published version identity. */
public final class AgentLifecycleConflictException extends RuntimeException {
    private final String code;

    public AgentLifecycleConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
