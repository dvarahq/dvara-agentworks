package com.dvarahq.agentworks.runplane.execution;

import com.dvarahq.agentworks.domain.run.ClaimScope;
import com.dvarahq.agentworks.domain.run.DurableRunStore;
import com.dvarahq.agentworks.domain.run.RunAuthorityException;
import com.dvarahq.agentworks.domain.run.RunCheckpoint;
import com.dvarahq.agentworks.domain.run.RunEffect;
import com.dvarahq.agentworks.domain.run.RunEvent;
import com.dvarahq.agentworks.domain.run.RunLifecycle;
import com.dvarahq.agentworks.domain.run.RunRecord;
import com.dvarahq.agentworks.domain.run.RunStoreConflictException;
import com.dvarahq.agentworks.domain.run.RunTransaction;
import com.dvarahq.agentworks.domain.run.WorkItem;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * An in-memory implementation of the {@link DurableRunStore} port for runtime-domain tests. Each transaction holds
 * one global lock and works on a copy that is published only on success, so a failure commits nothing. The clock is
 * the store's "database time" and is controlled by the test; cohort states are set by the test. It enforces the
 * port's contract (compare-and-set, gapless sequences, workspace scope) but none of PostgreSQL's authority, which
 * the PostgreSQL integration tests prove.
 */
final class InMemoryDurableRunStore extends com.dvarahq.agentworks.domain.run.CompatibleClaimStore {
    private final ReentrantLock lock = new ReentrantLock();
    private State state = new State();
    private volatile Instant now = Instant.parse("2026-09-25T10:00:00Z");

    static final class State {
        final Map<UUID, RunRecord> runs = new LinkedHashMap<>();
        final Map<UUID, List<RunEvent>> events = new HashMap<>();
        final Map<UUID, List<RunCheckpoint>> checkpoints = new HashMap<>();
        final Map<UUID, Map<String, RunEffect>> effects = new HashMap<>();
        final Map<UUID, RunTransaction.CheckpointResources> checkpointResources = new HashMap<>();
        final Map<UUID, WorkItem> items = new LinkedHashMap<>();
        final Map<UUID, String> cohorts = new HashMap<>();
        final Map<UUID, String> manifests = new HashMap<>();
        final Map<UUID, String> deploymentConfigurations = new HashMap<>();
        final Map<UUID, com.dvarahq.agentworks.domain.run.PinnedAgentVersion> versions = new HashMap<>();
        /** Keyed by run id and compatibility identity. */
        final Map<String, com.dvarahq.agentworks.domain.run.ClaimQuarantine> quarantines = new LinkedHashMap<>();
        final Map<UUID, com.dvarahq.agentworks.domain.run.RunPauseRequest> pauses = new LinkedHashMap<>();
        /** The actors who repeated each run's standing pause request (#201); removed with the request. */
        final Map<UUID, java.util.Set<String>> pauseRepeats = new HashMap<>();

        State copy() {
            State copy = new State();
            copy.runs.putAll(runs);
            events.forEach((run, list) -> copy.events.put(run, new ArrayList<>(list)));
            checkpoints.forEach((run, list) -> copy.checkpoints.put(run, new ArrayList<>(list)));
            effects.forEach((run, map) -> copy.effects.put(run, new LinkedHashMap<>(map)));
            copy.checkpointResources.putAll(checkpointResources);
            copy.items.putAll(items);
            copy.cohorts.putAll(cohorts);
            copy.manifests.putAll(manifests);
            copy.deploymentConfigurations.putAll(deploymentConfigurations);
            copy.versions.putAll(versions);
            copy.quarantines.putAll(quarantines);
            copy.pauses.putAll(pauses);
            pauseRepeats.forEach((run, actors) -> copy.pauseRepeats.put(run, new java.util.HashSet<>(actors)));
            return copy;
        }
    }

    void advance(java.time.Duration duration) {
        now = now.plus(duration);
    }

