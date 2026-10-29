package com.dvarahq.agentworks.storage.evaluation;

import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import com.dvarahq.agentworks.evaluation.EvaluationContracts.Baseline;
import com.dvarahq.agentworks.evaluation.EvaluationContracts.Budget;
import com.dvarahq.agentworks.evaluation.EvaluationContracts.Case;
import com.dvarahq.agentworks.evaluation.EvaluationContracts.Dataset;
import com.dvarahq.agentworks.evaluation.EvaluationContracts.Evaluator;
import com.dvarahq.agentworks.evaluation.EvaluationContracts.EvaluatorType;
import com.dvarahq.agentworks.evaluation.EvaluationContracts.Result;
import com.dvarahq.agentworks.evaluation.EvaluationContracts.Run;
import com.dvarahq.agentworks.evaluation.EvaluationContracts.RunStatus;
import com.dvarahq.agentworks.evaluation.EvaluationContracts.StartRequest;
import com.dvarahq.agentworks.evaluation.EvaluationException;
import com.dvarahq.agentworks.evaluation.EvaluationDigests;
import com.dvarahq.agentworks.evaluation.EvaluationRepository;
import com.dvarahq.agentworks.storage.TenantTransactionTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Spring JDBC authority for workspace-scoped evaluation definitions, results, and baselines. */
public final class JdbcEvaluationRepository implements EvaluationRepository {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {};
    private static final TypeReference<Map<String, BigDecimal>> DECIMAL_MAP = new TypeReference<>() {};
    private final JdbcTemplate jdbc;
    private final TenantTransactionTemplate tenants;

