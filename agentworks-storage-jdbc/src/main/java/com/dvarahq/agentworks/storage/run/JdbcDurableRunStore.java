package com.dvarahq.agentworks.storage.run;

import com.dvarahq.agentworks.domain.run.ClaimQuarantine;
import com.dvarahq.agentworks.domain.run.ClaimScope;
import com.dvarahq.agentworks.domain.run.CompatibleClaimStore;
import com.dvarahq.agentworks.domain.run.DurableRunStore;
import com.dvarahq.agentworks.domain.run.RunAuthorityException;
import com.dvarahq.agentworks.domain.run.RunBlockReason;
import com.dvarahq.agentworks.domain.run.RunCheckpoint;
import com.dvarahq.agentworks.domain.run.RunControl;
import com.dvarahq.agentworks.domain.run.RunEffect;
import com.dvarahq.agentworks.domain.run.RunEvent;
import com.dvarahq.agentworks.domain.run.RunLifecycle;
import com.dvarahq.agentworks.domain.run.RunLimits;
import com.dvarahq.agentworks.domain.run.RunPins;
import com.dvarahq.agentworks.domain.run.RunRecord;
import com.dvarahq.agentworks.domain.run.RunStoreConflictException;
import com.dvarahq.agentworks.domain.run.RunSummary;
import com.dvarahq.agentworks.domain.run.RunTransaction;
import com.dvarahq.agentworks.domain.run.RunUsage;
import com.dvarahq.agentworks.domain.run.WorkItem;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import com.dvarahq.agentworks.storage.TenantContext;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PostgreSQL reference implementation of {@link DurableRunStore}. Every statement is scoped by the transaction's
 * workspace binding. On PostgreSQL the V5_1 guards re-check each write; their {@code agentworks:} rejections are
 * surfaced as {@link RunAuthorityException}, while serialization failures, deadlocks, and lock timeouts propagate
 * unchanged so bounded retry can repeat the whole transaction. H2 runs the same statements for local development
 * without the database authority.
 */
public final class JdbcDurableRunStore extends CompatibleClaimStore {
    /** A code, optionally qualified by the cohort state that caused it. */
    private static final Pattern AUTHORITY = Pattern.compile(
            "agentworks:([A-Z_]+)(?: (PENDING|ROUTABLE|SUSPENDED|DRAINING|QUIESCENT|RETIRED|REVOKED)\\b)?");
    private static final Set<String> TRANSIENT = Set.of("40001", "40P01", "55P03");
    private static final String RUN = "r.id, r.workspace_id, r.definition_id, r.version_id, r.release_id, "
            + "r.deployment_id, r.environment_id, r.revision_id, r.cohort_id, r.admission, r.routing_generation_id, "
            + "r.routing_target_ordinal, r.parent_run_id, r.root_run_id, r.continuation_kind, r.trace_id, "
            + "r.manifest_version, r.limit_total_tokens, r.limit_model_calls, r.limit_tool_calls, "
            + "r.limit_runtime_millis, r.limit_cost_usd, r.created_at, r.lifecycle, r.block_reason, r.terminal_code, "
            + "r.event_sequence, r.last_event_digest, r.checkpoint_sequence, r.lease_fence, r.input_tokens, "
            + "r.output_tokens, r.total_tokens, r.model_calls, r.tool_calls, r.runtime_millis, r.estimated_cost_usd, "
            + "r.started_at, r.completed_at, r.row_version, r.lease_owner, r.lease_until, r.active_since, r.updated_at";
    private static final String EVENT = "workspace_id, run_id, sequence, id, event_type, schema_version, payload_json, "
            + "payload_digest, event_digest, trace_id, span_id, release_id, deployment_id, revision_id, cohort_id, actor, "
            + "lease_owner, lease_fence, created_at";
    private static final String CHECKPOINT = "id, workspace_id, run_id, cohort_id, sequence, event_sequence, "
            + "payload_json, payload_digest, lease_owner, lease_fence, created_at";
    private static final String EFFECT = "id, workspace_id, run_id, cohort_id, effect_key, request_digest, state, "
            + "attempts, result_json, resolution, lease_owner, lease_fence, started_at, completed_at";
    private static final String ITEM = "id, workspace_id, cohort_id, run_id, category, subject_id, state, created_at, "
            + "state_changed_at, terminal_at";

