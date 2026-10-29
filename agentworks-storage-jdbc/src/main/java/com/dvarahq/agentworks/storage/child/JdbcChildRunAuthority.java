package com.dvarahq.agentworks.storage.child;

import com.dvarahq.agentworks.child.ChildFailurePolicy;
import com.dvarahq.agentworks.child.ChildRunBudget;
import com.dvarahq.agentworks.child.ChildRunException;
import com.dvarahq.agentworks.child.ChildRunRecord;
import com.dvarahq.agentworks.child.ChildRunRequest;
import com.dvarahq.agentworks.child.ChildRunStatus;
import com.dvarahq.agentworks.child.ScheduledWakeupRecord;
import com.dvarahq.agentworks.domain.run.RunPins;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import com.dvarahq.agentworks.storage.TenantContext;
import com.dvarahq.agentworks.storage.lifecycle.Digests;
import com.dvarahq.agentworks.storage.run.ClaimQuarantineSql;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;

/** Spring JDBC authority for bounded child relationships and one-shot scheduled wakeups. */
public final class JdbcChildRunAuthority {
    public record ReconciliationCandidate(WorkspaceId workspace, boolean children, boolean dueWakeups) {}
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
    private static final String CHILD_COLUMNS = "id, workspace_id, parent_run_id, root_run_id, child_run_id, "
            + "parent_cohort_id, child_release_id, child_deployment_id, child_revision_id, child_cohort_id, depth, "
            + "idempotency_key, task_text, context_json, tools_json, budget_json, failure_policy, status, "
            + "terminal_code, created_at, terminal_at";
    private static final String WAKE_COLUMNS = "id, workspace_id, session_id, run_id, release_id, deployment_id, "
            + "revision_id, cohort_id, idempotency_key, correlation_id, due_at, payload_json, status, created_at, delivered_at";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private volatile Boolean postgres;

