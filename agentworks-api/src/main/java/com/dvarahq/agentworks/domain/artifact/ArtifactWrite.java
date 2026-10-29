package com.dvarahq.agentworks.domain.artifact;

import java.io.InputStream;
import java.time.Instant;
import java.util.Objects;

/** One bounded, single-use content stream and its immutable publication claims. */
public record ArtifactWrite(ArtifactOwner owner, String mediaType, long declaredSize, ArtifactDigest expectedDigest,
        Instant expiresAt, ArtifactClassification classification, boolean redacted, ArtifactProvenance provenance,
        InputStream content) {
    public ArtifactWrite {
        Objects.requireNonNull(owner, "owner");
        ArtifactMetadata.requireMediaType(mediaType);
        if (declaredSize < 0) throw new IllegalArgumentException("declared artifact size cannot be negative");
        Objects.requireNonNull(classification, "classification");
        Objects.requireNonNull(provenance, "provenance");
        Objects.requireNonNull(content, "content");
    }

    @Override
    public String toString() {
        return "ArtifactWrite[owner=" + owner + ", mediaType=" + mediaType + ", declaredSize=" + declaredSize
                + ", expectedDigest=" + expectedDigest + ", expiresAt=" + expiresAt + ", classification="
                + classification + ", redacted=" + redacted + ", provenance=" + provenance + "]";
    }
}