    private static final RowMapper<RunRecord> RUNS = (rs, n) -> new RunRecord(
            new RunPins(uuid(rs, "id"), uuid(rs, "workspace_id"), uuid(rs, "definition_id"), uuid(rs, "version_id"),
                    uuid(rs, "release_id"), uuid(rs, "deployment_id"), uuid(rs, "environment_id"),
                    uuid(rs, "revision_id"), uuid(rs, "cohort_id"), rs.getString("admission"),
                    uuid(rs, "routing_generation_id"), rs.getObject("routing_target_ordinal", Integer.class),
                    uuid(rs, "parent_run_id"), uuid(rs, "root_run_id"), rs.getString("continuation_kind"),
                    rs.getString("trace_id"), rs.getObject("manifest_version", Integer.class),
                    new RunLimits(rs.getObject("limit_total_tokens", Long.class), rs.getObject("limit_model_calls", Long.class),
                            rs.getObject("limit_tool_calls", Long.class), rs.getObject("limit_runtime_millis", Long.class),
                            rs.getBigDecimal("limit_cost_usd")),
                    instant(rs, "created_at")),
            new RunSummary(RunLifecycle.valueOf(rs.getString("lifecycle")),
                    rs.getString("block_reason") == null ? null : RunBlockReason.valueOf(rs.getString("block_reason")),
                    rs.getString("terminal_code"), rs.getLong("event_sequence"), rs.getString("last_event_digest"),
                    rs.getLong("checkpoint_sequence"), rs.getLong("lease_fence"),
                    new RunUsage(rs.getLong("input_tokens"), rs.getLong("output_tokens"), rs.getLong("total_tokens"),
                            rs.getLong("model_calls"), rs.getLong("tool_calls"), rs.getLong("runtime_millis"),
                            rs.getBigDecimal("estimated_cost_usd")),
                    instant(rs, "started_at"), instant(rs, "completed_at")),
            new RunControl(rs.getLong("row_version"), rs.getString("lease_owner"), instant(rs, "lease_until"),
                    instant(rs, "active_since"), instant(rs, "updated_at")));
    private static final RowMapper<RunEvent> EVENTS = (rs, n) -> new RunEvent(uuid(rs, "id"), uuid(rs, "workspace_id"),
            uuid(rs, "run_id"), rs.getLong("sequence"), rs.getString("event_type"), rs.getInt("schema_version"),
            rs.getString("payload_json"), rs.getString("payload_digest"), rs.getString("event_digest"),
            rs.getString("trace_id"), rs.getString("span_id"), uuid(rs, "release_id"), uuid(rs, "deployment_id"),
            uuid(rs, "revision_id"), uuid(rs, "cohort_id"), rs.getString("actor"), rs.getString("lease_owner"),
            rs.getLong("lease_fence"), instant(rs, "created_at"));
    private static final RowMapper<RunCheckpoint> CHECKPOINTS = (rs, n) -> new RunCheckpoint(uuid(rs, "id"),
            uuid(rs, "workspace_id"), uuid(rs, "run_id"), uuid(rs, "cohort_id"), rs.getLong("sequence"),
            rs.getLong("event_sequence"), rs.getString("payload_json"), rs.getString("payload_digest"),
            rs.getString("lease_owner"), rs.getLong("lease_fence"), instant(rs, "created_at"));
    private static final RowMapper<RunEffect> EFFECTS = (rs, n) -> new RunEffect(uuid(rs, "id"), uuid(rs, "workspace_id"),
            uuid(rs, "run_id"), uuid(rs, "cohort_id"), rs.getString("effect_key"), rs.getString("request_digest"),
            RunEffect.State.valueOf(rs.getString("state")), rs.getInt("attempts"), rs.getString("result_json"),
            rs.getString("resolution"), rs.getString("lease_owner"), rs.getLong("lease_fence"),
            instant(rs, "started_at"), instant(rs, "completed_at"));
    private static final RowMapper<WorkItem> ITEMS = (rs, n) -> new WorkItem(uuid(rs, "id"), uuid(rs, "workspace_id"),
            uuid(rs, "cohort_id"), uuid(rs, "run_id"), WorkItem.Category.valueOf(rs.getString("category")),
            uuid(rs, "subject_id"), WorkItem.State.valueOf(rs.getString("state")), instant(rs, "created_at"),
            instant(rs, "state_changed_at"), instant(rs, "terminal_at"));

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private volatile Boolean postgres;

