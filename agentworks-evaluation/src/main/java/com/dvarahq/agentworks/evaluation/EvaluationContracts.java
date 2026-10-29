package com.dvarahq.agentworks.evaluation;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Stable, provider-neutral contracts for deterministic Core evaluations. */
public final class EvaluationContracts {
    private EvaluationContracts() {}

    public enum EvaluatorType { EXACT, REGEX, JSON_SCHEMA, TOOL_TRACE, POLICY, LATENCY, COST, CUSTOM_JAVA }
    public enum RunStatus { RUNNING, COMPLETED, FAILED, CANCELLED, BUDGET_EXCEEDED }

    public record Case(String id, JsonNode input, JsonNode expected, Map<String, String> tags) {
        public Case {
            id = required(id, "case id", 128);
            input = copy(input); expected = copy(expected);
            tags = Map.copyOf(Objects.requireNonNullElse(tags, Map.of()));
            if (tags.size() > 32) throw new IllegalArgumentException("a case may have at most 32 tags");
        }
    }

    public record Dataset(UUID id, UUID workspaceId, String name, int version, String digest,
            boolean sensitive, Instant expiresAt, Instant createdAt, List<Case> cases) {
        public Dataset {
            Objects.requireNonNull(id, "id"); Objects.requireNonNull(workspaceId, "workspaceId");
            name = required(name, "dataset name", 160);
            if (version < 1) throw new IllegalArgumentException("dataset version must be positive");
            digest = EvaluationContracts.digest(digest); Objects.requireNonNull(createdAt, "createdAt");
            cases = List.copyOf(Objects.requireNonNull(cases, "cases"));
            if (cases.isEmpty() || cases.size() > 10_000) throw new IllegalArgumentException("dataset must contain 1 to 10000 cases");
            if (sensitive && expiresAt == null) throw new IllegalArgumentException("sensitive datasets require expiresAt");
        }
    }

    public record Evaluator(UUID id, UUID workspaceId, String name, int version, EvaluatorType type,
            JsonNode configuration, String digest, Instant createdAt) {
        public Evaluator {
            Objects.requireNonNull(id, "id"); Objects.requireNonNull(workspaceId, "workspaceId");
            name = required(name, "evaluator name", 160);
            if (version < 1) throw new IllegalArgumentException("evaluator version must be positive");
            Objects.requireNonNull(type, "type"); configuration = copy(configuration);
            digest = EvaluationContracts.digest(digest); Objects.requireNonNull(createdAt, "createdAt");
        }
    }

    /** Captured result of one already-isolated case execution. No evaluator invokes a live dependency. */
    public record Observation(String caseId, JsonNode output, List<String> toolTrace, boolean policyPassed,
            long latencyMillis, BigDecimal costUsd, String sourceIdentity, String sourceDigest) {
        public Observation {
            caseId = required(caseId, "case id", 128); output = copy(output);
            toolTrace = List.copyOf(Objects.requireNonNullElse(toolTrace, List.of()));
            if (toolTrace.size() > 1_000) throw new IllegalArgumentException("tool trace is too large");
            if (latencyMillis < 0) throw new IllegalArgumentException("latencyMillis cannot be negative");
            costUsd = Objects.requireNonNullElse(costUsd, BigDecimal.ZERO);
            if (costUsd.signum() < 0) throw new IllegalArgumentException("costUsd cannot be negative");
            sourceIdentity = required(sourceIdentity, "source identity", 256); sourceDigest = digest(sourceDigest);
        }
    }

    public record Budget(BigDecimal maximumCostUsd, long maximumCaseLatencyMillis, Duration maximumRuntime,
            int maximumConcurrency) {
        public Budget {
            maximumCostUsd = Objects.requireNonNullElse(maximumCostUsd, BigDecimal.ZERO);
            if (maximumCostUsd.signum() < 0) throw new IllegalArgumentException("maximumCostUsd cannot be negative");
            if (maximumCaseLatencyMillis < 0) throw new IllegalArgumentException("maximumCaseLatencyMillis cannot be negative");
            maximumRuntime = Objects.requireNonNullElse(maximumRuntime, Duration.ZERO);
            if (maximumRuntime.isNegative()) throw new IllegalArgumentException("maximumRuntime cannot be negative");
            if (maximumConcurrency < 1 || maximumConcurrency > 64) throw new IllegalArgumentException("maximumConcurrency must be between 1 and 64");
        }
        public boolean costBounded() { return maximumCostUsd.signum() > 0; }
        public boolean latencyBounded() { return maximumCaseLatencyMillis > 0; }
        public boolean runtimeBounded() { return !maximumRuntime.isZero(); }
    }

