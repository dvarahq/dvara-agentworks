package com.dvarahq.agentworks.testing.simulation;

/** Content-free evidence that a configured deterministic fault fired. */
public record FaultInjection(String faultId, String dependencyClass, String dependency, String operation,
        long occurrence, long seed, String failureCode) {}
