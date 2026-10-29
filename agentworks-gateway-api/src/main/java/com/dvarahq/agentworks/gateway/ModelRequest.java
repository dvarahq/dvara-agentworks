package com.dvarahq.agentworks.gateway;

import java.util.List;
import java.util.Objects;

/**
 * A provider-neutral model request. It carries no model identifier: the model comes from the release pin, so a
 * caller cannot swap models mid-run. {@link #toString()} renders no message content.
 *
 * @param maxOutputTokens optional output bound; {@code null} leaves the provider default
 * @param temperature optional sampling temperature; {@code null} leaves the provider default
 */
public record ModelRequest(List<ModelMessage> messages, List<ToolDefinition> tools, ResponseFormat responseFormat,
        Integer maxOutputTokens, Double temperature) {

    public ModelRequest {
        messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("a model request needs at least one message");
        }
        tools = tools == null ? List.of() : List.copyOf(tools);
        if (tools.stream().map(ToolDefinition::name).distinct().count() != tools.size()) {
            throw new IllegalArgumentException("tool names must be unique");
        }
        responseFormat = responseFormat == null ? ResponseFormat.TEXT : responseFormat;
        if (maxOutputTokens != null && maxOutputTokens < 1) {
            throw new IllegalArgumentException("maxOutputTokens must be positive");
        }
        if (temperature != null && (temperature.isNaN() || temperature < 0 || temperature > 2)) {
            throw new IllegalArgumentException("temperature must be within [0, 2]");
        }
    }

    public static ModelRequest of(ModelMessage... messages) {
        return new ModelRequest(List.of(messages), List.of(), ResponseFormat.TEXT, null, null);
    }

    public ModelRequest withTools(List<ToolDefinition> newTools) {
        return new ModelRequest(messages, newTools, responseFormat, maxOutputTokens, temperature);
    }

    public ModelRequest withResponseFormat(ResponseFormat format) {
        return new ModelRequest(messages, tools, format, maxOutputTokens, temperature);
    }

    @Override
    public String toString() {
        return "ModelRequest[messages=" + messages.size() + ", tools=" + tools.size() + ", responseFormat="
                + responseFormat.kind() + ", maxOutputTokens=" + maxOutputTokens + ", temperature=" + temperature + "]";
    }
}
