package com.dvarahq.agentworks.sdk.lifecycle;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable operational transition; terminal cohorts cannot become routable. */
public record CohortTransition(UUID cohortId, RuntimeCohortRef.State from, RuntimeCohortRef.State to,
                               long version, Instant at, String actor) {
    public CohortTransition {
        Objects.requireNonNull(cohortId); Objects.requireNonNull(from); Objects.requireNonNull(to);
        Objects.requireNonNull(at);
        if (version <= 0) throw new IllegalArgumentException("version");
        if (actor == null || actor.isBlank()) throw new IllegalArgumentException("actor");
        if (terminal(from) && to == RuntimeCohortRef.State.ROUTABLE) {
            throw new IllegalArgumentException("terminal cohort cannot become routable");
        }
    }
    private static boolean terminal(RuntimeCohortRef.State state) {
        return state == RuntimeCohortRef.State.DRAINING || state == RuntimeCohortRef.State.QUIESCENT
                || state == RuntimeCohortRef.State.RETIRED || state == RuntimeCohortRef.State.REVOKED;
    }
}
