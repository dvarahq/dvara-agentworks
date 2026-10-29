package com.dvarahq.agentworks.gateway;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Requested output format. {@link Kind#JSON_SCHEMA} needs {@link GatewayCapability#STRUCTURED_OUTPUT}.
 *
 * @param name the schema name providers require; {@code null} for text
 * @param jsonSchema the JSON Schema document; {@code null} for text
 * @param strict ask the provider to enforce the schema exactly. Strict enforcement usually restricts the schema (for
 *        OpenAI: every property required and {@code additionalProperties: false}); a provider refuses a schema it
 *        cannot enforce strictly with {@code PROVIDER_REJECTED_REQUEST} before producing output. With {@code false} the
 *        schema guides the model but may not be enforced, so the caller validates the content. Always {@code false}
 *        for text.
 */
public record ResponseFormat(Kind kind, String name, String jsonSchema, boolean strict) {

    public enum Kind { TEXT, JSON_SCHEMA }

    public static final ResponseFormat TEXT = new ResponseFormat(Kind.TEXT, null, null, false);

    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    public ResponseFormat {
        Objects.requireNonNull(kind, "kind");
        if (kind == Kind.TEXT && (name != null || jsonSchema != null || strict)) {
            throw new IllegalArgumentException("text format has no schema");
        }
        if (kind == Kind.JSON_SCHEMA) {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(jsonSchema, "jsonSchema");
            if (!NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("schema name must match " + NAME.pattern());
            }
        }
    }

    /** A strictly enforced JSON Schema format. */
    public static ResponseFormat jsonSchema(String name, String jsonSchema) {
        return jsonSchema(name, jsonSchema, true);
    }

    public static ResponseFormat jsonSchema(String name, String jsonSchema, boolean strict) {
        return new ResponseFormat(Kind.JSON_SCHEMA, name, jsonSchema, strict);
    }
}
