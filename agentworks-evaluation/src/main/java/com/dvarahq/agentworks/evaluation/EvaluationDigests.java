package com.dvarahq.agentworks.evaluation;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;

/** Content identities used by immutable evaluation definitions and evidence. */
public final class EvaluationDigests {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private EvaluationDigests() {}
    public static String json(JsonNode value) { return text(JSON.writeValueAsString(canonical(value))); }
    public static String value(Object value) { return json(JSON.valueToTree(value)); }
    public static String text(String value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static JsonNode canonical(JsonNode value) {
        if (value.isObject()) {
            ObjectNode sorted = JsonNodeFactory.instance.objectNode();
            value.properties().stream().sorted(Comparator.comparing(java.util.Map.Entry::getKey))
                    .forEach(entry -> sorted.set(entry.getKey(), canonical(entry.getValue())));
            return sorted;
        }
        if (value.isArray()) {
            ArrayNode ordered = JsonNodeFactory.instance.arrayNode();
            value.values().forEach(item -> ordered.add(canonical(item)));
            return ordered;
        }
        return value;
    }
}
