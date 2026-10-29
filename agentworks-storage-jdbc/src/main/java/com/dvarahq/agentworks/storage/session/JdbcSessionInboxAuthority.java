package com.dvarahq.agentworks.storage.session;

import com.dvarahq.agentworks.domain.run.RunLease;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import com.dvarahq.agentworks.session.AgentSession;
import com.dvarahq.agentworks.session.InboxMessageDraft;
import com.dvarahq.agentworks.session.InboxMessageStatus;
import com.dvarahq.agentworks.session.InboxMessageType;
import com.dvarahq.agentworks.session.SessionAuthorityException;
import com.dvarahq.agentworks.session.SessionInboxMessage;
import com.dvarahq.agentworks.storage.TenantContext;
import com.dvarahq.agentworks.storage.lifecycle.Digests;
import com.dvarahq.agentworks.storage.run.ClaimQuarantineSql;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static com.dvarahq.agentworks.session.SessionAuthorityException.Code;

/** PostgreSQL/Spring JDBC authority for logical sessions and their serialized durable inboxes. */
public final class JdbcSessionInboxAuthority {
    private static final String MESSAGE_COLUMNS = "id, workspace_id, session_id, run_id, release_id, deployment_id, "
            + "revision_id, cohort_id, cohort_transition_version, sequence, message_type, correlation_id, "
            + "payload_json, status, row_version, claimed_by, claim_fence, claim_until, created_at, terminal_at";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final DurablePayloadCodec payloads = new DurablePayloadCodec();
    private volatile Boolean postgres;

    public JdbcSessionInboxAuthority(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    public AgentSession create(WorkspaceId workspace, UUID id, UUID deploymentId) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(deploymentId, "deploymentId");
        return inTransaction(workspace, () -> {
            if (jdbc.queryForObject("select count(*) from agent_deployment where workspace_id = ? and id = ?",
                    Long.class, scope(), deploymentId) != 1) throw failure(Code.NOT_FOUND, "deployment does not exist");
            Instant now = databaseNow();
            jdbc.update("insert into agent_session(workspace_id, id, deployment_id, version, created_at, updated_at) "
                            + "values (?, ?, ?, 0, ?, ?)", scope(), id, deploymentId, timestamp(now), timestamp(now));
            return session(id, false).orElseThrow();
        });
    }