    public JdbcChildRunAuthority(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    public Optional<ChildRunRecord> replay(WorkspaceId workspace, UUID parentRunId, ChildRunRequest request) {
        return inTransaction(workspace, () -> jdbc.query("select " + CHILD_COLUMNS + ", request_digest from child_run "
                + "where workspace_id = ? and parent_run_id = ? and idempotency_key = ?", (rs, n) -> {
            if (!requestDigest(request).equals(rs.getString("request_digest"))) throw failure(
                    ChildRunException.Code.IDEMPOTENCY_CONFLICT, "child idempotency key was used for another request");
            return child(rs, n);
        }, scope(), parentRunId, request.idempotencyKey()).stream().findFirst());
    }

    public Limits limits(WorkspaceId workspace, UUID parentRunId) {
        return inTransaction(workspace, () -> {
            UUID root = jdbc.queryForObject("select root_run_id from agent_run where workspace_id = ? and id = ?",
                    UUID.class, scope(), parentRunId);
            // Serialize every allocation for this root before observing any counters or reservations.
            jdbc.queryForList("select id from agent_run where workspace_id = ? and id in (?, ?) order by id for update",
                    UUID.class, scope(), parentRunId, root);
            int depth = jdbc.queryForObject("select count(*) from child_run where workspace_id = ? and child_run_id = ?",
                    Integer.class, scope(), parentRunId) == 0 ? 0 : jdbc.queryForObject(
                    "select depth from child_run where workspace_id = ? and child_run_id = ?",
                    Integer.class, scope(), parentRunId);
            int fanOut = jdbc.queryForObject("select count(*) from child_run where workspace_id = ? and parent_run_id = ?",
                    Integer.class, scope(), parentRunId);
            int total = jdbc.queryForObject("select count(*) from child_run where workspace_id = ? and root_run_id = ?",
                    Integer.class, scope(), root);
            Long reservedTokens = jdbc.queryForObject("select coalesce(sum(budget_tokens), 0) from child_run "
                    + "where workspace_id = ? and root_run_id = ?", Long.class, scope(), root);
            java.math.BigDecimal reservedCost = jdbc.queryForObject("select coalesce(sum(budget_cost_usd), 0) from child_run "
                    + "where workspace_id = ? and root_run_id = ?", java.math.BigDecimal.class, scope(), root);
            return new Limits(depth, fanOut, total, reservedTokens, reservedCost);
        });
    }

    public ChildRunRecord create(WorkspaceId workspace, UUID relationId, RunPins parent, RunPins child,
            int depth, ChildRunRequest request) {
        return inTransaction(workspace, () -> {
            Instant now = databaseNow();
            jdbc.update("insert into child_run(workspace_id, id, parent_run_id, root_run_id, child_run_id, "
                            + "parent_cohort_id, child_release_id, child_deployment_id, child_revision_id, child_cohort_id, "
                            + "depth, idempotency_key, request_digest, task_text, context_json, tools_json, budget_json, budget_tokens, budget_cost_usd, "
                            + "failure_policy, status, terminal_code, created_at, terminal_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, "
                            + json() + ", " + json() + ", " + json() + ", ?, ?, ?, 'STARTING', null, ?, null)",
                    scope(), relationId, parent.id(), parent.rootRunId(), child.id(), parent.cohortId(), child.releaseId(),
                    child.deploymentId(), child.revisionId(), child.cohortId(), depth, request.idempotencyKey(),
                    requestDigest(request), request.task(), json(request.context()), json(request.tools()),
                    json(request.budget()), request.budget().maxTokens(), request.budget().maxCostUsd(),
                    request.failurePolicy().name(), timestamp(now));
            if (!postgres()) insertWork(parent.cohortId(), parent.id(), "CHILD_WORK", relationId, now);
            return requiredChild(relationId);
        });
    }

    public ChildRunRecord running(WorkspaceId workspace, UUID id) {
        return inTransaction(workspace, () -> {
            Instant now = databaseNow();
            int changed = jdbc.update("update child_run set status = 'RUNNING' where workspace_id = ? and id = ? and status = 'STARTING'",
                    scope(), id);
            ChildRunRecord value = requiredChild(id);
            if (changed > 0 && !postgres()) activeWork("CHILD_WORK", id, now);
            return value;
        });
    }

    public ChildRunRecord terminal(WorkspaceId workspace, UUID id, ChildRunStatus status, String code) {
        if (!status.terminal()) throw new IllegalArgumentException("a terminal child status is required");
        return inTransaction(workspace, () -> {
            Instant now = databaseNow();
            int changed = jdbc.update("update child_run set status = ?, terminal_code = ?, terminal_at = ? "
                            + "where workspace_id = ? and id = ? and status in ('STARTING', 'RUNNING')",
                    status.name(), identity(code, "terminal code"), timestamp(now), scope(), id);
            if (changed == 0) {
                ChildRunRecord existing = requiredChild(id);
                if (existing.status() == status && Objects.equals(existing.terminalCode(), code)) return existing;
                throw failure(ChildRunException.Code.ALREADY_TERMINAL, "child relationship already has an outcome");
            }
            if (!postgres()) terminalWork("CHILD_WORK", id, now);
            return requiredChild(id);
        });
    }

    public Optional<ChildRunRecord> findChild(WorkspaceId workspace, UUID id) {
        return inTransaction(workspace, () -> childRow(id));
    }

    public List<ChildRunRecord> openChildren(WorkspaceId workspace, int limit) {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("limit is out of range");
        return inTransaction(workspace, () -> jdbc.query("select " + CHILD_COLUMNS + " from child_run where workspace_id = ? "
                + "and status in ('STARTING', 'RUNNING') order by created_at, id fetch first " + limit + " rows only",
                this::child, scope()));
    }

    /** System-level restart scan; all mutations still pass through tenant-scoped methods and RLS. */
    public List<ReconciliationCandidate> reconciliationCandidates(UUID afterWorkspace, int limit) {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("limit is out of range");
        // cohort_work_item is the system-visible quiescence projection; domain tables remain FORCE-RLS protected.
        // Selecting all pending WAKEUP workspaces is intentional: tenant-scoped claimDue applies database time.
        return jdbc.query("select workspace_id, max(case when category = 'CHILD_WORK' then 1 else 0 end) as has_children, "
                        + "max(case when category = 'WAKEUP' then 1 else 0 end) as has_wakeups from cohort_work_item "
                        + "where category in ('CHILD_WORK', 'WAKEUP') and state <> 'TERMINAL' "
                        + (afterWorkspace == null ? "" : "and workspace_id > ? ") + "group by workspace_id "
                        + "order by workspace_id fetch first " + limit + " rows only",
                (rs, row) -> new ReconciliationCandidate(
                        new WorkspaceId(rs.getObject("workspace_id", UUID.class)),
                        rs.getInt("has_children") != 0, rs.getInt("has_wakeups") != 0),
                afterWorkspace == null ? new Object[0] : new Object[]{afterWorkspace});
    }

    public List<ReconciliationCandidate> reconciliationCandidates(int limit) {
        return reconciliationCandidates(null, limit);
    }

    public boolean hasDueWakeup(WorkspaceId workspace) {
        return inTransaction(workspace, () -> Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists(select 1 from scheduled_wakeup where workspace_id = ? and status = 'SCHEDULED' "
                        + "and due_at <= " + now() + ")", Boolean.class, scope())));
    }

