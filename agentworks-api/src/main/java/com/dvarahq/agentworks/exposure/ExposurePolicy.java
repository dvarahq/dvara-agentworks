package com.dvarahq.agentworks.exposure;

import java.util.Set;

/** Immutable channel policy captured in a deployment revision. */
public record ExposurePolicy(String authentication, Set<String> requiredScopes, long requestsPerMinute,
        long maxItemBytes, long maxEnvelopeBytes, Set<String> classifications, boolean toolContentAllowed) {
    public ExposurePolicy {
        if (authentication == null || authentication.isBlank() || authentication.length() > 128) {
            throw new IllegalArgumentException("authentication policy is required");
        }
        requiredScopes = Set.copyOf(requiredScopes == null ? Set.of() : requiredScopes);
        classifications = Set.copyOf(classifications == null ? Set.of("PUBLIC", "INTERNAL") : classifications);
        if (requestsPerMinute < 1 || maxItemBytes < 1 || maxEnvelopeBytes < maxItemBytes) {
            throw new IllegalArgumentException("invalid exposure bounds");
        }
    }
}
