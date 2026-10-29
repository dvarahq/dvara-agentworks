package com.dvarahq.agentworks.domain.artifact;

import java.util.Locale;

/** Canonical SHA-256 content digest. */
public record ArtifactDigest(String value) {
    public ArtifactDigest {
        if (value == null || !value.toLowerCase(Locale.ROOT).matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("artifact digest must be canonical sha256");
        }
        value = value.toLowerCase(Locale.ROOT);
    }
}