    /** Cancels future as well as due wakeups as soon as their run is terminal. */
    public int cancelTerminalWakeups(WorkspaceId workspace, int limit) {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("limit is out of range");
        return inTransaction(workspace, () -> {
            List<UUID> ids = jdbc.queryForList("select w.id from scheduled_wakeup w join agent_run r "
                    + "on r.workspace_id = w.workspace_id and r.id = w.run_id where w.workspace_id = ? "
                    + "and w.status = 'SCHEDULED' and r.lifecycle in ('SUCCEEDED','FAILED','CANCELLED') "
                    + "order by w.created_at, w.id fetch first " + limit + " rows only for update of w",
                    UUID.class, scope());
            ids.forEach(this::cancelWakeupScoped);
            return ids.size();
        });
    }

    public Optional<ScheduledWakeupRecord> replayWakeup(WorkspaceId workspace, UUID runId, String key,
            Instant dueAt, String correlationId, Map<String, Object> payload) {
        String digest = wakeDigest(dueAt, correlationId, payload);
        return inTransaction(workspace, () -> jdbc.query("select " + WAKE_COLUMNS + ", request_digest from scheduled_wakeup "
                + "where workspace_id = ? and run_id = ? and idempotency_key = ?", (rs, n) -> {
            if (!digest.equals(rs.getString("request_digest"))) throw failure(ChildRunException.Code.IDEMPOTENCY_CONFLICT,
                    "wakeup idempotency key was used for another request");
            return wakeup(rs, n);
        }, scope(), runId, key).stream().findFirst());
    }

    public ScheduledWakeupRecord createWakeup(WorkspaceId workspace, UUID id, UUID sessionId, RunPins run,
            String key, String correlationId, Instant dueAt, Map<String, Object> payload) {
        return inTransaction(workspace, () -> {
            long version = jdbc.queryForObject("select transition_version from runtime_cohort where workspace_id = ? and id = ?",
                    Long.class, scope(), run.cohortId());
            Instant now = databaseNow();
            jdbc.update("insert into scheduled_wakeup(workspace_id, id, session_id, run_id, release_id, deployment_id, "
                            + "revision_id, cohort_id, cohort_transition_version, idempotency_key, request_digest, correlation_id, "
                            + "due_at, payload_json, status, created_at, delivered_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, "
                            + json() + ", 'SCHEDULED', ?, null)", scope(), id, sessionId, run.id(), run.releaseId(),
                    run.deploymentId(), run.revisionId(), run.cohortId(), version, key,
                    wakeDigest(dueAt, correlationId, payload), correlationId, timestamp(dueAt), json(payload), timestamp(now));
            if (!postgres()) insertWork(run.cohortId(), run.id(), "WAKEUP", id, now);
            return requiredWakeup(id);
        });
    }

    /** Claims by updating one due row under a row lock; PostgreSQL time, never the node clock, decides eligibility. */
    public Optional<ScheduledWakeupRecord> claimDue(WorkspaceId workspace) {
        return inTransaction(workspace, () -> jdbc.query("select id from scheduled_wakeup where workspace_id = ? "
                        + "and status = 'SCHEDULED' and due_at <= " + now() + " order by due_at, id fetch first 1 row only for update",
                (rs, n) -> rs.getObject(1, UUID.class), scope()).stream().findFirst().map(this::requiredWakeup));
    }

    public ScheduledWakeupRecord delivered(WorkspaceId workspace, UUID id) {
        return terminalWakeup(workspace, id, ScheduledWakeupRecord.Status.DELIVERED);
    }

    public ScheduledWakeupRecord cancelWakeup(WorkspaceId workspace, UUID id) {
        return terminalWakeup(workspace, id, ScheduledWakeupRecord.Status.CANCELLED);
    }

