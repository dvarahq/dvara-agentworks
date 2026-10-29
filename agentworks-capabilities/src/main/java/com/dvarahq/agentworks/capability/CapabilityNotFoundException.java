package com.dvarahq.agentworks.capability;

/** A tenant-scoped capability identity was absent or not discoverable. */
public final class CapabilityNotFoundException extends RuntimeException {
    public CapabilityNotFoundException(String kind) {
        super(kind + " not found");
    }
}
