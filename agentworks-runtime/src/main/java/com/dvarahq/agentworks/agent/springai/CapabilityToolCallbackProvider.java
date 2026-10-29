package com.dvarahq.agentworks.agent.springai;

/** Supplies exact versioned capability-to-callback bindings for version-3 releases. */
@FunctionalInterface
public interface CapabilityToolCallbackProvider {
    CapabilityToolCallback[] getCapabilityToolCallbacks();

    static CapabilityToolCallbackProvider from(CapabilityToolCallback... callbacks) {
        CapabilityToolCallback[] snapshot = callbacks.clone();
        return () -> snapshot.clone();
    }
}