    private void cancelWakeupScoped(UUID id) {
        Instant now = databaseNow();
        int changed = jdbc.update("update scheduled_wakeup set status = 'CANCELLED' where workspace_id = ? and id = ? and status = 'SCHEDULED'",
                scope(), id);
        if (changed > 0 && !postgres()) terminalWork("WAKEUP", id, now);
    }

    private ScheduledWakeupRecord terminalWakeup(WorkspaceId workspace, UUID id, ScheduledWakeupRecord.Status status) {
        return inTransaction(workspace, () -> {
            Instant now = databaseNow();
            int changed = status == ScheduledWakeupRecord.Status.DELIVERED
                    ? jdbc.update("update scheduled_wakeup set status = 'DELIVERED', delivered_at = ? where workspace_id = ? and id = ? and status = 'SCHEDULED'",
                            timestamp(now), scope(), id)
                    : jdbc.update("update scheduled_wakeup set status = 'CANCELLED' where workspace_id = ? and id = ? and status = 'SCHEDULED'",
                            scope(), id);
            if (changed == 0) throw failure(ChildRunException.Code.WAKEUP_ALREADY_TERMINAL, "wakeup already has an outcome");
            if (!postgres()) terminalWork("WAKEUP", id, now);
            return requiredWakeup(id);
        });
    }

    private Optional<ChildRunRecord> childRow(UUID id) {
        return jdbc.query("select " + CHILD_COLUMNS + " from child_run where workspace_id = ? and id = ?",
                this::child, scope(), id).stream().findFirst();
    }
    private ChildRunRecord requiredChild(UUID id) {
        return childRow(id).orElseThrow(() -> failure(ChildRunException.Code.NOT_FOUND, "child relationship does not exist"));
    }
    private ScheduledWakeupRecord requiredWakeup(UUID id) {
        return jdbc.query("select " + WAKE_COLUMNS + " from scheduled_wakeup where workspace_id = ? and id = ?",
                this::wakeup, scope(), id).stream().findFirst()
                .orElseThrow(() -> failure(ChildRunException.Code.NOT_FOUND, "wakeup does not exist"));
    }

    private ChildRunRecord child(ResultSet rs, int row) throws SQLException {
        ChildRunBudget budget = JSON.readValue(rs.getString("budget_json"), ChildRunBudget.class);
        ChildRunRequest request = new ChildRunRequest(rs.getObject("child_deployment_id", UUID.class),
                rs.getObject("child_release_id", UUID.class), rs.getString("task_text"),
                JSON.readValue(rs.getString("context_json"), new TypeReference<Map<String, Object>>() {}),
                JSON.readValue(rs.getString("tools_json"), new TypeReference<Set<String>>() {}), budget,
                ChildFailurePolicy.valueOf(rs.getString("failure_policy")), rs.getString("idempotency_key"));
        return new ChildRunRecord(rs.getObject("id", UUID.class), rs.getObject("workspace_id", UUID.class),
                rs.getObject("parent_run_id", UUID.class), rs.getObject("root_run_id", UUID.class),
                rs.getObject("child_run_id", UUID.class), rs.getObject("parent_cohort_id", UUID.class),
                rs.getObject("child_release_id", UUID.class), rs.getObject("child_deployment_id", UUID.class),
                rs.getObject("child_revision_id", UUID.class), rs.getObject("child_cohort_id", UUID.class),
                rs.getInt("depth"), request, ChildRunStatus.valueOf(rs.getString("status")),
                rs.getString("terminal_code"), instant(rs, "created_at"), instant(rs, "terminal_at"));
    }

    private ScheduledWakeupRecord wakeup(ResultSet rs, int row) throws SQLException {
        return new ScheduledWakeupRecord(rs.getObject("id", UUID.class), rs.getObject("workspace_id", UUID.class),
                rs.getObject("session_id", UUID.class), rs.getObject("run_id", UUID.class),
                rs.getObject("release_id", UUID.class), rs.getObject("deployment_id", UUID.class),
                rs.getObject("revision_id", UUID.class), rs.getObject("cohort_id", UUID.class),
                rs.getString("idempotency_key"), rs.getString("correlation_id"), instant(rs, "due_at"),
                JSON.readValue(rs.getString("payload_json"), new TypeReference<Map<String, Object>>() {}),
                ScheduledWakeupRecord.Status.valueOf(rs.getString("status")), instant(rs, "created_at"),
                instant(rs, "delivered_at"));
    }

