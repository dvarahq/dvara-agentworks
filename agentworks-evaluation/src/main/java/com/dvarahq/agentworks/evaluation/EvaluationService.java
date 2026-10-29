package com.dvarahq.agentworks.evaluation;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static com.dvarahq.agentworks.evaluation.EvaluationContracts.*;

/** Application service for immutable definitions and restart-safe, deterministic local evaluation. */
public final class EvaluationService {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration MAX_SENSITIVE_RETENTION = Duration.ofDays(30);
    private final EvaluationRepository repository;
    private final EvaluatorEngine engine;
    private final Clock clock;

    public EvaluationService(EvaluationRepository repository, EvaluatorEngine engine, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.engine = Objects.requireNonNull(engine, "engine");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Dataset createDataset(UUID workspaceId, String name, int version, boolean sensitive, Instant expiresAt,
            List<Case> cases) {
        Objects.requireNonNull(workspaceId, "workspaceId"); cases = List.copyOf(Objects.requireNonNull(cases, "cases"));
        Set<String> ids = new HashSet<>();
        for (Case item : cases) {
            if (!ids.add(item.id())) throw new EvaluationException("DUPLICATE_CASE", "dataset case ids must be unique");
            if (JSON.writeValueAsBytes(item).length > 262_144) throw new EvaluationException("CASE_TOO_LARGE", "a dataset case exceeds 256 KiB");
        }
        Instant now = clock.instant();
        if (sensitive && expiresAt == null) {
            throw new EvaluationException("RETENTION_INVALID", "sensitive fixtures require expiresAt");
        }
        if (expiresAt != null && !expiresAt.isAfter(now)) throw new EvaluationException("RETENTION_INVALID", "expiresAt must be in the future");
        if (sensitive && expiresAt != null && expiresAt.isAfter(now.plus(MAX_SENSITIVE_RETENTION))) {
            throw new EvaluationException("RETENTION_INVALID", "sensitive fixtures may be retained for at most 30 days");
        }
        String digest = EvaluationDigests.value(Map.of("name", name, "version", version,
                "sensitive", sensitive, "expiresAt", expiresAt == null ? "" : expiresAt.toString(), "cases", cases));
        return repository.createDataset(workspaceId, name, version, digest, sensitive, expiresAt, cases, now);
    }

    public Evaluator createEvaluator(UUID workspaceId, String name, int version, EvaluatorType type, JsonNode config) {
        Objects.requireNonNull(config, "configuration");
        if (JSON.writeValueAsBytes(config).length > 262_144) {
            throw new EvaluationException("EVALUATOR_CONFIGURATION_INVALID", "evaluator configuration exceeds 256 KiB");
        }
        engine.validate(type, config);
        String digest = EvaluationDigests.text(type.name() + "\n" + EvaluationDigests.json(config));
        return repository.createEvaluator(workspaceId, name, version, type, config, digest, clock.instant());
    }

    public Report start(UUID workspaceId, StartRequest request) {
        Dataset dataset = dataset(workspaceId, request.datasetId());
        if (dataset.expiresAt() != null && !dataset.expiresAt().isAfter(clock.instant())) {
            throw new EvaluationException("DATASET_EXPIRED", "dataset retention has expired");
        }
        List<Evaluator> evaluators = requestedEvaluators(workspaceId, request.evaluatorIds());
        Set<String> caseIds = dataset.cases().stream().map(Case::id).collect(java.util.stream.Collectors.toSet());
        if (!caseIds.equals(request.observations().keySet())) {
            throw new EvaluationException("OBSERVATIONS_INCOMPLETE", "observations must match the dataset cases exactly");
        }
        for (Map.Entry<String, Observation> observation : request.observations().entrySet()) {
            if (!observation.getKey().equals(observation.getValue().caseId())) throw new EvaluationException("CASE_MISMATCH", "observation key does not match its case id");
        }
        String evaluatorSetDigest = EvaluationDigests.text(evaluators.stream().map(Evaluator::digest).sorted()
                .reduce((left, right) -> left + "\n" + right).orElseThrow());
        String requestDigest = EvaluationDigests.value(request);
        EvaluationRepository.StoredPlan stored = repository.createOrGetRun(workspaceId, request, requestDigest,
                dataset, evaluators, evaluatorSetDigest, Math.multiplyExact(dataset.cases().size(), evaluators.size()), clock.instant());
        if (!stored.requestDigest().equals(requestDigest)) throw new EvaluationException("IDEMPOTENCY_CONFLICT", "idempotency key was used for another evaluation");
        if (stored.run().status() == RunStatus.COMPLETED) return report(workspaceId, stored.run().id());
        if (stored.run().status() != RunStatus.RUNNING) repository.reopen(workspaceId, stored.run().id(), clock.instant());
        return execute(workspaceId, stored.run().id());
    }

    public Report resume(UUID workspaceId, UUID runId) {
        EvaluationRepository.StoredPlan plan = plan(workspaceId, runId);
        if (plan.run().status() == RunStatus.COMPLETED) return report(workspaceId, runId);
        if (plan.run().status() == RunStatus.BUDGET_EXCEEDED) throw new EvaluationException("BUDGET_IMMUTABLE", "a budget-exceeded run cannot resume with changed limits");
        repository.reopen(workspaceId, runId, clock.instant());
        return execute(workspaceId, runId);
    }

    public Run cancel(UUID workspaceId, UUID runId) { return repository.requestCancellation(workspaceId, runId); }
    public Dataset dataset(UUID workspaceId, UUID id) { return repository.dataset(workspaceId, id).orElseThrow(() -> notFound("DATASET_NOT_FOUND")); }
    public Evaluator evaluator(UUID workspaceId, UUID id) { return repository.evaluator(workspaceId, id).orElseThrow(() -> notFound("EVALUATOR_NOT_FOUND")); }
    public Run run(UUID workspaceId, UUID id) { return plan(workspaceId, id).run(); }
    public List<Dataset> datasets(UUID workspaceId) { return repository.datasets(workspaceId); }
    public int purgeExpiredSensitive(UUID workspaceId) {
        return repository.purgeExpiredSensitive(Objects.requireNonNull(workspaceId, "workspaceId"), clock.instant());
    }
    public List<Evaluator> evaluators(UUID workspaceId) { return repository.evaluators(workspaceId); }
    public List<Baseline> baselines(UUID workspaceId) { return repository.baselines(workspaceId); }

    public Report report(UUID workspaceId, UUID runId) {
        Run run = run(workspaceId, runId); List<Result> results = repository.results(workspaceId, runId);
        String digest = reportDigest(run, results);
        if (!run.reportDigest().isEmpty() && !run.reportDigest().equals(digest)) throw new EvaluationException("REPORT_INTEGRITY_FAILED", "stored evaluation report digest does not verify");
        return new Report(1, run, results, digest);
    }

    public Baseline createBaseline(UUID workspaceId, String name, int version, UUID runId,
            Map<String, BigDecimal> thresholds, Map<String, String> cohort) {
        Report report = report(workspaceId, runId);
        if (report.run().status() != RunStatus.COMPLETED) throw new EvaluationException("RUN_INCOMPLETE", "only a completed evaluation can become a baseline");
        thresholds = Map.copyOf(Objects.requireNonNullElse(thresholds, Map.of()));
        cohort = Map.copyOf(Objects.requireNonNullElse(cohort, Map.of()));
        thresholds.forEach((key, value) -> {
            if (!Set.of("minimumPassRate", "maximumPassRateDrop").contains(key) || value.signum() < 0 || value.compareTo(BigDecimal.ONE) > 0) {
                throw new EvaluationException("THRESHOLD_INVALID", "baseline thresholds must be pass-rate values between 0 and 1");
            }
        });
        return repository.createBaseline(workspaceId, name, version, runId, report.digest(), thresholds, cohort, clock.instant());
    }

    public Comparison compare(UUID workspaceId, UUID baselineId, UUID candidateRunId) {
        Baseline baseline = repository.baseline(workspaceId, baselineId).orElseThrow(() -> notFound("BASELINE_NOT_FOUND"));
        Report original = report(workspaceId, baseline.runId()); Report candidate = report(workspaceId, candidateRunId);
        List<String> reasons = new ArrayList<>();
        if (!original.run().datasetDigest().equals(candidate.run().datasetDigest())) reasons.add("DATASET_CHANGED");
        if (!original.run().evaluatorSetDigest().equals(candidate.run().evaluatorSetDigest())) reasons.add("EVALUATOR_SET_CHANGED");
        if (candidate.run().status() != RunStatus.COMPLETED) reasons.add("CANDIDATE_INCOMPLETE");
        BigDecimal originalRate = passRate(original.run()); BigDecimal candidateRate = passRate(candidate.run());
        BigDecimal delta = candidateRate.subtract(originalRate);
        BigDecimal minimum = baseline.thresholds().getOrDefault("minimumPassRate", BigDecimal.ZERO);
        BigDecimal maximumDrop = baseline.thresholds().getOrDefault("maximumPassRateDrop", BigDecimal.ONE);
        boolean comparable = reasons.isEmpty();
        boolean regression = comparable && (candidateRate.compareTo(minimum) < 0 || delta.negate().compareTo(maximumDrop) > 0);
        if (regression) reasons.add("PASS_RATE_REGRESSION");
        return new Comparison(baselineId, candidateRunId, comparable, originalRate, candidateRate, delta, regression, reasons);
    }

    private Report execute(UUID workspaceId, UUID runId) {
        EvaluationRepository.StoredPlan plan = plan(workspaceId, runId);
        Dataset dataset = dataset(workspaceId, plan.request().datasetId());
        List<Evaluator> evaluators = requestedEvaluators(workspaceId, plan.request().evaluatorIds());
        List<Result> existing = repository.results(workspaceId, runId);
        Set<String> completed = new HashSet<>(); existing.forEach(result -> completed.add(result.caseId() + "\n" + result.evaluatorId()));
        Instant attemptStarted = clock.instant();
        BigDecimal cost = plan.request().observations().values().stream().map(Observation::costUsd).reduce(BigDecimal.ZERO, BigDecimal::add);
        long maximumLatency = plan.request().observations().values().stream().mapToLong(Observation::latencyMillis).max().orElse(0L);
        Budget budget = plan.request().budget();
        if ((budget.costBounded() && cost.compareTo(budget.maximumCostUsd()) > 0)
                || (budget.latencyBounded() && maximumLatency > budget.maximumCaseLatencyMillis())) {
            return finish(workspaceId, runId, RunStatus.BUDGET_EXCEEDED, "BUDGET_EXCEEDED", cost, maximumLatency);
        }
        try {
            for (Case item : dataset.cases()) {
                Observation observation = plan.request().observations().get(item.id());
                for (Evaluator evaluator : evaluators) {
                    String key = item.id() + "\n" + evaluator.id(); if (completed.contains(key)) continue;
                    Run current = run(workspaceId, runId);
                    if (current.cancellationRequested()) return finish(workspaceId, runId, RunStatus.CANCELLED, "CANCELLED", cost, maximumLatency);
                    if (budget.runtimeBounded() && Duration.between(attemptStarted, clock.instant()).compareTo(budget.maximumRuntime()) > 0) {
                        return finish(workspaceId, runId, RunStatus.BUDGET_EXCEEDED, "RUNTIME_BUDGET_EXCEEDED", cost, maximumLatency);
                    }
                    Outcome outcome = engine.evaluate(evaluator, item, observation);
                    Instant now = clock.instant(); String evidenceDigest = EvaluationDigests.json(outcome.evidence());
                    repository.appendResult(workspaceId, new Result(runId, item.id(), evaluator.id(), outcome.passed(),
                            outcome.score(), outcome.code(), outcome.evidence(), evidenceDigest, now));
                }
            }
            return finish(workspaceId, runId, RunStatus.COMPLETED, "", cost, maximumLatency);
        } catch (RuntimeException failure) {
            String code = failure instanceof EvaluationException typed ? typed.code() : "EVALUATOR_FAILED";
            finish(workspaceId, runId, RunStatus.FAILED, code, cost, maximumLatency);
            throw failure;
        }
    }

    private Report finish(UUID workspaceId, UUID runId, RunStatus status, String code, BigDecimal cost, long latency) {
        List<Result> results = repository.results(workspaceId, runId); Run before = run(workspaceId, runId);
        String digest = EvaluationDigests.text(before.datasetDigest() + "\n" + before.evaluatorSetDigest() + "\n"
                + results.stream().sorted(Comparator.comparing(Result::caseId).thenComparing(result -> result.evaluatorId().toString()))
                .map(Result::evidenceDigest).reduce((left, right) -> left + "\n" + right).orElse(""));
        Run ended = repository.finish(workspaceId, runId, status, passed(results), cost, latency, digest, code, clock.instant());
        return new Report(1, ended, results, digest);
    }

    private List<Evaluator> requestedEvaluators(UUID workspaceId, List<UUID> ids) {
        LinkedHashSet<UUID> unique = new LinkedHashSet<>(ids);
        if (unique.size() != ids.size()) throw new EvaluationException("DUPLICATE_EVALUATOR", "evaluator ids must be unique");
        return unique.stream().map(id -> evaluator(workspaceId, id)).toList();
    }
    private EvaluationRepository.StoredPlan plan(UUID workspaceId, UUID runId) { return repository.plan(workspaceId, runId).orElseThrow(() -> notFound("EVALUATION_RUN_NOT_FOUND")); }
    private static int passed(List<Result> results) { return Math.toIntExact(results.stream().filter(Result::passed).count()); }
    private static BigDecimal passRate(Run run) { return run.completedResults() == 0 ? BigDecimal.ZERO : BigDecimal.valueOf(run.passedResults()).divide(BigDecimal.valueOf(run.completedResults()), 6, RoundingMode.HALF_UP); }
    private static String reportDigest(Run run, List<Result> results) { return EvaluationDigests.text(run.datasetDigest() + "\n" + run.evaluatorSetDigest() + "\n" + results.stream().sorted(Comparator.comparing(Result::caseId).thenComparing(result -> result.evaluatorId().toString())).map(Result::evidenceDigest).reduce((left, right) -> left + "\n" + right).orElse("")); }
    private static EvaluationException notFound(String code) { return new EvaluationException(code, "evaluation resource was not found"); }
}
