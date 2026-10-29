package com.dvarahq.agentworks.storage.operation;

import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import com.dvarahq.agentworks.operation.DurableOperationAuthorityException;
import com.dvarahq.agentworks.operation.DurableOperationDraft;
import com.dvarahq.agentworks.operation.DurableOperationHandle;
import com.dvarahq.agentworks.operation.DurableOperationRecord;
import com.dvarahq.agentworks.operation.DurableOperationResult;
import com.dvarahq.agentworks.operation.DurableOperationStatus;
import com.dvarahq.agentworks.storage.TenantContext;
import com.dvarahq.agentworks.storage.lifecycle.Digests;
import com.dvarahq.agentworks.storage.run.ClaimQuarantineSql;
import com.dvarahq.agentworks.storage.session.DurablePayloadCodec;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static com.dvarahq.agentworks.operation.DurableOperationAuthorityException.Code;

/** Spring JDBC authority for one-winner durable-operation start, progress, completion, and cancellation. */
public final class JdbcDurableOperationAuthority {
    public record ReconciliationCandidate(WorkspaceId workspace, UUID operationId) {}
    private static final String COLUMNS = "id, workspace_id, session_id, run_id, release_id, deployment_id, "
            + "revision_id, cohort_id, cohort_transition_version, correlation_id, idempotency_key, provider_id, "
            + "operation_type, input_json, callback_token_version, external_id, status, progress_percent, result_code, result_json, "
            + "created_at, deadline, updated_at, resolved_by, terminal_at";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final DurablePayloadCodec payloads = new DurablePayloadCodec();
    private volatile Boolean postgres;