    public JdbcDurableRunStore(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    @Override
    public <T> T inTransaction(WorkspaceId workspace, Function<RunTransaction, T> work) {
        Objects.requireNonNull(workspace, "workspace");
        return authority(() -> transactions.execute(status -> {
            try (var ignored = TenantContext.bind(workspace)) {
                return work.apply(new Transaction(workspace));
            }
        }));
    }

    /**
     * Reports the selected run before its row is read and decoded, so a decoding failure is attributed to it. The
     * identity is resolved by {@link CompatibleClaimStore}, which refuses a scope that names none (#185, #201, #206).
     */
    @Override
    protected <T> Optional<T> claimCompatible(ClaimScope scope, String compatibility,
            java.util.function.Consumer<UUID> selected, BiFunction<RunRecord, RunTransaction, T> work) {
        return authority(() -> transactions.execute(status -> {
            // A workspace-scoped worker establishes RLS directly. The shared worker pool resolves only the next
            // candidate's workspace through a narrow SECURITY DEFINER function, then repeats and locks the complete
            // candidate query under that tenant's RLS policy.
            WorkspaceId candidateTenant = scope.workspace().orElseGet(() ->
                    postgres() ? candidateWorkspace(scope, compatibility).orElse(null) : null);
            if (postgres() && candidateTenant == null) return Optional.<T>empty();
            boolean sharedPoolClaim = scope.workspace().isEmpty();
            TenantContext.Scope tenant = candidateTenant == null ? null : TenantContext.bind(candidateTenant);
            try (tenant) {
                List<Object> arguments = new ArrayList<>();
                StringBuilder sql = new StringBuilder("select r.workspace_id, r.id from agent_run r ");
                if (sharedPoolClaim) {
                    sql.append("left join run_admission_fairness f on f.workspace_id = r.workspace_id ");
                }
                sql.append("where ").append(ClaimQuarantineSql.claimable("r", now()))
                        .append(" and exists (select 1 from runtime_cohort c where "
                        + "c.workspace_id = r.workspace_id and c.id = r.cohort_id and "
                        + ClaimQuarantineSql.cohortAdmits("c") + ") and r.manifest_version in (");
                sql.append(placeholders(scope.manifestVersions().size())).append(')');
                arguments.addAll(scope.manifestVersions());
                if (!scope.executorTypes().isEmpty()) {
                    // A node selects only runs it can execute, so runs of other executor types never block its queue.
                    sql.append(" and r.executor_type in (").append(placeholders(scope.executorTypes().size())).append(')');
                    arguments.addAll(scope.executorTypes());
                }
                scope.workspace().ifPresent(workspace -> {
                    sql.append(" and r.workspace_id = ?");
                    arguments.add(workspace.value());
                });
                if (!scope.excludedRunIds().isEmpty()) {
                    sql.append(" and r.id not in (").append(placeholders(scope.excludedRunIds().size())).append(')');
                    arguments.addAll(scope.excludedRunIds());
                }
                // Runs quarantined for this node's compatibility identity are skipped by predicate, however many there
                // are, so none can starve the runs behind it; quarantines of other identities do not apply (#181). The
                // primary key (workspace_id, run_id, compatibility) serves the probe.
                sql.append(" and not exists (select 1 from agent_run_claim_quarantine q where q.workspace_id = "
                        + "r.workspace_id and q.run_id = r.id and q.compatibility = ? and ")
                        .append(ClaimQuarantineSql.inForce("q", now())).append(')');
                arguments.add(compatibility);
                // Deficit round-robin by workspace, FIFO within one workspace. A workspace with a deep old backlog is
                // served once, then moves behind every workspace served less recently, so queue age cannot monopolize
                // the shared worker pool. Database time avoids node-clock authority.
                sql.append(sharedPoolClaim ? " order by f.last_claimed_at nulls first, r.created_at, r.id"
                        : " order by r.created_at, r.id");
                sql.append(" fetch first 1 rows only")
                        .append(postgres() ? " for no key update of r skip locked" : " for update");
                List<UUID[]> candidates = jdbc.query(sql.toString(),
                        (rs, n) -> new UUID[]{uuid(rs, "workspace_id"), uuid(rs, "id")}, arguments.toArray());
                if (candidates.isEmpty()) return Optional.<T>empty();
                WorkspaceId workspace = new WorkspaceId(candidates.getFirst()[0]);
                if (sharedPoolClaim && postgres()) {
                    jdbc.update("insert into run_admission_fairness(workspace_id, last_claimed_at) values (?, "
                                    + "current_timestamp) on conflict (workspace_id) do update set last_claimed_at = "
                                    + "excluded.last_claimed_at",
                            workspace.value());
                } else if (sharedPoolClaim) {
                    jdbc.update("merge into run_admission_fairness(workspace_id, last_claimed_at) key(workspace_id) "
                            + "values (?, current_timestamp)", workspace.value());
                }
                selected.accept(candidates.getFirst()[1]);
                try (var ignored = TenantContext.bind(workspace)) {
                    Transaction transaction = new Transaction(workspace);
                    RunRecord run = transaction.findRun(candidates.getFirst()[1]).orElseThrow();
                    return Optional.ofNullable(work.apply(run, transaction));
                }
            }
        }));
    }

    private Optional<WorkspaceId> candidateWorkspace(ClaimScope scope, String compatibility) {
        List<UUID> candidates = jdbc.query(connection -> {
            var statement = connection.prepareStatement("select agentworks_candidate_workspace(?, ?, ?, ?)");
            statement.setArray(1, connection.createArrayOf("integer", scope.manifestVersions().toArray()));
            statement.setArray(2, connection.createArrayOf("varchar", scope.executorTypes().toArray()));
            statement.setArray(3, connection.createArrayOf("uuid", scope.excludedRunIds().toArray()));
            statement.setString(4, compatibility);
            return statement;
        }, (rs, n) -> rs.getObject(1, UUID.class));
        if (candidates.isEmpty() || candidates.getFirst() == null) return Optional.empty();
        return Optional.of(new WorkspaceId(candidates.getFirst()));
    }

    private static final String PAUSE_REQUEST = "workspace_id, run_id, requested_by, requested_at, reason";
    private static final RowMapper<com.dvarahq.agentworks.domain.run.RunPauseRequest> PAUSE_REQUESTS = (rs, n) ->
            new com.dvarahq.agentworks.domain.run.RunPauseRequest(uuid(rs, "workspace_id"), uuid(rs, "run_id"),
                    rs.getString("requested_by"), instant(rs, "requested_at"), rs.getString("reason"));

    static final String QUARANTINE = "workspace_id, run_id, compatibility, reason, code, quarantines, quarantined_by, "
            + "quarantined_at, not_before, closed_at";
    static final RowMapper<ClaimQuarantine> QUARANTINES = (rs, n) -> new ClaimQuarantine(uuid(rs, "workspace_id"),
            uuid(rs, "run_id"), rs.getString("compatibility"), ClaimQuarantine.Reason.valueOf(rs.getString("reason")),
            rs.getString("code"), rs.getInt("quarantines"), rs.getString("quarantined_by"), instant(rs, "quarantined_at"),
            instant(rs, "not_before"), instant(rs, "closed_at"));

    @Override
    public boolean keepsClaimQuarantine() {
        return true;
    }

    /**
     * Fenced by the run's row lock, taken with the claimability predicate (and the observed event sequence and lease
     * fence, when the request carries them) re-checked after any wait: a run another worker claimed (a live lease),
     * that moved on or finished, or that no longer exists is never quarantined, and a quarantine already in force is
     * returned unchanged. Lock order is the run, then its quarantine row, and no path locks them the other way round.
     */
    @Override
    public Optional<ClaimQuarantine> quarantine(UUID runId, ClaimQuarantine.Request request) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(request, "request");
        return authority(() -> transactions.execute(status -> {
            List<UUID> resolved = postgres()
                    ? jdbc.query("select agentworks_run_workspace(?)", (rs, n) -> rs.getObject(1, UUID.class), runId)
                    : jdbc.query("select workspace_id from agent_run where id = ?",
                            (rs, n) -> rs.getObject(1, UUID.class), runId);
            if (resolved.isEmpty() || resolved.getFirst() == null) return Optional.<ClaimQuarantine>empty();
            WorkspaceId workspace = new WorkspaceId(resolved.getFirst());
            try (var ignored = TenantContext.bind(workspace)) {
                List<Object> arguments = new ArrayList<>(List.of(workspace.value(), runId));
                StringBuilder sql = new StringBuilder("select r.workspace_id from agent_run r where r.workspace_id = ? "
                        + "and r.id = ? and " + ClaimQuarantineSql.claimable("r", now()));
                if (request.observed() != null) {
                    sql.append(" and r.event_sequence = ? and r.lease_fence = ?");
                    arguments.add(request.observed().eventSequence());
                    arguments.add(request.observed().leaseFence());
                }
                sql.append(postgres() ? " for no key update" : " for update");
                if (jdbc.query(sql.toString(), (rs, n) -> uuid(rs, "workspace_id"), arguments.toArray()).isEmpty()) {
                    return Optional.<ClaimQuarantine>empty();
                }
                OffsetDateTime observed = jdbc.queryForObject("select " + now(), OffsetDateTime.class);
                Instant now = Objects.requireNonNull(observed).toInstant().truncatedTo(ChronoUnit.MICROS);
                Optional<ClaimQuarantine> previous = first(jdbc.query("select " + QUARANTINE + " from "
                        + "agent_run_claim_quarantine where workspace_id = ? and run_id = ? and compatibility = ? for update",
                        QUARANTINES, workspace.value(), runId, request.compatibility()));
                // A quarantine already in force (another worker's, recorded meanwhile) is kept, never extended.
                if (previous.isPresent() && previous.get().excludes(now)) return previous;
                int count = ClaimQuarantine.next(previous.orElse(null), now);
                ClaimQuarantine recorded = new ClaimQuarantine(workspace.value(), runId, request.compatibility(),
                        request.reason(), request.code(), count, request.worker(), now, request.notBefore(now, count),
                        null);
                if (previous.isEmpty()) {
                    jdbc.update("insert into agent_run_claim_quarantine(" + QUARANTINE
                                    + ") values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", workspace.value(), runId,
                            recorded.compatibility(), recorded.reason().name(), recorded.code(), count,
                            recorded.quarantinedBy(), timestamp(now), timestamp(recorded.notBefore()), null);
                } else {
                    jdbc.update("update agent_run_claim_quarantine set reason = ?, code = ?, quarantines = ?, "
                                    + "quarantined_by = ?, quarantined_at = ?, not_before = ?, closed_at = null "
                                    + "where workspace_id = ? and run_id = ? and compatibility = ?",
                            recorded.reason().name(), recorded.code(), count, recorded.quarantinedBy(), timestamp(now),
                            timestamp(recorded.notBefore()), workspace.value(), runId, recorded.compatibility());
                }
                return Optional.of(recorded);
            }
        }));
    }

