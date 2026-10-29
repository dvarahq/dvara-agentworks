package com.dvarahq.agentworks.domain.artifact;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/** One transform over exactly the artifact references declared here. */
public record ArtifactComputeRequest(ArtifactOwner owner, String actor, ArtifactRunBinding run,
        ArtifactComputeOperator operator, List<ArtifactRef> inputs, String outputMediaType,
        Instant expiresAt, ArtifactClassification classification, boolean redacted) {
    public ArtifactComputeRequest {
        Objects.requireNonNull(owner, "owner");
        if (actor == null || actor.isBlank() || actor.length() > 256) {
            throw new IllegalArgumentException("artifact compute actor is invalid");
        }
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(operator, "operator");
        inputs = inputs == null ? List.of() : List.copyOf(inputs);
        inputs.forEach(input -> Objects.requireNonNull(input, "artifact compute input"));
        if (inputs.isEmpty() || new LinkedHashSet<>(inputs).size() != inputs.size()) {
            throw new IllegalArgumentException("artifact compute inputs must be non-empty and unique");
        }
        ArtifactMetadata.requireMediaType(outputMediaType);
        Objects.requireNonNull(classification, "classification");
    }
}
