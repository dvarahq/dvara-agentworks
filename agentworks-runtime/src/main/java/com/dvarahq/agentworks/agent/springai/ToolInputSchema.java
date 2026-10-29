package com.dvarahq.agentworks.agent.springai;

import com.dvarahq.agentworks.capability.AgentTool;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Compiled, offline JSON Schema validation at the tool execution boundary. */
final class ToolInputSchema {
    enum Verdict { ALLOW, DENY }

    record Decision(Verdict verdict, String reason, int violations, String schemaHash) {
        Decision {
            Objects.requireNonNull(verdict, "verdict");
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(schemaHash, "schemaHash");
            if (violations < 0) throw new IllegalArgumentException("violations cannot be negative");
        }

        boolean allowed() {
            return verdict == Verdict.ALLOW;
        }
    }

    private static final String DRAFT_2020_12 = "https://json-schema.org/draft/2020-12/schema";
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private final Schema schema;
    private final String schemaHash;

    private ToolInputSchema(Schema schema, String schemaHash) {
        this.schema = schema;
        this.schemaHash = schemaHash;
    }

    static ToolInputSchema compile(String schemaJson) {
        JsonNode document;
        try {
            document = JSON.readTree(schemaJson);
        } catch (JacksonException malformed) {
            throw invalidSchema();
        }
        if (document == null || !document.isObject()) throw invalidSchema();
        selfContained(document);
        try {
            Schema compiled = registry().getSchema(document);
            return new ToolInputSchema(compiled, AgentTool.digest(schemaJson));
        } catch (RuntimeException unusable) {
            throw invalidSchema();
        }
    }

    Decision validate(String input) {
        JsonNode value;
        try {
            value = JSON.readTree(input == null ? "" : input);
        } catch (JacksonException malformed) {
            return new Decision(Verdict.DENY, "INVALID_JSON", 1, schemaHash);
        }
        if (value == null || value.isMissingNode()) {
            return new Decision(Verdict.DENY, "INVALID_JSON", 1, schemaHash);
        }
        List<com.networknt.schema.Error> errors;
        try {
            errors = schema.validate(value);
        } catch (RuntimeException refused) {
            return new Decision(Verdict.DENY, "VALIDATION_FAILED", 1, schemaHash);
        }
        if (!errors.isEmpty()) {
            return new Decision(Verdict.DENY, "SCHEMA_VIOLATION", errors.size(), schemaHash);
        }
        return new Decision(Verdict.ALLOW, "SCHEMA_MATCHED", 0, schemaHash);
    }

    /** Refuse schemas that can load remote resources or use unbounded regular-expression evaluation. */
    private static void selfContained(JsonNode node) {
        if (node.isArray()) {
            for (JsonNode element : node.values()) selfContained(element);
            return;
        }
        if (!node.isObject()) return;
        for (Map.Entry<String, JsonNode> field : node.properties()) {
            String key = field.getKey();
            JsonNode value = field.getValue();
            switch (key) {
                case "$id" -> {
                    if (value.isString()) throw invalidSchema();
                }
                case "$schema" -> {
                    if (value.isString() && !DRAFT_2020_12.equals(value.stringValue())) throw invalidSchema();
                }
                case "$ref", "$dynamicRef", "$recursiveRef" -> {
                    if (value.isString() && !value.stringValue().startsWith("#")) throw invalidSchema();
                }
                case "pattern" -> {
                    if (value.isString()) throw invalidSchema();
                }
                case "patternProperties" -> throw invalidSchema();
                case "format" -> {
                    if (value.isString() && "regex".equals(value.stringValue())) throw invalidSchema();
                }
                default -> {
                }
            }
            selfContained(value);
        }
    }

    private static SchemaRegistry registry() {
        return SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12, builder -> builder.schemaLoader(
                loader -> loader.fetchRemoteResources(false)
                        .block(iri -> !iri.toString().startsWith("https://json-schema.org/draft/2020-12/"))));
    }

    private static ExecutorInput.InvalidInputException invalidSchema() {
        return new ExecutorInput.InvalidInputException("TOOL_INPUT_SCHEMA_INVALID",
                "a selected tool has an unusable or unsafe input schema");
    }
}
