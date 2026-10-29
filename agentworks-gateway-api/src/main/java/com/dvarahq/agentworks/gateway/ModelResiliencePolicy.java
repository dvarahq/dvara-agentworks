package com.dvarahq.agentworks.gateway;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Immutable, release-capturable policy for one model role. Fallback identities are concrete release-time pins, never
 * mutable aliases. The conservative default makes one attempt and permits no substitution.
 */
public record ModelResiliencePolicy(int maxAttemptsPerRoute, Duration initialBackoff, Duration maxBackoff,
        double jitter, int maxConcurrent, Duration bulkheadWait, int breakerFailureThreshold,
        Duration breakerOpenDuration, Duration requestTimeout, Duration streamIdleTimeout,
        List<ResolvedModelIdentity> fallbacks) {

    public static final ModelResiliencePolicy DEFAULT = new ModelResiliencePolicy(1, Duration.ofMillis(100),
            Duration.ofSeconds(2), 0.2, 32, Duration.ZERO, 5, Duration.ofSeconds(30), Duration.ofMinutes(2),
            Duration.ofSeconds(30), List.of());

    public ModelResiliencePolicy {
        if (maxAttemptsPerRoute < 1 || maxAttemptsPerRoute > 4) {
            throw new IllegalArgumentException("maxAttemptsPerRoute must be between 1 and 4");
        }
        Objects.requireNonNull(initialBackoff, "initialBackoff");
        Objects.requireNonNull(maxBackoff, "maxBackoff");
        Objects.requireNonNull(bulkheadWait, "bulkheadWait");
        Objects.requireNonNull(breakerOpenDuration, "breakerOpenDuration");
        Objects.requireNonNull(requestTimeout, "requestTimeout");
        Objects.requireNonNull(streamIdleTimeout, "streamIdleTimeout");
        if (initialBackoff.isNegative() || maxBackoff.isNegative() || maxBackoff.compareTo(Duration.ofSeconds(30)) > 0
                || initialBackoff.compareTo(maxBackoff) > 0) {
            throw new IllegalArgumentException("backoff must be ordered, non-negative, and at most 30 seconds");
        }
        if (!Double.isFinite(jitter) || jitter < 0 || jitter > 1) {
            throw new IllegalArgumentException("jitter must be between 0 and 1");
        }
        if (maxConcurrent < 1 || maxConcurrent > 10_000) {
            throw new IllegalArgumentException("maxConcurrent must be between 1 and 10000");
        }
        if (bulkheadWait.isNegative() || bulkheadWait.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException("bulkheadWait must be between zero and 30 seconds");
        }
        if (breakerFailureThreshold < 1 || breakerFailureThreshold > 10_000) {
            throw new IllegalArgumentException("breakerFailureThreshold must be between 1 and 10000");
        }
        if (breakerOpenDuration.isNegative() || breakerOpenDuration.isZero()
                || breakerOpenDuration.compareTo(Duration.ofMinutes(10)) > 0) {
            throw new IllegalArgumentException("breakerOpenDuration must be positive and at most 10 minutes");
        }
        if (requestTimeout.isNegative() || requestTimeout.isZero()
                || requestTimeout.compareTo(ModelInvocation.MAX_TIMEOUT) > 0) {
            throw new IllegalArgumentException("requestTimeout must be positive and at most "
                    + ModelInvocation.MAX_TIMEOUT);
        }
        if (streamIdleTimeout.isNegative() || streamIdleTimeout.isZero()
                || streamIdleTimeout.compareTo(Duration.ofMinutes(10)) > 0) {
            throw new IllegalArgumentException("streamIdleTimeout must be positive and at most 10 minutes");
        }
        fallbacks = List.copyOf(Objects.requireNonNull(fallbacks, "fallbacks"));
        if (fallbacks.size() > 3) throw new IllegalArgumentException("at most 3 fallbacks are permitted");
    }

    public ModelResiliencePolicy withFallbacks(List<ResolvedModelIdentity> identities) {
        return new ModelResiliencePolicy(maxAttemptsPerRoute, initialBackoff, maxBackoff, jitter, maxConcurrent,
                bulkheadWait, breakerFailureThreshold, breakerOpenDuration, requestTimeout, streamIdleTimeout,
                identities);
    }
}