    private final class Transaction implements RunTransaction {
        private final WorkspaceId workspace;

        private Transaction(WorkspaceId workspace) {
            this.workspace = workspace;
        }

        @Override
        public WorkspaceId workspace() {
            return workspace;
        }

        @Override
        public Instant now() {
            OffsetDateTime now = jdbc.queryForObject("select " + JdbcDurableRunStore.this.now(), OffsetDateTime.class);
            return Objects.requireNonNull(now).toInstant().truncatedTo(ChronoUnit.MICROS);
        }

        @Override
        public Optional<RunRecord> findRun(UUID runId) {
            return first(jdbc.query("select " + RUN + " from agent_run r where r.workspace_id = ? and r.id = ?", RUNS,
                    scope(), runId));
        }

        @Override
        public Optional<RunRecord> lockRun(UUID runId) {
            return first(jdbc.query("select " + RUN + " from agent_run r where r.workspace_id = ? and r.id = ?"
                    + (postgres() ? " for no key update" : " for update"), RUNS, scope(), runId));
        }

        @Override
        public void updateRun(RunRecord expected, RunRecord updated) {
            if (!expected.id().equals(updated.id()) || !expected.pins().equals(updated.pins())) {
                throw new IllegalArgumentException("run pins are immutable");
            }
            RunSummary summary = updated.summary();
            RunControl control = updated.control();
            RunUsage usage = summary.usage();
            int changed = jdbc.update("update agent_run set lifecycle = ?, block_reason = ?, terminal_code = ?, "
                            + "event_sequence = ?, last_event_digest = ?, checkpoint_sequence = ?, lease_fence = ?, "
                            + "input_tokens = ?, output_tokens = ?, total_tokens = ?, model_calls = ?, tool_calls = ?, "
                            + "runtime_millis = ?, estimated_cost_usd = ?, started_at = ?, completed_at = ?, row_version = ?, "
                            + "lease_owner = ?, lease_until = ?, active_since = ?, updated_at = ? "
                            + "where workspace_id = ? and id = ? and row_version = ?",
                    summary.lifecycle().name(), summary.blockReason() == null ? null : summary.blockReason().name(),
                    summary.terminalCode(), summary.eventSequence(), summary.lastEventDigest(),
                    summary.checkpointSequence(), summary.leaseFence(), usage.inputTokens(), usage.outputTokens(),
                    usage.totalTokens(), usage.modelCalls(), usage.toolCalls(), usage.runtimeMillis(),
                    usage.estimatedCostUsd(), timestamp(summary.startedAt()), timestamp(summary.completedAt()),
                    control.rowVersion(), control.leaseOwner(), timestamp(control.leaseUntil()),
                    timestamp(control.activeSince()), timestamp(control.updatedAt()), scope(), expected.id(),
                    expected.control().rowVersion());
            if (changed != 1) throw new RunStoreConflictException("run " + expected.id() + " changed concurrently");
            if (summary.lifecycle().terminal() && !expected.summary().lifecycle().terminal()) {
                // A run that ended can never be claimed again: its claim quarantines close with it (#181), so they
                // leave the active listing and are never mistaken for a reason the run is waiting.
                jdbc.update("update agent_run_claim_quarantine set closed_at = " + JdbcDurableRunStore.this.now()
                        + " where workspace_id = ? and run_id = ? and closed_at is null", scope(), expected.id());
            }
        }

