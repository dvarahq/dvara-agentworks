package com.dvarahq.agentworks.storage.run;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The one definition of the SQL predicates that decide whether a claim quarantine applies (#181, #185), shared by the
 * claim query and the quarantine write ({@link JdbcDurableRunStore}), the Control Plane listing
 * ({@link JdbcClaimQuarantineRepository}), and the quiescence report
 * ({@code JdbcLifecycleRepository.quiescenceBlockers}), so they cannot drift apart. Every predicate judges time by
 * {@link #now(boolean)}: the actual current time, never the start time of a long transaction.
 */
public final class ClaimQuarantineSql {
    private ClaimQuarantineSql() {
    }

    /** The actual current time on PostgreSQL ({@code clock_timestamp()}); H2 has only {@code current_timestamp}. */
    public static String now(boolean postgres) {
        return postgres ? "clock_timestamp()" : "current_timestamp";
    }

    /** Whether {@code jdbc} connects to PostgreSQL. */
    public static boolean postgres(JdbcTemplate jdbc) {
        return Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>) connection ->
                "PostgreSQL".equals(connection.getMetaData().getDatabaseProductName())));
    }

    /**
     * Whether the run aliased {@code run} can be claimed, ignoring quarantines: it is queued, or active with a lapsed
     * lease. A run another worker holds under a live lease is not claimable, so no quarantine keeps it waiting.
     */
    public static String claimable(String run, String now) {
        return "(" + run + ".lifecycle = 'QUEUED' or (" + run + ".lifecycle = 'ACTIVE' and " + run + ".lease_until <= "
                + now + "))";
    }

    /** Whether the run aliased {@code run} has not ended. */
    public static String notEnded(String run) {
        return "(" + run + ".lifecycle not in ('SUCCEEDED', 'FAILED', 'CANCELLED'))";
    }

    /** Whether the cohort aliased {@code cohort} admits claims of its runs: it is routable or draining. */
    public static String cohortAdmits(String cohort) {
        return "(" + cohort + ".state in ('ROUTABLE', 'DRAINING'))";
    }

    /** Whether a worker holds the run aliased {@code run} under a lease that has not lapsed. */
    public static String liveLease(String run, String now) {
        return "(" + run + ".lifecycle = 'ACTIVE' and " + run + ".lease_until > " + now + ")";
    }

    /** Whether the quarantine row aliased {@code quarantine} is in force: open, and not lapsed. */
    public static String inForce(String quarantine, String now) {
        return "(" + quarantine + ".closed_at is null and (" + quarantine + ".not_before is null or " + quarantine
                + ".not_before > " + now + "))";
    }

    /**
     * Whether the quarantine row aliased {@code quarantine} is what keeps the run aliased {@code run}, of the cohort
     * aliased {@code cohort}, from being claimed by the nodes of the quarantine's identity: the quarantine is in force,
     * the run is otherwise claimable, and its cohort admits claims. The Control Plane listing ({@code Listed.heldBack})
     * and the quiescence report ({@code QUARANTINED_RUN}) both use it, so they always agree (#189). A run another
     * worker holds under a live lease, one that is blocked, paused, or ended, and one whose cohort admits no claims are
     * not held back by the quarantine, whichever identity recorded it. Nodes of other compatibility identities ignore
     * the quarantine and may claim the run; whether any such node is running, with the run's executor type and
     * manifest version, is not recorded, so the reports built on this predicate say so explicitly.
     */
    public static String holdsBack(String quarantine, String run, String cohort, String now) {
        return "(" + inForce(quarantine, now) + " and " + claimable(run, now) + " and " + cohortAdmits(cohort) + ")";
    }
}
