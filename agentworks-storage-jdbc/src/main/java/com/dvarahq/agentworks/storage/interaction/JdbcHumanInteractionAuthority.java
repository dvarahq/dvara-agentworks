package com.dvarahq.agentworks.storage.interaction;

import com.dvarahq.agentworks.domain.security.InvocationIdentity;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import com.dvarahq.agentworks.interaction.HumanInteractionAssignment;
import com.dvarahq.agentworks.interaction.HumanInteractionAuthorityException;
import com.dvarahq.agentworks.interaction.HumanInteractionDraft;
import com.dvarahq.agentworks.interaction.HumanInteractionKind;
import com.dvarahq.agentworks.interaction.HumanInteractionPrompt;
import com.dvarahq.agentworks.interaction.HumanInteractionRecord;
import com.dvarahq.agentworks.interaction.HumanInteractionRequest;
import com.dvarahq.agentworks.interaction.HumanInteractionResponse;
import com.dvarahq.agentworks.interaction.HumanInteractionStatus;
import com.dvarahq.agentworks.storage.TenantContext;
import com.dvarahq.agentworks.storage.run.ClaimQuarantineSql;
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
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static com.dvarahq.agentworks.interaction.HumanInteractionAuthorityException.Code;

/**
 * Spring JDBC authority for durable human interactions. Every operation binds one workspace and one local JDBC
 * transaction; PostgreSQL time, row locks, and the V29_1 cohort trigger decide the winning outcome.
 */
public final class JdbcHumanInteractionAuthority {
    private static final String COLUMNS = "id, workspace_id, run_id, release_id, deployment_id, revision_id, "
            + "cohort_id, cohort_transition_version, correlation_id, idempotency_key, request_digest, kind, "
            + "prompt_json, assignment_level, assigned_principal_id, status, response_json, resolved_by, created_at, "
            + "deadline, resolved_at";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final HumanInteractionJsonCodec codec = new HumanInteractionJsonCodec();
    private volatile Boolean postgres;

    public JdbcHumanInteractionAuthority(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    /** Creates once by run/idempotency key; matching retries return the original database-authored result. */
    public HumanInteractionRecord create(WorkspaceId workspace, HumanInteractionDraft draft) {
        Objects.requireNonNull(workspace, "workspace");
        Objects.requireNonNull(draft, "draft");
        if (!workspace.value().equals(draft.workspaceId())) {
            throw failure(Code.RUN_PINS_MISMATCH, "interaction workspace does not match transaction workspace");
        }
        return inTransaction(workspace, () -> create(draft));
    }

    public Optional<HumanInteractionRecord> find(WorkspaceId workspace, UUID interactionId) {
        Objects.requireNonNull(interactionId, "interactionId");
        return inTransaction(workspace, () -> row(interactionId, false));
    }

    /** Finds the immutable interaction occupying one run-scoped idempotency key. */
    public Optional<HumanInteractionRecord> findByIdempotency(WorkspaceId workspace, UUID runId, String key) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(key, "key");
        return inTransaction(workspace, () -> byIdempotency(runId, key, false));
    }

    /** Interactions newest first, optionally filtered by status and run, using a stable created-at/id keyset. */
    public List<HumanInteractionRecord> list(WorkspaceId workspace, HumanInteractionStatus status, UUID runId,
            Instant beforeCreatedAt, UUID beforeId, int limit) {
        Objects.requireNonNull(workspace, "workspace");
        if ((beforeCreatedAt == null) != (beforeId == null)) {
            throw new IllegalArgumentException("interaction list position needs both createdAt and id");
        }
        if (limit < 1 || limit > 201) throw new IllegalArgumentException("interaction list limit is out of range");
        return inTransaction(workspace, () -> {
            StringBuilder sql = new StringBuilder("select ").append(COLUMNS)
                    .append(" from human_interaction where workspace_id = ?");
            List<Object> values = new ArrayList<>();
            values.add(scope());
            if (status != null) {
                sql.append(" and status = ?");
                values.add(status.name());
            }
            if (runId != null) {
                sql.append(" and run_id = ?");
                values.add(runId);
            }
            if (beforeCreatedAt != null) {
                sql.append(" and (created_at < ? or (created_at = ? and id < ?))");
                values.add(timestamp(beforeCreatedAt));
                values.add(timestamp(beforeCreatedAt));
                values.add(beforeId);
            }
            sql.append(" order by created_at desc, id desc fetch first ").append(limit).append(" rows only");
            return jdbc.query(sql.toString(), this::record, values.toArray());
        });
    }