    private void insertWork(UUID cohortId, UUID runId, String category, UUID subject, Instant now) {
        jdbc.update("insert into cohort_work_item(workspace_id, id, cohort_id, run_id, category, subject_id, state, created_at, state_changed_at, terminal_at) "
                        + "values (?, ?, ?, ?, ?, ?, 'PENDING', ?, ?, null)",
                scope(), UUID.randomUUID(), cohortId, runId, category, subject, timestamp(now), timestamp(now));
    }
    private void activeWork(String category, UUID subject, Instant now) {
        jdbc.update("update cohort_work_item set state = 'ACTIVE', state_changed_at = ? where workspace_id = ? and category = ? and subject_id = ? and state <> 'TERMINAL'",
                timestamp(now), scope(), category, subject);
    }
    private void terminalWork(String category, UUID subject, Instant now) {
        jdbc.update("update cohort_work_item set state = 'TERMINAL', state_changed_at = ?, terminal_at = ? where workspace_id = ? and category = ? and subject_id = ? and state <> 'TERMINAL'",
                timestamp(now), timestamp(now), scope(), category, subject);
    }

    private <T> T inTransaction(WorkspaceId workspace, Supplier<T> work) {
        return transactions.execute(status -> {
            try (var ignored = TenantContext.bind(workspace)) {
                try { return work.get(); }
                catch (ChildRunException failure) { throw failure; }
                catch (DataAccessException failure) { throw translate(failure); }
            }
        });
    }
    private RuntimeException translate(DataAccessException failure) {
        String detail = String.valueOf(failure.getMostSpecificCause().getMessage());
        if (detail.contains("COHORT_NOT_ADMITTING")) return failure(ChildRunException.Code.COHORT_NOT_ADMITTING, detail, failure);
        if (detail.contains("RUN_PINS_MISMATCH")) return failure(ChildRunException.Code.RUN_PINS_MISMATCH, detail, failure);
        if (detail.contains("WAKEUP_NOT_DUE")) return failure(ChildRunException.Code.WAKEUP_NOT_DUE, detail, failure);
        if (detail.contains("ALREADY_TERMINAL")) return failure(ChildRunException.Code.ALREADY_TERMINAL, detail, failure);
        return failure;
    }

    public static String requestDigest(ChildRunRequest request) {
        Map<String, Object> canonical = new TreeMap<>();
        canonical.put("targetDeploymentId", request.targetDeploymentId().toString());
        canonical.put("expectedReleaseId", request.expectedReleaseId().toString());
        canonical.put("task", request.task()); canonical.put("context", request.context());
        canonical.put("tools", request.tools().stream().sorted().toList()); canonical.put("budget", request.budget());
        canonical.put("failurePolicy", request.failurePolicy().name());
        return Digests.prefixed(JSON.writeValueAsString(canonical));
    }
    private static String wakeDigest(Instant dueAt, String correlationId, Map<String, Object> payload) {
        return Digests.prefixed(JSON.writeValueAsString(Map.of("dueAt", dueAt.toString(),
                "correlationId", correlationId, "payload", new TreeMap<>(payload))));
    }
    private static String json(Object value) { return JSON.writeValueAsString(value); }
    private UUID scope() { return TenantContext.requireWorkspace().value(); }
    private Instant databaseNow() { return jdbc.queryForObject("select " + now(), OffsetDateTime.class).toInstant(); }
    private String now() { return ClaimQuarantineSql.now(postgres()); }
    private String json() { return postgres() ? "cast(? as jsonb)" : "?"; }
    private boolean postgres() { Boolean value = postgres; if (value == null) { value = ClaimQuarantineSql.postgres(jdbc); postgres = value; } return value; }
    private static OffsetDateTime timestamp(Instant value) { return value.atOffset(ZoneOffset.UTC); }
    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class); return value == null ? null : value.toInstant();
    }
    private static String identity(String value, String name) {
        if (value == null || value.isBlank() || value.length() > 128) throw new IllegalArgumentException(name + " is invalid");
        return value;
    }
    private static ChildRunException failure(ChildRunException.Code code, String message) { return new ChildRunException(code, message); }
    private static ChildRunException failure(ChildRunException.Code code, String message, Throwable cause) { return new ChildRunException(code, message, cause); }
    public record Limits(int depth, int fanOut, int totalChildren, long reservedTokens,
            java.math.BigDecimal reservedCostUsd) {}
}