    Instant now() {
        return now;
    }

    void cohort(UUID cohortId, String cohortState) {
        state.cohorts.put(cohortId, cohortState);
    }

    void version(com.dvarahq.agentworks.domain.run.PinnedAgentVersion version) {
        state.versions.put(version.versionId(), version);
    }

    void manifest(UUID releaseId, String manifestJson) {
        state.manifests.put(releaseId, manifestJson);
    }

    void deploymentConfiguration(UUID revisionId, String configurationJson) {
        state.deploymentConfigurations.put(revisionId, configurationJson);
    }

    void checkpointResources(UUID runId, RunTransaction.CheckpointResources resources) {
        state.checkpointResources.put(runId, resources);
    }

    /** Test-only direct access, bypassing the engine, to simulate corruption or legacy rows. */
    State raw() {
        return state;
    }

    @Override
    public <T> T inTransaction(WorkspaceId workspace, Function<RunTransaction, T> work) {
        transactionAttempts.incrementAndGet();
        T result;
        lock.lock();
        try {
            State draft = state.copy();
            result = work.apply(new Transaction(workspace, draft));
            state = draft;
        } finally {
            lock.unlock();
        }
        Runnable hook = afterCommit;
        if (hook != null) hook.run();
        return result;
    }

    /** Test hook: runs on the calling thread after each committed {@link #inTransaction}, outside the store lock. */
    volatile Runnable afterCommit;
    /** How many transactions were begun, including those that failed. */
    final java.util.concurrent.atomic.AtomicInteger transactionAttempts = new java.util.concurrent.atomic.AtomicInteger();
    /** How many times a transaction read a release manifest. */
    final java.util.concurrent.atomic.AtomicInteger manifestReads = new java.util.concurrent.atomic.AtomicInteger();
    /** How many times a transaction read a run's pause request. */
    final java.util.concurrent.atomic.AtomicInteger pauseReads = new java.util.concurrent.atomic.AtomicInteger();


    /** Test hook: fails the selection of a run as a row that cannot be decoded would, after reporting it. */
    final java.util.Set<UUID> undecodable = java.util.concurrent.ConcurrentHashMap.newKeySet();

