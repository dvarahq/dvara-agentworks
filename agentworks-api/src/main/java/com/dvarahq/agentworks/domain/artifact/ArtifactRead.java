package com.dvarahq.agentworks.domain.artifact;

import java.util.Objects;

/** Tenant-scoped whole-content or byte-range read. A null length means through end of content. */
public record ArtifactRead(ArtifactOwner owner, ArtifactRef reference, long offset, Long length) {
    public ArtifactRead {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(reference, "reference");
        if (offset < 0) throw new IllegalArgumentException("artifact read offset cannot be negative");
        if (length != null && length < 1) throw new IllegalArgumentException("artifact read length must be positive");
    }

    public static ArtifactRead entire(ArtifactOwner owner, ArtifactRef reference) {
        return new ArtifactRead(owner, reference, 0, null);
    }
}