    /** Resolves an open, unexpired request for an exactly assigned responder. */
    public HumanInteractionRecord respond(WorkspaceId workspace, UUID interactionId, InvocationIdentity responder,
            HumanInteractionResponse response, String actor) {
        Objects.requireNonNull(responder, "responder");
        Objects.requireNonNull(response, "response");
        return transition(workspace, interactionId, actor, HumanInteractionStatus.RESPONDED, response, responder);
    }

    public HumanInteractionRecord expire(WorkspaceId workspace, UUID interactionId, String actor) {
        return transition(workspace, interactionId, actor, HumanInteractionStatus.EXPIRED, null, null);
    }

    public HumanInteractionRecord cancel(WorkspaceId workspace, UUID interactionId, String actor) {
        return transition(workspace, interactionId, actor, HumanInteractionStatus.CANCELLED, null, null);
    }

    public HumanInteractionRecord revoke(WorkspaceId workspace, UUID interactionId, String actor) {
        return transition(workspace, interactionId, actor, HumanInteractionStatus.REVOKED, null, null);
    }

    private HumanInteractionRecord create(HumanInteractionDraft draft) {
        String digest = digest(draft);
        Optional<HumanInteractionRecord> replay = byIdempotency(draft.runId(), draft.idempotencyKey(), false);
        if (replay.isPresent()) return replay(replay.orElseThrow(), draft, digest);

        CohortAdmission cohort = cohortAndValidatePins(draft);
        requireAdmitting(cohort.state());
        Instant databaseNow = databaseNow();
        HumanInteractionRequest request = draft.at(databaseNow);
        String insert = "insert into human_interaction(workspace_id, id, run_id, release_id, deployment_id, "
                + "revision_id, cohort_id, cohort_transition_version, correlation_id, idempotency_key, request_digest, "
                + "kind, prompt_json, assignment_level, assigned_principal_id, status, response_json, resolved_by, "
                + "created_at, deadline, resolved_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, " + json()
                + ", ?, ?, 'OPEN', null, null, ?, ?, null)";
        Object[] values = {draft.workspaceId(), draft.id(), draft.runId(), draft.releaseId(), draft.deploymentId(),
                draft.revisionId(), draft.cohortId(), cohort.transitionVersion(), draft.correlationId(), draft.idempotencyKey(),
                digest, draft.prompt().kind().name(), codec.prompt(draft.prompt()), draft.assignment().level().name(),
                draft.assignment().principalId().orElse(null), timestamp(request.createdAt()),
                timestamp(request.deadline())};
        int inserted;
        if (postgres()) {
            inserted = jdbc.update(insert + " on conflict (workspace_id, run_id, idempotency_key) do nothing", values);
        } else {
            inserted = jdbc.update(insert, values);
        }
        if (inserted == 0) {
            return replay(byIdempotency(draft.runId(), draft.idempotencyKey(), true).orElseThrow(), draft, digest);
        }
        if (!postgres()) {
            jdbc.update("insert into cohort_work_item(workspace_id, id, cohort_id, run_id, category, subject_id, state, "
                            + "created_at, state_changed_at, terminal_at) values (?, ?, ?, ?, 'INTERACTION', ?, "
                            + "'PENDING', ?, ?, null)", draft.workspaceId(), UUID.randomUUID(), draft.cohortId(),
                    draft.runId(), draft.id(), timestamp(request.createdAt()), timestamp(request.createdAt()));
        }
        return row(draft.id(), false).orElseThrow();
    }

