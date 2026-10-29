package com.dvarahq.agentworks.observability;

import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import com.dvarahq.agentworks.storage.TenantTransactionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Tenant-bound, aggregate-only diagnostic reads. Payload, actor, credential and content columns are never selected. */
public final class SupportBundleRepository {
    private final JdbcTemplate jdbc;
    private final TenantTransactionTemplate transactions;

    public SupportBundleRepository(JdbcTemplate jdbc, TenantTransactionTemplate transactions) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    public Snapshot snapshot(WorkspaceId workspace, String traceId, int maxEvents) {
        return transactions.execute(workspace, () -> {
            UUID id = workspace.value();
            return new Snapshot(
                    grouped("select lifecycle, count(*) from agent_run where workspace_id = ? group by lifecycle", id),
                    grouped("select state, count(*) from runtime_cohort where workspace_id = ? group by state", id),
                    grouped("select category || ':' || state, count(*) from cohort_work_item "
                            + "where workspace_id = ? group by category, state", id),
                    grouped("select status, count(*) from durable_operation where workspace_id = ? group by status", id),
                    grouped("select status, count(*) from session_inbox where workspace_id = ? group by status", id),
                    grouped("select status, count(*) from human_interaction where workspace_id = ? group by status", id),
                    grouped("select validation_state, count(*) from artifact_object where workspace_id = ? "
                            + "group by validation_state", id),
                    scalar("select count(*) from agent_run_claim_quarantine where workspace_id = ?", id),
                    oldest("select min(created_at) from agent_run where workspace_id = ? and lifecycle = 'QUEUED'", id),
                    oldest("select min(drain_started_at) from runtime_cohort where workspace_id = ? "
                            + "and state = 'DRAINING'", id),
                    traceId == null ? null : correlation(id, traceId, maxEvents));
        });
    }

    private Map<String, Long> grouped(String sql, UUID workspace) {
        Map<String, Long> result = new LinkedHashMap<>();
        jdbc.query(sql, (RowCallbackHandler) row -> result.put(row.getString(1), row.getLong(2)), workspace);
        return Map.copyOf(result);
    }

    private long scalar(String sql, UUID workspace) {
        Long result = jdbc.queryForObject(sql, Long.class, workspace);
        return result == null ? 0 : result;
    }

    private Instant oldest(String sql, UUID workspace) {
        return jdbc.query(sql, result -> result.next() ? instant(result, 1) : null, workspace);
    }

    private Correlation correlation(UUID workspace, String traceId, int maxEvents) {
        List<RunIdentity> runs = jdbc.query("select id, release_id, deployment_id, revision_id, cohort_id, lifecycle, "
                        + "terminal_code, executor_type, created_at from agent_run where workspace_id = ? and trace_id = ? "
                        + "order by created_at, id fetch first 20 rows only",
                (row, ignored) -> new RunIdentity(row.getObject(1, UUID.class), row.getObject(2, UUID.class),
                        row.getObject(3, UUID.class), row.getObject(4, UUID.class), row.getObject(5, UUID.class),
                        row.getString(6), row.getString(7), row.getString(8), instant(row, 9)), workspace, traceId);
        List<EventIdentity> events = maxEvents == 0 ? List.of() : jdbc.query(
                "select run_id, sequence, event_type, span_id, created_at from agent_run_event "
                        + "where workspace_id = ? and trace_id = ? order by created_at, run_id, sequence "
                        + "fetch first ? rows only",
                (row, ignored) -> new EventIdentity(row.getObject(1, UUID.class), row.getLong(2), row.getString(3),
                        row.getString(4), instant(row, 5)), workspace, traceId, maxEvents);
        List<UUID> artifacts = ids("select a.id from artifact_object a join agent_run r "
                + "on r.workspace_id = a.workspace_id and r.id = a.run_id "
                + "where a.workspace_id = ? and r.trace_id = ? order by a.created_at, a.id fetch first 50 rows only",
                workspace, traceId);
        List<UUID> operations = ids("select o.id from durable_operation o join agent_run r "
                + "on r.workspace_id = o.workspace_id and r.id = o.run_id "
                + "where o.workspace_id = ? and r.trace_id = ? order by o.created_at, o.id fetch first 50 rows only",
                workspace, traceId);
        return new Correlation(traceId, runs, events, artifacts, operations);
    }

    private List<UUID> ids(String sql, UUID workspace, String traceId) {
        return jdbc.query(sql, (row, ignored) -> row.getObject(1, UUID.class), workspace, traceId);
    }

    private static Instant instant(ResultSet row, int column) throws SQLException {
        Object value = row.getObject(column);
        if (value == null) return null;
        if (value instanceof OffsetDateTime time) return time.toInstant();
        if (value instanceof java.sql.Timestamp time) return time.toInstant();
        return Instant.parse(value.toString());
    }

    public record Snapshot(Map<String, Long> runs, Map<String, Long> cohorts, Map<String, Long> workItems,
            Map<String, Long> operations, Map<String, Long> inbox, Map<String, Long> interactions,
            Map<String, Long> artifacts, long quarantinedRuns, Instant oldestQueuedAt, Instant oldestDrainAt,
            Correlation correlation) {}
    public record Correlation(String traceId, List<RunIdentity> runs, List<EventIdentity> events,
            List<UUID> artifacts, List<UUID> operations) {}
    public record RunIdentity(UUID runId, UUID releaseId, UUID deploymentId, UUID revisionId, UUID cohortId,
            String lifecycle, String terminalCode, String executorType, Instant createdAt) {}
    public record EventIdentity(UUID runId, long sequence, String eventType, String spanId, Instant createdAt) {}
}
