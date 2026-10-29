package com.dvarahq.agentworks.autoconfigure;

import com.dvarahq.agentworks.controlplane.deployment.EvaluationPromotionEvidenceSource;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleJson;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.EvidenceKind;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.PromotionEvidence;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import com.dvarahq.agentworks.evaluation.EvaluationContracts.RunStatus;
import com.dvarahq.agentworks.evaluation.EvaluationException;
import com.dvarahq.agentworks.evaluation.EvaluationService;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Reads and integrity-verifies immutable #53 reports, binding them to the selected release. */
public final class EvaluationPromotionEvidenceAdapter implements EvaluationPromotionEvidenceSource {
    private final java.util.function.BiFunction<UUID, UUID, com.dvarahq.agentworks.evaluation.EvaluationContracts.Report> reports;
    private final java.util.function.BiFunction<UUID, java.util.List<UUID>, Set<com.dvarahq.agentworks.evaluation.EvaluationContracts.EvaluatorType>> evaluatorTypes;

    public EvaluationPromotionEvidenceAdapter(EvaluationService evaluations) {
        this(evaluations::report, (workspace, ids) -> ids.stream()
                .map(id -> evaluations.evaluator(workspace, id).type()).collect(java.util.stream.Collectors.toSet()));
    }
    EvaluationPromotionEvidenceAdapter(java.util.function.BiFunction<UUID, UUID,
            com.dvarahq.agentworks.evaluation.EvaluationContracts.Report> reports) {
        this(reports, (workspace, ids) -> Set.of());
    }
    EvaluationPromotionEvidenceAdapter(java.util.function.BiFunction<UUID, UUID,
            com.dvarahq.agentworks.evaluation.EvaluationContracts.Report> reports,
            java.util.function.BiFunction<UUID, java.util.List<UUID>,
                    Set<com.dvarahq.agentworks.evaluation.EvaluationContracts.EvaluatorType>> evaluatorTypes) {
        this.reports = reports;
        this.evaluatorTypes = evaluatorTypes;
    }

    @Override public Optional<PromotionEvidence> resolve(WorkspaceId workspace, UUID releaseId,
            String releaseManifest, String trustedReportDigest, UUID runId, EvidenceKind kind) {
        try {
            var report = reports.apply(workspace.value(), runId);
            var run = report.run();
            JsonNode agent = LifecycleJson.MAPPER.readTree(releaseManifest).path("agent");
            String specDigest = agent.path("specDigest").asString();
            boolean releaseBound = ("spec:" + specDigest).equals(run.subjectIdentity())
                    || specDigest.equals(run.subjectIdentity());
            boolean evidenceBound = trustedReportDigest.equals(report.digest())
                    && trustedReportDigest.equals(run.reportDigest())
                    && specDigest.equals(run.releaseEvidenceReference());
            if (!releaseBound || !evidenceBound
                    || run.status() != RunStatus.COMPLETED) return Optional.empty();
            Set<com.dvarahq.agentworks.evaluation.EvaluationContracts.EvaluatorType> types =
                    evaluatorTypes.apply(workspace.value(), run.evaluatorIds());
            boolean categorized = switch (kind) {
                case TEST -> types.stream().anyMatch(type -> Set.of(
                        com.dvarahq.agentworks.evaluation.EvaluationContracts.EvaluatorType.EXACT,
                        com.dvarahq.agentworks.evaluation.EvaluationContracts.EvaluatorType.REGEX,
                        com.dvarahq.agentworks.evaluation.EvaluationContracts.EvaluatorType.JSON_SCHEMA,
                        com.dvarahq.agentworks.evaluation.EvaluationContracts.EvaluatorType.TOOL_TRACE,
                        com.dvarahq.agentworks.evaluation.EvaluationContracts.EvaluatorType.CUSTOM_JAVA).contains(type));
                case SECURITY -> types.contains(
                        com.dvarahq.agentworks.evaluation.EvaluationContracts.EvaluatorType.POLICY);
                case COST -> types.contains(com.dvarahq.agentworks.evaluation.EvaluationContracts.EvaluatorType.COST);
                case LATENCY -> types.contains(
                        com.dvarahq.agentworks.evaluation.EvaluationContracts.EvaluatorType.LATENCY);
                case EVALUATION -> !types.isEmpty();
                default -> false;
            };
            if (!categorized) return Optional.empty();
            boolean passed = run.totalResults() > 0 && run.completedResults() == run.totalResults()
                    && run.passedResults() == run.totalResults();
            Map<String, BigDecimal> metrics = switch (kind) {
                case TEST, EVALUATION, SECURITY -> Map.of("passRate", BigDecimal.valueOf(run.passedResults())
                        .divide(BigDecimal.valueOf(run.completedResults()), 8, java.math.RoundingMode.HALF_UP));
                case COST -> Map.of("usd", run.consumedCostUsd());
                case LATENCY -> Map.of("maximumMs", BigDecimal.valueOf(run.maximumObservedLatencyMillis()));
                default -> Map.of();
            };
            return Optional.of(new PromotionEvidence(run.id(), kind, releaseId, report.digest(),
                    passed ? "PASSED" : "FAILED", metrics, null,
                    run.completedAt() == null ? run.startedAt() : run.completedAt()));
        } catch (EvaluationException notFoundOrInvalid) {
            return Optional.empty();
        }
    }
}
