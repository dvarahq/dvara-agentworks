package com.dvarahq.agentworks.capability;

import com.dvarahq.agentworks.domain.security.PermissionRisk;

import java.util.Objects;

/** Stable authorization facts declared by a tool version and pinned into every new release. */
public record ToolEffect(String resource, String action, PermissionRisk risk) {
    public static final String UNCLASSIFIED_RESOURCE = "legacy-unclassified";

    public ToolEffect {
        resource = exact(resource, "resource", 256);
        action = exact(action, "action", 128);
        Objects.requireNonNull(risk, "risk");
    }

    /** Fail-safe compatibility value for callers that predate explicit effect declarations. */
    public static ToolEffect unclassified() {
        return new ToolEffect(UNCLASSIFIED_RESOURCE, "invoke", PermissionRisk.CRITICAL);
    }

    private static String exact(String value, String field, int maximum) {
        Objects.requireNonNull(value, field);
        if (value.isBlank() || !value.equals(value.trim()) || value.length() > maximum) {
            throw new IllegalArgumentException(field + " must be a bounded non-blank exact value");
        }
        return value;
    }
}
