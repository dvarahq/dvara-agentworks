package com.dvarahq.agentworks.interaction;

import java.util.Objects;

public record UiContent(DvaraUi ui, ContentMetadata metadata) implements InteractionContent {
    public UiContent {
        Objects.requireNonNull(ui, "ui");
        Objects.requireNonNull(metadata, "metadata");
    }
    @Override public ContentType type() { return ContentType.UI; }
}
