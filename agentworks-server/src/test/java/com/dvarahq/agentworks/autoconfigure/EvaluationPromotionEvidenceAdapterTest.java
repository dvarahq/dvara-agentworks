package com.dvarahq.agentworks.autoconfigure;

import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.EvidenceKind;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import com.dvarahq.agentworks.evaluation.EvaluationContracts;
import com.dvarahq.agentworks.evaluation.EvaluationException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EvaluationPromotionEvidenceAdapterTest {
    @Test
    void derivesEvaluationCostAndLatencyOnlyFromACompletedReleaseAndBundleBoundReport() {
        UUID workspaceId = UUID.randomUUID(), releaseId = UUID.randomUUID(), runId = UUID.randomUUID();
        String reportDigest = "sha256:" + "2".repeat(64);
        String specDigest = "sha256:" + "7".repeat(64);
        String manifest = com.dvarahq.agentworks.controlplane.lifecycle.LifecycleJson.write(
                java.util.Map.of("agent", java.util.Map.of("specDigest", specDigest)));
        var run = new EvaluationContracts.Run(runId, workspaceId, UUID.randomUUID(), "sha256:" + "3".repeat(64),
                List.of(UUID.randomUUID()), "sha256:" + "4".repeat(64), "spec:" + specDigest, specDigest,
                new EvaluationContracts.Budget(BigDecimal.TEN, 1000, Duration.ofMinutes(1), 1),
                EvaluationContracts.RunStatus.COMPLETED, false, 4, 4, 4, new BigDecimal("1.25"), 87,
                reportDigest, "", Instant.parse("2026-10-15T00:00:00Z"),
                Instant.parse("2026-10-15T00:01:00Z"));
        var adapter = new EvaluationPromotionEvidenceAdapter((selectedWorkspace, selectedRun) -> {
            assertThat(selectedWorkspace).isEqualTo(workspaceId);
            assertThat(selectedRun).isEqualTo(runId);
            return new EvaluationContracts.Report(1, run, List.of(), reportDigest);
        }, (selectedWorkspace, evaluators) -> java.util.Set.of(
                EvaluationContracts.EvaluatorType.EXACT, EvaluationContracts.EvaluatorType.POLICY,
                EvaluationContracts.EvaluatorType.COST, EvaluationContracts.EvaluatorType.LATENCY));
        var workspace = new WorkspaceId(workspaceId);

        assertThat(adapter.resolve(workspace, releaseId, manifest, reportDigest, runId, EvidenceKind.EVALUATION)).get()
                .extracting(evidence -> evidence.status(), evidence -> evidence.metrics().get("passRate"))
                .containsExactly("PASSED", new BigDecimal("1.00000000"));
        assertThat(adapter.resolve(workspace, releaseId, manifest, reportDigest, runId, EvidenceKind.COST)).get()
                .extracting(evidence -> evidence.metrics().get("usd")).isEqualTo(new BigDecimal("1.25"));
        assertThat(adapter.resolve(workspace, releaseId, manifest, reportDigest, runId, EvidenceKind.LATENCY)).get()
                .extracting(evidence -> evidence.metrics().get("maximumMs")).isEqualTo(new BigDecimal("87"));
        assertThat(adapter.resolve(workspace, releaseId, manifest, "sha256:" + "9".repeat(64), runId,
                EvidenceKind.EVALUATION)).isEmpty();
        String otherManifest = com.dvarahq.agentworks.controlplane.lifecycle.LifecycleJson.write(
                java.util.Map.of("agent", java.util.Map.of("specDigest", "sha256:" + "8".repeat(64))));
        assertThat(adapter.resolve(workspace, releaseId, otherManifest, reportDigest, runId,
                EvidenceKind.EVALUATION)).isEmpty();
        assertThat(adapter.resolve(workspace, releaseId, manifest, reportDigest, runId, EvidenceKind.TEST)).get()
                .extracting(evidence -> evidence.status()).isEqualTo("PASSED");
        assertThat(adapter.resolve(workspace, releaseId, manifest, reportDigest, runId, EvidenceKind.SECURITY)).get()
                .extracting(evidence -> evidence.status()).isEqualTo("PASSED");
    }

    @Test
    void failedUnknownAndDigestMismatchedReportsCannotPass() {
        UUID workspaceId = UUID.randomUUID(), releaseId = UUID.randomUUID(), runId = UUID.randomUUID();
        String reportDigest = "sha256:" + "5".repeat(64);
        String specDigest = "sha256:" + "7".repeat(64);
        String manifest = com.dvarahq.agentworks.controlplane.lifecycle.LifecycleJson.write(
                java.util.Map.of("agent", java.util.Map.of("specDigest", specDigest)));
        var failed = new EvaluationContracts.Run(runId, workspaceId, UUID.randomUUID(),
                "sha256:" + "3".repeat(64), List.of(UUID.randomUUID()), "sha256:" + "4".repeat(64),
                "spec:" + specDigest, specDigest,
                new EvaluationContracts.Budget(BigDecimal.TEN, 1000, Duration.ofMinutes(1), 1),
                EvaluationContracts.RunStatus.COMPLETED, false, 4, 4, 3, BigDecimal.ONE, 50,
                reportDigest, "", Instant.parse("2026-10-15T00:00:00Z"),
                Instant.parse("2026-10-15T00:01:00Z"));
        var adapter = new EvaluationPromotionEvidenceAdapter((workspace, selected) ->
                new EvaluationContracts.Report(1, failed, List.of(), reportDigest),
                (workspace, evaluators) -> java.util.Set.of(EvaluationContracts.EvaluatorType.EXACT));

        assertThat(adapter.resolve(new WorkspaceId(workspaceId), releaseId, manifest, reportDigest, runId,
                EvidenceKind.TEST))
                .get().extracting(evidence -> evidence.status()).isEqualTo("FAILED");
        assertThat(adapter.resolve(new WorkspaceId(workspaceId), releaseId, manifest,
                "sha256:" + "6".repeat(64), runId, EvidenceKind.SECURITY)).isEmpty();
        assertThat(adapter.resolve(new WorkspaceId(workspaceId), releaseId, manifest, reportDigest, runId,
                EvidenceKind.SECURITY)).isEmpty();

        var unavailable = new EvaluationPromotionEvidenceAdapter((workspace, selected) -> {
            throw new EvaluationException("NOT_FOUND", "not found");
        });
        assertThat(unavailable.resolve(new WorkspaceId(workspaceId), releaseId, manifest, reportDigest, runId,
                EvidenceKind.EVALUATION)).isEmpty();
    }
}
