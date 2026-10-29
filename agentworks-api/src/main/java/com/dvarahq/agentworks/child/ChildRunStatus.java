package com.dvarahq.agentworks.child;

/** Durable outcome of a parent-directed child invocation. */
public enum ChildRunStatus {
    STARTING, RUNNING, SUCCEEDED, FAILED, CANCELLED;

    public boolean terminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }
}
