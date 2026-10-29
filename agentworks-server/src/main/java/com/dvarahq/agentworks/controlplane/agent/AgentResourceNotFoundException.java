package com.dvarahq.agentworks.controlplane.agent;

/**
 * A definition or version is absent from the caller's workspace. Missing and cross-workspace resources are
 * deliberately indistinguishable.
 */
public final class AgentResourceNotFoundException extends RuntimeException {
    public AgentResourceNotFoundException() {
        super("agent resource not found");
    }
}
