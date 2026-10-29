package com.dvarahq.agentworks.agent.springai;

import com.dvarahq.agentworks.gateway.ResponseFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * What one run asks the Spring AI executor to do, read from the run's durable input.
 * <ul>
 *   <li>A text input is the user message.</li>
 *   <li>An object with a text {@code prompt} uses it as the user message and may request structured output with
 *       {@code responseFormat: {name, schema, strict}}: a JSON Schema (draft 2020-12) that the model output must
 *       satisfy.</li>
 *   <li>Any other JSON input is sent as its canonical JSON text.</li>
 * </ul>
 * A requested schema is self-contained: {@code $id}, a {@code $schema} other than draft 2020-12, and any non-local
 * {@code $ref} are refused, so validation never loads a resource.
 */
record ExecutorInput(String prompt, Optional<StructuredOutput> structured) {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final String DRAFT_2020_12 = "https://json-schema.org/draft/2020-12/schema";

    /** The run input is not something the executor can act on. */
    static final class InvalidInputException extends RuntimeException {
        private final String code;

        InvalidInputException(String code, String message) {
            super(message, null, false, false);
            this.code = code;
        }

        String code() {
            return code;
        }
    }

    /** A requested JSON output and the compiled schema it is validated against. */
    record StructuredOutput(String name, String schemaJson, boolean strict, Schema schema) {
        ResponseFormat responseFormat() {
            return ResponseFormat.jsonSchema(name, schemaJson, strict);
        }

        /**
         * The model output as JSON satisfying the schema, or a typed failure. The failure names only the schema rule
         * broken and where, never the output text.
         */
        JsonNode validate(String content) {
            JsonNode value;
            try {
                value = JSON.readTree(content);
            } catch (JacksonException malformed) {
                throw new InvalidInputException("STRUCTURED_OUTPUT_INVALID", "the model output is not valid JSON");
            }
            if (value == null || value.isMissingNode()) {
                throw new InvalidInputException("STRUCTURED_OUTPUT_INVALID", "the model output is empty");
            }
            List<com.networknt.schema.Error> errors = schema.validate(value);
            if (!errors.isEmpty()) {
                var first = errors.getFirst();
                throw new InvalidInputException("STRUCTURED_OUTPUT_INVALID", "the model output violates schema keyword '"
                        + first.getKeyword() + "' at '" + first.getInstanceLocation() + "' (" + errors.size()
                        + (errors.size() == 1 ? " violation)" : " violations)"));
            }
            return value;
        }
    }

    static ExecutorInput from(JsonNode input) {
        if (input == null || input.isNull() || input.isMissingNode()) {
            throw new InvalidInputException("INVALID_INPUT", "the run has no input");
        }
        if (input.isString()) {
            if (input.stringValue().isBlank()) throw invalid("the run input is blank");
            return new ExecutorInput(input.stringValue(), Optional.empty());
        }
        JsonNode prompt = input.get("prompt");
        if (!input.isObject() || prompt == null) return new ExecutorInput(JSON.writeValueAsString(input), Optional.empty());
        if (!prompt.isString() || prompt.stringValue().isBlank()) {
            throw new InvalidInputException("INVALID_INPUT", "prompt is a non-blank string");
        }
        JsonNode format = input.get("responseFormat");
        if (format == null || format.isNull()) return new ExecutorInput(prompt.stringValue(), Optional.empty());
        return new ExecutorInput(prompt.stringValue(), Optional.of(structured(format)));
    }

    private static StructuredOutput structured(JsonNode format) {
        if (!format.isObject()) throw invalid("responseFormat is an object");
        JsonNode name = format.path("name");
        JsonNode schema = format.path("schema");
        JsonNode strict = format.path("strict");
        if (!name.isString() || !NAME.matcher(name.stringValue()).matches()) {
            throw invalid("responseFormat.name is 1 to 64 characters of [A-Za-z0-9_-]");
        }
        if (!schema.isObject()) throw invalid("responseFormat.schema is a JSON Schema object");
        if (!strict.isMissingNode() && !strict.isBoolean()) throw invalid("responseFormat.strict is a boolean");
        selfContained(schema);
        Schema compiled;
        try {
            compiled = registry().getSchema(schema);
        } catch (RuntimeException unusable) {
            throw invalid("responseFormat.schema is not a usable JSON Schema");
        }
        return new StructuredOutput(name.stringValue(), JSON.writeValueAsString(schema),
                strict.isMissingNode() || strict.booleanValue(), compiled);
    }

    /**
     * Walks every value of the schema, whatever keyword or name holds it, and refuses what could make validation load
     * a resource or run unbounded work on model output. Walking everything (rather than only known subschema keywords)
     * means an in-document {@code $ref} can never reach an unchecked location. It refuses, anywhere:
     * <ul>
     *   <li>{@code $id} with a text value, a {@code $schema} other than draft 2020-12, and a {@code $ref},
     *       {@code $dynamicRef}, or {@code $recursiveRef} that is not a local {@code #} pointer;</li>
     *   <li>regular expressions: {@code pattern} with a text value, {@code patternProperties}, and
     *       {@code format: regex}. {@code java.util.regex} can backtrack catastrophically and ignores interrupts.</li>
     * </ul>
     * A property named {@code pattern} or {@code $id} holds a schema object, not text, and stays allowed; a property
     * named {@code patternProperties} is refused, conservatively.
     */
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
                    if (value.isString()) throw invalid("responseFormat.schema may not declare $id");
                }
                case "$schema" -> {
                    if (value.isString() && !DRAFT_2020_12.equals(value.stringValue())) {
                        throw invalid("responseFormat.schema must use JSON Schema draft 2020-12");
                    }
                }
                case "$ref", "$dynamicRef", "$recursiveRef" -> {
                    if (value.isString() && !value.stringValue().startsWith("#")) {
                        throw invalid("responseFormat.schema may only reference its own definitions");
                    }
                }
                case "pattern" -> {
                    if (value.isString()) throw invalid("responseFormat.schema may not use regular expressions (pattern)");
                }
                case "patternProperties" ->
                        throw invalid("responseFormat.schema may not use regular expressions (patternProperties)");
                case "format" -> {
                    if (value.isString() && "regex".equals(value.stringValue())) {
                        throw invalid("responseFormat.schema may not use regular expressions (format: regex)");
                    }
                }
                default -> {
                }
            }
            selfContained(value);
        }
    }

    /**
     * The validator never retrieves anything: remote fetching is off, and every resource except the draft 2020-12
     * meta-schemas (which the library serves from its classpath) is blocked. Defense in depth behind
     * {@link #selfContained(JsonNode)}.
     */
    private static SchemaRegistry registry() {
        return SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12, builder -> builder.schemaLoader(
                loader -> loader.fetchRemoteResources(false)
                        .block(iri -> !iri.toString().startsWith("https://json-schema.org/draft/2020-12/"))));
    }

    private static InvalidInputException invalid(String message) {
        return new InvalidInputException("INVALID_INPUT", message);
    }
}
