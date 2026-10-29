package com.dvarahq.agentworks.domain.run;

import com.dvarahq.agentworks.domain.tenant.WorkspaceId;

import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Persistence port of the durable run engine (ADR-003). PostgreSQL is the reference implementation; the runtime
 * domain depends only on this contract, so no JDBC type leaks into the engine.
 *
 * <p>Each call runs its work in one atomic transaction (joining a surrounding transaction on the same connection
 * pool), so events, the summary, checkpoints, effects, and ledger rows commit together or not at all.
 */
public interface DurableRunStore {
    /** Runs {@code work} in one transaction bound to {@code workspace}; every read and write is scoped to it. */
    <T> T inTransaction(WorkspaceId workspace, Function<RunTransaction, T> work);

    /**
     * Selects and locks the oldest claimable run in {@code scope} without waiting on runs other workers hold: a
     * queued run, or an active run whose lease has expired in database time, whose cohort still executes work.
     * {@code work} then runs in the same transaction, bound to that run's workspace. Empty when nothing is
     * claimable. A scope must name the claiming node's compatibility identity: a store honors the quarantines recorded
     * for it by reading {@link ClaimScope#requiredCompatibility()}, which refuses a scope that names none (#185,
     * #201). The shipped stores extend {@link CompatibleClaimStore}, which resolves that identity before the store's
     * claim runs and hands it over as a non-null argument (#206). That base class is necessarily opt-in for a public
     * persistence SPI: a third-party implementation that implements this interface directly must call
     * {@code requiredCompatibility()} and apply the quarantine predicate itself. The engine always claims with a scope
     * that names its own identity (#210).
     */
    <T> Optional<T> claimNext(ClaimScope scope, BiFunction<RunRecord, RunTransaction, T> work);

    /**
     * As {@link #claimNext(ClaimScope, BiFunction)}, and reports the selected run's id to {@code selected} as soon as
     * it is selected, before the run is read and decoded, so a caller can attribute a failure that happens while
     * reading, decoding, or claiming that run to it (#179). Stores should override this; the default reports only the
     * runs that reach {@code work}.
     */
    default <T> Optional<T> claimNext(ClaimScope scope, java.util.function.Consumer<java.util.UUID> selected,
            BiFunction<RunRecord, RunTransaction, T> work) {
        scope.requiredCompatibility();
        return claimNext(scope, (run, tx) -> {
            selected.accept(run.id());
            return work.apply(run, tx);
        });
    }

    /*
     * Claim quarantine (issue #181). A store's claimNext never selects a run while a quarantine recorded for the
     * scope's compatibility identity excludes it (ClaimQuarantine#excludes, in database time). A store closes a run's
     * quarantines when the run ends. Listing and releasing quarantines are Control Plane operations, outside this
     * Run Plane port (JdbcClaimQuarantineRepository). The defaults refuse, for stores that keep no quarantine.
     */

    /**
     * Whether this store keeps the claim quarantine. A worker refuses a store that does not, at construction, since one
     * poison run would block its queue forever.
     */
    default boolean keepsClaimQuarantine() {
        return false;
    }

    /**
     * Quarantines {@code runId} for the request's compatibility identity in one transaction, fenced by the run's row
     * lock: only while the run is still claimable (queued, or active with an expired lease) and, when the request says
     * what the refused claim observed, still in that state, so neither a run another worker claimed meanwhile nor a later
     * state of it is ever quarantined. A quarantine already in force is returned unchanged, so racing workers never
     * extend it. Otherwise the run's earlier quarantines for that identity are counted, and
     * {@link ClaimQuarantine.Request#notBefore} decides its end in database time. Empty when the run is no longer
     * claimable, has moved on, or does not exist.
     */
    default Optional<ClaimQuarantine> quarantine(java.util.UUID runId, ClaimQuarantine.Request request) {
        throw new UnsupportedOperationException("this store keeps no claim quarantine");
    }

}
