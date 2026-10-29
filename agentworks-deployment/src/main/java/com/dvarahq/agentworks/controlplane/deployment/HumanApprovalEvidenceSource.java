package com.dvarahq.agentworks.controlplane.deployment;

import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.PromotionEvidence;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;

import java.util.Optional;
import java.util.UUID;

/** Narrow adapter from an authenticated immutable approval authority. */
@FunctionalInterface
public interface HumanApprovalEvidenceSource {
    Optional<PromotionEvidence> resolve(WorkspaceId workspace, UUID environmentId, String policyDigest,
            UUID releaseId, UUID approvalId);
    HumanApprovalEvidenceSource NONE = (workspace, environmentId, policyDigest, releaseId, approvalId) ->
            Optional.empty();
}