    @Override
    protected <T> Optional<T> claimCompatible(ClaimScope scope, String compatibility,
            java.util.function.Consumer<UUID> selected, BiFunction<RunRecord, RunTransaction, T> work) {
        lock.lock();
        try {
            State draft = state.copy();
            Optional<RunRecord> candidate = draft.runs.values().stream()
                    .filter(run -> run.lifecycle() == RunLifecycle.QUEUED || (run.lifecycle() == RunLifecycle.ACTIVE
                            && !run.control().leaseUntil().isAfter(now)))
                    .filter(run -> List.of("ROUTABLE", "DRAINING").contains(draft.cohorts.get(run.pins().cohortId())))
                    .filter(run -> run.pins().manifestVersion() != null
                            && scope.manifestVersions().contains(run.pins().manifestVersion()))
                    .filter(run -> scope.workspace().map(w -> w.value().equals(run.pins().workspaceId())).orElse(true))
                    .filter(run -> !scope.excludedRunIds().contains(run.id()))
                    .filter(run -> draft.quarantines.get(key(run.id(), compatibility)) == null
                            || !draft.quarantines.get(key(run.id(), compatibility)).excludes(now))
                    .filter(run -> selects(scope, draft, run))
                    .min(Comparator.comparing((RunRecord run) -> run.pins().createdAt()).thenComparing(RunRecord::id));
            if (candidate.isEmpty()) return Optional.empty();
            selected.accept(candidate.get().id());
            if (undecodable.contains(candidate.get().id())) {
                throw new IllegalStateException("run row could not be decoded");
            }
            T result = work.apply(candidate.get(), new Transaction(new WorkspaceId(candidate.get().pins().workspaceId()), draft));
            state = draft;
            return Optional.ofNullable(result);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean keepsClaimQuarantine() {
        return true;
    }

    private static String key(UUID runId, String compatibility) {
        return runId + "|" + compatibility;
    }

    /** Test hook: every quarantine write fails, as a missing grant or a lock failure would. */
    volatile boolean failQuarantineWrites;

    @Override
    public Optional<com.dvarahq.agentworks.domain.run.ClaimQuarantine> quarantine(UUID runId,
            com.dvarahq.agentworks.domain.run.ClaimQuarantine.Request request) {
        if (failQuarantineWrites) {
            throw new org.springframework.dao.PermissionDeniedDataAccessException("quarantine write refused", null);
        }
        lock.lock();
        try {
            RunRecord run = state.runs.get(runId);
            if (run == null || !(run.lifecycle() == RunLifecycle.QUEUED || (run.lifecycle() == RunLifecycle.ACTIVE
                    && !run.control().leaseUntil().isAfter(now)))) {
                return Optional.empty();
            }
            if (request.observed() != null && (request.observed().eventSequence() != run.summary().eventSequence()
                    || request.observed().leaseFence() != run.summary().leaseFence())) {
                return Optional.empty();
            }
            String key = key(runId, request.compatibility());
            var previous = state.quarantines.get(key);
            if (previous != null && previous.excludes(now)) return Optional.of(previous);
            int count = com.dvarahq.agentworks.domain.run.ClaimQuarantine.next(previous, now);
            var recorded = new com.dvarahq.agentworks.domain.run.ClaimQuarantine(run.pins().workspaceId(), runId,
                    request.compatibility(), request.reason(), request.code(), count, request.worker(), now,
                    request.notBefore(now, count), null);
            state.quarantines.put(key, recorded);
            return Optional.of(recorded);
        } finally {
            lock.unlock();
        }
    }

    /** Every pending operator pause request (#183), of any workspace. */
    List<com.dvarahq.agentworks.domain.run.RunPauseRequest> pauseRequests() {
        lock.lock();
        try {
            return List.copyOf(state.pauses.values());
        } finally {
            lock.unlock();
        }
    }

    /** Test view of every quarantine of a workspace, as the Control Plane's repository lists them with inactive ones. */
    List<com.dvarahq.agentworks.domain.run.ClaimQuarantine> quarantines(WorkspaceId workspace) {
        lock.lock();
        try {
            return state.quarantines.values().stream()
                    .filter(quarantine -> quarantine.workspaceId().equals(workspace.value())).toList();
        } finally {
            lock.unlock();
        }
    }

    /** Test stand-in for the Control Plane's release: every identity's quarantine of the run; how many. */
    int release(WorkspaceId workspace, UUID runId) {
        lock.lock();
        try {
            int before = state.quarantines.size();
            state.quarantines.values().removeIf(quarantine -> quarantine.runId().equals(runId)
                    && quarantine.workspaceId().equals(workspace.value()));
            return before - state.quarantines.size();
        } finally {
            lock.unlock();
        }
    }

    private static final tools.jackson.databind.json.JsonMapper MANIFESTS =
            tools.jackson.databind.json.JsonMapper.builder().build();

    /** The executor type the run's pinned release manifest names, as admission records it on the run row. */
    private static String executorType(State state, RunRecord run) {
        String manifest = state.manifests.get(run.pins().releaseId());
        if (manifest == null) return null;
        return MANIFESTS.readTree(manifest).path("executor").path("type").stringValue(null);
    }

    /**
     * Whether a scope restricted to executor types selects the run, as the claim query's {@code executor_type in (...)}
     * does: a run without a recorded type matches no type (#181), rather than failing the claim.
     */
    private static boolean selects(ClaimScope scope, State state, RunRecord run) {
        if (scope.executorTypes().isEmpty()) return true;
        String type = executorType(state, run);
        return type != null && scope.executorTypes().contains(type);
    }

    /** Seeds a run with its RUN_CREATED event, as admission would. */
    void seed(RunRecord run, RunEvent created) {
        lock.lock();
        try {
            state.runs.put(run.id(), run);
            state.events.put(run.id(), new ArrayList<>(List.of(created)));
        } finally {
            lock.unlock();
        }
    }

    private final class Transaction implements RunTransaction {
        private final WorkspaceId workspace;
        private final State draft;

        private Transaction(WorkspaceId workspace, State draft) {
            this.workspace = workspace;
            this.draft = draft;
        }

        @Override
        public WorkspaceId workspace() {
            return workspace;
        }

        @Override
        public Instant now() {
            return now;
        }

        @Override
        public Optional<RunRecord> findRun(UUID runId) {
            return Optional.ofNullable(draft.runs.get(runId))
                    .filter(run -> run.pins().workspaceId().equals(workspace.value()));
        }

        @Override
        public Optional<RunRecord> lockRun(UUID runId) {
            return findRun(runId);
        }

        @Override
        public void updateRun(RunRecord expected, RunRecord updated) {
            RunRecord current = findRun(expected.id()).orElseThrow();
            if (current.control().rowVersion() != expected.control().rowVersion()) {
                throw new RunStoreConflictException("run changed concurrently");
            }
            if (!current.pins().equals(updated.pins())) throw new RunAuthorityException("IMMUTABLE_PIN", null, null);
            draft.runs.put(updated.id(), updated);
            if (updated.lifecycle().terminal() && !expected.lifecycle().terminal()) {
                // As the JDBC store does: a run that ended closes its claim quarantines.
                draft.quarantines.replaceAll((key, quarantine) -> !quarantine.runId().equals(updated.id())
                        || quarantine.closedAt() != null ? quarantine
                        : new com.dvarahq.agentworks.domain.run.ClaimQuarantine(quarantine.workspaceId(),
                                quarantine.runId(), quarantine.compatibility(), quarantine.reason(), quarantine.code(),
                                quarantine.quarantines(), quarantine.quarantinedBy(), quarantine.quarantinedAt(),
                                quarantine.notBefore(), now));
            }
        }

        @Override
        public void appendEvents(List<RunEvent> events) {
            for (RunEvent event : events) {
                if (!event.workspaceId().equals(workspace.value())) throw new IllegalArgumentException("workspace");
                List<RunEvent> history = draft.events.computeIfAbsent(event.runId(), ignored -> new ArrayList<>());
                if (event.sequence() != history.size() + 1) {
                    throw new RunAuthorityException("EVENT_SEQUENCE", null, null);
                }
                history.add(event);
            }
        }

        @Override
        public List<RunEvent> events(UUID runId) {
            return List.copyOf(draft.events.getOrDefault(runId, List.of()));
        }

        @Override
        public String cohortState(UUID cohortId) {
            return Objects.requireNonNull(draft.cohorts.get(cohortId), "cohort");
        }

        @Override
        public Optional<String> releaseManifest(UUID releaseId) {
            manifestReads.incrementAndGet();
            return Optional.ofNullable(draft.manifests.get(releaseId));
        }

        @Override
        public Optional<String> deploymentConfiguration(UUID revisionId) {
            return Optional.ofNullable(draft.deploymentConfigurations.get(revisionId));
        }

        @Override
        public Optional<com.dvarahq.agentworks.domain.run.PinnedAgentVersion> agentVersion(UUID versionId) {
            return Optional.ofNullable(draft.versions.get(versionId));
        }

        @Override
        public void insertCheckpoint(RunCheckpoint checkpoint) {
            List<RunCheckpoint> list = draft.checkpoints.computeIfAbsent(checkpoint.runId(), ignored -> new ArrayList<>());
            if (checkpoint.sequence() != list.size() + 1) throw new RunAuthorityException("CHECKPOINT_SEQUENCE", null, null);
            list.add(checkpoint);
        }

        @Override
        public Optional<RunCheckpoint> latestCheckpoint(UUID runId) {
            List<RunCheckpoint> list = draft.checkpoints.getOrDefault(runId, List.of());
            return list.isEmpty() ? Optional.empty() : Optional.of(list.getLast());
        }

        @Override
        public RunTransaction.CheckpointResources checkpointResources(UUID runId) {
            return draft.checkpointResources.getOrDefault(runId, RunTransaction.CheckpointResources.EMPTY);
        }

        @Override
        public Optional<RunEffect> findEffect(UUID runId, String effectKey) {
            return Optional.ofNullable(draft.effects.getOrDefault(runId, Map.of()).get(effectKey));
        }

        @Override
        public List<RunEffect> effects(UUID runId) {
            return List.copyOf(draft.effects.getOrDefault(runId, Map.of()).values());
        }

        @Override
        public Optional<com.dvarahq.agentworks.domain.run.RunPauseRequest> pauseRequest(UUID runId) {
            pauseReads.incrementAndGet();
            return Optional.ofNullable(draft.pauses.get(runId))
                    .filter(request -> request.workspaceId().equals(workspace.value()));
        }

        @Override
        public void insertPauseRequest(com.dvarahq.agentworks.domain.run.RunPauseRequest request) {
            if (!request.workspaceId().equals(workspace.value())) {
                throw new RunAuthorityException("WORKSPACE_MISMATCH", null, null);
            }
            if (draft.pauses.putIfAbsent(request.runId(), request) != null) {
                throw new IllegalStateException("the run already has a pause request");
            }
        }

        @Override
        public Optional<com.dvarahq.agentworks.domain.run.RunPauseRequest> takePauseRequest(UUID runId) {
            Optional<com.dvarahq.agentworks.domain.run.RunPauseRequest> request = pauseRequest(runId);
            request.ifPresent(ignored -> {
                draft.pauses.remove(runId);
                draft.pauseRepeats.remove(runId);
            });
            return request;
        }

        @Override
        public boolean recordPauseRepeat(UUID runId, String actor, java.time.Instant now) {
            if (pauseRequest(runId).isEmpty()) throw new IllegalStateException("the run has no pause request");
            return draft.pauseRepeats.computeIfAbsent(runId, id -> new java.util.HashSet<>()).add(actor);
        }

        @Override
        public List<String> pendingEffectKeys(UUID runId) {
            return effects(runId).stream().filter(effect -> effect.state() == RunEffect.State.PENDING)
                    .map(RunEffect::effectKey).toList();
        }

        @Override
        public void insertEffect(RunEffect effect) {
            var map = draft.effects.computeIfAbsent(effect.runId(), ignored -> new LinkedHashMap<>());
            if (map.putIfAbsent(effect.effectKey(), effect) != null) throw new RunStoreConflictException("duplicate effect");
        }

        @Override
        public void updateEffect(RunEffect expected, RunEffect updated) {
            RunEffect current = findEffect(expected.runId(), expected.effectKey()).orElseThrow();
            if (current.state() != expected.state() || current.attempts() != expected.attempts()) {
                throw new RunStoreConflictException("effect changed concurrently");
            }
            draft.effects.get(expected.runId()).put(expected.effectKey(), updated);
        }

        @Override
        public List<WorkItem> openWorkItems(UUID runId) {
            return draft.items.values().stream().filter(item -> runId.equals(item.runId()))
                    .filter(item -> item.state() != WorkItem.State.TERMINAL).toList();
        }

        @Override
        public void insertWorkItem(WorkItem item) {
            draft.items.put(item.id(), item);
        }

        @Override
        public void updateWorkItem(WorkItem expected, WorkItem updated) {
            WorkItem current = draft.items.get(expected.id());
            if (current == null || current.state() != expected.state()) throw new RunStoreConflictException("item changed");
            draft.items.put(updated.id(), updated);
        }
    }
}