        @Override
        public void appendEvents(List<RunEvent> events) {
            for (RunEvent event : events) {
                requireScope(event.workspaceId());
                jdbc.update("insert into agent_run_event(" + EVENT + ") values (?, ?, ?, ?, ?, ?, " + json()
                                + ", ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        scope(), event.runId(), event.sequence(), event.id(), event.type(), event.schemaVersion(),
                        event.payloadJson(), event.payloadDigest(), event.eventDigest(), event.traceId(), event.spanId(),
                        event.releaseId(), event.deploymentId(), event.revisionId(), event.cohortId(), event.actor(),
                        event.leaseOwner(), event.leaseFence(), timestamp(event.createdAt()));
            }
        }

        @Override
        public List<RunEvent> events(UUID runId) {
            return jdbc.query("select " + EVENT + " from agent_run_event where workspace_id = ? and run_id = ? "
                    + "order by sequence", EVENTS, scope(), runId);
        }

        /** A bounded range read on the event primary key (workspace, run, sequence). */
        @Override
        public List<RunEvent> eventWindow(UUID runId, long fromSequence, long throughSequence, int limit) {
            if (limit < 1 || fromSequence > throughSequence) return List.of();
            return jdbc.query("select " + EVENT + " from agent_run_event where workspace_id = ? and run_id = ? "
                    + "and sequence >= ? and sequence <= ? order by sequence fetch first " + limit + " rows only",
                    EVENTS, scope(), runId, fromSequence, throughSequence);
        }

