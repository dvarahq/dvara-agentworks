package com.dvarahq.agentworks.interaction;

import java.util.Map;
import java.util.Objects;

public record StructuredContent(String schemaId, Map<String, Object> value,
        ContentMetadata metadata) implements InteractionContent {
    public StructuredContent {
        schemaId = InteractionValues.id(schemaId, "schemaId");
        value = InteractionValues.map(value, "value");
        Objects.requireNonNull(metadata, "metadata");
    }
    @Override public ContentType type() { return ContentType.STRUCTURED; }
}
