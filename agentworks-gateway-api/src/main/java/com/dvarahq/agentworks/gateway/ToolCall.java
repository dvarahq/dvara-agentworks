package com.dvarahq.agentworks.gateway;

import java.util.Objects;

/** A tool call requested by the model; {@link #toString()} does not render arguments. */
public record ToolCall(String id, String name, String argumentsJson) {
    public ToolCall {
        GatewayDescriptor.requireText(id, "id");
        GatewayDescriptor.requireText(name, "name");
        Objects.requireNonNull(argumentsJson, "argumentsJson");
    }

    @Override
    public String toString() {
        return "ToolCall[id=" + id + ", name=" + name + ", arguments=<redacted>]";
    }
}
