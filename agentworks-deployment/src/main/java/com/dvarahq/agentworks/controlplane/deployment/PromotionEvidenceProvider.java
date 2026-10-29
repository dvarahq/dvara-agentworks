package com.dvarahq.agentworks.controlplane.deployment;

import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.EvidenceReference;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.PromotionEvidence;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;

import java.util.List;
import java.util.UUID;

/**
 * Resolves content-free evidence from an authoritative immutable source. Issue #54's signed release bundle is the
 * production provider; promotion never trusts evidence values supplied in the HTTP request.
 */
@FunctionalInterface
public interface PromotionEvidenceProvider {
    List<PromotionEvidence> resolve(WorkspaceId workspace, UUID environmentId, String policyDigest, UUID releaseId,
            List<EvidenceReference> references);

    PromotionEvidenceProvider NONE = (workspace, environmentId, policyDigest, releaseId, references) -> List.of();
}
