package com.dvarahq.agentworks.domain.artifact;

import java.util.Objects;
import java.util.UUID;

/** Opaque immutable artifact identity. Possession of a reference never grants access. */
public record ArtifactRef(UUID value) {
    public ArtifactRef {
        Objects.requireNonNull(value, "value");
    }
}
