package com.dvarahq.agentworks.domain.run;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * One keyset page of a workspace's runs, newest first (issue #35). Every filter is optional; {@code lifecycles}
 * empty means any lifecycle. The page starts strictly after the position {@code (beforeCreatedAt, beforeId)} in the
 * order {@code createdAt DESC, id DESC}, so pages never repeat or skip a run while new runs are admitted.
 *
 * @param limit the number of runs to return; stores return at most this many
 */
public record RunQuery(UUID deploymentId, UUID releaseId, Set<RunLifecycle> lifecycles, Instant beforeCreatedAt,
        UUID beforeId, int limit) {
    public RunQuery {
        lifecycles = lifecycles == null || lifecycles.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(lifecycles));
        if ((beforeCreatedAt == null) != (beforeId == null)) {
            throw new IllegalArgumentException("a page position names both the creation time and the id");
        }
        if (limit < 1) throw new IllegalArgumentException("limit must be positive");
    }

    /** The first page of every run. */
    public static RunQuery first(int limit) {
        return new RunQuery(null, null, Set.of(), null, null, limit);
    }

    /** True when {@code run} matches the filters, ignoring the page position. */
    public boolean matches(RunRecord run) {
        Objects.requireNonNull(run, "run");
        return (deploymentId == null || deploymentId.equals(run.pins().deploymentId()))
                && (releaseId == null || releaseId.equals(run.pins().releaseId()))
                && (lifecycles.isEmpty() || lifecycles.contains(run.lifecycle()));
    }
}
