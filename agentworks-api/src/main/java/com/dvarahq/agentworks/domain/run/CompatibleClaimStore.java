package com.dvarahq.agentworks.domain.run;

import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * A {@link DurableRunStore} whose claims always honor the claim quarantine (#181, #206): both claim methods are final
 * and resolve the claiming node's compatibility identity with {@link ClaimScope#requiredCompatibility()}, refusing a
 * scope that names none, before the store's own claim runs. The store implements only
 * {@link #claimCompatible}, which receives that identity as a non-null argument, so no store built on this class can
 * read "no identity" as "no quarantine", whichever claim method its caller uses. The reference stores extend it.
 */
public abstract class CompatibleClaimStore implements DurableRunStore {

    @Override
    public final <T> Optional<T> claimNext(ClaimScope scope, BiFunction<RunRecord, RunTransaction, T> work) {
        return claimNext(scope, selected -> { }, work);
    }

    @Override
    public final <T> Optional<T> claimNext(ClaimScope scope, Consumer<UUID> selected,
            BiFunction<RunRecord, RunTransaction, T> work) {
        java.util.Objects.requireNonNull(scope, "scope");
        return claimCompatible(scope, scope.requiredCompatibility(), java.util.Objects.requireNonNull(selected,
                "selected"), java.util.Objects.requireNonNull(work, "work"));
    }

    /**
     * Claims as {@link DurableRunStore#claimNext(ClaimScope, Consumer, BiFunction)} does, skipping the runs quarantined
     * for {@code compatibility}.
     *
     * @param compatibility the claiming node's compatibility identity, never {@code null}
     */
    protected abstract <T> Optional<T> claimCompatible(ClaimScope scope, String compatibility, Consumer<UUID> selected,
            BiFunction<RunRecord, RunTransaction, T> work);
}
