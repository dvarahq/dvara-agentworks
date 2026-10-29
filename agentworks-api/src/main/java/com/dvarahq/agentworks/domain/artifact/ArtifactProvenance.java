package com.dvarahq.agentworks.domain.artifact;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/** Stable source, actor, exact runtime generation, and declared input lineage for an artifact. */
public record ArtifactProvenance(String source, String actor, ArtifactRunBinding run, List<ArtifactRef> inputs) {
    public ArtifactProvenance {
        if (source == null || !source.matches("[A-Z][A-Z0-9_]{0,63}")) {
            throw new IllegalArgumentException("artifact provenance source is invalid");
        }
        if (actor == null || actor.isBlank() || actor.length() > 256) {
            throw new IllegalArgumentException("artifact provenance actor is invalid");
        }
        inputs = inputs == null ? List.of() : List.copyOf(inputs);
        inputs.forEach(input -> Objects.requireNonNull(input, "artifact lineage input"));
        if (inputs.size() > 256 || new LinkedHashSet<>(inputs).size() != inputs.size()) {
            throw new IllegalArgumentException("artifact input lineage is invalid");
        }
    }

    public static ArtifactProvenance upload(String actor) {
        return new ArtifactProvenance("UPLOAD", actor, null, List.of());
    }
}
