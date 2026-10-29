package com.dvarahq.agentworks.domain.artifact;

import java.time.Instant;
import java.util.Objects;

/** Immutable content facts plus independently governed lifecycle/security state. */
public record ArtifactMetadata(ArtifactRef reference, ArtifactOwner owner, String mediaType, long size,
        ArtifactDigest digest, Instant createdAt, Instant expiresAt, ArtifactClassification classification,
        boolean redacted, ArtifactValidation validation, ArtifactProvenance provenance,
        ArtifactLifecycleState lifecycle, String validationReason, Instant stateChangedAt) {
    public ArtifactMetadata(ArtifactRef reference, ArtifactOwner owner, String mediaType, long size,
            ArtifactDigest digest, Instant createdAt, Instant expiresAt, ArtifactClassification classification,
            boolean redacted, ArtifactValidation validation, ArtifactProvenance provenance) {
        this(reference, owner, mediaType, size, digest, createdAt, expiresAt, classification, redacted, validation,
                provenance, ArtifactLifecycleState.ACTIVE, "LEGACY_ACCEPTED", createdAt);
    }

    public ArtifactMetadata {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(owner, "owner");
        requireMediaType(mediaType);
        if (size < 0) throw new IllegalArgumentException("artifact size cannot be negative");
        Objects.requireNonNull(digest, "digest");
        Objects.requireNonNull(createdAt, "createdAt");
        if (expiresAt != null && !expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("artifact expiry must be after creation");
        }
        Objects.requireNonNull(classification, "classification");
        Objects.requireNonNull(validation, "validation");
        Objects.requireNonNull(provenance, "provenance");
        Objects.requireNonNull(lifecycle, "lifecycle");
        if (validationReason == null || validationReason.isBlank() || validationReason.length() > 256) {
            throw new IllegalArgumentException("artifact validation reason is invalid");
        }
        Objects.requireNonNull(stateChangedAt, "stateChangedAt");
        if (stateChangedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("artifact state change cannot precede creation");
        }
    }

    static void requireMediaType(String mediaType) {
        if (mediaType == null || mediaType.length() > 255
                || !mediaType.matches("[A-Za-z0-9][A-Za-z0-9!#$&^_.+-]*/[A-Za-z0-9][A-Za-z0-9!#$&^_.+-]*")) {
            throw new IllegalArgumentException("artifact media type is invalid");
        }
    }
}