        /** Newest first; the keyset position excludes the run it names, so pages neither repeat nor skip runs. */
        @Override
        public List<RunRecord> listRuns(com.dvarahq.agentworks.domain.run.RunQuery query) {
            List<Object> arguments = new ArrayList<>();
            StringBuilder sql = new StringBuilder("select " + RUN + " from agent_run r where r.workspace_id = ?");
            arguments.add(scope());
            if (query.deploymentId() != null) {
                sql.append(" and r.deployment_id = ?");
                arguments.add(query.deploymentId());
            }
            if (query.releaseId() != null) {
                sql.append(" and r.release_id = ?");
                arguments.add(query.releaseId());
            }
            if (!query.lifecycles().isEmpty()) {
                sql.append(" and r.lifecycle in (").append(placeholders(query.lifecycles().size())).append(')');
                query.lifecycles().stream().map(Enum::name).sorted().forEach(arguments::add);
            }
            if (query.beforeCreatedAt() != null) {
                // A row-value comparison lets the index seek to the position instead of filtering later rows.
                sql.append(" and (r.created_at, r.id) < (?, ?)");
                arguments.add(timestamp(query.beforeCreatedAt()));
                arguments.add(query.beforeId());
            }
            sql.append(" order by r.created_at desc, r.id desc fetch first ").append(query.limit()).append(" rows only");
            return jdbc.query(sql.toString(), RUNS, arguments.toArray());
        }

        @Override
        public java.util.Map<UUID, Long> eventSequences(java.util.Collection<UUID> runIds) {
            java.util.Map<UUID, Long> sequences = new java.util.HashMap<>();
            List<UUID> ids = List.copyOf(new java.util.LinkedHashSet<>(runIds));
            for (int start = 0; start < ids.size(); start += 500) {
                List<UUID> chunk = ids.subList(start, Math.min(ids.size(), start + 500));
                List<Object> arguments = new ArrayList<>();
                arguments.add(scope());
                arguments.addAll(chunk);
                jdbc.query("select id, event_sequence from agent_run where workspace_id = ? and id in ("
                                + placeholders(chunk.size()) + ")",
                        rs -> {
                            sequences.put(uuid(rs, "id"), rs.getLong("event_sequence"));
                        }, arguments.toArray());
            }
            return sequences;
        }

        @Override
        public String cohortState(UUID cohortId) {
            return first(jdbc.queryForList("select state from runtime_cohort where workspace_id = ? and id = ?",
                    String.class, scope(), cohortId)).orElseThrow(() -> new RunAuthorityException("NOT_FOUND", null, null));
        }

        @Override
        public Optional<String> releaseManifest(UUID releaseId) {
            return first(jdbc.queryForList("select manifest_json from agent_release where workspace_id = ? and id = ?",
                    String.class, scope(), releaseId));
        }

        @Override
        public Optional<String> deploymentConfiguration(UUID revisionId) {
            return first(jdbc.queryForList("select configuration_json from deployment_revision where workspace_id = ? "
                    + "and id = ?", String.class, scope(), revisionId));
        }

        @Override
        public Optional<com.dvarahq.agentworks.domain.run.PinnedAgentVersion> agentVersion(UUID versionId) {
            return first(jdbc.query("select id, spec_json, spec_digest from agent_version where workspace_id = ? "
                    + "and id = ?", (rs, n) -> new com.dvarahq.agentworks.domain.run.PinnedAgentVersion(uuid(rs, "id"),
                    rs.getString("spec_json"), rs.getString("spec_digest")), scope(), versionId));
        }

        @Override
        public void insertCheckpoint(RunCheckpoint checkpoint) {
            requireScope(checkpoint.workspaceId());
            jdbc.update("insert into agent_run_checkpoint(workspace_id, run_id, sequence, id, cohort_id, event_sequence, "
                            + "payload_json, payload_digest, lease_owner, lease_fence, created_at) values (?, ?, ?, ?, ?, ?, "
                            + json() + ", ?, ?, ?, ?)", scope(), checkpoint.runId(), checkpoint.sequence(), checkpoint.id(),
                    checkpoint.cohortId(), checkpoint.eventSequence(), checkpoint.payloadJson(),
                    checkpoint.payloadDigest(), checkpoint.leaseOwner(), checkpoint.leaseFence(),
                    timestamp(checkpoint.createdAt()));
        }

