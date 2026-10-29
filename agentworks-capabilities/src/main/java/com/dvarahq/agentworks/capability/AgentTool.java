package com.dvarahq.agentworks.capability;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Immutable discovery metadata; it contains no executable or credential material. */
public record AgentTool(ToolId id, ToolType type, String summary, String description, String inputSchema,
        String schemaHash, ToolEffect effect, Set<String> tags, Set<String> compatibility, int summaryTokens, int detailTokens,
        Availability availability) implements ToolMetadata {
    private static final Pattern DIGEST = Pattern.compile("^sha256:[0-9a-f]{64}$");

    public enum Availability { AVAILABLE, ACTIVE, DISABLED, DENIED }

    public AgentTool {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        summary = text(summary, "summary", 512);
        description = text(description, "description", 4096);
        inputSchema = text(inputSchema, "inputSchema", 262_144);
        Objects.requireNonNull(schemaHash, "schemaHash");
        if (!DIGEST.matcher(schemaHash).matches()) throw new IllegalArgumentException("invalid schema hash");
        if (!schemaHash.equals(digest(inputSchema))) throw new IllegalArgumentException("schema hash mismatch");
        Objects.requireNonNull(effect, "effect");
        tags = Set.copyOf(Objects.requireNonNull(tags, "tags"));
        compatibility = Set.copyOf(Objects.requireNonNull(compatibility, "compatibility"));
        if (summaryTokens < 1 || detailTokens < summaryTokens) throw new IllegalArgumentException("invalid token footprint");
        Objects.requireNonNull(availability, "availability");
    }

    private static String text(String value, String field, int maximum) {
        Objects.requireNonNull(value, field);
        if (value.isBlank() || value.length() > maximum) throw new IllegalArgumentException("invalid " + field);
        return value;
    }

    public static String digest(String schema) {
        Objects.requireNonNull(schema, "schema");
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(schema.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    public ToolSummary metadata() {
        return new ToolSummary(id, type, summary, schemaHash, effect, tags, compatibility, summaryTokens, detailTokens,
                availability);
    }
}
