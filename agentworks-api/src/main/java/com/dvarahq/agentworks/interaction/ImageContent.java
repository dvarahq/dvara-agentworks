package com.dvarahq.agentworks.interaction;

import java.net.URI;
import java.util.Objects;

public record ImageContent(URI uri, String mediaType, String altText, String digest,
        ContentMetadata metadata) implements InteractionContent {
    public ImageContent {
        uri = InteractionValues.uri(uri, true);
        mediaType = InteractionValues.text(mediaType, "mediaType", 128);
        if (!mediaType.startsWith("image/")) throw new IllegalArgumentException("image media type required");
        if (altText != null && altText.length() > 4096) throw new IllegalArgumentException("altText is too long");
        digest = InteractionValues.digest(digest);
        Objects.requireNonNull(metadata, "metadata");
    }
    @Override public ContentType type() { return ContentType.IMAGE; }
}
