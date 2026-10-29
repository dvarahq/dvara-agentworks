package com.dvarahq.agentworks.sandbox;

import com.dvarahq.agentworks.domain.sandbox.SandboxCapability;

import java.util.Set;
import java.util.TreeSet;

/** A sandbox was refused before allocation because its provider cannot enforce the requested assurances. */
public final class SandboxCapabilityUnavailableException extends RuntimeException {
    private final Set<SandboxCapability> required;
    private final Set<SandboxCapability> available;

    public SandboxCapabilityUnavailableException(Set<SandboxCapability> required, Set<SandboxCapability> available) {
        super("Sandbox capabilities unavailable: required=" + new TreeSet<>(required)
                + " available=" + new TreeSet<>(available));
        this.required = Set.copyOf(required);
        this.available = Set.copyOf(available);
    }

    public Set<SandboxCapability> required() {
        return required;
    }

    public Set<SandboxCapability> available() {
        return available;
    }
}
