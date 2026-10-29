package com.dvarahq.agentworks.controlplane.deployment;

import com.dvarahq.agentworks.controlplane.evidence.ReleaseEvidenceAuthority;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.EvidenceKind;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.EvidenceReference;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.PromotionEvidence;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Adapts issue #54's currently trusted stored bundle into issue #55's content-free gate evidence. */
public final class ReleaseBundlePromotionEvidenceProvider implements PromotionEvidenceProvider {
    private final java.util.function.Function<UUID,
            com.dvarahq.agentworks.controlplane.evidence.ReleaseEvidence.Bundle> bundles;
    private final EvaluationPromotionEvidenceSource evaluations;
    private final HumanApprovalEvidenceSource approvals;

    public ReleaseBundlePromotionEvidenceProvider(ReleaseEvidenceAuthority authority) {
        this(authority, EvaluationPromotionEvidenceSource.NONE, HumanApprovalEvidenceSource.NONE);
    }

    public ReleaseBundlePromotionEvidenceProvider(ReleaseEvidenceAuthority authority,
            EvaluationPromotionEvidenceSource evaluations) {
        this(authority, evaluations, HumanApprovalEvidenceSource.NONE);
    }

    public ReleaseBundlePromotionEvidenceProvider(ReleaseEvidenceAuthority authority,
            EvaluationPromotionEvidenceSource evaluations, HumanApprovalEvidenceSource approvals) {
        this(Objects.requireNonNull(authority, "authority")::trustedBundle, evaluations, approvals);
    }

    ReleaseBundlePromotionEvidenceProvider(java.util.function.Function<UUID,
            com.dvarahq.agentworks.controlplane.evidence.ReleaseEvidence.Bundle> bundles,
            EvaluationPromotionEvidenceSource evaluations, HumanApprovalEvidenceSource approvals) {
        this.bundles = Objects.requireNonNull(bundles, "bundles");
        this.evaluations = Objects.requireNonNull(evaluations, "evaluations");
        this.approvals = Objects.requireNonNull(approvals, "approvals");
    }

    @Override public List<PromotionEvidence> resolve(WorkspaceId workspace, UUID environmentId, String policyDigest,
            UUID releaseId, List<EvidenceReference> references) {
        if (references == null || references.isEmpty()) return List.of();
        var bundle = bundles.apply(releaseId);
        List<PromotionEvidence> resolved = new ArrayList<>();
        for (EvidenceReference selected : references) {
            if (selected == null) continue;
            if (selected.kind() == EvidenceKind.RELEASE_EVIDENCE && releaseId.equals(selected.id())) {
                resolved.add(new PromotionEvidence(releaseId, EvidenceKind.RELEASE_EVIDENCE, releaseId,
                        bundle.digest(), "VERIFIED", Map.of(), bundle.signature().keyId(),
                        bundle.signature().signedAt()));
                continue;
            }
            if (selected.kind() == EvidenceKind.TEST || selected.kind() == EvidenceKind.EVALUATION
                    || selected.kind() == EvidenceKind.SECURITY || selected.kind() == EvidenceKind.COST
                    || selected.kind() == EvidenceKind.LATENCY) {
                bundle.payload().evidence().stream()
                        .filter(reference -> reference.kind()
                                == com.dvarahq.agentworks.controlplane.evidence.ReleaseEvidence.EvidenceKind.EVALUATION)
                        .filter(reference -> reference.identity().equals(selected.id().toString())
                                || reference.identity().equals("evaluation:" + selected.id()))
                        .findFirst().flatMap(reference -> evaluations.resolve(workspace, releaseId,
                                bundle.payload().manifest(), reference.digest(), selected.id(), selected.kind()))
                        .ifPresent(resolved::add);
                continue;
            }
            if (selected.kind() == EvidenceKind.HUMAN_APPROVAL) {
                approvals.resolve(workspace, environmentId, policyDigest, releaseId, selected.id())
                        .ifPresent(resolved::add);
                continue;
            }
        }
        return List.copyOf(resolved);
    }
}