    public JdbcEvaluationRepository(JdbcTemplate jdbc, TenantTransactionTemplate tenants) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc"); this.tenants = Objects.requireNonNull(tenants, "tenants");
    }

    @Override
    public Dataset createDataset(UUID workspaceId, String name, int version, String digest, boolean sensitive,
            Instant expiresAt, List<Case> cases, Instant now) {
        return in(workspaceId, () -> {
            UUID id = UUID.randomUUID();
            jdbc.update("insert into evaluation_dataset(workspace_id,id,name,version,content_digest,sensitive,expires_at,created_at) values (?,?,?,?,?,?,?,?)",
                    workspaceId, id, name, version, digest, sensitive, timestamp(expiresAt), timestamp(now));
            for (Case item : cases) jdbc.update("insert into evaluation_case(workspace_id,dataset_id,case_id,input_json,expected_json,tags_json,input_digest,expected_digest,purged_at) values (?,?,?,?,?,?,?,?,null)",
                    workspaceId, id, item.id(), json(item.input()), json(item.expected()), json(item.tags()),
                    EvaluationDigests.json(item.input()), EvaluationDigests.json(item.expected()));
            return new Dataset(id, workspaceId, name, version, digest, sensitive, expiresAt, now, cases);
        });
    }

    @Override public Optional<Dataset> dataset(UUID workspaceId, UUID id) {
        return in(workspaceId, () -> jdbc.query("select * from evaluation_dataset where workspace_id=? and id=?",
                (rs, row) -> datasetRow(rs), workspaceId, id).stream().findFirst());
    }
    @Override public List<Dataset> datasets(UUID workspaceId) {
        return in(workspaceId, () -> List.copyOf(jdbc.query("select * from evaluation_dataset where workspace_id=? order by created_at desc,id desc",
                (rs, row) -> datasetRow(rs), workspaceId)));
    }
    @Override public int purgeExpiredSensitive(UUID workspaceId, Instant now) {
        return in(workspaceId, () -> jdbc.update("update evaluation_case set "
                        + "input_json='{\"$agentworksPurgedDigest\":\"' || input_digest || '\"}',"
                        + "expected_json='{\"$agentworksPurgedDigest\":\"' || expected_digest || '\"}',purged_at=? "
                        + "where workspace_id=? and purged_at is null and exists (select 1 from evaluation_dataset d "
                        + "where d.workspace_id=evaluation_case.workspace_id and d.id=evaluation_case.dataset_id "
                        + "and d.sensitive=true and d.expires_at<=?)",
                timestamp(now), workspaceId, timestamp(now)));
    }

    @Override
    public Evaluator createEvaluator(UUID workspaceId, String name, int version, EvaluatorType type,
            JsonNode configuration, String digest, Instant now) {
        return in(workspaceId, () -> {
            UUID id = UUID.randomUUID();
            jdbc.update("insert into evaluation_evaluator(workspace_id,id,name,version,evaluator_type,configuration_json,content_digest,created_at) values (?,?,?,?,?,?,?,?)",
                    workspaceId, id, name, version, type.name(), json(configuration), digest, timestamp(now));
            return new Evaluator(id, workspaceId, name, version, type, configuration, digest, now);
        });
    }
    @Override public Optional<Evaluator> evaluator(UUID workspaceId, UUID id) {
        return in(workspaceId, () -> jdbc.query("select * from evaluation_evaluator where workspace_id=? and id=?",
                (rs, row) -> evaluatorRow(rs), workspaceId, id).stream().findFirst());
    }
    @Override public List<Evaluator> evaluators(UUID workspaceId) {
        return in(workspaceId, () -> List.copyOf(jdbc.query("select * from evaluation_evaluator where workspace_id=? order by created_at desc,id desc",
                (rs, row) -> evaluatorRow(rs), workspaceId)));
    }

    @Override
    public StoredPlan createOrGetRun(UUID workspaceId, StartRequest request, String requestDigest, Dataset dataset,
            List<Evaluator> evaluators, String evaluatorSetDigest, int totalResults, Instant now) {
        return in(workspaceId, () -> {
            Optional<StoredPlan> existing = planByKey(workspaceId, request.idempotencyKey());
            if (existing.isPresent()) return existing.orElseThrow();
            UUID id = UUID.randomUUID();
            try {
                jdbc.update("insert into evaluation_run(workspace_id,id,dataset_id,dataset_digest,evaluator_ids_json,evaluator_set_digest,subject_identity,release_evidence_ref,budget_json,request_json,request_digest,idempotency_key,status,cancellation_requested,completed_results,total_results,passed_results,consumed_cost_usd,maximum_latency_ms,report_digest,failure_code,started_at,completed_at) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                        workspaceId, id, dataset.id(), dataset.digest(), json(evaluators.stream().map(Evaluator::id).toList()), evaluatorSetDigest,
                        request.subjectIdentity(), request.releaseEvidenceReference(), json(request.budget()), json(request), requestDigest,
                        request.idempotencyKey(), RunStatus.RUNNING.name(), false, 0, totalResults, 0, BigDecimal.ZERO, 0L, "", "",
                        timestamp(now), null);
            } catch (DataAccessException race) {
                return planByKey(workspaceId, request.idempotencyKey()).orElseThrow(() -> race);
            }
            return plan(workspaceId, id).orElseThrow();
        });
    }

    @Override public Optional<StoredPlan> plan(UUID workspaceId, UUID runId) {
        return in(workspaceId, () -> jdbc.query("select * from evaluation_run where workspace_id=? and id=?",
                (rs, row) -> planRow(rs), workspaceId, runId).stream().findFirst());
    }

    @Override public Run reopen(UUID workspaceId, UUID runId, Instant now) {
        return in(workspaceId, () -> {
            int changed = jdbc.update("update evaluation_run set status='RUNNING',cancellation_requested=false,completed_at=null,failure_code='' where workspace_id=? and id=? and status in ('FAILED','CANCELLED')",
                    workspaceId, runId);
            if (changed == 0) {
                Run current = requiredRun(workspaceId, runId);
                if (current.status() != RunStatus.RUNNING) throw new EvaluationException("RUN_NOT_RESUMABLE", "evaluation run cannot resume");
            }
            return requiredRun(workspaceId, runId);
        });
    }

    @Override public List<Result> results(UUID workspaceId, UUID runId) {
        return in(workspaceId, () -> List.copyOf(jdbc.query("select * from evaluation_result where workspace_id=? and run_id=? order by case_id,evaluator_id",
                (rs, row) -> resultRow(rs), workspaceId, runId)));
    }

    @Override public void appendResult(UUID workspaceId, Result result) {
        in(workspaceId, () -> {
            Optional<Result> existing = jdbc.query("select * from evaluation_result where workspace_id=? and run_id=? and case_id=? and evaluator_id=?",
                    (rs, row) -> resultRow(rs), workspaceId, result.runId(), result.caseId(), result.evaluatorId()).stream().findFirst();
            if (existing.isPresent()) {
                if (!existing.orElseThrow().evidenceDigest().equals(result.evidenceDigest())) throw new EvaluationException("RESULT_CONFLICT", "stored evaluation result differs");
                return null;
            }
            try {
                jdbc.update("insert into evaluation_result(workspace_id,run_id,case_id,evaluator_id,passed,score,result_code,evidence_json,evidence_digest,created_at) values (?,?,?,?,?,?,?,?,?,?)",
                        workspaceId, result.runId(), result.caseId(), result.evaluatorId(), result.passed(), result.score(), result.code(),
                        json(result.evidence()), result.evidenceDigest(), timestamp(result.createdAt()));
                jdbc.update("update evaluation_run set completed_results=completed_results+1,passed_results=passed_results+? where workspace_id=? and id=? and status='RUNNING'",
                        result.passed() ? 1 : 0, workspaceId, result.runId());
            } catch (DataAccessException race) {
                Result winner = jdbc.query("select * from evaluation_result where workspace_id=? and run_id=? and case_id=? and evaluator_id=?",
                        (rs, row) -> resultRow(rs), workspaceId, result.runId(), result.caseId(), result.evaluatorId()).stream().findFirst().orElseThrow(() -> race);
                if (!winner.evidenceDigest().equals(result.evidenceDigest())) throw new EvaluationException("RESULT_CONFLICT", "stored evaluation result differs");
            }
            return null;
        });
    }

    @Override public Run finish(UUID workspaceId, UUID runId, RunStatus status, int passedResults,
            BigDecimal consumedCost, long maximumLatency, String reportDigest, String failureCode, Instant now) {
        if (status == RunStatus.RUNNING) throw new IllegalArgumentException("finish requires a terminal status");
        return in(workspaceId, () -> {
            int count = jdbc.queryForObject("select count(*) from evaluation_result where workspace_id=? and run_id=?", Integer.class, workspaceId, runId);
            jdbc.update("update evaluation_run set status=?,cancellation_requested=false,completed_results=?,passed_results=?,consumed_cost_usd=?,maximum_latency_ms=?,report_digest=?,failure_code=?,completed_at=? where workspace_id=? and id=? and status='RUNNING'",
                    status.name(), count, passedResults, consumedCost, maximumLatency, reportDigest, failureCode, timestamp(now), workspaceId, runId);
            return requiredRun(workspaceId, runId);
        });
    }

    @Override public Run requestCancellation(UUID workspaceId, UUID runId) {
        return in(workspaceId, () -> {
            jdbc.update("update evaluation_run set cancellation_requested=true where workspace_id=? and id=? and status='RUNNING'", workspaceId, runId);
            return requiredRun(workspaceId, runId);
        });
    }

    @Override public Baseline createBaseline(UUID workspaceId, String name, int version, UUID runId,
            String reportDigest, Map<String, BigDecimal> thresholds, Map<String, String> cohort, Instant now) {
        return in(workspaceId, () -> {
            UUID id = UUID.randomUUID();
            jdbc.update("insert into evaluation_baseline(workspace_id,id,name,version,run_id,report_digest,thresholds_json,cohort_json,created_at) values (?,?,?,?,?,?,?,?,?)",
                    workspaceId, id, name, version, runId, reportDigest, json(thresholds), json(cohort), timestamp(now));
            return new Baseline(id, workspaceId, name, version, runId, reportDigest, thresholds, cohort, now);
        });
    }
    @Override public Optional<Baseline> baseline(UUID workspaceId, UUID baselineId) {
        return in(workspaceId, () -> jdbc.query("select * from evaluation_baseline where workspace_id=? and id=?",
                (rs, row) -> baselineRow(rs), workspaceId, baselineId).stream().findFirst());
    }
    @Override public List<Baseline> baselines(UUID workspaceId) {
        return in(workspaceId, () -> List.copyOf(jdbc.query("select * from evaluation_baseline where workspace_id=? order by created_at desc,id desc",
                (rs, row) -> baselineRow(rs), workspaceId)));
    }

    private Optional<StoredPlan> planByKey(UUID workspaceId, String key) {
        return jdbc.query("select * from evaluation_run where workspace_id=? and idempotency_key=?", (rs, row) -> planRow(rs), workspaceId, key).stream().findFirst();
    }
    private Run requiredRun(UUID workspaceId, UUID runId) { return plan(workspaceId, runId).map(StoredPlan::run).orElseThrow(() -> new EvaluationException("EVALUATION_RUN_NOT_FOUND", "evaluation run was not found")); }
    private Dataset datasetRow(ResultSet rs) throws SQLException {
        UUID workspace = rs.getObject("workspace_id", UUID.class); UUID id = rs.getObject("id", UUID.class);
        List<Case> cases = jdbc.query("select * from evaluation_case where workspace_id=? and dataset_id=? order by case_id",
                (row, number) -> new Case(row.getString("case_id"), tree(row.getString("input_json")),
                        tree(row.getString("expected_json")), read(row.getString("tags_json"), STRING_MAP)), workspace, id);
        return new Dataset(id, workspace, rs.getString("name"), rs.getInt("version"), rs.getString("content_digest"),
                rs.getBoolean("sensitive"), instantNullable(rs, "expires_at"), instant(rs, "created_at"), cases);
    }
    private static Evaluator evaluatorRow(ResultSet rs) throws SQLException { return new Evaluator(rs.getObject("id", UUID.class), rs.getObject("workspace_id", UUID.class), rs.getString("name"), rs.getInt("version"), EvaluatorType.valueOf(rs.getString("evaluator_type")), tree(rs.getString("configuration_json")), rs.getString("content_digest"), instant(rs, "created_at")); }
    private static StoredPlan planRow(ResultSet rs) throws SQLException { return new StoredPlan(runRow(rs), read(rs.getString("request_json"), StartRequest.class), rs.getString("request_digest")); }
    private static Run runRow(ResultSet rs) throws SQLException {
        List<UUID> evaluatorIds = new ArrayList<>(); tree(rs.getString("evaluator_ids_json")).values().forEach(node -> evaluatorIds.add(UUID.fromString(node.stringValue())));
        return new Run(rs.getObject("id", UUID.class), rs.getObject("workspace_id", UUID.class), rs.getObject("dataset_id", UUID.class), rs.getString("dataset_digest"), evaluatorIds,
                rs.getString("evaluator_set_digest"), rs.getString("subject_identity"), rs.getString("release_evidence_ref"), read(rs.getString("budget_json"), Budget.class),
                RunStatus.valueOf(rs.getString("status")), rs.getBoolean("cancellation_requested"), rs.getInt("completed_results"), rs.getInt("total_results"), rs.getInt("passed_results"),
                rs.getBigDecimal("consumed_cost_usd"), rs.getLong("maximum_latency_ms"), rs.getString("report_digest"), rs.getString("failure_code"), instant(rs, "started_at"), instantNullable(rs, "completed_at"));
    }
    private static Result resultRow(ResultSet rs) throws SQLException { return new Result(rs.getObject("run_id", UUID.class), rs.getString("case_id"), rs.getObject("evaluator_id", UUID.class), rs.getBoolean("passed"), rs.getBigDecimal("score"), rs.getString("result_code"), tree(rs.getString("evidence_json")), rs.getString("evidence_digest"), instant(rs, "created_at")); }
    private static Baseline baselineRow(ResultSet rs) throws SQLException { return new Baseline(rs.getObject("id", UUID.class), rs.getObject("workspace_id", UUID.class), rs.getString("name"), rs.getInt("version"), rs.getObject("run_id", UUID.class), rs.getString("report_digest"), read(rs.getString("thresholds_json"), DECIMAL_MAP), read(rs.getString("cohort_json"), STRING_MAP), instant(rs, "created_at")); }

    private <T> T in(UUID workspaceId, java.util.function.Supplier<T> work) { return tenants.execute(new WorkspaceId(workspaceId), work); }
    private static String json(Object value) { return JSON.writeValueAsString(value); }
    private static JsonNode tree(String value) { return JSON.readTree(value); }
    private static <T> T read(String value, Class<T> type) { return JSON.readValue(value, type); }
    private static <T> T read(String value, TypeReference<T> type) { return JSON.readValue(value, type); }
    private static OffsetDateTime timestamp(Instant value) { return value == null ? null : value.atOffset(ZoneOffset.UTC); }
    private static Instant instant(ResultSet rs, String name) throws SQLException { return rs.getObject(name, OffsetDateTime.class).toInstant(); }
    private static Instant instantNullable(ResultSet rs, String name) throws SQLException { OffsetDateTime value = rs.getObject(name, OffsetDateTime.class); return value == null ? null : value.toInstant(); }
}
