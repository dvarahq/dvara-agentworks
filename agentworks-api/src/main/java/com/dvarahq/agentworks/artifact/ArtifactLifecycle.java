package com.dvarahq.agentworks.artifact;

import com.dvarahq.agentworks.domain.artifact.ArtifactMaintenanceReport;
import com.dvarahq.agentworks.domain.artifact.ArtifactMetadata;
import com.dvarahq.agentworks.domain.artifact.ArtifactOwner;
import com.dvarahq.agentworks.domain.artifact.ArtifactPin;
import com.dvarahq.agentworks.domain.artifact.ArtifactPinKind;
import com.dvarahq.agentworks.domain.artifact.ArtifactRef;
import com.dvarahq.agentworks.domain.artifact.ArtifactValidation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Provider-neutral mutable lifecycle state around immutable artifact content and metadata. */
public interface ArtifactLifecycle {
    ArtifactMetadata metadata(ArtifactOwner owner, ArtifactRef reference);

    ArtifactMetadata expire(ArtifactOwner owner, ArtifactRef reference, String actor);

    ArtifactMetadata transitionValidation(ArtifactOwner owner, ArtifactRef reference,
            ArtifactValidation validation, String reason, String actor);

    ArtifactPin pin(ArtifactOwner owner, ArtifactRef reference, ArtifactPinKind kind,
            UUID subjectId, Instant expiresAt, String actor);

    void unpin(ArtifactOwner owner, ArtifactRef reference, UUID pinId, String actor);

    List<ArtifactPin> pins(ArtifactOwner owner, ArtifactRef reference);

    /** Reconciles bounded stale uploads, expiry, reference accounting, and unreferenced content. */
    ArtifactMaintenanceReport maintain(int limit);
}
