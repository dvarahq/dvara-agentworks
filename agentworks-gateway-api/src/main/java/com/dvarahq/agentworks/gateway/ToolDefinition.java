package com.dvarahq.agentworks.gateway;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A tool offered to the model.
 *
 * @param parametersJsonSchema the JSON Schema of the arguments, as a JSON object document
 */
public record ToolDefinition(String name, String description, String parametersJsonSchema) {

    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    public ToolDefinition {
        Objects.requireNonNull(name, "name");
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("tool name must match " + NAME.pattern());
        }
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(parametersJsonSchema, "parametersJsonSchema");
    }
}
