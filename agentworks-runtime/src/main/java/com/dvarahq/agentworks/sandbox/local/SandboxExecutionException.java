package com.dvarahq.agentworks.sandbox.local;

public final class SandboxExecutionException extends RuntimeException {
    public SandboxExecutionException(String message) {
        super(message);
    }

    public SandboxExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