        @Override
        public Optional<RunCheckpoint> latestCheckpoint(UUID runId) {
            return first(jdbc.query("select " + CHECKPOINT + " from agent_run_checkpoint where workspace_id = ? "
                    + "and run_id = ? order by sequence desc fetch first 1 rows only", CHECKPOINTS, scope(), runId));
        }

        @Override
        public RunTransaction.CheckpointResources checkpointResources(UUID runId) {
            List<RunTransaction.CheckpointArtifact> artifacts = jdbc.query("select id, content_digest, lifecycle_state, "
                            + "validation_state from artifact_object where workspace_id = ? and run_id = ? "
                            + "order by created_at, id",
                    (rs, n) -> new RunTransaction.CheckpointArtifact(uuid(rs, "id"),
                            rs.getString("content_digest"), rs.getString("lifecycle_state"),
                            rs.getString("validation_state")), scope(), runId);
            List<RunTransaction.CheckpointSandbox> sandboxes = jdbc.query("select id, provider, state from "
                            + "sandbox_resource where workspace_id = ? and run_id = ? order by created_at, id",
                    (rs, n) -> new RunTransaction.CheckpointSandbox(uuid(rs, "id"), rs.getString("provider"),
                            rs.getString("state"), "RECONCILE_ONLY"), scope(), runId);
            return new RunTransaction.CheckpointResources(artifacts, sandboxes);
        }

        @Override
        public Optional<RunEffect> findEffect(UUID runId, String effectKey) {
            return first(jdbc.query("select " + EFFECT + " from agent_run_effect where workspace_id = ? and run_id = ? "
                    + "and effect_key = ?", EFFECTS, scope(), runId, effectKey));
        }

        @Override
        public List<RunEffect> effects(UUID runId) {
            return jdbc.query("select " + EFFECT + " from agent_run_effect where workspace_id = ? and run_id = ? "
                    + "order by started_at, effect_key", EFFECTS, scope(), runId);
        }

        @Override
        public List<String> pendingEffectKeys(UUID runId) {
            return jdbc.queryForList("select effect_key from agent_run_effect where workspace_id = ? and run_id = ? "
                    + "and state = 'PENDING' order by started_at, effect_key", String.class, scope(), runId);
        }

        @Override
        public void insertEffect(RunEffect effect) {
            requireScope(effect.workspaceId());
            jdbc.update("insert into agent_run_effect(" + EFFECT + ") values (?, ?, ?, ?, ?, ?, ?, ?, " + json()
                            + ", ?, ?, ?, ?, ?)", effect.id(), scope(), effect.runId(), effect.cohortId(),
                    effect.effectKey(), effect.requestDigest(), effect.state().name(), effect.attempts(),
                    effect.resultJson(), effect.resolution(), effect.leaseOwner(), effect.leaseFence(),
                    timestamp(effect.startedAt()), timestamp(effect.completedAt()));
        }

        @Override
        public void updateEffect(RunEffect expected, RunEffect updated) {
            int changed = jdbc.update("update agent_run_effect set state = ?, attempts = ?, result_json = " + json()
                            + ", resolution = ?, lease_owner = ?, lease_fence = ?, completed_at = ? where workspace_id = ? "
                            + "and run_id = ? and effect_key = ? and state = ? and attempts = ?",
                    updated.state().name(), updated.attempts(), updated.resultJson(), updated.resolution(),
                    updated.leaseOwner(), updated.leaseFence(), timestamp(updated.completedAt()), scope(),
                    expected.runId(), expected.effectKey(), expected.state().name(), expected.attempts());
            if (changed != 1) throw new RunStoreConflictException("effect " + expected.effectKey() + " changed concurrently");
        }

        @Override
        public Optional<com.dvarahq.agentworks.domain.run.RunPauseRequest> pauseRequest(UUID runId) {
            return first(jdbc.query("select " + PAUSE_REQUEST + " from agent_run_pause_request where workspace_id = ? "
                    + "and run_id = ?", PAUSE_REQUESTS, scope(), runId));
        }

        @Override
        public void insertPauseRequest(com.dvarahq.agentworks.domain.run.RunPauseRequest request) {
            requireScope(request.workspaceId());
            jdbc.update("insert into agent_run_pause_request(workspace_id, run_id, requested_by, requested_at, reason) "
                    + "values (?, ?, ?, ?, ?)", request.workspaceId(), request.runId(), request.requestedBy(),
                    timestamp(request.requestedAt()), request.reason());
        }

        @Override
        public Optional<com.dvarahq.agentworks.domain.run.RunPauseRequest> takePauseRequest(UUID runId) {
            if (postgres()) {
                return first(jdbc.query("delete from agent_run_pause_request where workspace_id = ? and run_id = ? "
                        + "returning " + PAUSE_REQUEST, PAUSE_REQUESTS, scope(), runId));
            }
            Optional<com.dvarahq.agentworks.domain.run.RunPauseRequest> request = pauseRequest(runId);
            request.ifPresent(ignored -> jdbc.update("delete from agent_run_pause_request where workspace_id = ? "
                    + "and run_id = ?", scope(), runId));
            return request;
        }

