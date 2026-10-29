package com.dvarahq.agentworks.storage.lifecycle;

/** Typed failure after bounded retry; the operation committed nothing. */
public final class LifecycleRetryExhaustedException extends RuntimeException {
    private final String operation;

    public LifecycleRetryExhaustedException(String operation, int attempts, Throwable cause) {
        super(operation + " did not commit after " + attempts + " attempts; no partial state was committed", cause);
        this.operation = operation;
    }

    public String operation() {
        return operation;
    }
}