    public record StartRequest(UUID datasetId, List<UUID> evaluatorIds, String subjectIdentity,
            String releaseEvidenceReference, Map<String, Observation> observations, Budget budget, String idempotencyKey) {
        public StartRequest {
            Objects.requireNonNull(datasetId, "datasetId");
            evaluatorIds = List.copyOf(Objects.requireNonNull(evaluatorIds, "evaluatorIds"));
            if (evaluatorIds.isEmpty() || evaluatorIds.size() > 32) throw new IllegalArgumentException("1 to 32 evaluators are required");
            subjectIdentity = required(subjectIdentity, "subject identity", 256);
            releaseEvidenceReference = optional(releaseEvidenceReference, 256);
            observations = Map.copyOf(Objects.requireNonNull(observations, "observations"));
            budget = Objects.requireNonNull(budget, "budget");
            idempotencyKey = required(idempotencyKey, "idempotency key", 128);
        }
    }

    public record Outcome(boolean passed, BigDecimal score, String code, JsonNode evidence) {
        public Outcome {
            score = Objects.requireNonNull(score, "score");
            if (score.signum() < 0 || score.compareTo(BigDecimal.ONE) > 0) throw new IllegalArgumentException("score must be between 0 and 1");
            code = required(code, "outcome code", 96); evidence = copy(evidence);
        }
    }

    public record Result(UUID runId, String caseId, UUID evaluatorId, boolean passed, BigDecimal score,
            String code, JsonNode evidence, String evidenceDigest, Instant createdAt) {
        public Result {
            Objects.requireNonNull(runId, "runId"); caseId = required(caseId, "case id", 128);
            Objects.requireNonNull(evaluatorId, "evaluatorId"); Objects.requireNonNull(score, "score");
            code = required(code, "result code", 96); evidence = copy(evidence);
            evidenceDigest = digest(evidenceDigest); Objects.requireNonNull(createdAt, "createdAt");
        }
    }

    public record Run(UUID id, UUID workspaceId, UUID datasetId, String datasetDigest, List<UUID> evaluatorIds,
            String evaluatorSetDigest, String subjectIdentity, String releaseEvidenceReference, Budget budget,
            RunStatus status, boolean cancellationRequested, int completedResults, int totalResults, int passedResults,
            BigDecimal consumedCostUsd, long maximumObservedLatencyMillis, String reportDigest, String failureCode,
            Instant startedAt, Instant completedAt) {
        public Run {
            Objects.requireNonNull(id, "id"); Objects.requireNonNull(workspaceId, "workspaceId");
            Objects.requireNonNull(datasetId, "datasetId"); datasetDigest = digest(datasetDigest);
            evaluatorIds = List.copyOf(Objects.requireNonNull(evaluatorIds, "evaluatorIds"));
            evaluatorSetDigest = digest(evaluatorSetDigest); subjectIdentity = required(subjectIdentity, "subject identity", 256);
            releaseEvidenceReference = optional(releaseEvidenceReference, 256); Objects.requireNonNull(budget, "budget");
            Objects.requireNonNull(status, "status"); consumedCostUsd = Objects.requireNonNull(consumedCostUsd, "consumedCostUsd");
            reportDigest = optional(reportDigest, 71); failureCode = optional(failureCode, 96);
            Objects.requireNonNull(startedAt, "startedAt");
        }
    }

    public record Report(int version, Run run, List<Result> results, String digest) {
        public Report {
            if (version != 1) throw new IllegalArgumentException("unsupported report version");
            Objects.requireNonNull(run, "run"); results = List.copyOf(Objects.requireNonNull(results, "results"));
            digest = EvaluationContracts.digest(digest);
        }
    }

    public record Baseline(UUID id, UUID workspaceId, String name, int version, UUID runId, String reportDigest,
            Map<String, BigDecimal> thresholds, Map<String, String> cohort, Instant createdAt) {
        public Baseline {
            Objects.requireNonNull(id, "id"); Objects.requireNonNull(workspaceId, "workspaceId");
            name = required(name, "baseline name", 160);
            if (version < 1) throw new IllegalArgumentException("baseline version must be positive");
            Objects.requireNonNull(runId, "runId"); reportDigest = digest(reportDigest);
            thresholds = Map.copyOf(Objects.requireNonNullElse(thresholds, Map.of()));
            cohort = Map.copyOf(Objects.requireNonNullElse(cohort, Map.of())); Objects.requireNonNull(createdAt, "createdAt");
        }
    }

    public record Comparison(UUID baselineId, UUID candidateRunId, boolean comparable,
            BigDecimal baselinePassRate, BigDecimal candidatePassRate, BigDecimal passRateDelta,
            boolean regression, List<String> reasons) {
        public Comparison { reasons = List.copyOf(Objects.requireNonNull(reasons, "reasons")); }
    }

    static JsonNode copy(JsonNode node) { return Objects.requireNonNullElse(node, JsonNodeFactory.instance.nullNode()).deepCopy(); }
    static String required(String value, String name, int max) {
        if (value == null || value.isBlank() || value.length() > max) throw new IllegalArgumentException(name + " is required and must be at most " + max + " characters");
        return value;
    }
    static String optional(String value, int max) {
        if (value == null) return "";
        if (value.length() > max) throw new IllegalArgumentException("value must be at most " + max + " characters");
        return value;
    }
    static String digest(String value) {
        if (value == null || !value.matches("sha256:[0-9a-f]{64}")) throw new IllegalArgumentException("a sha256 digest is required");
        return value;
    }
}
