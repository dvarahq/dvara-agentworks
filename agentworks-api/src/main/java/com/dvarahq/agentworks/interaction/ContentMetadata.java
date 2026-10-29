package com.dvarahq.agentworks.interaction;

import java.util.Objects;
import java.util.Set;

/** Security and provenance metadata that follows content through every channel adapter. */
public record ContentMetadata(String classification, String sourceTrust, boolean redacted, long sizeBytes) {
    private static final Set<String> CLASSIFICATIONS = Set.of("PUBLIC", "INTERNAL", "CONFIDENTIAL", "RESTRICTED");
    private static final Set<String> TRUST = Set.of("UNTRUSTED", "AUTHENTICATED", "SYSTEM", "VERIFIED");

    public ContentMetadata {
        Objects.requireNonNull(classification, "classification");
        Objects.requireNonNull(sourceTrust, "sourceTrust");
        if (!CLASSIFICATIONS.contains(classification) || !TRUST.contains(sourceTrust) || sizeBytes < 0) {
            throw new IllegalArgumentException("invalid content metadata");
        }
    }

    public static ContentMetadata untrusted(long sizeBytes) {
        return new ContentMetadata("INTERNAL", "UNTRUSTED", false, sizeBytes);
    }
}
