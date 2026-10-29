package com.dvarahq.agentworks.interaction;

import java.util.Objects;

public record TextContent(String text, ContentMetadata metadata) implements InteractionContent {
    public TextContent {
        text = InteractionValues.text(text, "text", 1_048_576);
        Objects.requireNonNull(metadata, "metadata");
    }
    @Override public ContentType type() { return ContentType.TEXT; }
}