    private HumanInteractionRecord transition(WorkspaceId workspace, UUID id, String actor,
            HumanInteractionStatus target, HumanInteractionResponse response, InvocationIdentity responder) {
        Objects.requireNonNull(workspace, "workspace");
        Objects.requireNonNull(id, "interactionId");
        if (actor == null || actor.isBlank() || actor.length() > 255) {
            throw new IllegalArgumentException("interaction outcome actor is required");
        }
        return inTransaction(workspace, () -> {
            HumanInteractionRecord observed = row(id, false)
                    .orElseThrow(() -> failure(Code.NOT_FOUND, "interaction does not exist"));
            String cohortState = lockCohort(observed.request().cohortId());
            HumanInteractionRecord current = row(id, true)
                    .orElseThrow(() -> failure(Code.NOT_FOUND, "interaction does not exist"));
            if (current.status().terminal()) {
                throw failure(Code.ALREADY_TERMINAL, "interaction already has outcome " + current.status());
            }
            if (target == HumanInteractionStatus.RESPONDED) {
                requireAdmitting(cohortState);
                if (!current.request().assignment().permits(responder)) {
                    throw failure(Code.UNAUTHORIZED_RESPONDER, "responder is not assigned to this interaction");
                }
                if (!current.request().accepts(response)) {
                    throw failure(Code.INVALID_RESPONSE, "response does not match the interaction request");
                }
            } else if (target == HumanInteractionStatus.REVOKED
                    && current.request().prompt().kind() != HumanInteractionKind.AUTHORIZATION) {
                throw failure(Code.INVALID_TRANSITION, "only an authorization interaction can be revoked");
            }
            String condition = switch (target) {
                case RESPONDED -> " and deadline > " + now();
                case EXPIRED -> " and deadline <= " + now();
                default -> "";
            };
            int changed = jdbc.update("update human_interaction set status = ?, response_json = " + json()
                            + ", resolved_by = ?, resolved_at = " + now()
                            + " where workspace_id = ? and id = ? and status = 'OPEN'" + condition,
                    target.name(), response == null ? null : codec.response(response), actor, workspace.value(), id);
            if (changed != 1) {
                Code code = target == HumanInteractionStatus.EXPIRED ? Code.DEADLINE_NOT_REACHED : Code.DEADLINE_PASSED;
                throw failure(code, "database deadline rejects interaction outcome " + target);
            }
            if (!postgres()) {
                int terminalized = jdbc.update("update cohort_work_item set state = 'TERMINAL', "
                                + "state_changed_at = " + now() + ", terminal_at = " + now()
                                + " where workspace_id = ? and category = 'INTERACTION' and subject_id = ? "
                                + "and state <> 'TERMINAL'", workspace.value(), id);
                if (terminalized != 1) throw new IllegalStateException("interaction ledger row is missing");
            }
            return row(id, false).orElseThrow();
        });
    }

    private CohortAdmission cohortAndValidatePins(HumanInteractionDraft draft) {
        List<CohortAdmission> cohorts = jdbc.query("select c.transition_version, c.state from runtime_cohort c "
                        + "join agent_run r on "
                        + "r.workspace_id = c.workspace_id and r.cohort_id = c.id where c.workspace_id = ? and c.id = ? "
                        + "and r.id = ? and r.release_id = ? and r.deployment_id = ? and r.revision_id = ?"
                        + (postgres() ? " for share of c" : " for update"),
                (rs, row) -> new CohortAdmission(rs.getLong(1), rs.getString(2)), draft.workspaceId(),
                draft.cohortId(), draft.runId(), draft.releaseId(),
                draft.deploymentId(), draft.revisionId());
        if (cohorts.size() != 1) throw failure(Code.RUN_PINS_MISMATCH, "interaction pins do not match its run");
        return cohorts.getFirst();
    }

    private String lockCohort(UUID cohortId) {
        List<String> rows = jdbc.query("select state from runtime_cohort where workspace_id = ? and id = ?"
                        + (postgres() ? " for share" : " for update"),
                (rs, row) -> rs.getString(1), scope(), cohortId);
        if (rows.size() != 1) throw failure(Code.RUN_PINS_MISMATCH, "interaction cohort does not exist");
        return rows.getFirst();
    }

    private static void requireAdmitting(String state) {
        if ("PENDING".equals(state) || "RETIRED".equals(state) || "REVOKED".equals(state)) {
            throw failure(Code.COHORT_NOT_ADMITTING, "cohort does not admit interaction work: " + state);
        }
    }

    private Optional<HumanInteractionRecord> byIdempotency(UUID runId, String key, boolean lock) {
        String suffix = lock ? " for update" : "";
        return jdbc.query("select " + COLUMNS + " from human_interaction where workspace_id = ? and run_id = ? "
                        + "and idempotency_key = ?" + suffix, this::record, scope(), runId, key).stream().findFirst();
    }

    private Optional<HumanInteractionRecord> row(UUID id, boolean lock) {
        return jdbc.query("select " + COLUMNS + " from human_interaction where workspace_id = ? and id = ?"
                        + (lock ? " for update" : ""), this::record, scope(), id).stream().findFirst();
    }

    private HumanInteractionRecord replay(HumanInteractionRecord existing, HumanInteractionDraft draft,
            String digest) {
        String stored = jdbc.queryForObject("select request_digest from human_interaction where workspace_id = ? "
                + "and id = ?", String.class, scope(), existing.request().id());
        boolean legacyDefaultPolicy = draft.prompt() instanceof HumanInteractionPrompt.Authorization authorization
                && HumanInteractionPrompt.Authorization.DEFAULT_POLICY_ID.equals(authorization.policyId())
                && digest(draft, false).equals(stored);
        if (!digest.equals(stored) && !legacyDefaultPolicy) {
            throw failure(Code.IDEMPOTENCY_CONFLICT, "idempotency key was already used for another interaction");
        }
        return existing;
    }

