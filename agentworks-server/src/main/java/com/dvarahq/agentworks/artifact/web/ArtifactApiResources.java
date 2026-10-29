package com.dvarahq.agentworks.artifact.web;

import com.dvarahq.agentworks.domain.artifact.ArtifactClassification;
import com.dvarahq.agentworks.domain.artifact.ArtifactLifecycleState;
import com.dvarahq.agentworks.domain.artifact.ArtifactMetadata;
import com.dvarahq.agentworks.domain.artifact.ArtifactPin;
import com.dvarahq.agentworks.domain.artifact.ArtifactPinKind;
import com.dvarahq.agentworks.domain.artifact.ArtifactProvenance;
import com.dvarahq.agentworks.domain.artifact.ArtifactValidation;

import java.time.Instant;
import java.util.UUID;

public final class ArtifactApiResources {
    private ArtifactApiResources() {}

    public record ArtifactResource(UUID id, String mediaType, long size, String digest, Instant createdAt,
            Instant expiresAt, ArtifactClassification classification, boolean redacted,
            ArtifactValidation validation, String validationReason, ArtifactLifecycleState lifecycle,
            Instant stateChangedAt, ArtifactProvenance provenance) {
        public static ArtifactResource of(ArtifactMetadata value) {
            return new ArtifactResource(value.reference().value(), value.mediaType(), value.size(),
                    value.digest().value(), value.createdAt(), value.expiresAt(), value.classification(),
                    value.redacted(), value.validation(), value.validationReason(), value.lifecycle(),
                    value.stateChangedAt(), value.provenance());
        }
    }

    public record ValidationRequest(ArtifactValidation validation, String reason) {}

    public record PinRequest(ArtifactPinKind kind, UUID subjectId, Instant expiresAt) {}

    public record PinResource(UUID id, UUID artifactId, ArtifactPinKind kind, UUID subjectId,
            Instant createdAt, Instant expiresAt, String actor) {
        public static PinResource of(ArtifactPin value) {
            return new PinResource(value.id(), value.artifact().value(), value.kind(), value.subjectId(),
                    value.createdAt(), value.expiresAt(), value.actor());
        }
    }
}