        /**
         * One row per actor and standing request (#201), removed with the request by its foreign key, written by one
         * insert that reports whether it added the row. On PostgreSQL it is INSERT ... ON CONFLICT DO NOTHING, which
         * the Run Plane may run without reading the table (V20_1). The caller holds the run locked.
         */
        @Override
        public boolean recordPauseRepeat(UUID runId, String actor, Instant now) {
            String insert = "insert into agent_run_pause_repeat(workspace_id, run_id, actor, repeated_at) ";
            if (postgres()) {
                return jdbc.update(insert + "values (?, ?, ?, ?) on conflict do nothing", scope(), runId, actor,
                        timestamp(now)) == 1;
            }
            return jdbc.update(insert + "select cast(? as uuid), cast(? as uuid), cast(? as varchar(255)), "
                    + "cast(? as timestamp with time zone) where not exists (select 1 from agent_run_pause_repeat "
                    + "where workspace_id = ? and run_id = ? and actor = ?)", scope(), runId, actor, timestamp(now),
                    scope(), runId, actor) == 1;
        }

        /** The cohort's state and whether the run has a pause request, in one query (#183). */
        @Override
        public WriteState writeState(UUID runId, UUID cohortId) {
            return first(jdbc.query("select c.state, exists (select 1 from agent_run_pause_request p where "
                    + "p.workspace_id = c.workspace_id and p.run_id = ?) as pause_requested from runtime_cohort c "
                    + "where c.workspace_id = ? and c.id = ?", (rs, n) -> new WriteState(rs.getString("state"),
                    rs.getBoolean("pause_requested")), runId, scope(), cohortId))
                    .orElseThrow(() -> new RunAuthorityException("NOT_FOUND", null, null));
        }

        @Override
        public List<WorkItem> openWorkItems(UUID runId) {
            return jdbc.query("select " + ITEM + " from cohort_work_item where workspace_id = ? and run_id = ? "
                    + "and state <> 'TERMINAL' order by created_at, id", ITEMS, scope(), runId);
        }

        @Override
        public void insertWorkItem(WorkItem item) {
            requireScope(item.workspaceId());
            jdbc.update("insert into cohort_work_item(" + ITEM + ") values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", item.id(),
                    scope(), item.cohortId(), item.runId(), item.category().name(), item.subjectId(),
                    item.state().name(), timestamp(item.createdAt()), timestamp(item.stateChangedAt()),
                    timestamp(item.terminalAt()));
        }

        @Override
        public void updateWorkItem(WorkItem expected, WorkItem updated) {
            int changed = jdbc.update("update cohort_work_item set state = ?, state_changed_at = ?, terminal_at = ? "
                            + "where workspace_id = ? and id = ? and state = ?", updated.state().name(),
                    timestamp(updated.stateChangedAt()), timestamp(updated.terminalAt()), scope(), expected.id(),
                    expected.state().name());
            if (changed != 1) throw new RunStoreConflictException("work item " + expected.id() + " changed concurrently");
        }

        private UUID scope() {
            UUID bound = TenantContext.requireWorkspace().value();
            if (!bound.equals(workspace.value())) throw new IllegalStateException("transaction is bound to another workspace");
            return bound;
        }

        private void requireScope(UUID workspaceId) {
            if (workspaceId != null && !workspaceId.equals(scope())) {
                throw new IllegalArgumentException("a run write must stay in its workspace");
            }
        }
    }

    /** Maps database-authority rejections to typed failures; transient failures stay retryable. */
    private static <T> T authority(Supplier<T> work) {
        try {
            return work.get();
        } catch (DataAccessException failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof SQLException sql && TRANSIENT.contains(sql.getSQLState())) throw failure;
            }
            Matcher code = AUTHORITY.matcher(String.valueOf(failure.getMostSpecificCause().getMessage()));
            if (code.find()) throw new RunAuthorityException(code.group(1), code.group(2), failure);
            throw failure;
        }
    }

    private boolean postgres() {
        Boolean value = postgres;
        if (value == null) {
            value = ClaimQuarantineSql.postgres(jdbc);
            postgres = value;
        }
        return value;
    }

    /** Actual current time: a lease must not be judged by the start time of a long transaction. */
    private String now() {
        return ClaimQuarantineSql.now(postgres());
    }

    private String json() {
        return postgres() ? "cast(? as jsonb)" : "?";
    }

    private static String placeholders(int count) {
        return String.join(", ", java.util.Collections.nCopies(count, "?"));
    }

    private static <T> Optional<T> first(List<T> rows) {
        return rows.stream().findFirst();
    }

    private static UUID uuid(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, UUID.class);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime timestamp(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
