package com.dvarahq.agentworks.storage.run;

import com.dvarahq.agentworks.domain.run.ClaimQuarantine;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import com.dvarahq.agentworks.storage.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The Control Plane's view of the claim quarantine (issue #181): it lists quarantines and releases them. It runs on the
 * Control Plane connection, which holds the only grants to update or delete quarantine rows (V11_1); the Run Plane only
 * records them. The administrative API, view, and audit over it are #79.
 */
public final class JdbcClaimQuarantineRepository {
    /** The most quarantines one page lists. */
    public static final int MAX_PAGE = 500;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private volatile Boolean postgres;

    public JdbcClaimQuarantineRepository(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    /**
     * Where a page continues: after the quarantine of {@code runId} for {@code compatibility}. Pages are ordered by
     * that immutable key (#185), so a quarantine recorded again, or one that lapses, never shifts a later page.
     */
    public record After(UUID runId, String compatibility) {
        public After {
            Objects.requireNonNull(runId, "runId");
            ClaimQuarantine.compatibility(compatibility);
        }

        /** The position after {@code last}, the final quarantine of the previous page. */
        public static After of(ClaimQuarantine last) {
            return new After(last.runId(), last.compatibility());
        }

        /** The position after {@code last}, the final entry of the previous page. */
        public static After of(Listed last) {
            return of(last.quarantine());
        }
    }

    /**
     * A listed quarantine with the state of its run (#185), so an operator can tell whether the quarantine is what
     * keeps the run waiting.
     *
     * @param runLifecycle the run's lifecycle, such as {@code QUEUED}, {@code PAUSED}, or {@code BLOCKED}
     * @param cohortState the state of the run's cohort; only a {@code ROUTABLE} or {@code DRAINING} cohort's runs are
     *     claimed at all
     * @param heldBack true when the quarantine is what holds the run back for the nodes of its identity: it is in
     *     force, the run is otherwise claimable (queued, or active with a lapsed lease), and its cohort admits claims.
     *     False for a paused or blocked run, which waits for a resume or unblock, and for a run whose cohort admits no
     *     claims. Whether nodes of another compatibility identity, which ignore the quarantine, are running and can
     *     claim the run (by executor type and manifest version) is not known here, so a held-back run is not proof that
     *     no node will run it (#79 must re-check under the run lock before any cancellation).
     */
    public record Listed(ClaimQuarantine quarantine, String runLifecycle, String cohortState, boolean heldBack) {
        public Listed {
            Objects.requireNonNull(quarantine, "quarantine");
            Objects.requireNonNull(runLifecycle, "runLifecycle");
        }
    }

    /**
     * One page of the quarantines in force (open, and not lapsed) on runs of {@code workspace} that have not ended and
     * that no worker holds under a live lease, each labelled with the compatibility identity that recorded it and with
     * the run's state ({@link Listed}). Paused and blocked runs are listed, with {@code heldBack} false (#185). A run
     * that a worker holds under a live lease (typically a node of another identity that can run it) is not listed,
     * whichever identity quarantined it.
     *
     * @param after where the page starts; {@code null} for the first page
     */
    public List<Listed> active(WorkspaceId workspace, int limit, After after) {
        return list(workspace, false, limit, after);
    }

    /**
     * One page of the quarantines in {@code workspace}: those {@link #active active}, or, with
     * {@code includeInactive}, every recorded one, including lapsed ones, those of runs held under a live lease, and
     * those closed because their run ended. Ordered by run and compatibility identity.
     *
     * @param after where the page starts; {@code null} for the first page
     */
    public List<Listed> list(WorkspaceId workspace, boolean includeInactive, int limit, After after) {
        Objects.requireNonNull(workspace, "workspace");
        if (limit < 1 || limit > MAX_PAGE) throw new IllegalArgumentException("a page lists 1 to " + MAX_PAGE);
        return transactions.execute(status -> {
            try (var ignored = TenantContext.bind(workspace)) {
                String now = now();
                List<Object> arguments = new ArrayList<>(List.of(workspace.value()));
                StringBuilder sql = new StringBuilder("select " + qualified() + ", r.lifecycle as run_lifecycle, "
                        + "c.state as cohort_state, case when " + ClaimQuarantineSql.holdsBack("q", "r", "c", now)
                        + " then 1 else 0 end as held_back "
                        + "from agent_run_claim_quarantine q "
                        + "join agent_run r on r.workspace_id = q.workspace_id and r.id = q.run_id "
                        + "left join runtime_cohort c on c.workspace_id = r.workspace_id and c.id = r.cohort_id "
                        + "where q.workspace_id = ?");
                if (!includeInactive) {
                    sql.append(" and ").append(ClaimQuarantineSql.inForce("q", now))
                            .append(" and ").append(ClaimQuarantineSql.notEnded("r")).append(" and not ")
                            .append(ClaimQuarantineSql.liveLease("r", now));
                }
                if (after != null) {
                    sql.append(" and (q.run_id, q.compatibility) > (?, ?)");
                    arguments.addAll(List.of(after.runId(), after.compatibility()));
                }
                sql.append(" order by q.run_id, q.compatibility fetch first ? rows only");
                arguments.add(limit);
                return jdbc.query(sql.toString(), (rs, n) -> new Listed(JdbcDurableRunStore.QUARANTINES.mapRow(rs, n),
                        rs.getString("run_lifecycle"), rs.getString("cohort_state"), rs.getInt("held_back") == 1),
                        arguments.toArray());
            }
        });
    }

    private static String qualified() {
        return java.util.Arrays.stream(JdbcDurableRunStore.QUARANTINE.split(","))
                .map(column -> "q." + column.strip()).collect(java.util.stream.Collectors.joining(", "));
    }

    /**
     * Releases every quarantine of a run, for every compatibility identity, so workers select it again at once; the
     * number released, zero when it had none. A release only widens which nodes may claim the run and never changes the
     * run, so it needs no re-check of the run's state and takes no run lock (the Control Plane has no update privilege
     * on runs, which PostgreSQL requires for a row lock). A cancellation offered because a run is held back (#79) must
     * instead re-check, under the run's lock, that it is still held back.
     */
    public int release(WorkspaceId workspace, UUID runId) {
        Objects.requireNonNull(workspace, "workspace");
        Objects.requireNonNull(runId, "runId");
        Integer released = transactions.execute(status -> {
            try (var ignored = TenantContext.bind(workspace)) {
                return jdbc.update("delete from agent_run_claim_quarantine where workspace_id = ? and run_id = ?",
                        workspace.value(), runId);
            }
        });
        return released == null ? 0 : released;
    }

    private String now() {
        Boolean value = postgres;
        if (value == null) {
            value = ClaimQuarantineSql.postgres(jdbc);
            postgres = value;
        }
        return ClaimQuarantineSql.now(value);
    }
}
