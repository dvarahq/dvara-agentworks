package com.dvarahq.agentworks.evaluation;

import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.dvarahq.agentworks.evaluation.EvaluationContracts.*;

/** Persistence boundary for immutable evaluation definitions, incremental results, and baselines. */
public interface EvaluationRepository {
    Dataset createDataset(UUID workspaceId, String name, int version, String digest, boolean sensitive,
            Instant expiresAt, List<Case> cases, Instant now);
    Optional<Dataset> dataset(UUID workspaceId, UUID id);
    List<Dataset> datasets(UUID workspaceId);
    int purgeExpiredSensitive(UUID workspaceId, Instant now);

    Evaluator createEvaluator(UUID workspaceId, String name, int version, EvaluatorType type,
            JsonNode configuration, String digest, Instant now);
    Optional<Evaluator> evaluator(UUID workspaceId, UUID id);
    List<Evaluator> evaluators(UUID workspaceId);

    StoredPlan createOrGetRun(UUID workspaceId, StartRequest request, String requestDigest, Dataset dataset,
            List<Evaluator> evaluators, String evaluatorSetDigest, int totalResults, Instant now);
    Optional<StoredPlan> plan(UUID workspaceId, UUID runId);
    Run reopen(UUID workspaceId, UUID runId, Instant now);
    List<Result> results(UUID workspaceId, UUID runId);
    void appendResult(UUID workspaceId, Result result);
    Run finish(UUID workspaceId, UUID runId, RunStatus status, int passedResults, BigDecimal consumedCost,
            long maximumLatency, String reportDigest, String failureCode, Instant now);
    Run requestCancellation(UUID workspaceId, UUID runId);

    Baseline createBaseline(UUID workspaceId, String name, int version, UUID runId, String reportDigest,
            Map<String, BigDecimal> thresholds, Map<String, String> cohort, Instant now);
    Optional<Baseline> baseline(UUID workspaceId, UUID baselineId);
    List<Baseline> baselines(UUID workspaceId);

    record StoredPlan(Run run, StartRequest request, String requestDigest) {
        public StoredPlan { java.util.Objects.requireNonNull(run); java.util.Objects.requireNonNull(request); java.util.Objects.requireNonNull(requestDigest); }
    }
}
