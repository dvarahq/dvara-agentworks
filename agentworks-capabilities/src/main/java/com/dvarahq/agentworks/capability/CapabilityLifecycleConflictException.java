package com.dvarahq.agentworks.capability;

/** The requested lifecycle transition is not legal from the catalog's current state. */
public final class CapabilityLifecycleConflictException extends RuntimeException {
    public CapabilityLifecycleConflictException(String kind) {
        super(kind + " lifecycle transition is not allowed");
    }
}
