package com.dvarahq.agentworks.tck;

/** Creates fresh ready or intentionally unavailable fixtures for deterministic conformance checks. */
@FunctionalInterface
public interface ProviderProbeFactory {
    ProviderProbe create(boolean ready);
}
