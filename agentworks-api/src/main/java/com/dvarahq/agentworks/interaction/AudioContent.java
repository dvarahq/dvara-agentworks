package com.dvarahq.agentworks.interaction;

import java.net.URI;
import java.util.Objects;

public record AudioContent(URI uri, String mediaType, String transcript, String digest,
        ContentMetadata metadata) implements InteractionContent {
    public AudioContent {
        uri = InteractionValues.uri(uri, true);
        mediaType = InteractionValues.text(mediaType, "mediaType", 128);
        if (!mediaType.startsWith("audio/")) throw new IllegalArgumentException("audio media type required");
        if (transcript != null && transcript.length() > 1_048_576) throw new IllegalArgumentException("transcript is too long");
        digest = InteractionValues.digest(digest);
        Objects.requireNonNull(metadata, "metadata");
    }
    @Override public ContentType type() { return ContentType.AUDIO; }
}
