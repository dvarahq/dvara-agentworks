package com.dvarahq.agentworks.testing.simulation;

import java.util.*;

/** Reproduction and cleanup evidence embedded in every AgentTest report. */
public record SimulationEvidence(String environment, int version, long seed, List<SimulationFault> faults,
        List<FaultInjection> injections, Map<String, String> cleanup) {
    public SimulationEvidence {
        if (environment == null || environment.isBlank() || version < 1) throw new IllegalArgumentException("invalid simulation identity");
        faults = List.copyOf(faults); injections = List.copyOf(injections);
        cleanup = Collections.unmodifiableMap(new LinkedHashMap<>(cleanup));
    }
    public static SimulationEvidence direct(long seed) {
        return new SimulationEvidence("direct-agent-test", 1, seed, List.of(), List.of(), Map.of());
    }
}
