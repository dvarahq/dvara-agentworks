package com.dvarahq.agentworks.controlplane.lifecycle;

/** Absent from the caller's workspace; missing and cross-workspace resources are indistinguishable. */
public final class LifecycleNotFoundException extends RuntimeException {
    public LifecycleNotFoundException() {
        super("lifecycle resource not found");
    }
}
