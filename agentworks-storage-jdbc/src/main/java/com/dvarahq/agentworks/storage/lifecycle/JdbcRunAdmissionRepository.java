package com.dvarahq.agentworks.storage.lifecycle;

import com.dvarahq.agentworks.domain.lifecycle.state.LifecycleRows.RunRow;
import com.dvarahq.agentworks.domain.run.RunLimits;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.time.Instant;

import static com.dvarahq.agentworks.storage.lifecycle.JdbcLifecycleRepository.first;
import static com.dvarahq.agentworks.storage.lifecycle.JdbcLifecycleRepository.instant;
import static com.dvarahq.agentworks.storage.lifecycle.JdbcLifecycleRepository.timestamp;
import static com.dvarahq.agentworks.storage.lifecycle.JdbcLifecycleRepository.uuid;
import static com.dvarahq.agentworks.storage.lifecycle.JdbcLifecycleRepository.workspace;

/**
 * Run-plane persistence for run admission. Rows carry immutable pins; on PostgreSQL the cohort-fence trigger
 * re-validates each root against the current routing generation and each continuation against its parent.
 */
public final class JdbcRunAdmissionRepository {
    private static final String RUN = "id, workspace_id, definition_id, version_id, release_id, deployment_id, "
            + "environment_id, revision_id, cohort_id, admission, routing_generation_id, routing_target_ordinal, "
            + "parent_run_id, root_run_id, continuation_kind, lifecycle, idempotency_key_digest, "
            + "idempotency_digest_version, request_fingerprint, created_at";
    private static final RowMapper<RunRow> RUNS = (rs, n) -> new RunRow(uuid(rs, "id"), uuid(rs, "workspace_id"),
            uuid(rs, "definition_id"), uuid(rs, "version_id"), uuid(rs, "release_id"), uuid(rs, "deployment_id"),
            uuid(rs, "environment_id"), uuid(rs, "revision_id"), uuid(rs, "cohort_id"), rs.getString("admission"),
            uuid(rs, "routing_generation_id"), (Integer) rs.getObject("routing_target_ordinal", Integer.class),
            uuid(rs, "parent_run_id"), uuid(rs, "root_run_id"), rs.getString("continuation_kind"),
            rs.getString("lifecycle"), rs.getString("idempotency_key_digest"),
            (Integer) rs.getObject("idempotency_digest_version", Integer.class), rs.getString("request_fingerprint"),
            instant(rs, "created_at"));

    private final JdbcTemplate jdbc;
    private final Dialect dialect;

