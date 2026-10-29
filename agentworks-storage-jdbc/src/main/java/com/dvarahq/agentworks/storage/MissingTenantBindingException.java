package com.dvarahq.agentworks.storage;

public final class MissingTenantBindingException extends IllegalStateException {
    public MissingTenantBindingException(String message) { super(message); }
    public MissingTenantBindingException(String message, Throwable cause) { super(message, cause); }
}
