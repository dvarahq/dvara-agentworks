package com.dvarahq.agentworks.domain.security;

import java.util.Objects;

/** Immutable execution intent and autonomy defaults captured by a release. */
public record ExecutionPolicy(ExecutionIntent initialIntent, AutonomyProfile autonomyProfile) {
    public ExecutionPolicy {
        Objects.requireNonNull(initialIntent, "initialIntent");
        Objects.requireNonNull(autonomyProfile, "autonomyProfile");
    }
}
