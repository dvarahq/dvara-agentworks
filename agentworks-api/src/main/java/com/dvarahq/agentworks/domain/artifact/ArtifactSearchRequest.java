package com.dvarahq.agentworks.domain.artifact;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** Stable newest-first keyset search within one structural tenant. */
public record ArtifactSearchRequest(ArtifactOwner owner, Set<String> mediaTypes,
        ArtifactClassification classification, Instant beforeCreatedAt, UUID beforeId, int limit) {
    public static final int MAX_LIMIT = 200;

    public ArtifactSearchRequest {
        if (owner == null) throw new NullPointerException("owner");
        mediaTypes = mediaTypes == null ? Set.of() : Set.copyOf(mediaTypes);
        mediaTypes.forEach(ArtifactMetadata::requireMediaType);
        if ((beforeCreatedAt == null) != (beforeId == null)) {
            throw new IllegalArgumentException("artifact page position names both creation time and id");
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("artifact search limit must be between 1 and " + MAX_LIMIT);
        }
    }

    public static ArtifactSearchRequest first(ArtifactOwner owner, int limit) {
        return new ArtifactSearchRequest(owner, Set.of(), null, null, null, limit);
    }
}