    private HumanInteractionRecord record(ResultSet rs, int row) throws SQLException {
        HumanInteractionKind kind = HumanInteractionKind.valueOf(rs.getString("kind"));
        HumanInteractionPrompt prompt = codec.prompt(kind, rs.getString("prompt_json"));
        UUID principal = rs.getObject("assigned_principal_id", UUID.class);
        HumanInteractionAssignment assignment = new HumanInteractionAssignment(
                rs.getObject("workspace_id", UUID.class),
                HumanInteractionAssignment.Level.valueOf(rs.getString("assignment_level")),
                Optional.ofNullable(principal));
        HumanInteractionRequest request = new HumanInteractionRequest(rs.getObject("id", UUID.class),
                rs.getObject("workspace_id", UUID.class), rs.getObject("run_id", UUID.class),
                rs.getObject("release_id", UUID.class), rs.getObject("deployment_id", UUID.class),
                rs.getObject("revision_id", UUID.class), rs.getObject("cohort_id", UUID.class),
                rs.getString("correlation_id"), rs.getString("idempotency_key"), prompt, assignment,
                instant(rs, "created_at"), instant(rs, "deadline"));
        String response = rs.getString("response_json");
        return new HumanInteractionRecord(request, rs.getLong("cohort_transition_version"),
                HumanInteractionStatus.valueOf(rs.getString("status")),
                response == null ? Optional.empty() : Optional.of(codec.response(kind, response)),
                Optional.ofNullable(rs.getString("resolved_by")), Optional.ofNullable(instant(rs, "resolved_at")));
    }

    private String digest(HumanInteractionDraft draft) {
        return digest(draft, true);
    }

    private String digest(HumanInteractionDraft draft, boolean includeAuthorizationPolicy) {
        String canonical = String.join("\n", draft.id().toString(), draft.workspaceId().toString(),
                draft.runId().toString(), draft.releaseId().toString(), draft.deploymentId().toString(),
                draft.revisionId().toString(), draft.cohortId().toString(), draft.correlationId(),
                draft.idempotencyKey(), draft.prompt().kind().name(),
                codec.prompt(draft.prompt(), includeAuthorizationPolicy),
                draft.assignment().level().name(), draft.assignment().principalId().map(UUID::toString).orElse(""),
                draft.timeout().toString());
        try {
            return "sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private <T> T inTransaction(WorkspaceId workspace, Supplier<T> work) {
        return transactions.execute(status -> {
            try (var ignored = TenantContext.bind(workspace)) {
                try {
                    return work.get();
                } catch (HumanInteractionAuthorityException failure) {
                    throw failure;
                } catch (DataAccessException failure) {
                    String detail = String.valueOf(failure.getMostSpecificCause().getMessage());
                    if (detail.contains("RUN_PINS_MISMATCH")) throw failure(Code.RUN_PINS_MISMATCH, detail, failure);
                    if (detail.contains("COHORT_NOT_ADMITTING")) throw failure(Code.COHORT_NOT_ADMITTING, detail, failure);
                    if (detail.contains("INTERACTION_DEADLINE_PASSED")) throw failure(Code.DEADLINE_PASSED, detail, failure);
                    if (detail.contains("INTERACTION_DEADLINE_NOT_REACHED")) {
                        throw failure(Code.DEADLINE_NOT_REACHED, detail, failure);
                    }
                    if (detail.contains("INTERACTION_ALREADY_TERMINAL")) {
                        throw failure(Code.ALREADY_TERMINAL, detail, failure);
                    }
                    throw failure;
                }
            }
        });
    }

    private UUID scope() {
        return TenantContext.requireWorkspace().value();
    }

    private Instant databaseNow() {
        return jdbc.queryForObject("select " + now(), OffsetDateTime.class).toInstant();
    }

    private String now() {
        return ClaimQuarantineSql.now(postgres());
    }

    private String json() {
        return postgres() ? "cast(? as jsonb)" : "?";
    }

    private boolean postgres() {
        Boolean value = postgres;
        if (value == null) {
            value = ClaimQuarantineSql.postgres(jdbc);
            postgres = value;
        }
        return value;
    }

    private static OffsetDateTime timestamp(Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static HumanInteractionAuthorityException failure(Code code, String message) {
        return new HumanInteractionAuthorityException(code, message);
    }

    private static HumanInteractionAuthorityException failure(Code code, String message, Throwable cause) {
        return new HumanInteractionAuthorityException(code, message, cause);
    }

    private record CohortAdmission(long transitionVersion, String state) {}
}
