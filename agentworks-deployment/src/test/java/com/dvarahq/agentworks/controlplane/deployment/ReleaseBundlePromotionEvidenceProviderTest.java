package com.dvarahq.agentworks.controlplane.deployment;

import com.dvarahq.agentworks.controlplane.evidence.ReleaseEvidence;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleJson;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.EvidenceKind;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.EvidenceReference;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.PromotionEvidence;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import com.dvarahq.agentworks.storage.lifecycle.Digests;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ReleaseBundlePromotionEvidenceProviderTest {
    @Test
    void signedCallerClaimsNeverBecomeVerdictsWithoutAnAuthoritativeRecord() {
        UUID releaseId = UUID.randomUUID();
        UUID test = UUID.randomUUID(), security = UUID.randomUUID(), approval = UUID.randomUUID();
        var bundle = bundle(releaseId, List.of(
                ref(ReleaseEvidence.EvidenceKind.AGENT_TEST, test.toString(), '1'),
                ref(ReleaseEvidence.EvidenceKind.SECURITY, security.toString(), '2'),
                ref(ReleaseEvidence.EvidenceKind.CONFIGURATION, "approval:" + approval, '3')));
        var provider = new ReleaseBundlePromotionEvidenceProvider(ignored -> bundle,
                EvaluationPromotionEvidenceSource.NONE, HumanApprovalEvidenceSource.NONE);

        assertThat(provider.resolve(new WorkspaceId(UUID.randomUUID()), UUID.randomUUID(), digest('9'), releaseId,
                List.of(
                new EvidenceReference(test, EvidenceKind.TEST),
                new EvidenceReference(security, EvidenceKind.SECURITY),
                new EvidenceReference(approval, EvidenceKind.HUMAN_APPROVAL)))).isEmpty();
    }

    @Test
    void aSignedEvaluationDigestSelectsAuthoritativeVerdictsForSeveralGateKinds() {
        UUID workspaceId = UUID.randomUUID(), releaseId = UUID.randomUUID(), runId = UUID.randomUUID();
        String reportDigest = digest('4');
        var bundle = bundle(releaseId, List.of(
                new ReleaseEvidence.EvidenceReference(ReleaseEvidence.EvidenceKind.EVALUATION,
                        "evaluation:" + runId, reportDigest)));
        EvaluationPromotionEvidenceSource evaluations = (workspace, release, manifest, trustedDigest, selectedRun,
                kind) -> {
            assertThat(workspace.value()).isEqualTo(workspaceId);
            assertThat(release).isEqualTo(releaseId);
            assertThat(manifest).isEqualTo(bundle.payload().manifest());
            assertThat(trustedDigest).isEqualTo(reportDigest);
            assertThat(selectedRun).isEqualTo(runId);
            return Optional.of(new PromotionEvidence(runId, kind, releaseId, reportDigest, "PASSED", Map.of(), null,
                    Instant.parse("2026-10-15T00:00:00Z")));
        };
        var provider = new ReleaseBundlePromotionEvidenceProvider(ignored -> bundle, evaluations,
                HumanApprovalEvidenceSource.NONE);

        assertThat(provider.resolve(new WorkspaceId(workspaceId), UUID.randomUUID(), digest('9'), releaseId, List.of(
                new EvidenceReference(runId, EvidenceKind.EVALUATION),
                new EvidenceReference(runId, EvidenceKind.COST),
                new EvidenceReference(runId, EvidenceKind.LATENCY))))
                .extracting(PromotionEvidence::kind)
                .containsExactly(EvidenceKind.EVALUATION, EvidenceKind.COST, EvidenceKind.LATENCY);
    }

    private static ReleaseEvidence.Bundle bundle(UUID releaseId, List<ReleaseEvidence.EvidenceReference> references) {
        String manifest = LifecycleJson.write(Map.of("releaseEvidence", references));
        var payload = new ReleaseEvidence.Payload(ReleaseEvidence.BUNDLE_VERSION, releaseId, manifest,
                Digests.prefixed(manifest), references, null);
        var signature = new ReleaseEvidence.SignatureEnvelope("Ed25519", "test-key", digest('f'), "signature",
                Instant.parse("2026-10-15T00:00:00Z"));
        return new ReleaseEvidence.Bundle(payload, signature);
    }

    private static ReleaseEvidence.EvidenceReference ref(ReleaseEvidence.EvidenceKind kind, String identity,
            char digestCharacter) {
        return new ReleaseEvidence.EvidenceReference(kind, identity, digest(digestCharacter));
    }

    private static String digest(char value) { return "sha256:" + String.valueOf(value).repeat(64); }
}
