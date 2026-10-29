package com.dvarahq.agentworks.interaction;

import java.util.Objects;
import java.util.UUID;

public record ArtifactContent(UUID artifactId, String mediaType, String digest,
        ContentMetadata metadata) implements InteractionContent {
    public ArtifactContent {
        Objects.requireNonNull(artifactId, "artifactId");
        mediaType = InteractionValues.text(mediaType, "mediaType", 128);
        digest = InteractionValues.digest(digest);
        Objects.requireNonNull(metadata, "metadata");
    }
    @Override public ContentType type() { return ContentType.ARTIFACT; }
}
