package com.dvarahq.agentworks.domain.run;

import com.dvarahq.agentworks.domain.tenant.WorkspaceId;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Workspace-bound operations inside one {@link DurableRunStore} transaction. Write order is part of the contract,
 * because the database checks each event against the ownership the run records at that moment: a lease holder
 * appends its events, checkpoints, and effects while its lease is still recorded and then updates the summary
 * (which may release the lease); a claim or a control write updates the summary first and then appends the events
 * it counts. At commit the summary must count exactly the events and checkpoints that exist.
 */
public interface RunTransaction {
    WorkspaceId workspace();

    /** Current database time; worker and controller clocks are never used for leases or lifecycle facts. */
    Instant now();

    Optional<RunRecord> findRun(UUID runId);

    /** Locks the run row for the rest of the transaction. */
    Optional<RunRecord> lockRun(UUID runId);

    /**
     * Compare-and-set of the run summary and control state against {@code expected.control().rowVersion()}.
     * Throws {@link RunStoreConflictException} when another writer changed the row.
     */
    void updateRun(RunRecord expected, RunRecord updated);

    /** Appends events in gapless per-run sequence; the summary must count them by commit. */
    void appendEvents(List<RunEvent> events);

    /** Every stored event of the run in sequence order. */
    List<RunEvent> events(UUID runId);

    /**
     * The run's stored events with a sequence in {@code [fromSequence, throughSequence]}, in sequence order, at most
     * {@code limit} of them (issue #35). Stores should override this with a bounded range read; the default filters
     * the full history.
     */
    default List<RunEvent> eventWindow(UUID runId, long fromSequence, long throughSequence, int limit) {
        return events(runId).stream()
                .filter(event -> event.sequence() >= fromSequence && event.sequence() <= throughSequence)
                .limit(limit).toList();
    }

    /**
     * The highest sequence whose event is no longer retained, or 0 while the run's history is complete. No Core
     * store compacts run history yet; retention and purge (#59) set it, and readers must then refuse to resume a
     * reader positioned before it instead of pretending a gap-free resume (issue #35).
     */
    default long compactedThrough(UUID runId) {
        return 0;
    }

    /** One keyset page of this workspace's runs, newest first (issue #35). */
    default List<RunRecord> listRuns(RunQuery query) {
        throw new UnsupportedOperationException("this store does not list runs");
    }

    /**
     * The current event sequence of each listed run of this workspace that exists; runs of other workspaces and
     * unknown ids are absent (issue #35).
     */
    default java.util.Map<UUID, Long> eventSequences(java.util.Collection<UUID> runIds) {
        java.util.Map<UUID, Long> sequences = new java.util.HashMap<>();
        for (UUID runId : runIds) {
            findRun(runId).ifPresent(run -> sequences.put(runId, run.summary().eventSequence()));
        }
        return sequences;
    }

    /** The current state of the cohort the run is pinned to. */
    String cohortState(UUID cohortId);

    /** The canonical manifest bytes of an approved release. */
    Optional<String> releaseManifest(UUID releaseId);

    /** The immutable canonical deployment configuration pinned by {@code revisionId}. */
    default Optional<String> deploymentConfiguration(UUID revisionId) {
        return Optional.empty();
    }

    /** The immutable AgentVersion {@code versionId} of this workspace: its canonical spec and digest (issue #34). */
    Optional<PinnedAgentVersion> agentVersion(UUID versionId);

    void insertCheckpoint(RunCheckpoint checkpoint);

    Optional<RunCheckpoint> latestCheckpoint(UUID runId);

    /**
     * Durable resource identities owned by the run at this checkpoint boundary (#44). The engine records these in the
     * checkpoint envelope; restore still re-reads the authoritative rows and never treats this projection as authority.
     */
    default CheckpointResources checkpointResources(UUID runId) {
        return CheckpointResources.EMPTY;
    }

    record CheckpointResources(List<CheckpointArtifact> artifacts, List<CheckpointSandbox> sandboxes) {
        public static final CheckpointResources EMPTY = new CheckpointResources(List.of(), List.of());
        public CheckpointResources {
            artifacts = List.copyOf(artifacts);
            sandboxes = List.copyOf(sandboxes);
        }
    }

    record CheckpointArtifact(UUID id, String digest, String lifecycle, String validation) {}

    /** {@code restoreAssurance} is explicit; Core currently records {@code RECONCILE_ONLY}, never synthetic restore. */
    record CheckpointSandbox(UUID id, String provider, String state, String restoreAssurance) {}

    Optional<RunEffect> findEffect(UUID runId, String effectKey);

    List<RunEffect> effects(UUID runId);

    /** Keys of the run's effects that are still PENDING (in doubt), without loading any result. */
    List<String> pendingEffectKeys(UUID runId);

    void insertEffect(RunEffect effect);

    /** Compare-and-set of an effect against {@code expected.state()} and {@code expected.attempts()}. */
    void updateEffect(RunEffect expected, RunEffect updated);

    /** The run's pending operator pause request (#183), if any. */
    Optional<RunPauseRequest> pauseRequest(UUID runId);

    /** Records a pause request for a run this transaction holds locked; a run has at most one. */
    void insertPauseRequest(RunPauseRequest request);

    /** Removes and returns the run's pause request, if any, in one statement where the store can. */
    Optional<RunPauseRequest> takePauseRequest(UUID runId);

    /**
     * Records that {@code actor} repeated the run's standing pause request, which this transaction holds with the run
     * locked (#201); the record goes with the request. True the first time for that actor and request, false after.
     * Stores should override this; the default keeps no record and answers true every time.
     */
    default boolean recordPauseRepeat(UUID runId, String actor, java.time.Instant now) {
        return true;
    }

    /**
     * What a lease holder's write reports back (#183): the state of the run's cohort and whether an operator asked for
     * the run to pause. Stores should answer both with one query; the default asks twice.
     */
    default WriteState writeState(UUID runId, UUID cohortId) {
        return new WriteState(cohortState(cohortId), pauseRequest(runId).isPresent());
    }

    /** See {@link #writeState(UUID, UUID)}. */
    record WriteState(String cohortState, boolean pauseRequested) {}

    /** Non-terminal ledger items of the run. */
    List<WorkItem> openWorkItems(UUID runId);

    void insertWorkItem(WorkItem item);

    /** Compare-and-set of a ledger item against {@code expected.state()}. */
    void updateWorkItem(WorkItem expected, WorkItem updated);
}
