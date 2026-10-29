package com.dvarahq.agentworks.capability;

import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Discovery view that structurally cannot contain a description or input schema. */
public record ToolSummary(ToolId id, ToolType type, String summary, String schemaHash, ToolEffect effect, Set<String> tags,
        Set<String> compatibility, int summaryTokens, int detailTokens, AgentTool.Availability availability)
        implements ToolMetadata {
    private static final Pattern DIGEST = Pattern.compile("^sha256:[0-9a-f]{64}$");

    public ToolSummary {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(summary, "summary");
        Objects.requireNonNull(schemaHash, "schemaHash");
        if (!DIGEST.matcher(schemaHash).matches()) throw new IllegalArgumentException("invalid schema hash");
        Objects.requireNonNull(effect, "effect");
        tags = Set.copyOf(Objects.requireNonNull(tags, "tags"));
        compatibility = Set.copyOf(Objects.requireNonNull(compatibility, "compatibility"));
        Objects.requireNonNull(availability, "availability");
        if (summary.isBlank() || summaryTokens < 1 || detailTokens < summaryTokens) {
            throw new IllegalArgumentException("invalid tool summary");
        }
    }
}
