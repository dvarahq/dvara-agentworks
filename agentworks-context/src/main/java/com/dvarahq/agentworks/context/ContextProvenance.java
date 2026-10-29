package com.dvarahq.agentworks.context;

import java.util.Objects;

/** Non-content attribution retained in compiler decisions and durable debug evidence. */
public record ContextProvenance(String sourceType, String sourceId, String contentDigest, String trust) {
    public ContextProvenance {
        sourceType = required(sourceType, "sourceType", 64);
        sourceId = required(sourceId, "sourceId", 256);
        contentDigest = required(contentDigest, "contentDigest", 80);
        if (!contentDigest.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("contentDigest must be sha256:<64 lowercase hex>");
        }
        trust = required(trust, "trust", 64);
    }

    private static String required(String value, String name, int maximum) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > maximum) throw new IllegalArgumentException(name + " is invalid");
        return value;
    }
}