    /** Deterministic channel mapping: concurrent delivery creates one session and all retries observe it. */
    public AgentSession findOrCreate(WorkspaceId workspace, UUID id, UUID deploymentId) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(deploymentId, "deploymentId");
        return inTransaction(workspace, () -> {
            Optional<AgentSession> existing = session(id, false);
            if (existing.isPresent()) {
                if (!existing.orElseThrow().deploymentId().equals(deploymentId)) {
                    throw failure(Code.RUN_PINS_MISMATCH, "deterministic session belongs to another deployment");
                }
                return existing.orElseThrow();
            }
            if (jdbc.queryForObject("select count(*) from agent_deployment where workspace_id = ? and id = ?",
                    Long.class, scope(), deploymentId) != 1) throw failure(Code.NOT_FOUND, "deployment does not exist");
            Instant now = databaseNow();
            try {
                jdbc.update("insert into agent_session(workspace_id, id, deployment_id, version, created_at, updated_at) "
                                + "values (?, ?, ?, 0, ?, ?)", scope(), id, deploymentId,
                        timestamp(now), timestamp(now));
            } catch (DataAccessException race) {
                AgentSession winner = session(id, false).orElseThrow(() -> race);
                if (!winner.deploymentId().equals(deploymentId)) {
                    throw failure(Code.RUN_PINS_MISMATCH, "deterministic session belongs to another deployment", race);
                }
                return winner;
            }
            return session(id, false).orElseThrow();
        });
    }

    public Optional<AgentSession> findSession(WorkspaceId workspace, UUID id) {
        return inTransaction(workspace, () -> session(id, false));
    }

    public List<AgentSession> listSessions(WorkspaceId workspace, Instant beforeCreatedAt, UUID beforeId, int limit) {
        if ((beforeCreatedAt == null) != (beforeId == null)) throw new IllegalArgumentException("session cursor is incomplete");
        if (limit < 1 || limit > 201) throw new IllegalArgumentException("session limit is out of range");
        return inTransaction(workspace, () -> {
            String where = beforeCreatedAt == null ? "" : " and (created_at < ? or (created_at = ? and id < ?))";
            Object[] values = beforeCreatedAt == null ? new Object[]{scope()} : new Object[]{scope(),
                    timestamp(beforeCreatedAt), timestamp(beforeCreatedAt), beforeId};
            return jdbc.query("select id, workspace_id, deployment_id, version, created_at, updated_at from "
                    + "agent_session where workspace_id = ?" + where
                    + " order by created_at desc, id desc fetch first " + limit + " rows only", this::session, values);
        });
    }

    /** Idempotently associates a run with its deployment's logical session. */
    public void linkRun(WorkspaceId workspace, UUID sessionId, UUID runId) {
        inTransaction(workspace, () -> {
            Instant now = databaseNow();
            int changed = postgres()
                    ? jdbc.update("insert into agent_session_run(workspace_id, session_id, run_id, linked_at) "
                                    + "values (?, ?, ?, ?) on conflict (workspace_id, session_id, run_id) do nothing",
                            scope(), sessionId, runId, timestamp(now))
                    : insertLinkH2(sessionId, runId, now);
            if (changed == 0 && jdbc.queryForObject("select count(*) from agent_session_run where workspace_id = ? "
                    + "and session_id = ? and run_id = ?", Long.class, scope(), sessionId, runId) != 1) {
                throw failure(Code.RUN_NOT_IN_SESSION, "run belongs to another session");
            }
            return null;
        });
    }

    public SessionInboxMessage enqueue(WorkspaceId workspace, InboxMessageDraft draft) {
        Objects.requireNonNull(draft, "draft");
        if (!workspace.value().equals(draft.workspaceId())) throw failure(Code.RUN_PINS_MISMATCH, "workspace mismatch");
        return inTransaction(workspace, () -> enqueue(draft));
    }

    public Optional<SessionInboxMessage> find(WorkspaceId workspace, UUID id) {
        return inTransaction(workspace, () -> message(id, false));
    }

    public Optional<SessionInboxMessage> findByIdempotency(WorkspaceId workspace, UUID sessionId, String key) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(key, "key");
        return inTransaction(workspace, () -> byIdempotency(sessionId, key));
    }

    public List<SessionInboxMessage> list(WorkspaceId workspace, UUID sessionId, InboxMessageStatus status,
            Long beforeSequence, int limit) {
        if (limit < 1 || limit > 201) throw new IllegalArgumentException("inbox limit is out of range");
        return inTransaction(workspace, () -> {
            StringBuilder sql = new StringBuilder("select ").append(MESSAGE_COLUMNS)
                    .append(" from session_inbox where workspace_id = ? and session_id = ?");
            List<Object> values = new ArrayList<>(List.of(scope(), sessionId));
            if (status != null) { sql.append(" and status = ?"); values.add(status.name()); }
            if (beforeSequence != null) { sql.append(" and sequence < ?"); values.add(beforeSequence); }
            sql.append(" order by sequence desc fetch first ").append(limit).append(" rows only");
            return jdbc.query(sql.toString(), this::message, values.toArray());
        });
    }

    /** Claims pending (or expired-claim) entries in deterministic session order at a worker safe boundary. */
    public List<SessionInboxMessage> claim(RunLease lease, int limit, Duration claimFor) {
        Objects.requireNonNull(lease, "lease");
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("claim limit is out of range");
        if (claimFor == null || claimFor.isZero() || claimFor.isNegative() || claimFor.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("claim duration must be positive and at most one hour");
        }
        return inTransaction(lease.workspace(), () -> {
            String lock = postgres() ? " for update skip locked" : " for update";
            List<UUID> ids = jdbc.query("select id from session_inbox where workspace_id = ? and run_id = ? "
                            + "and (status = 'PENDING' or (status = 'CLAIMED' and claim_until <= " + now() + ")) "
                            + "order by sequence fetch first " + limit + " rows only" + lock,
                    (rs, row) -> rs.getObject(1, UUID.class), scope(), lease.runId());
            Instant until = databaseNow().plus(claimFor);
            List<SessionInboxMessage> claimed = new ArrayList<>();
            for (UUID id : ids) {
                int changed = jdbc.update("update session_inbox set status = 'CLAIMED', row_version = row_version + 1, "
                                + "claimed_by = ?, claim_fence = ?, claim_until = ?, terminal_at = null "
                                + "where workspace_id = ? and id = ?",
                        lease.owner(), lease.fence(), timestamp(until), scope(), id);
                if (changed != 1) throw failure(Code.STALE_VERSION, "inbox claim lost an optimistic race");
                claimed.add(message(id, false).orElseThrow());
            }
            return List.copyOf(claimed);
        });
    }

    public SessionInboxMessage consume(RunLease lease, UUID id, long expectedVersion) {
        return terminal(lease.workspace(), id, expectedVersion, InboxMessageStatus.CONSUMED, lease);
    }

    public SessionInboxMessage cancel(WorkspaceId workspace, UUID id, long expectedVersion) {
        return terminal(workspace, id, expectedVersion, InboxMessageStatus.CANCELLED, null);
    }

    public SessionInboxMessage supersede(WorkspaceId workspace, UUID id, long expectedVersion) {
        return terminal(workspace, id, expectedVersion, InboxMessageStatus.SUPERSEDED, null);
    }

    private SessionInboxMessage enqueue(InboxMessageDraft draft) {
        String json = payloads.write(draft.payload());
        String digest = Digests.prefixed(String.join("\n", draft.sessionId().toString(), draft.runId().toString(),
                draft.type().name(), draft.correlationId(), draft.idempotencyKey(), json));
        Optional<SessionInboxMessage> replay = byIdempotency(draft.sessionId(), draft.idempotencyKey());
        if (replay.isPresent()) {
            String stored = jdbc.queryForObject("select request_digest from session_inbox where workspace_id = ? and id = ?",
                    String.class, scope(), replay.orElseThrow().id());
            if (!digest.equals(stored)) throw failure(Code.IDEMPOTENCY_CONFLICT,
                    "inbox idempotency key was used for another message");
            return replay.orElseThrow();
        }
        AgentSession session = session(draft.sessionId(), true)
                .orElseThrow(() -> failure(Code.NOT_FOUND, "session does not exist"));
        if (!session.deploymentId().equals(draft.deploymentId()) || !linked(draft.sessionId(), draft.runId())) {
            throw failure(Code.RUN_NOT_IN_SESSION, "run is not linked to this session");
        }
        Cohort cohort = validatePins(draft);
        Instant now = databaseNow();
        long sequence = session.version() + 1;
        jdbc.update("update agent_session set version = ?, updated_at = ? where workspace_id = ? and id = ? and version = ?",
                sequence, timestamp(now), scope(), draft.sessionId(), session.version());
        String insert = "insert into session_inbox(workspace_id, id, session_id, run_id, release_id, deployment_id, "
                + "revision_id, cohort_id, cohort_transition_version, sequence, message_type, correlation_id, "
                + "idempotency_key, request_digest, payload_json, status, row_version, claimed_by, claim_fence, "
                + "claim_until, created_at, terminal_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, "
                + json() + ", 'PENDING', 0, null, null, null, ?, null)";
        jdbc.update(insert, scope(), draft.id(), draft.sessionId(), draft.runId(), draft.releaseId(),
                draft.deploymentId(), draft.revisionId(), draft.cohortId(), cohort.version(), sequence,
                draft.type().name(), draft.correlationId(), draft.idempotencyKey(), digest, json, timestamp(now));
        if (!postgres()) insertWork(draft, now);
        return message(draft.id(), false).orElseThrow();
    }

    private SessionInboxMessage terminal(WorkspaceId workspace, UUID id, long expectedVersion,
            InboxMessageStatus target, RunLease lease) {
        return inTransaction(workspace, () -> {
            SessionInboxMessage current = message(id, true)
                    .orElseThrow(() -> failure(Code.NOT_FOUND, "inbox message does not exist"));
            if (current.status().terminal()) throw failure(Code.ALREADY_TERMINAL, "inbox message is already terminal");
            if (current.version() != expectedVersion) throw failure(Code.STALE_VERSION, "inbox version changed");
            if (target == InboxMessageStatus.CONSUMED && (lease == null || !current.runId().equals(lease.runId())
                    || !current.claimedBy().orElse("").equals(lease.owner())
                    || current.claimFence().orElse(-1L) != lease.fence())) {
                throw failure(Code.STALE_CLAIM, "worker does not own the inbox claim");
            }
            Instant now = databaseNow();
            int changed = jdbc.update("update session_inbox set status = ?, row_version = row_version + 1, "
                            + "claimed_by = null, claim_fence = null, claim_until = null, terminal_at = ? "
                            + "where workspace_id = ? and id = ? and row_version = ?",
                    target.name(), timestamp(now), scope(), id, expectedVersion);
            if (changed != 1) throw failure(Code.STALE_VERSION, "inbox version changed");
            if (!postgres()) terminalWork(id, now);
            return message(id, false).orElseThrow();
        });
    }

    private Optional<AgentSession> session(UUID id, boolean lock) {
        return jdbc.query("select id, workspace_id, deployment_id, version, created_at, updated_at from agent_session "
                        + "where workspace_id = ? and id = ?" + (lock ? " for update" : ""), this::session,
                scope(), id).stream().findFirst();
    }

    private AgentSession session(ResultSet rs, int row) throws SQLException {
        return new AgentSession(rs.getObject("id", UUID.class), rs.getObject("workspace_id", UUID.class),
                rs.getObject("deployment_id", UUID.class), rs.getLong("version"), instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }

    private Optional<SessionInboxMessage> message(UUID id, boolean lock) {
        return jdbc.query("select " + MESSAGE_COLUMNS + " from session_inbox where workspace_id = ? and id = ?"
                        + (lock ? " for update" : ""), this::message, scope(), id).stream().findFirst();
    }

    private Optional<SessionInboxMessage> byIdempotency(UUID sessionId, String key) {
        return jdbc.query("select " + MESSAGE_COLUMNS + " from session_inbox where workspace_id = ? and session_id = ? "
                + "and idempotency_key = ?", this::message, scope(), sessionId, key).stream().findFirst();
    }

    private SessionInboxMessage message(ResultSet rs, int row) throws SQLException {
        return new SessionInboxMessage(rs.getObject("id", UUID.class), rs.getObject("workspace_id", UUID.class),
                rs.getObject("session_id", UUID.class), rs.getObject("run_id", UUID.class),
                rs.getObject("release_id", UUID.class), rs.getObject("deployment_id", UUID.class),
                rs.getObject("revision_id", UUID.class), rs.getObject("cohort_id", UUID.class),
                rs.getLong("cohort_transition_version"), rs.getLong("sequence"),
                InboxMessageType.valueOf(rs.getString("message_type")), rs.getString("correlation_id"),
                payloads.read(rs.getString("payload_json")), InboxMessageStatus.valueOf(rs.getString("status")),
                rs.getLong("row_version"), instant(rs, "created_at"), Optional.ofNullable(rs.getString("claimed_by")),
                Optional.ofNullable((Long) rs.getObject("claim_fence", Long.class)),
                Optional.ofNullable(instant(rs, "claim_until")), Optional.ofNullable(instant(rs, "terminal_at")));
    }

    private Cohort validatePins(InboxMessageDraft draft) {
        List<Cohort> rows = jdbc.query("select c.transition_version, c.state from agent_run r join runtime_cohort c on "
                        + "c.workspace_id = r.workspace_id and c.id = r.cohort_id where r.workspace_id = ? and r.id = ? "
                        + "and r.release_id = ? and r.deployment_id = ? and r.revision_id = ? and r.cohort_id = ?"
                        + (postgres() ? " for share of c" : " for update"),
                (rs, row) -> new Cohort(rs.getLong(1), rs.getString(2)), scope(), draft.runId(), draft.releaseId(),
                draft.deploymentId(), draft.revisionId(), draft.cohortId());
        if (rows.size() != 1) throw failure(Code.RUN_PINS_MISMATCH, "inbox pins do not match run");
        if (List.of("PENDING", "RETIRED", "REVOKED").contains(rows.getFirst().state())) {
            throw failure(Code.COHORT_NOT_ADMITTING, "cohort does not accept inbox delivery");
        }
        return rows.getFirst();
    }

    private boolean linked(UUID sessionId, UUID runId) {
        return jdbc.queryForObject("select count(*) from agent_session_run where workspace_id = ? and session_id = ? "
                + "and run_id = ?", Long.class, scope(), sessionId, runId) == 1;
    }

    private int insertLinkH2(UUID sessionId, UUID runId, Instant now) {
        if (linked(sessionId, runId)) return 0;
        return jdbc.update("insert into agent_session_run(workspace_id, session_id, run_id, linked_at) values (?, ?, ?, ?)",
                scope(), sessionId, runId, timestamp(now));
    }

    private void insertWork(InboxMessageDraft draft, Instant now) {
        jdbc.update("insert into cohort_work_item(workspace_id, id, cohort_id, run_id, category, subject_id, state, "
                        + "created_at, state_changed_at, terminal_at) values (?, ?, ?, ?, 'INBOX', ?, 'PENDING', ?, ?, null)",
                scope(), UUID.randomUUID(), draft.cohortId(), draft.runId(), draft.id(), timestamp(now), timestamp(now));
    }

    private void terminalWork(UUID id, Instant now) {
        jdbc.update("update cohort_work_item set state = 'TERMINAL', state_changed_at = ?, terminal_at = ? where "
                + "workspace_id = ? and category = 'INBOX' and subject_id = ? and state <> 'TERMINAL'",
                timestamp(now), timestamp(now), scope(), id);
    }

    private <T> T inTransaction(WorkspaceId workspace, Supplier<T> work) {
        return transactions.execute(status -> {
            try (var ignored = TenantContext.bind(workspace)) {
                try { return work.get(); }
                catch (SessionAuthorityException failure) { throw failure; }
                catch (DataAccessException failure) { throw translate(failure); }
            }
        });
    }

    private RuntimeException translate(DataAccessException failure) {
        String detail = String.valueOf(failure.getMostSpecificCause().getMessage());
        if (detail.contains("SESSION_RUN_MISMATCH")) return failure(Code.RUN_NOT_IN_SESSION, detail, failure);
        if (detail.contains("RUN_PINS_MISMATCH")) return failure(Code.RUN_PINS_MISMATCH, detail, failure);
        if (detail.contains("COHORT_NOT_ADMITTING")) return failure(Code.COHORT_NOT_ADMITTING, detail, failure);
        if (detail.contains("INBOX_ALREADY_TERMINAL")) return failure(Code.ALREADY_TERMINAL, detail, failure);
        if (detail.contains("INBOX_ALREADY_CLAIMED")) return failure(Code.ALREADY_CLAIMED, detail, failure);
        if (detail.contains("STALE_INBOX_CLAIM") || detail.contains("STALE_LEASE")) {
            return failure(Code.STALE_CLAIM, detail, failure);
        }
        if (detail.contains("STALE_INBOX_VERSION") || detail.contains("STALE_SESSION_VERSION")) {
            return failure(Code.STALE_VERSION, detail, failure);
        }
        return failure;
    }

    private UUID scope() { return TenantContext.requireWorkspace().value(); }
    private Instant databaseNow() { return jdbc.queryForObject("select " + now(), OffsetDateTime.class).toInstant(); }
    private String now() { return ClaimQuarantineSql.now(postgres()); }
    private String json() { return postgres() ? "cast(? as jsonb)" : "?"; }
    private boolean postgres() {
        Boolean value = postgres;
        if (value == null) { value = ClaimQuarantineSql.postgres(jdbc); postgres = value; }
        return value;
    }
    private static OffsetDateTime timestamp(Instant value) { return value.atOffset(ZoneOffset.UTC); }
    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class); return value == null ? null : value.toInstant();
    }
    private static SessionAuthorityException failure(Code code, String message) {
        return new SessionAuthorityException(code, message);
    }
    private static SessionAuthorityException failure(Code code, String message, Throwable cause) {
        return new SessionAuthorityException(code, message, cause);
    }
    private record Cohort(long version, String state) {}
}
