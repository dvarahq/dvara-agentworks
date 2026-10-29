package com.dvarahq.agentworks.controlplane.deployment;

import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.EvidenceKind;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.PromotionEvidence;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;

import java.util.Optional;
import java.util.UUID;

/** Narrow adapter from immutable issue #53 reports into promotion evidence. */
@FunctionalInterface
public interface EvaluationPromotionEvidenceSource {
    Optional<PromotionEvidence> resolve(WorkspaceId workspace, UUID releaseId, String releaseManifest,
            String trustedReportDigest, UUID evaluationRunId, EvidenceKind requestedKind);

    EvaluationPromotionEvidenceSource NONE = (workspace, releaseId, manifest, reportDigest, runId, kind) ->
            Optional.empty();
}
