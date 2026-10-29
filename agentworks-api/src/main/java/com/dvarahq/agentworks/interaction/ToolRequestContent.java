package com.dvarahq.agentworks.interaction;

import java.util.Map;
import java.util.Objects;

public record ToolRequestContent(String callId, String toolIdentity, Map<String, Object> arguments,
        ContentMetadata metadata) implements InteractionContent {
    public ToolRequestContent {
        callId = InteractionValues.id(callId, "callId");
        toolIdentity = InteractionValues.id(toolIdentity, "toolIdentity");
        arguments = InteractionValues.map(arguments, "arguments");
        Objects.requireNonNull(metadata, "metadata");
    }
    @Override public ContentType type() { return ContentType.TOOL_REQUEST; }
}