    public JdbcDurableOperationAuthority(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    /** Creates a STARTING intent once by run/idempotency key after the run atomically entered operation blocking. */
    public DurableOperationRecord create(WorkspaceId workspace, DurableOperationDraft draft) {
        Objects.requireNonNull(draft, "draft");
        if (!workspace.value().equals(draft.workspaceId())) throw failure(Code.RUN_PINS_MISMATCH, "workspace mismatch");
        return inTransaction(workspace, () -> create(draft));
    }

    public Optional<DurableOperationRecord> find(WorkspaceId workspace, UUID id) {
        return inTransaction(workspace, () -> row(id, false));
    }

    /**
     * Resolves a retry before the caller attempts to block the run again. The request digest deliberately excludes
     * the newly proposed operation id and callback token, so a byte-for-byte retry finds the original intent while
     * reuse of the key for different work is refused.
     */
    public Optional<DurableOperationRecord> replay(WorkspaceId workspace, DurableOperationDraft draft) {
        Objects.requireNonNull(draft, "draft");
        if (!workspace.value().equals(draft.workspaceId())) throw failure(Code.RUN_PINS_MISMATCH, "workspace mismatch");
        return inTransaction(workspace, () -> {
            Optional<DurableOperationRecord> replay = byIdempotency(draft.runId(), draft.idempotencyKey());
            if (replay.isEmpty()) return Optional.empty();
            String stored = jdbc.queryForObject("select request_digest from durable_operation where workspace_id = ? and id = ?",
                    String.class, scope(), replay.orElseThrow().id());
            if (!requestDigest(draft).equals(stored)) throw failure(Code.IDEMPOTENCY_CONFLICT,
                    "operation idempotency key was used for another request");
            return replay;
        });
    }

    public List<DurableOperationRecord> list(WorkspaceId workspace, UUID sessionId, DurableOperationStatus status,
            Instant beforeCreatedAt, UUID beforeId, int limit) {
        if ((beforeCreatedAt == null) != (beforeId == null)) throw new IllegalArgumentException("operation cursor is incomplete");
        if (limit < 1 || limit > 201) throw new IllegalArgumentException("operation limit is out of range");
        return inTransaction(workspace, () -> {
            StringBuilder sql = new StringBuilder("select ").append(COLUMNS)
                    .append(" from durable_operation where workspace_id = ?");
            List<Object> values = new ArrayList<>(List.of(scope()));
            if (sessionId != null) { sql.append(" and session_id = ?"); values.add(sessionId); }
            if (status != null) { sql.append(" and status = ?"); values.add(status.name()); }
            if (beforeCreatedAt != null) {
                sql.append(" and (created_at < ? or (created_at = ? and id < ?))");
                values.add(timestamp(beforeCreatedAt)); values.add(timestamp(beforeCreatedAt)); values.add(beforeId);
            }
            sql.append(" order by created_at desc, id desc fetch first ").append(limit).append(" rows only");
            return jdbc.query(sql.toString(), this::record, values.toArray());
        });
    }

    /** STARTING -> RUNNING after an idempotent provider start returns its opaque handle. */
    public DurableOperationRecord running(WorkspaceId workspace, UUID id, DurableOperationHandle handle) {
        Objects.requireNonNull(handle, "handle");
        return inTransaction(workspace, () -> {
            DurableOperationRecord current = required(id, true);
            if (current.status().terminal()) throw failure(Code.ALREADY_TERMINAL, "operation already completed");
            if (!current.provider().equals(handle.provider())) {
                throw failure(Code.INVALID_TRANSITION, "provider returned a handle for another provider");
            }
            if (current.status() == DurableOperationStatus.RUNNING) {
                if (!current.handle().orElseThrow().equals(handle)) {
                    throw failure(Code.INVALID_TRANSITION, "provider start changed the operation handle");
                }
                return current;
            }
            Instant now = databaseNow();
            jdbc.update("update durable_operation set external_id = ?, status = 'RUNNING', updated_at = ? "
                            + "where workspace_id = ? and id = ? and status = 'STARTING'",
                    handle.externalId(), timestamp(now), scope(), id);
            if (!postgres()) activeWork(id, now);
            return required(id, false);
        });
    }

    public DurableOperationRecord progress(WorkspaceId workspace, UUID id, int percent) {
        if (percent < 0 || percent > 100) throw new IllegalArgumentException("progress must be 0 to 100");
        return inTransaction(workspace, () -> {
            DurableOperationRecord current = required(id, true);
            if (current.status().terminal()) throw failure(Code.ALREADY_TERMINAL, "operation already completed");
            if (current.status() != DurableOperationStatus.RUNNING) {
                throw failure(Code.INVALID_TRANSITION, "operation has not started");
            }
            if (current.progressPercent() != null && percent < current.progressPercent()) {
                throw failure(Code.INVALID_TRANSITION, "operation progress cannot move backward");
            }
            Instant now = databaseNow();
            jdbc.update("update durable_operation set progress_percent = ?, updated_at = ? where workspace_id = ? and id = ?",
                    percent, timestamp(now), scope(), id);
            return required(id, false);
        });
    }

    public DurableOperationRecord complete(WorkspaceId workspace, UUID id, DurableOperationResult result, String actor) {
        return terminal(workspace, id, result, null, actor);
    }

    public DurableOperationRecord completeFromCallback(WorkspaceId workspace, UUID id, String callbackToken,
            DurableOperationResult result, String actor) {
        if (callbackToken == null || callbackToken.isBlank()) {
            throw failure(Code.CALLBACK_UNAUTHORIZED, "callback token is required");
        }
        return terminal(workspace, id, result, callbackToken, actor);
    }

    public DurableOperationRecord cancel(WorkspaceId workspace, UUID id, String code, String actor) {
        return terminal(workspace, id, new DurableOperationResult(DurableOperationStatus.CANCELLED,
                code == null ? "CANCELLED" : code, Map.of()), null, actor);
    }

    /** Open operations eligible for restart reconciliation, oldest update first. */
    public List<DurableOperationRecord> recoverable(WorkspaceId workspace, int limit) {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("recovery limit is out of range");
        return inTransaction(workspace, () -> jdbc.query("select " + COLUMNS + " from durable_operation where "
                        + "workspace_id = ? and status in ('STARTING','RUNNING') order by updated_at, id fetch first "
                        + limit + " rows only", this::record, scope()));
    }

    /** System-level startup scan; each returned candidate is reconciled through its tenant-scoped authority. */
    public List<ReconciliationCandidate> reconciliationCandidates(int limit) {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("recovery limit is out of range");
        return jdbc.query("select workspace_id, id from durable_operation where status in ('STARTING','RUNNING') "
                        + "order by updated_at, id fetch first " + limit + " rows only",
                (rs, row) -> new ReconciliationCandidate(
                        new WorkspaceId(rs.getObject("workspace_id", UUID.class)),
                        rs.getObject("id", UUID.class)));
    }

    /** Constant-shape SHA-256 digest; only this digest is persisted. */
    public static String tokenDigest(String token) {
        Objects.requireNonNull(token, "token");
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private DurableOperationRecord create(DurableOperationDraft draft) {
        String input = payloads.write(draft.input());
        String digest = requestDigest(draft, input);
        Optional<DurableOperationRecord> replay = byIdempotency(draft.runId(), draft.idempotencyKey());
        if (replay.isPresent()) {
            String stored = jdbc.queryForObject("select request_digest from durable_operation where workspace_id = ? and id = ?",
                    String.class, scope(), replay.orElseThrow().id());
            if (!digest.equals(stored)) throw failure(Code.IDEMPOTENCY_CONFLICT,
                    "operation idempotency key was used for another request");
            return replay.orElseThrow();
        }
        Cohort cohort = validate(draft);
        Instant now = databaseNow();
        Instant deadline = now.plus(draft.timeout());
        jdbc.update("insert into durable_operation(workspace_id, id, session_id, run_id, release_id, deployment_id, "
                        + "revision_id, cohort_id, cohort_transition_version, correlation_id, idempotency_key, "
                        + "request_digest, provider_id, operation_type, input_json, callback_token_digest, callback_token_version, external_id, "
                        + "status, progress_percent, result_code, result_json, resolved_by, created_at, deadline, updated_at, terminal_at) "
                        + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, " + json()
                        + ", ?, ?, null, 'STARTING', null, null, null, null, ?, ?, ?, null)",
                scope(), draft.id(), draft.sessionId(), draft.runId(), draft.releaseId(), draft.deploymentId(),
                draft.revisionId(), draft.cohortId(), cohort.version(), draft.correlationId(), draft.idempotencyKey(),
                digest, draft.provider(), draft.operationType(), input, draft.callbackTokenDigest(),
                draft.callbackTokenVersion(), timestamp(now),
                timestamp(deadline), timestamp(now));
        if (!postgres()) insertWork(draft, now);
        return required(draft.id(), false);
    }

    private String requestDigest(DurableOperationDraft draft) {
        return requestDigest(draft, payloads.write(draft.input()));
    }

    private static String requestDigest(DurableOperationDraft draft, String input) {
        return Digests.prefixed(String.join("\n", draft.sessionId().toString(), draft.runId().toString(),
                draft.provider(), draft.operationType(), draft.correlationId(), draft.idempotencyKey(), input,
                draft.timeout().toString()));
    }

    private DurableOperationRecord terminal(WorkspaceId workspace, UUID id, DurableOperationResult result,
            String callbackToken, String actor) {
        Objects.requireNonNull(result, "result");
        if (actor == null || actor.isBlank() || actor.length() > 255) {
            throw new IllegalArgumentException("operation outcome actor is required");
        }
        return inTransaction(workspace, () -> {
            DurableOperationRecord current = required(id, true);
            if (current.status().terminal()) throw failure(Code.ALREADY_TERMINAL, "operation already has an outcome");
            if (callbackToken != null) verifyToken(id, callbackToken);
            if (current.status() == DurableOperationStatus.STARTING && result.status() == DurableOperationStatus.SUCCEEDED) {
                throw failure(Code.INVALID_TRANSITION, "an operation cannot succeed before provider start is recorded");
            }
            Instant now = databaseNow();
            jdbc.update("update durable_operation set status = ?, progress_percent = ?, result_code = ?, result_json = "
                            + json() + ", resolved_by = ?, updated_at = ?, terminal_at = ? where workspace_id = ? and id = ? "
                            + "and status in ('STARTING','RUNNING')",
                    result.status().name(), result.status() == DurableOperationStatus.SUCCEEDED ? 100
                            : current.progressPercent(), result.code(), payloads.write(result.output()), actor, timestamp(now),
                    timestamp(now), scope(), id);
            if (!postgres()) terminalWork(id, now);
            return required(id, false);
        });
    }

    private void verifyToken(UUID id, String token) {
        String stored = jdbc.queryForObject("select callback_token_digest from durable_operation where workspace_id = ? "
                + "and id = ?", String.class, scope(), id);
        if (stored == null || !MessageDigest.isEqual(stored.getBytes(StandardCharsets.US_ASCII),
                tokenDigest(token).getBytes(StandardCharsets.US_ASCII))) {
            throw failure(Code.CALLBACK_UNAUTHORIZED, "callback token is invalid");
        }
    }

    private Cohort validate(DurableOperationDraft draft) {
        List<Cohort> rows = jdbc.query("select c.transition_version, c.state from agent_session_run sr "
                        + "join agent_run r on r.workspace_id = sr.workspace_id and r.id = sr.run_id "
                        + "join runtime_cohort c on c.workspace_id = r.workspace_id and c.id = r.cohort_id "
                        + "where sr.workspace_id = ? and sr.session_id = ? and sr.run_id = ? and r.release_id = ? "
                        + "and r.deployment_id = ? and r.revision_id = ? and r.cohort_id = ? and r.lifecycle = 'BLOCKED' "
                        + "and r.block_reason = 'DURABLE_OPERATION'" + (postgres() ? " for share of c" : " for update"),
                (rs, row) -> new Cohort(rs.getLong(1), rs.getString(2)), scope(), draft.sessionId(), draft.runId(),
                draft.releaseId(), draft.deploymentId(), draft.revisionId(), draft.cohortId());
        if (rows.size() != 1) throw failure(Code.RUN_PINS_MISMATCH,
                "operation pins, session association, or run block do not match");
        if (List.of("PENDING", "RETIRED", "REVOKED", "SUSPENDED").contains(rows.getFirst().state())) {
            throw failure(Code.COHORT_NOT_ADMITTING, "cohort does not admit a new operation");
        }
        return rows.getFirst();
    }

    private Optional<DurableOperationRecord> byIdempotency(UUID runId, String key) {
        return jdbc.query("select " + COLUMNS + " from durable_operation where workspace_id = ? and run_id = ? "
                + "and idempotency_key = ?", this::record, scope(), runId, key).stream().findFirst();
    }

    private DurableOperationRecord required(UUID id, boolean lock) {
        return row(id, lock).orElseThrow(() -> failure(Code.NOT_FOUND, "operation does not exist"));
    }

    private Optional<DurableOperationRecord> row(UUID id, boolean lock) {
        return jdbc.query("select " + COLUMNS + " from durable_operation where workspace_id = ? and id = ?"
                + (lock ? " for update" : ""), this::record, scope(), id).stream().findFirst();
    }

    private DurableOperationRecord record(ResultSet rs, int row) throws SQLException {
        String external = rs.getString("external_id");
        String resultJson = rs.getString("result_json");
        DurableOperationStatus status = DurableOperationStatus.valueOf(rs.getString("status"));
        return new DurableOperationRecord(rs.getObject("id", UUID.class), rs.getObject("workspace_id", UUID.class),
                rs.getObject("session_id", UUID.class), rs.getObject("run_id", UUID.class),
                rs.getObject("release_id", UUID.class), rs.getObject("deployment_id", UUID.class),
                rs.getObject("revision_id", UUID.class), rs.getObject("cohort_id", UUID.class),
                rs.getLong("cohort_transition_version"), rs.getString("correlation_id"),
                rs.getString("idempotency_key"), rs.getString("provider_id"), rs.getString("operation_type"),
                payloads.read(rs.getString("input_json")), rs.getInt("callback_token_version"), external == null ? Optional.empty()
                        : Optional.of(new DurableOperationHandle(rs.getString("provider_id"), external)),
                status, (Integer) rs.getObject("progress_percent", Integer.class), resultJson == null ? Optional.empty()
                        : Optional.of(new DurableOperationResult(status, rs.getString("result_code"), payloads.read(resultJson))),
                instant(rs, "created_at"), instant(rs, "deadline"), instant(rs, "updated_at"),
                Optional.ofNullable(rs.getString("resolved_by")), Optional.ofNullable(instant(rs, "terminal_at")));
    }

    private void insertWork(DurableOperationDraft draft, Instant now) {
        jdbc.update("insert into cohort_work_item(workspace_id, id, cohort_id, run_id, category, subject_id, state, "
                        + "created_at, state_changed_at, terminal_at) values (?, ?, ?, ?, 'OPERATION', ?, 'PENDING', ?, ?, null)",
                scope(), UUID.randomUUID(), draft.cohortId(), draft.runId(), draft.id(), timestamp(now), timestamp(now));
    }
    private void activeWork(UUID id, Instant now) {
        jdbc.update("update cohort_work_item set state = 'ACTIVE', state_changed_at = ? where workspace_id = ? "
                + "and category = 'OPERATION' and subject_id = ? and state <> 'TERMINAL'", timestamp(now), scope(), id);
    }
    private void terminalWork(UUID id, Instant now) {
        jdbc.update("update cohort_work_item set state = 'TERMINAL', state_changed_at = ?, terminal_at = ? where "
                + "workspace_id = ? and category = 'OPERATION' and subject_id = ? and state <> 'TERMINAL'",
                timestamp(now), timestamp(now), scope(), id);
    }

    private <T> T inTransaction(WorkspaceId workspace, Supplier<T> work) {
        return transactions.execute(status -> {
            try (var ignored = TenantContext.bind(workspace)) {
                try { return work.get(); }
                catch (DurableOperationAuthorityException failure) { throw failure; }
                catch (DataAccessException failure) { throw translate(failure); }
            }
        });
    }
    private RuntimeException translate(DataAccessException failure) {
        String detail = String.valueOf(failure.getMostSpecificCause().getMessage());
        if (detail.contains("RUN_PINS_MISMATCH") || detail.contains("OPERATION_NOT_ADMISSIBLE")) {
            return failure(Code.RUN_PINS_MISMATCH, detail, failure);
        }
        if (detail.contains("COHORT_NOT_ADMITTING")) return failure(Code.COHORT_NOT_ADMITTING, detail, failure);
        if (detail.contains("OPERATION_ALREADY_TERMINAL")) return failure(Code.ALREADY_TERMINAL, detail, failure);
        if (detail.contains("INVALID_OPERATION_TRANSITION")) return failure(Code.INVALID_TRANSITION, detail, failure);
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
    private static DurableOperationAuthorityException failure(Code code, String message) {
        return new DurableOperationAuthorityException(code, message);
    }
    private static DurableOperationAuthorityException failure(Code code, String message, Throwable cause) {
        return new DurableOperationAuthorityException(code, message, cause);
    }
    private record Cohort(long version, String state) {}
}
