package com.dvarahq.agentworks.controlplane.agent.web;

public final class WorkspaceScopeUnavailableException extends RuntimeException {
    public WorkspaceScopeUnavailableException() {
        super("no authorized workspace scope is available for this request");
    }
}
