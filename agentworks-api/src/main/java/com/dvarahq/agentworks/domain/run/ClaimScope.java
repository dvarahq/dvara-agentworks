package com.dvarahq.agentworks.domain.run;

import com.dvarahq.agentworks.domain.tenant.WorkspaceId;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * What a stateless worker may claim. Workers are interchangeable across agents, tenants, deployments, and
 * releases; a scope only narrows eligibility. {@code manifestVersions} are the release-manifest versions this node
 * can read, so a node never claims a run it cannot load, and {@code excludedRunIds} are runs a caller excludes
 * explicitly (at most {@link #MAX_EXCLUDED_RUNS}). Quarantined runs need no exclusion here: the store's claim query
 * skips the runs quarantined for {@code compatibility}, the claiming node's runtime compatibility identity
 * ({@link ClaimQuarantine}, issue #181). {@link #requiredCompatibility()} refuses a scope without one. A store built on
 * {@link CompatibleClaimStore} receives the identity resolved, as a non-null argument, from whichever claim method its
 * caller uses (#206); a third-party store that implements {@link DurableRunStore} directly is responsible for invoking
 * that method and applying its quarantine predicate (#210). The engine always names its own; the record's plain
 * {@link #compatibility()} accessor stays nullable, so the record copies, matches, and serializes as one.
 * {@code executorTypes} are the executor types this node runs (issue #34): the claim query selects only runs whose
 * recorded executor type is one of them, so runs of other types are never selected and never block this node. An empty
 * set places no restriction.
 */
public record ClaimScope(Optional<WorkspaceId> workspace, Set<Integer> manifestVersions, Set<UUID> excludedRunIds,
        Set<String> executorTypes, String compatibility) {
    /** The most runs one claim may exclude; the exclusions are bound into the claim query. */
    public static final int MAX_EXCLUDED_RUNS = 1024;

    public ClaimScope {
        Objects.requireNonNull(workspace, "workspace");
        manifestVersions = Set.copyOf(manifestVersions);
        excludedRunIds = Set.copyOf(excludedRunIds);
        if (excludedRunIds.size() > MAX_EXCLUDED_RUNS) {
            throw new IllegalArgumentException("a claim excludes at most " + MAX_EXCLUDED_RUNS + " runs");
        }
        executorTypes = executorTypes == null ? Set.of() : Set.copyOf(executorTypes);
        if (manifestVersions.isEmpty()) throw new IllegalArgumentException("a worker reads at least one manifest version");
        if (compatibility != null) ClaimQuarantine.compatibility(compatibility);
    }

    /**
     * The claiming node's compatibility identity, which a store reads to honor quarantines (#185). A scope that names
     * none refuses to give one instead of returning null, so a conforming store cannot read a missing identity as "no
     * quarantine applies" (#201). The record's own {@link #compatibility()} stays a plain, nullable component. The
     * engine always names its own.
     *
     * @throws IllegalArgumentException when the scope names no compatibility identity ({@link #namesCompatibility()})
     */
    public String requiredCompatibility() {
        if (compatibility == null) {
            throw new IllegalArgumentException("a claim names the claiming node's compatibility identity, so the "
                    + "runs quarantined for it are skipped; use compatibleWith");
        }
        return compatibility;
    }

    /** Whether this scope names a compatibility identity; only then can it be claimed with. */
    public boolean namesCompatibility() {
        return compatibility != null;
    }

    /**
     * A scope of any workspace. It names no compatibility identity, so it cannot claim through a conforming store until
     * {@link #compatibleWith} names one; {@code DurableRunEngine} names its own (#185, #201).
     */
    public static ClaimScope any(Set<Integer> manifestVersions) {
        return new ClaimScope(Optional.empty(), manifestVersions, Set.of(), Set.of(), null);
    }

    public ClaimScope in(WorkspaceId only) {
        return new ClaimScope(Optional.of(only), manifestVersions, excludedRunIds, executorTypes, compatibility);
    }

    /** Replaces the excluded runs with {@code runIds}. */
    public ClaimScope excluding(Set<UUID> runIds) {
        return new ClaimScope(workspace, manifestVersions, runIds, executorTypes, compatibility);
    }

    /**
     * Also excludes {@code runIds}, keeping the runs this scope already excludes (#185). The caller's exclusions come
     * first: when the union would exceed {@link #MAX_EXCLUDED_RUNS}, only as many of {@code runIds} as fit are added.
     */
    public ClaimScope alsoExcluding(Set<UUID> runIds) {
        java.util.Set<UUID> union = new java.util.LinkedHashSet<>(excludedRunIds);
        for (UUID runId : runIds) {
            if (union.size() >= MAX_EXCLUDED_RUNS) break;
            union.add(runId);
        }
        return new ClaimScope(workspace, manifestVersions, union, executorTypes, compatibility);
    }

    /** Restricts claims to runs whose release pins one of {@code types}. */
    public ClaimScope executing(Set<String> types) {
        if (types.isEmpty()) throw new IllegalArgumentException("a worker executes at least one executor type");
        return new ClaimScope(workspace, manifestVersions, excludedRunIds, types, compatibility);
    }

    /** Honors the quarantines recorded for {@code identity}, the claiming node's runtime compatibility identity. */
    public ClaimScope compatibleWith(String identity) {
        return new ClaimScope(workspace, manifestVersions, excludedRunIds, executorTypes,
                ClaimQuarantine.compatibility(identity));
    }

    /** True when this node runs {@code executorType}. */
    public boolean executes(String executorType) {
        return executorTypes.isEmpty() || executorTypes.contains(executorType);
    }
}
