package com.dvarahq.agentworks.evaluation.web;

import com.dvarahq.agentworks.controlplane.agent.web.WorkspaceScopeResolver;
import com.dvarahq.agentworks.evaluation.EvaluationContracts.Baseline;
import com.dvarahq.agentworks.evaluation.EvaluationContracts.Budget;
import com.dvarahq.agentworks.evaluation.EvaluationContracts.Case;
import com.dvarahq.agentworks.evaluation.EvaluationContracts.Comparison;
import com.dvarahq.agentworks.evaluation.EvaluationContracts.Dataset;
import com.dvarahq.agentworks.evaluation.EvaluationContracts.Evaluator;
import com.dvarahq.agentworks.evaluation.EvaluationContracts.EvaluatorType;
import com.dvarahq.agentworks.evaluation.EvaluationContracts.Observation;
import com.dvarahq.agentworks.evaluation.EvaluationContracts.Report;
import com.dvarahq.agentworks.evaluation.EvaluationContracts.Run;
import com.dvarahq.agentworks.evaluation.EvaluationContracts.StartRequest;
import com.dvarahq.agentworks.evaluation.EvaluationDigests;
import com.dvarahq.agentworks.evaluation.EvaluationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Tenant-scoped API for deterministic evaluation definitions, runs, reports, and baselines. */
@RestController
@RequestMapping(path = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
public final class EvaluationController {
    private final EvaluationService evaluations;
    private final WorkspaceScopeResolver workspaces;
    public EvaluationController(EvaluationService evaluations, WorkspaceScopeResolver workspaces) {
        this.evaluations = Objects.requireNonNull(evaluations); this.workspaces = Objects.requireNonNull(workspaces);
    }

    @PostMapping(path = "/evaluation-datasets", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<DatasetResource> createDataset(HttpServletRequest servlet, @RequestBody CreateDataset request,
            UriComponentsBuilder uris) {
        UUID workspace = workspaces.resolve(servlet).value();
        Dataset created = evaluations.createDataset(workspace, request.name(), request.version(), request.sensitive(),
                request.expiresAt(), request.cases());
        return ResponseEntity.created(uris.path("/api/v1/evaluation-datasets/{id}").buildAndExpand(created.id()).toUri())
                .body(dataset(created));
    }
    @GetMapping("/evaluation-datasets") public CollectionResource<DatasetResource> datasets(HttpServletRequest servlet) {
        return collection(evaluations.datasets(workspaces.resolve(servlet).value()).stream().map(EvaluationController::dataset).toList());
    }
    @GetMapping("/evaluation-datasets/{id}") DatasetResource dataset(HttpServletRequest servlet, @PathVariable UUID id) {
        return dataset(evaluations.dataset(workspaces.resolve(servlet).value(), id));
    }
    @PostMapping("/evaluation-datasets/purge-expired") PurgeResult purgeExpired(HttpServletRequest servlet) {
        return new PurgeResult(evaluations.purgeExpiredSensitive(workspaces.resolve(servlet).value()));
    }

    @PostMapping(path = "/evaluators", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Evaluator> createEvaluator(HttpServletRequest servlet, @RequestBody CreateEvaluator request,
            UriComponentsBuilder uris) {
        Evaluator created = evaluations.createEvaluator(workspaces.resolve(servlet).value(), request.name(), request.version(),
                request.type(), request.configuration());
        return ResponseEntity.created(uris.path("/api/v1/evaluators/{id}").buildAndExpand(created.id()).toUri()).body(created);
    }
    @GetMapping("/evaluators") public CollectionResource<Evaluator> evaluators(HttpServletRequest servlet) {
        return collection(evaluations.evaluators(workspaces.resolve(servlet).value()));
    }
    @GetMapping("/evaluators/{id}") Evaluator evaluator(HttpServletRequest servlet, @PathVariable UUID id) {
        return evaluations.evaluator(workspaces.resolve(servlet).value(), id);
    }

    @PostMapping(path = "/evaluation-runs", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Report> start(HttpServletRequest servlet, @RequestBody StartEvaluation request,
            UriComponentsBuilder uris) {
        UUID workspace = workspaces.resolve(servlet).value();
        Report report = evaluations.start(workspace, new StartRequest(request.datasetId(), request.evaluatorIds(),
                request.subjectIdentity(), request.releaseEvidenceReference(), request.observations(), request.budget(),
                request.idempotencyKey()));
        return ResponseEntity.status(HttpStatus.CREATED)
                .location(uris.path("/api/v1/evaluation-runs/{id}").buildAndExpand(report.run().id()).toUri()).body(report);
    }
    @GetMapping("/evaluation-runs/{id}") Run run(HttpServletRequest servlet, @PathVariable UUID id) {
        return evaluations.run(workspaces.resolve(servlet).value(), id);
    }
    @GetMapping("/evaluation-runs/{id}/report") Report report(HttpServletRequest servlet, @PathVariable UUID id) {
        return evaluations.report(workspaces.resolve(servlet).value(), id);
    }
    @PostMapping("/evaluation-runs/{id}/cancel") Run cancel(HttpServletRequest servlet, @PathVariable UUID id) {
        return evaluations.cancel(workspaces.resolve(servlet).value(), id);
    }
    @PostMapping("/evaluation-runs/{id}/resume") Report resume(HttpServletRequest servlet, @PathVariable UUID id) {
        return evaluations.resume(workspaces.resolve(servlet).value(), id);
    }

    @PostMapping(path = "/evaluation-baselines", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Baseline> baseline(HttpServletRequest servlet, @RequestBody CreateBaseline request,
            UriComponentsBuilder uris) {
        Baseline created = evaluations.createBaseline(workspaces.resolve(servlet).value(), request.name(), request.version(),
                request.runId(), request.thresholds(), request.cohort());
        return ResponseEntity.created(uris.path("/api/v1/evaluation-baselines/{id}").buildAndExpand(created.id()).toUri()).body(created);
    }
    @GetMapping("/evaluation-baselines") public CollectionResource<Baseline> baselines(HttpServletRequest servlet) {
        return collection(evaluations.baselines(workspaces.resolve(servlet).value()));
    }
    @GetMapping("/evaluation-baselines/{id}/comparison") Comparison comparison(HttpServletRequest servlet,
            @PathVariable UUID id, @RequestParam UUID candidateRunId) {
        return evaluations.compare(workspaces.resolve(servlet).value(), id, candidateRunId);
    }

    private static DatasetResource dataset(Dataset dataset) {
        List<CaseResource> cases = dataset.cases().stream().map(item -> dataset.sensitive()
                ? new CaseResource(item.id(), null, null, item.tags(), caseDigest(item.input()), caseDigest(item.expected()))
                : new CaseResource(item.id(), item.input(), item.expected(), item.tags(), caseDigest(item.input()), caseDigest(item.expected()))).toList();
        return new DatasetResource(dataset.id(), dataset.workspaceId(), dataset.name(), dataset.version(), dataset.digest(),
                dataset.sensitive(), dataset.expiresAt(), dataset.createdAt(), cases);
    }
    private static <T> CollectionResource<T> collection(List<T> items) { return new CollectionResource<>(items, null); }
    private static String caseDigest(JsonNode value) {
        JsonNode purged = value.get("$agentworksPurgedDigest");
        return purged != null && purged.isString() ? purged.stringValue() : EvaluationDigests.json(value);
    }

    public record CreateDataset(String name, int version, boolean sensitive, Instant expiresAt, List<Case> cases) {}
    public record CreateEvaluator(String name, int version, EvaluatorType type, JsonNode configuration) {}
    public record StartEvaluation(UUID datasetId, List<UUID> evaluatorIds, String subjectIdentity,
            String releaseEvidenceReference, Map<String, Observation> observations, Budget budget, String idempotencyKey) {}
    public record CreateBaseline(String name, int version, UUID runId, Map<String, BigDecimal> thresholds,
            Map<String, String> cohort) {}
    public record DatasetResource(UUID id, UUID workspaceId, String name, int version, String digest,
            boolean sensitive, Instant expiresAt, Instant createdAt, List<CaseResource> cases) {}
    public record CaseResource(String id, JsonNode input, JsonNode expected, Map<String, String> tags,
            String inputDigest, String expectedDigest) {}
    public record CollectionResource<T>(List<T> items, String nextCursor) {}
    public record PurgeResult(int purgedCases) {}
}
