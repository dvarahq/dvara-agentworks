package com.dvarahq.agentworks.context;

import java.math.BigDecimal;
import java.util.Objects;

/** Immutable release-captured context compiler policy. */
public record PinnedContextPolicy(int maximumTokens, String loadingPolicy, boolean cacheAware,
        boolean compactionEnabled, String compactionStrategy, BigDecimal compactionThreshold, String policyHash) {
    public PinnedContextPolicy {
        if (maximumTokens < 1 || maximumTokens > 1_000_000) throw new IllegalArgumentException("invalid maximumTokens");
        loadingPolicy = required(loadingPolicy, "loadingPolicy");
        compactionStrategy = required(compactionStrategy, "compactionStrategy");
        Objects.requireNonNull(compactionThreshold, "compactionThreshold");
        if (compactionThreshold.signum() < 0 || compactionThreshold.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("invalid compactionThreshold");
        }
        policyHash = required(policyHash, "policyHash");
        if (!policyHash.matches("sha256:[0-9a-f]{64}")) throw new IllegalArgumentException("invalid policyHash");
    }
    private static String required(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > 80) throw new IllegalArgumentException(name + " is invalid");
        return value;
    }
}
