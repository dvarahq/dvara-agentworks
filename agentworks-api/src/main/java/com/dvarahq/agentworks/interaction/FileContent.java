package com.dvarahq.agentworks.interaction;

import java.net.URI;
import java.util.Objects;

public record FileContent(URI uri, String name, String mediaType, String digest,
        ContentMetadata metadata) implements InteractionContent {
    public FileContent {
        uri = InteractionValues.uri(uri, true);
        name = InteractionValues.text(name, "name", 255);
        if (name.contains("/") || name.contains("\\") || name.equals(".") || name.equals("..")) {
            throw new IllegalArgumentException("unsafe file name");
        }
        mediaType = InteractionValues.text(mediaType, "mediaType", 128);
        digest = InteractionValues.digest(digest);
        Objects.requireNonNull(metadata, "metadata");
    }
    @Override public ContentType type() { return ContentType.FILE; }
}
