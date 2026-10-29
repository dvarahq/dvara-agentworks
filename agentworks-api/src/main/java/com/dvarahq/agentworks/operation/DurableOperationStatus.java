package com.dvarahq.agentworks.operation;

/** One-winner durable operation lifecycle. */
public enum DurableOperationStatus {
    STARTING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED;

    public boolean terminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }
}
