package com.dvarahq.agentworks.storage.sandbox;

import com.dvarahq.agentworks.domain.sandbox.SandboxOwner;
import com.dvarahq.agentworks.domain.sandbox.SandboxRef;
import com.dvarahq.agentworks.domain.sandbox.SandboxResource;
import com.dvarahq.agentworks.domain.sandbox.SandboxResource.CleanupLease;
import com.dvarahq.agentworks.domain.sandbox.SandboxResource.State;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import com.dvarahq.agentworks.sandbox.SandboxLifecycleAuthority;
import com.dvarahq.agentworks.storage.TenantContext;
import com.dvarahq.agentworks.storage.run.ClaimQuarantineSql;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** Spring JDBC sandbox state machine. External provider calls must occur between these short local transactions. */
public final class JdbcSandboxLifecycleAuthority implements SandboxLifecycleAuthority {
    private static final String COLUMNS = "workspace_id, id, organization_id, run_id, release_id, revision_id, "
            + "cohort_id, provider, state, expires_at, cleanup_after, cleanup_owner, cleanup_fence, cleanup_until, "
            + "cleanup_failures, last_failure_code, created_at, updated_at, terminal_at";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private volatile Boolean postgres;

    public JdbcSandboxLifecycleAuthority(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    @Override
    public SandboxResource reserve(WorkspaceId workspace, UUID sandboxId, SandboxOwner owner, String provider,
            Duration maximumRuntime, Duration provisioningTimeout) {
        Objects.requireNonNull(sandboxId, "sandboxId");
        Objects.requireNonNull(owner, "owner");
        validateDuration(maximumRuntime, "maximumRuntime");
        validateDuration(provisioningTimeout, "provisioningTimeout");
        if (!workspace.value().equals(owner.workspaceId())) {
            throw new IllegalArgumentException("sandbox owner does not match workspace scope");
        }
        return inTransaction(workspace, () -> {
            Instant now = databaseNow();
            UUID workItem = UUID.randomUUID();
            jdbc.update("insert into sandbox_resource(workspace_id, id, work_item_id, organization_id, run_id, "
                            + "release_id, revision_id, cohort_id, provider, state, expires_at, cleanup_after, "
                            + "cleanup_owner, cleanup_fence, cleanup_until, cleanup_failures, last_failure_code, "
                            + "created_at, activated_at, updated_at, terminal_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, "
                            + "'PROVISIONING', ?, ?, null, 0, null, 0, null, ?, null, ?, null)",
                    workspace.value(), sandboxId, workItem, owner.organizationId(), owner.runId(), owner.releaseId(),
                    owner.deploymentRevisionId(), owner.cohortId(), provider, timestamp(now.plus(maximumRuntime)),
                    timestamp(now.plus(provisioningTimeout)), timestamp(now), timestamp(now));
            if (!postgres()) insertWorkItem(workItem, sandboxId, owner, now);
            return required(sandboxId, false);
        });
    }

    @Override
    public SandboxResource activate(WorkspaceId workspace, UUID sandboxId) {
        return transition(workspace, sandboxId, () -> {
            Instant now = databaseNow();
            int changed = jdbc.update("update sandbox_resource set state = 'ACTIVE', activated_at = ?, "
                            + "cleanup_after = expires_at, updated_at = ? where workspace_id = ? and id = ? "
                            + "and state = 'PROVISIONING' and cleanup_after > ?", timestamp(now), timestamp(now),
                    scope(), sandboxId, timestamp(now));
            if (changed != 1) throw new IllegalStateException("sandbox is not awaiting activation");
            if (!postgres()) updateWorkItem(sandboxId, "ACTIVE", now);
        });
    }

    @Override
    public SandboxResource requestCleanup(WorkspaceId workspace, UUID sandboxId) {
        return transition(workspace, sandboxId, () -> {
            SandboxResource current = required(sandboxId, true);
            if (current.state() == State.TERMINAL || current.state() == State.CLEANUP_PENDING
                    || current.state() == State.CLEANING) return;
            Instant now = databaseNow();
            jdbc.update("update sandbox_resource set state = 'CLEANUP_PENDING', cleanup_after = ?, updated_at = ? "
                    + "where workspace_id = ? and id = ?", timestamp(now), timestamp(now), scope(), sandboxId);
        });
    }

    @Override
    public Optional<SandboxResource> claimCleanup(WorkspaceId workspace, String provider, String worker,
            Duration lease) {
        validateProvider(provider);
        validateWorker(worker);
        validateDuration(lease, "lease");
        return inTransaction(workspace, () -> {
            Instant now = databaseNow();
            List<SandboxResource> candidate = jdbc.query("select " + COLUMNS + " from sandbox_resource "
                            + "where workspace_id = ? and provider = ? and state <> 'TERMINAL' and cleanup_after <= ? "
                            + "and (state <> 'CLEANING' or cleanup_until <= ?) order by cleanup_after, created_at, id "
                            + "fetch first 1 rows only for update skip locked", this::map, scope(), provider,
                    timestamp(now), timestamp(now));
            if (candidate.isEmpty()) return Optional.empty();
            return Optional.of(claim(candidate.getFirst(), worker, lease, now));
        });
    }

    @Override
    public Optional<SandboxResource> claimCleanup(WorkspaceId workspace, UUID sandboxId, String provider,
            String worker, Duration lease) {
        Objects.requireNonNull(sandboxId, "sandboxId");
        validateProvider(provider);
        validateWorker(worker);
        validateDuration(lease, "lease");
        return inTransaction(workspace, () -> {
            Instant now = databaseNow();
            List<SandboxResource> candidate = jdbc.query("select " + COLUMNS + " from sandbox_resource "
                            + "where workspace_id = ? and id = ? and provider = ? and state <> 'TERMINAL' "
                            + "and cleanup_after <= ? and (state <> 'CLEANING' or cleanup_until <= ?) for update",
                    this::map, scope(), sandboxId, provider, timestamp(now), timestamp(now));
            if (candidate.isEmpty()) return Optional.empty();
            return Optional.of(claim(candidate.getFirst(), worker, lease, now));
        });
    }

    @Override
    public SandboxResource cleanupFailed(WorkspaceId workspace, UUID sandboxId, String worker, long fence,
            String failureCode, Duration retryDelay) {
        validateWorker(worker);
        validateFailureCode(failureCode);
        validateDuration(retryDelay, "retryDelay");
        return transition(workspace, sandboxId, () -> {
            Instant now = databaseNow();
            int changed = jdbc.update("update sandbox_resource set state = 'CLEANUP_PENDING', cleanup_after = ?, "
                            + "cleanup_owner = null, cleanup_until = null, cleanup_failures = cleanup_failures + 1, "
                            + "last_failure_code = ?, updated_at = ? where workspace_id = ? and id = ? "
                            + "and state = 'CLEANING' and cleanup_owner = ? and cleanup_fence = ? and cleanup_until > ?",
                    timestamp(now.plus(retryDelay)), failureCode, timestamp(now), scope(), sandboxId, worker, fence,
                    timestamp(now));
            if (changed != 1) throw new IllegalStateException("stale sandbox cleanup lease");
        });
    }

    @Override
    public SandboxResource cleanupSucceeded(WorkspaceId workspace, UUID sandboxId, String worker, long fence) {
        validateWorker(worker);
        return transition(workspace, sandboxId, () -> {
            Instant now = databaseNow();
            int changed = jdbc.update("update sandbox_resource set state = 'TERMINAL', cleanup_owner = null, "
                            + "cleanup_until = null, updated_at = ?, terminal_at = ? where workspace_id = ? and id = ? "
                            + "and state = 'CLEANING' and cleanup_owner = ? and cleanup_fence = ? and cleanup_until > ?",
                    timestamp(now), timestamp(now), scope(), sandboxId, worker, fence, timestamp(now));
            if (changed != 1) throw new IllegalStateException("stale sandbox cleanup lease");
            if (!postgres()) updateWorkItem(sandboxId, "TERMINAL", now);
        });
    }

    @Override
    public Optional<SandboxResource> find(WorkspaceId workspace, UUID sandboxId) {
        return inTransaction(workspace, () -> find(sandboxId, false));
    }

    @Override
    public List<SandboxResource> open(WorkspaceId workspace) {
        return inTransaction(workspace, () -> jdbc.query("select " + COLUMNS + " from sandbox_resource "
                + "where workspace_id = ? and state <> 'TERMINAL' order by created_at, id", this::map, scope()));
    }

    @Override
    public List<WorkspaceId> workspacesWithOpenResources(String provider) {
        validateProvider(provider);
        return transactions.execute(status -> jdbc.query("select distinct workspace_id from sandbox_resource "
                        + "where provider = ? and state <> 'TERMINAL' order by workspace_id",
                (rs, row) -> new WorkspaceId(uuid(rs, "workspace_id")), provider));
    }

    private SandboxResource claim(SandboxResource selected, String worker, Duration lease, Instant now) {
        long fence = selected.cleanupFence() + 1;
        int changed = jdbc.update("update sandbox_resource set state = 'CLEANING', cleanup_owner = ?, "
                        + "cleanup_fence = ?, cleanup_until = ?, updated_at = ? where workspace_id = ? and id = ?",
                worker, fence, timestamp(now.plus(lease)), timestamp(now), scope(), selected.reference().value());
        if (changed != 1) throw new IllegalStateException("sandbox cleanup claim was lost");
        return required(selected.reference().value(), false);
    }

    private SandboxResource transition(WorkspaceId workspace, UUID sandboxId, Runnable mutation) {
        return inTransaction(workspace, () -> {
            required(sandboxId, true);
            mutation.run();
            return required(sandboxId, false);
        });
    }

    private Optional<SandboxResource> find(UUID id, boolean lock) {
        List<SandboxResource> rows = jdbc.query("select " + COLUMNS + " from sandbox_resource where workspace_id = ? "
                + "and id = ?" + (lock ? " for update" : ""), this::map, scope(), id);
        return rows.stream().findFirst();
    }

    private SandboxResource required(UUID id, boolean lock) {
        return find(id, lock).orElseThrow(() -> new IllegalArgumentException("sandbox resource does not exist"));
    }

    private SandboxResource map(ResultSet rs, int row) throws SQLException {
        SandboxOwner owner = new SandboxOwner(uuid(rs, "organization_id"), uuid(rs, "workspace_id"),
                uuid(rs, "run_id"), uuid(rs, "release_id"), uuid(rs, "revision_id"), uuid(rs, "cohort_id"));
        String leaseOwner = rs.getString("cleanup_owner");
        Optional<CleanupLease> lease = leaseOwner == null ? Optional.empty() : Optional.of(new CleanupLease(leaseOwner,
                rs.getLong("cleanup_fence"), instant(rs, "cleanup_until")));
        return new SandboxResource(new SandboxRef(uuid(rs, "id"), owner), rs.getString("provider"),
                State.valueOf(rs.getString("state")), instant(rs, "expires_at"), instant(rs, "cleanup_after"),
                rs.getLong("cleanup_fence"), lease, rs.getInt("cleanup_failures"),
                Optional.ofNullable(rs.getString("last_failure_code")),
                instant(rs, "created_at"), instant(rs, "updated_at"), Optional.ofNullable(instant(rs, "terminal_at")));
    }

    private void insertWorkItem(UUID workItem, UUID sandboxId, SandboxOwner owner, Instant now) {
        jdbc.update("insert into cohort_work_item(workspace_id, id, cohort_id, run_id, category, subject_id, state, "
                        + "created_at, state_changed_at, terminal_at) values (?, ?, ?, ?, 'SANDBOX', ?, 'PENDING', "
                        + "?, ?, null)", owner.workspaceId(), workItem, owner.cohortId(), owner.runId(), sandboxId,
                timestamp(now), timestamp(now));
    }

    private void updateWorkItem(UUID sandboxId, String state, Instant now) {
        jdbc.update("update cohort_work_item set state = ?, state_changed_at = ?, terminal_at = ? "
                        + "where workspace_id = ? and category = 'SANDBOX' and subject_id = ? and state <> 'TERMINAL'",
                state, timestamp(now), "TERMINAL".equals(state) ? timestamp(now) : null, scope(), sandboxId);
    }

    private <T> T inTransaction(WorkspaceId workspace, Supplier<T> work) {
        Objects.requireNonNull(workspace, "workspace");
        return transactions.execute(status -> {
            try (var ignored = TenantContext.bind(workspace)) {
                return work.get();
            }
        });
    }

    private UUID scope() { return TenantContext.requireWorkspace().value(); }

    private Instant databaseNow() {
        return Objects.requireNonNull(jdbc.queryForObject("select " + ClaimQuarantineSql.now(postgres()),
                OffsetDateTime.class)).toInstant();
    }

    private boolean postgres() {
        Boolean value = postgres;
        if (value == null) postgres = value = ClaimQuarantineSql.postgres(jdbc);
        return value;
    }

    private static void validateDuration(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) throw new IllegalArgumentException(name + " must be positive");
    }

    private static void validateWorker(String worker) {
        if (worker == null || worker.isBlank() || worker.length() > 128) {
            throw new IllegalArgumentException("cleanup worker is invalid");
        }
    }

    private static void validateProvider(String provider) {
        if (provider == null || !provider.matches("[a-z][a-z0-9-]{0,31}")) {
            throw new IllegalArgumentException("sandbox provider identity is invalid");
        }
    }

    private static void validateFailureCode(String code) {
        if (code == null || !code.matches("[A-Z][A-Z0-9_]{0,63}")) {
            throw new IllegalArgumentException("cleanup failure code is invalid");
        }
    }

    private static OffsetDateTime timestamp(Instant value) { return value.atOffset(ZoneOffset.UTC); }
    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
    private static UUID uuid(ResultSet rs, String column) throws SQLException { return rs.getObject(column, UUID.class); }
}