    public JdbcRunAdmissionRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.dialect = new Dialect(jdbc);
    }

    /**
     * Facts a run is created with beside its lifecycle pins: its trace identity, the release-manifest version it was
     * admitted under, its limits, and the digest of its {@code RUN_CREATED} event, which the caller appends in the
     * same transaction, and the executor type its pinned release manifest names (issue #34), which the claim query
     * filters on and which never changes.
     */
    public record RunCreation(String traceId, int manifestVersion, RunLimits limits, String createdEventDigest,
            String executorType, String placement, boolean requiresSandbox, String admissionPolicyVersion) {
        public RunCreation {
            Objects.requireNonNull(traceId, "traceId");
            Objects.requireNonNull(executorType, "executorType");
            Objects.requireNonNull(limits, "limits");
            Objects.requireNonNull(createdEventDigest, "createdEventDigest");
            Objects.requireNonNull(placement, "placement");
            Objects.requireNonNull(admissionPolicyVersion, "admissionPolicyVersion");
        }
    }

    /** Counts captured under the serialized workspace/deployment admission lock. */
    public record AdmissionSnapshot(long workspaceOutstanding, long deploymentOutstanding, long workspaceQueued,
            long deploymentQueued, long sandboxOutstanding, long placementOutstanding, long rootsInWindow,
            Long reservedTokens, java.math.BigDecimal reservedCostUsd) {}

    /** Privacy-safe decision evidence; requestDigest is over the canonical request, never the request itself. */
    public record AdmissionDecision(UUID id, UUID deploymentId, UUID runId, String admissionClass, String outcome,
            String code, String placement, String policyVersion, String requestDigest, AdmissionSnapshot snapshot,
            Instant createdAt) {}

    public void insert(RunRow run, RunCreation creation) {
        RunLimits limits = creation.limits();
        try {
            jdbc.update("insert into agent_run(" + RUN + ", trace_id, manifest_version, limit_total_tokens, "
                            + "limit_model_calls, limit_tool_calls, limit_runtime_millis, limit_cost_usd, event_sequence, "
                            + "last_event_digest, updated_at, executor_type, admission_placement, "
                            + "admission_requires_sandbox, admission_policy_version) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, "
                            + "?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?)",
                    run.id(), workspace(), run.definitionId(), run.versionId(), run.releaseId(), run.deploymentId(),
                    run.environmentId(), run.revisionId(), run.cohortId(), run.admission(), run.routingGenerationId(),
                    run.routingTargetOrdinal(), run.parentRunId(), run.rootRunId(), run.continuationKind(),
                    run.lifecycle(), run.idempotencyKeyDigest(), run.idempotencyDigestVersion(), run.requestFingerprint(),
                    timestamp(run.createdAt()), creation.traceId(), creation.manifestVersion(), limits.maxTotalTokens(),
                    limits.maxModelCalls(), limits.maxToolCalls(), limits.maxRuntimeMillis(), limits.maxCostUsd(),
                    creation.createdEventDigest(), timestamp(run.createdAt()), creation.executorType(),
                    creation.placement(), creation.requiresSandbox(), creation.admissionPolicyVersion());
        } catch (DuplicateKeyException concurrentRetry) {
            // Only the idempotency key is unique besides the random identity; retrying replays the winner.
            throw new ConcurrentInsertException(concurrentRetry);
        }
    }

    public Optional<RunRow> find(UUID id) {
        return first(jdbc.query("select " + RUN + " from agent_run where workspace_id = ? and id = ?", RUNS, workspace(), id));
    }

    /** Finds a root under any candidate digest of one idempotency key. */
    public Optional<RunRow> findByIdempotencyKey(UUID deploymentId, List<String> keyDigests) {
        String placeholders = String.join(", ", keyDigests.stream().map(digest -> "?").toList());
        List<Object> arguments = new ArrayList<>(List.of(workspace(), deploymentId));
        arguments.addAll(keyDigests);
        return first(jdbc.query("select " + RUN + " from agent_run where workspace_id = ? and deployment_id = ? "
                + "and idempotency_key_digest in (" + placeholders + ")", RUNS, arguments.toArray()));
    }

    /** True when a keyed run on this deployment was digested with a key version outside {@code retained}. */
    public boolean holdsUnretainedVersion(UUID deploymentId, Set<Integer> retained) {
        String placeholders = String.join(", ", retained.stream().map(version -> "?").toList());
        List<Object> arguments = new ArrayList<>(List.of(workspace(), deploymentId));
        arguments.addAll(retained);
        return !jdbc.queryForList("select 1 from agent_run where workspace_id = ? and deployment_id = ? "
                + "and idempotency_digest_version not in (" + placeholders + ") fetch first 1 rows only", Integer.class,
                arguments.toArray()).isEmpty();
    }

    /**
     * Serializes admissions that share an idempotency key on PostgreSQL; call before looking the key up.
     * {@code lockScope} must be independent of the HMAC key version.
     */
    public void lockKey(UUID deploymentId, String lockScope) {
        dialect.lockKey(workspace() + "/run.admit/" + deploymentId + "/" + lockScope);
    }

    /** Serializes capacity decisions in stable workspace-then-deployment order. */
    public void lockCapacity(UUID deploymentId) {
        dialect.lockKey(workspace() + "/run.capacity/workspace");
        dialect.lockKey(workspace() + "/run.capacity/deployment/" + deploymentId);
    }

    public AdmissionSnapshot admissionSnapshot(UUID deploymentId, String placement, Instant rateCutoff) {
        Long[] counts = jdbc.queryForObject("select "
                        + "count(case when lifecycle not in ('SUCCEEDED','FAILED','CANCELLED') then 1 end), "
                        + "count(case when deployment_id = ? and lifecycle not in ('SUCCEEDED','FAILED','CANCELLED') then 1 end), "
                        + "count(case when lifecycle = 'QUEUED' then 1 end), "
                        + "count(case when deployment_id = ? and lifecycle = 'QUEUED' then 1 end), "
                        + "count(case when admission_requires_sandbox = true and lifecycle not in ('SUCCEEDED','FAILED','CANCELLED') then 1 end), "
                        + "count(case when admission_placement = ? and lifecycle not in ('SUCCEEDED','FAILED','CANCELLED') then 1 end), "
                        + "coalesce(sum(case when lifecycle not in ('SUCCEEDED','FAILED','CANCELLED') then limit_total_tokens else 0 end),0), "
                        + "count(case when lifecycle not in ('SUCCEEDED','FAILED','CANCELLED') and limit_total_tokens is null then 1 end) "
                        + "from agent_run where workspace_id = ?", (rs, row) -> new Long[]{rs.getLong(1), rs.getLong(2),
                                rs.getLong(3), rs.getLong(4), rs.getLong(5), rs.getLong(6), rs.getLong(7), rs.getLong(8)},
                deploymentId, deploymentId, placement, workspace());
        long roots = jdbc.queryForObject("select count(*) from run_admission_decision where workspace_id = ? "
                        + "and admission_class = 'ROOT' and outcome = 'ACCEPTED' and created_at >= ?", Long.class,
                workspace(), timestamp(rateCutoff));
        Object[] cost = jdbc.queryForObject("select coalesce(sum(limit_cost_usd),0), "
                        + "count(case when limit_cost_usd is null then 1 end) from agent_run "
                        + "where workspace_id = ? and lifecycle not in ('SUCCEEDED','FAILED','CANCELLED')",
                (rs, row) -> new Object[]{rs.getBigDecimal(1), rs.getLong(2)}, workspace());
        return new AdmissionSnapshot(counts[0], counts[1], counts[2], counts[3], counts[4], counts[5], roots,
                counts[7] > 0 ? Long.MAX_VALUE : counts[6], (Long) cost[1] > 0
                        ? java.math.BigDecimal.valueOf(Long.MAX_VALUE) : (java.math.BigDecimal) cost[0]);
    }

    public void insertDecision(AdmissionDecision decision) {
        AdmissionSnapshot snapshot = decision.snapshot();
        jdbc.update("insert into run_admission_decision(workspace_id, id, deployment_id, run_id, admission_class, "
                        + "outcome, event_type, code, placement, policy_version, request_digest, workspace_outstanding, "
                        + "deployment_outstanding, workspace_queued, deployment_queued, sandbox_outstanding, "
                        + "placement_outstanding, created_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                workspace(), decision.id(), decision.deploymentId(), decision.runId(), decision.admissionClass(),
                decision.outcome(), "ACCEPTED".equals(decision.outcome()) ? "ADMISSION_ACCEPTED" : "ADMISSION_REJECTED",
                decision.code(), decision.placement(), decision.policyVersion(),
                decision.requestDigest(), snapshot.workspaceOutstanding(), snapshot.deploymentOutstanding(),
                snapshot.workspaceQueued(), snapshot.deploymentQueued(), snapshot.sandboxOutstanding(),
                snapshot.placementOutstanding(), timestamp(decision.createdAt()));
    }

    public List<RunRow> listByCohort(UUID cohortId) {
        return jdbc.query("select " + RUN + " from agent_run where workspace_id = ? and cohort_id = ? order by created_at, id",
                RUNS, workspace(), cohortId);
    }
}
