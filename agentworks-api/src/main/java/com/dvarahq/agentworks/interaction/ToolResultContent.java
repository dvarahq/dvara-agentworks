package com.dvarahq.agentworks.interaction;

import java.util.Map;
import java.util.Objects;

public record ToolResultContent(String callId, boolean successful, Map<String, Object> value,
        ContentMetadata metadata) implements InteractionContent {
    public ToolResultContent {
        callId = InteractionValues.id(callId, "callId");
        value = InteractionValues.map(value, "value");
        Objects.requireNonNull(metadata, "metadata");
    }
    @Override public ContentType type() { return ContentType.TOOL_RESULT; }
}
