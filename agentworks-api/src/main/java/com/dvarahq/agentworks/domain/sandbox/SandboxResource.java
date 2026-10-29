package com.dvarahq.agentworks.domain.sandbox;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** PostgreSQL-authoritative lifecycle state for one externally allocated sandbox. */
public record SandboxResource(SandboxRef reference, String provider, State state, Instant expiresAt,
        Instant cleanupAfter, long cleanupFence, Optional<CleanupLease> cleanupLease, int cleanupFailures,
        Optional<String> lastFailureCode, Instant createdAt, Instant updatedAt, Optional<Instant> terminalAt) {
    public SandboxResource {
        Objects.requireNonNull(reference, "reference");
        if (provider == null || !provider.matches("[a-z][a-z0-9-]{0,31}")) {
            throw new IllegalArgumentException("sandbox provider identity is invalid");
        }
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(cleanupAfter, "cleanupAfter");
        if (cleanupFence < 0) throw new IllegalArgumentException("cleanupFence cannot be negative");
        cleanupLease = Objects.requireNonNull(cleanupLease, "cleanupLease");
        if ((state == State.CLEANING) != cleanupLease.isPresent()) {
            throw new IllegalArgumentException("only a cleaning sandbox has a cleanup lease");
        }
        if (cleanupLease.isPresent() && cleanupLease.orElseThrow().fence() != cleanupFence) {
            throw new IllegalArgumentException("cleanup lease fence does not match resource fence");
        }
        if (cleanupFailures < 0) throw new IllegalArgumentException("cleanupFailures cannot be negative");
        lastFailureCode = Objects.requireNonNull(lastFailureCode, "lastFailureCode");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        terminalAt = Objects.requireNonNull(terminalAt, "terminalAt");
        if ((state == State.TERMINAL) != terminalAt.isPresent()) {
            throw new IllegalArgumentException("only a terminal sandbox has terminalAt");
        }
    }

    public enum State { PROVISIONING, ACTIVE, CLEANUP_PENDING, CLEANING, TERMINAL }

    public record CleanupLease(String owner, long fence, Instant until) {
        public CleanupLease {
            if (owner == null || owner.isBlank() || owner.length() > 128) {
                throw new IllegalArgumentException("cleanup lease owner is invalid");
            }
            if (fence < 1) throw new IllegalArgumentException("cleanup lease fence must be positive");
            Objects.requireNonNull(until, "until");
        }
    }
}
