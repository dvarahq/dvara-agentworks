package com.dvarahq.agentworks.interaction;

import java.net.URI;
import java.util.Objects;

public record ReferenceContent(URI uri, String label, String relation,
        ContentMetadata metadata) implements InteractionContent {
    public ReferenceContent {
        uri = InteractionValues.uri(uri, true);
        label = InteractionValues.text(label, "label", 1024);
        relation = InteractionValues.id(relation, "relation");
        Objects.requireNonNull(metadata, "metadata");
    }
    @Override public ContentType type() { return ContentType.REFERENCE; }
}
