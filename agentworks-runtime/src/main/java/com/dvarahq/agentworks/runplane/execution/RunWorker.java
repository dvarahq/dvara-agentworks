package com.dvarahq.agentworks.runplane.execution;

import com.dvarahq.agentworks.domain.run.ClaimQuarantine;
import com.dvarahq.agentworks.domain.run.ClaimScope;
import com.dvarahq.agentworks.domain.run.RunLease;
import com.dvarahq.agentworks.domain.run.RunPins;
import com.dvarahq.agentworks.domain.run.RunStoreConflictException;
import com.dvarahq.agentworks.runplane.RunPlaneComponent;
import com.dvarahq.agentworks.runplane.execution.DurableRunEngine.ClaimedRun;
import com.dvarahq.agentworks.runplane.execution.DurableRunEngine.EffectInDoubtException;
import com.dvarahq.agentworks.runplane.execution.DurableRunEngine.EffectInvoker;
import com.dvarahq.agentworks.runplane.execution.DurableRunEngine.EffectOutcome;
import com.dvarahq.agentworks.runplane.execution.DurableRunEngine.EventDraft;
import com.dvarahq.agentworks.runplane.execution.DurableRunEngine.RestoredCheckpoint;
import com.dvarahq.agentworks.runplane.execution.DurableRunEngine.WriteResult;
import com.dvarahq.agentworks.runplane.execution.ReleaseManifestReader.RunManifest;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import com.dvarahq.agentworks.domain.run.RunRecord;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A stateless, interchangeable worker (ADR-027): it claims one eligible run at a time from any agent, tenant,
 * deployment, or release in its scope, loads everything from the run's exact pins, runs the handler under a
 * heartbeated lease, and records how the continuation ended. Blocked and paused runs release the worker.
 *
 * <p>A run that one such worker cannot claim is quarantined durably (#181), for every node of this node's runtime
 * compatibility identity ({@link DurableRunEngine#compatibility()}), so that it cannot block the rest of the scope: their
 * claim query skips it by predicate, however many quarantined runs there are, so no number of poison runs can starve a
 * healthy one. Nodes of another identity ignore the quarantine and may claim the run, so a refusal by an old or
 * misconfigured node never takes a run away from nodes that can execute it. A run whose history fails its integrity check ({@link #INTEGRITY_CODES}) is
 * quarantined until an operator releases it: digests do not depend on a node's version. Any other refusal (a version
 * this node cannot read, or a malformed manifest or payload, which a newer version may have written) and a run whose
 * claim failed {@value #MAX_CONSECUTIVE_CLAIM_FAILURES} times in a row are quarantined for {@code claimQuarantine},
 * doubled by each later quarantine, so a capable node may still claim the run when it lapses; after
 * {@value #MAX_TIMED_QUARANTINES} timed quarantines the next one lasts until released, so the claim attempts any one
 * run can cost the nodes of an identity are bounded. The only local state is the bounded per-run streak of failed
 * claims, and a small, short-lived fallback skip of a run whose quarantine could not be recorded, so a failing
 * quarantine write does not leave that run at the head of the queue; a node's workers share both
 * ({@link NodeClaimState}, #185), and the skip adds to the caller's own exclusions. Runs of
 * executor types this node does not run are never selected at all: the claim query filters them by their recorded
 * executor type, so they need no exclusion.
 *
 * <p>Configuration errors (an invalid worker id or lease duration) fail fast in the constructor instead of failing
 * every poll. When a heartbeat learns that the run ended (its runtime limit failed it) or that the lease is
 * definitively lost, the execution ends: the heartbeat stops and the handler thread is interrupted, so a handler
 * that honors interruption releases the worker. While a modeled effect is in flight, the heartbeat defers the
 * runtime limit by at most {@code effectRuntimeGrace}, so the effect's outcome is recorded instead of being orphaned
 * as PENDING; the next heartbeat or write then enforces the limit.
 */
public final class RunWorker implements RunPlaneComponent, AutoCloseable {
    /**
     * What one poll did. {@code REFUSED} means a run was quarantined durably, for every node: this node cannot
     * interpret the run, or the run's claim failed repeatedly.
     */
    public enum Poll { IDLE, EXECUTED, REFUSED, LOST, RETRY_LATER }

    /**
     * Failed claims of the same run, each within {@link #CLAIM_FAILURE_WINDOW} of the previous one, after which the run
     * is quarantined. Failures are counted per run, so claims of other runs in between do not reset them.
     */
    public static final int MAX_CONSECUTIVE_CLAIM_FAILURES = 3;
    /**
     * How long after a run's previous failed claim a new failure still extends its streak; independent of
     * {@code claimQuarantine}, so a short quarantine or a slow poll loop still builds a streak (#179).
     */
    public static final Duration CLAIM_FAILURE_WINDOW = Duration.ofMinutes(15);
    /** The most runs whose claim failures one worker tracks at a time; the least recently failed are dropped first. */
    public static final int MAX_TRACKED_CLAIM_FAILURES = 1024;
    /** How long every node first skips a run whose claim keeps failing; each later quarantine doubles it. */
    public static final Duration DEFAULT_CLAIM_QUARANTINE = Duration.ofMinutes(5);
    /** The shortest configurable first quarantine; database timestamps have microsecond precision. */
    public static final Duration MIN_CLAIM_QUARANTINE = Duration.ofSeconds(1);
    /** The longest configurable first quarantine. */
    public static final Duration MAX_CLAIM_QUARANTINE = Duration.ofDays(1);
    /** Timed quarantines of one run; the next one lasts until an operator releases it (#181). */
    public static final int MAX_TIMED_QUARANTINES = 5;
    /**
     * Claim refusals that no node of any version could get past: the run's history breaks its digest chain or diverges
     * from its summary, or the run predates the durable engine. They are quarantined at once until released. Every
     * other refusal may be a version skew (a newer node may read it), so it is quarantined for a time first.
     */
    public static final Set<String> INTEGRITY_CODES = Set.of("CORRUPT_HISTORY", "LEGACY_RUN");
    private static final System.Logger LOG = System.getLogger(RunWorker.class.getName());
    /** The most runs this worker skips locally because their quarantine could not be recorded. */
    public static final int MAX_LOCAL_FALLBACK = 256;
    /**
     * The longest this worker skips a run locally whose quarantine could not be recorded (never longer than
     * {@code claimQuarantine}); then it tries again.
     */
    public static final Duration LOCAL_FALLBACK = Duration.ofMinutes(1);
    /** How far past its runtime limit a run may go while a modeled effect is in flight. */
    public static final Duration DEFAULT_EFFECT_RUNTIME_GRACE = Duration.ofMinutes(2);
    /** Claim refusals that come from a race, not from the run: they never count toward a quarantine. */
    private static final Set<String> TRANSIENT_CLAIM_CODES = Set.of("COHORT_NOT_ADMITTING", "COHORT_STATE_CHANGED",
            "STALE_LEASE");

    private final DurableRunEngine engine;
    private final RunHandler handler;
    private final String workerId;
    private final ClaimScope scope;
    private final Duration leaseDuration;
    private final Duration claimQuarantine;
    private final Duration effectRuntimeGrace;
    private final DurableHumanInteractionCoordinator interactions;
    private final DurableChildRunCoordinator children;
    private final ScheduledExecutorService heartbeats;
    /**
     * Guarded by itself: runs whose quarantine could not be recorded and the {@link System#nanoTime()} until which this
     * node's workers skip them, oldest first, bounded at {@link #MAX_LOCAL_FALLBACK}. A fallback only (#181).
     */
    private final java.util.LinkedHashMap<UUID, Long> fallback;
    /** Guarded by itself: per-run failure count and the {@link System#nanoTime()} of the latest failure. */
    private final java.util.LinkedHashMap<UUID, long[]> claimFailures;

    /**
     * The local claim state of one node (#185): the per-run streaks of failed claims and the fallback skip of runs whose
     * quarantine could not be recorded. A node's workers share one, so a poison run's failures accumulate across the
     * pool and a skipped run is skipped by every worker, instead of each thread hitting the run on its own.
     */
    public static final class NodeClaimState {
        private final java.util.LinkedHashMap<UUID, Long> fallback = new java.util.LinkedHashMap<>();
        private final java.util.LinkedHashMap<UUID, long[]> claimFailures =
                new java.util.LinkedHashMap<>(16, 0.75f, true);
    }

    public RunWorker(DurableRunEngine engine, RunHandler handler, String workerId, ClaimScope scope,
            Duration leaseDuration) {
        this(engine, handler, workerId, scope, leaseDuration, DEFAULT_CLAIM_QUARANTINE, DEFAULT_EFFECT_RUNTIME_GRACE);
    }

    /**
     * @throws IllegalArgumentException for an engine whose store keeps no claim quarantine, an invalid worker id or
     *     lease duration, a quarantine shorter than
     *     {@link #MIN_CLAIM_QUARANTINE} or longer than {@link #MAX_CLAIM_QUARANTINE}, or a negative grace
     */
    public RunWorker(DurableRunEngine engine, RunHandler handler, String workerId, ClaimScope scope,
            Duration leaseDuration, Duration claimQuarantine, Duration effectRuntimeGrace) {
        this(engine, handler, workerId, scope, leaseDuration, claimQuarantine, effectRuntimeGrace,
                new NodeClaimState());
    }

    /** As the other constructors, sharing {@code node}'s local claim state with the node's other workers (#185). */
    public RunWorker(DurableRunEngine engine, RunHandler handler, String workerId, ClaimScope scope,
            Duration leaseDuration, Duration claimQuarantine, Duration effectRuntimeGrace, NodeClaimState node) {
        this(engine, handler, workerId, scope, leaseDuration, claimQuarantine, effectRuntimeGrace, node, null);
    }

    /** As the other constructors, with the authority that atomically blocks for durable human interactions. */
    public RunWorker(DurableRunEngine engine, RunHandler handler, String workerId, ClaimScope scope,
            Duration leaseDuration, Duration claimQuarantine, Duration effectRuntimeGrace, NodeClaimState node,
            DurableHumanInteractionCoordinator interactions) {
        this(engine, handler, workerId, scope, leaseDuration, claimQuarantine, effectRuntimeGrace, node, interactions, null);
    }

    public RunWorker(DurableRunEngine engine, RunHandler handler, String workerId, ClaimScope scope,
            Duration leaseDuration, Duration claimQuarantine, Duration effectRuntimeGrace, NodeClaimState node,
            DurableHumanInteractionCoordinator interactions, DurableChildRunCoordinator children) {
        Objects.requireNonNull(node, "node");
        this.fallback = node.fallback;
        this.claimFailures = node.claimFailures;
        this.engine = Objects.requireNonNull(engine, "engine");
        if (!engine.keepsClaimQuarantine()) {
            // Without a quarantine one poison run would block the queue forever (#181): refuse it at startup.
            throw new IllegalArgumentException("the run store keeps no claim quarantine");
        }
        this.handler = Objects.requireNonNull(handler, "handler");
        try {
            this.workerId = RunEventCodec.identity(workerId, "workerId");
            // The engine would refuse an invalid lease on every claim, forever; refuse it once, here (#175).
            this.leaseDuration = DurableRunEngine.lease(Objects.requireNonNull(leaseDuration, "leaseDuration"));
        } catch (RunEngineException invalid) {
            throw new IllegalArgumentException(invalid.getMessage(), invalid);
        }
        this.scope = Objects.requireNonNull(scope, "scope");
        // A zero quarantine would disable the exclusion and let one poison run block the queue (#181).
        this.claimQuarantine = Objects.requireNonNull(claimQuarantine, "claimQuarantine");
        if (claimQuarantine.compareTo(MIN_CLAIM_QUARANTINE) < 0 || claimQuarantine.compareTo(MAX_CLAIM_QUARANTINE) > 0) {
            throw new IllegalArgumentException("claimQuarantine must be positive: at least " + MIN_CLAIM_QUARANTINE
                    + " and at most " + MAX_CLAIM_QUARANTINE);
        }
        this.effectRuntimeGrace = nonNegative(effectRuntimeGrace, "effectRuntimeGrace");
        this.interactions = interactions;
        this.children = children;
        if (effectRuntimeGrace.compareTo(DurableRunEngine.MAX_RUNTIME_GRACE) > 0) {
            throw new IllegalArgumentException("effectRuntimeGrace is at most " + DurableRunEngine.MAX_RUNTIME_GRACE);
        }
        this.heartbeats = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "agentworks-heartbeat-" + this.workerId);
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Claims and executes at most one run; no claim failure escapes to the caller's poll loop. */
    public Poll pollOnce() {
        if (heartbeats.isShutdown()) throw new IllegalStateException("the worker is closed");
        Optional<ClaimedRun> claimed;
        // The run whose own claim attempt threw, if any; failures outside an attempt are never attributed to a run.
        AtomicReference<DurableRunEngine.FailedClaim> failedRun = new AtomicReference<>();
        try {
            Set<UUID> skipped = fallbackSkipped();
            claimed = engine.claimNext(workerId, skipped.isEmpty() ? scope : scope.alsoExcluding(skipped), leaseDuration,
                    failedRun::set);
        } catch (RunClaimRefusedException refused) {
            // This node cannot interpret the run. A run whose history fails its integrity check stays excluded until
            // released; any other refusal may be a version skew, so it is excluded for a doubling time first. Either way
            // only for nodes of this node's compatibility identity.
            boolean integrity = INTEGRITY_CODES.contains(refused.code());
            return quarantine(refused.runId(), refused.observed(), ClaimQuarantine.Reason.UNINTERPRETABLE,
                    code(refused.code(), "CLAIM_REFUSED"), integrity ? null : claimQuarantine);
        } catch (com.dvarahq.agentworks.storage.lifecycle.LifecycleRetryExhaustedException exhausted) {
            // Exhausted retries of a retryable failure changed nothing and say nothing about the run: try again.
            return Poll.RETRY_LATER;
        } catch (org.springframework.dao.DataAccessException database) {
            // Only an error the run's own data can cause (an integrity violation, or an unclassified guard failure)
            // counts toward its quarantine. Connection loss, timeouts, serialization or deadlock failures, races such
            // as a duplicate key, and environment errors such as a denied permission or bad SQL say nothing about it.
            if (!countsAgainstRun(database)) return Poll.RETRY_LATER;
            return claimFailed(failedRun.get(), "CLAIM_REJECTED_BY_DATABASE");
        } catch (org.springframework.transaction.TransactionException transaction) {
            // Transaction infrastructure (for example no connection to begin one) is never the run's fault (#179).
            return Poll.RETRY_LATER;
        } catch (RunEngineException refused) {
            // A claim that raced a cohort transition changed nothing: try again. Any other typed refusal of this run's
            // claim counts toward its quarantine.
            if (TRANSIENT_CLAIM_CODES.contains(refused.code())) return Poll.RETRY_LATER;
            return claimFailed(failedRun.get(), code(refused.code(), "CLAIM_FAILED"));
        } catch (RuntimeException unexpected) {
            // A failure raised while claiming a specific run is that run's problem, not the poll loop's.
            if (failedRun.get() == null) throw unexpected;
            return claimFailed(failedRun.get(), "CLAIM_FAILED");
        }
        if (claimed.isEmpty()) return Poll.IDLE;
        claimSucceeded(claimed.get().run().id());
        Execution execution = new Execution(claimed.get());
        // An operator asked for this run to pause while an earlier worker held it, and that worker's lease expired
        // first: the claim is a safe boundary with nothing in flight, so it pauses before any work (#183), unless an
        // effect the lost worker started is in doubt. Then the run recovers as usual, and pauses at the first safe
        // boundary after that effect's outcome is recorded.
        if (claimed.get().pauseRequested()) {
            try {
                if (engine.pauseIfRequested(claimed.get().lease()).isPresent()) return Poll.EXECUTED;
            } catch (RunEngineException limitOrLost) {
                return "LIMIT_EXCEEDED".equals(limitOrLost.code()) ? Poll.EXECUTED : Poll.LOST;
            } catch (RuntimeException unavailable) {
                // The database could not answer: the claim is abandoned, and recovery tries again after the lease.
                return Poll.LOST;
            }
        }
        long period = Math.max(1, leaseDuration.toMillis() / 3);
        try {
            execution.beat = heartbeats.scheduleAtFixedRate(execution::heartbeat, period, period, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.RejectedExecutionException closing) {
            // Closed while claiming: abandon the claim unwritten; its lease lapses and another worker recovers it.
            return Poll.LOST;
        }
        try {
            RunOutcome outcome = execution.runHandler();
            execution.stopBeating();
            if (execution.pausedByRequest) return Poll.EXECUTED;
            if (execution.ended) return execution.endedPoll();
            return execution.finish(outcome);
        } catch (Abandoned shutdown) {
            // The poll thread itself was interrupted (for example a shutdown): the claim is abandoned without another
            // write, its lease lapses, and a healthy worker recovers the run. The interrupt is kept for the caller.
            execution.stopBeating();
            Thread.currentThread().interrupt();
            return Poll.LOST;
        } catch (Unresponsive stuck) {
            // The run already ended (or its lease is gone) and the handler ignored its interrupt for a whole lease: the
            // worker moves on, and the handler's further writes are refused locally.
            execution.stopBeating();
            return execution.endedPoll();
        } catch (EffectInDoubtException inDoubt) {
            execution.stopBeating();
            if (execution.ended) return execution.endedPoll();
            return execution.terminate(execution.lease, "EFFECT_IN_DOUBT", inDoubt.getMessage());
        } catch (RunBackpressureException saturated) {
            // The handler explicitly declares that no external effect or run write started. Leave the claim untouched;
            // its lease lapses and another poll retries after local capacity recovers.
            execution.stopBeating();
            if (execution.ended) return execution.endedPoll();
            return Poll.RETRY_LATER;
        } catch (RunEngineException failure) {
            execution.stopBeating();
            if (execution.pausedByRequest) return Poll.EXECUTED;
            if (execution.ended) return execution.endedPoll();
            return execution.onEngineFailure(failure);
        } catch (org.springframework.dao.DataAccessException
                 | org.springframework.transaction.TransactionException
                 | com.dvarahq.agentworks.storage.lifecycle.LifecycleRetryExhaustedException infrastructure) {
            // Database unavailability or exhausted retries are not the handler's fault: the lease lapses and a
            // healthy worker recovers the run from its last committed state.
            execution.stopBeating();
            if (execution.ended) return execution.endedPoll();
            return Poll.LOST;
        } catch (Exception failure) {
            execution.stopBeating();
            if (execution.pausedByRequest) return Poll.EXECUTED;
            // A handler stopped by the heartbeat (interrupted) ends here: the run already ended, or it was lost.
            if (execution.ended) return execution.endedPoll();
            return execution.terminate(execution.lease, "HANDLER_FAILED", String.valueOf(failure.getMessage()));
        } finally {
            execution.stopBeating();
        }
    }

    /** The poll thread was interrupted while its handler ran; the claim is abandoned. */
    private static final class Abandoned extends Exception {
        private Abandoned() {
            super(null, null, false, false);
        }
    }

    /** The execution ended, but its handler did not stop within a lease of being interrupted. */
    private static final class Unresponsive extends Exception {
        private Unresponsive() {
            super(null, null, false, false);
        }
    }

    /** A handler or tool raised an {@link Error} other than a JVM failure; the run fails instead of the pool thread. */
    static final class HandlerError extends Exception {
        HandlerError(Error cause) {
            super("the handler failed with " + cause.getClass().getName(), cause);
        }
    }

    @Override
    public void close() {
        heartbeats.shutdownNow();
    }

    /**
     * Records a durable quarantine of {@code runId} for every node of this node's compatibility identity; {@code base}
     * null quarantines it until an operator releases it. A run that is no longer claimable, or no longer in the state
     * {@code observed} (another worker claimed it meanwhile, or it moved on), is not quarantined, and the next poll may
     * select it again. When the write itself fails, this worker skips the run locally for {@link #LOCAL_FALLBACK}, so it
     * does not stay at the head of the queue, and tries to record it again afterwards.
     */
    private Poll quarantine(UUID runId, ClaimQuarantine.Observed observed, ClaimQuarantine.Reason reason, String code,
            Duration base) {
        Optional<ClaimQuarantine> recorded;
        try {
            recorded = engine.quarantine(runId, new ClaimQuarantine.Request(engine.compatibility(), reason, code, workerId,
                    base, MAX_TIMED_QUARANTINES, observed));
        } catch (RuntimeException unrecorded) {
            LOG.log(System.Logger.Level.WARNING, "worker {0} could not quarantine run {1} ({2}): {3}; it skips the run "
                    + "locally for {4}", workerId, runId, code, unrecorded.getClass().getName(), LOCAL_FALLBACK);
            skipLocally(runId);
            return Poll.RETRY_LATER;
        }
        claimSucceeded(runId);
        if (recorded.isEmpty()) return Poll.RETRY_LATER;
        ClaimQuarantine quarantine = recorded.get();
        LOG.log(System.Logger.Level.WARNING, "worker {0}: run {1} of workspace {2} is quarantined ({3} {4}, quarantine "
                + "{5}) {6}", workerId, runId, quarantine.workspaceId(), quarantine.reason(), quarantine.code(),
                quarantine.quarantines(),
                quarantine.indefinite() ? "until an operator releases it" : "until " + quarantine.notBefore());
        return Poll.REFUSED;
    }

    /** A typed code as a quarantine records it, or {@code fallback} for one it cannot store. */
    private static String code(String code, String fallback) {
        return code != null && code.matches("[A-Z][A-Z0-9_]{0,63}") ? code : fallback;
    }

    /** The runs this worker skips locally because their quarantine could not be recorded; expired ones are dropped. */
    private Set<UUID> fallbackSkipped() {
        long now = System.nanoTime();
        synchronized (fallback) {
            if (fallback.isEmpty()) return Set.of();
            fallback.values().removeIf(until -> now - until >= 0);
            return Set.copyOf(fallback.keySet());
        }
    }

    private void skipLocally(UUID runId) {
        synchronized (fallback) {
            fallback.remove(runId);
            Duration skip = LOCAL_FALLBACK.compareTo(claimQuarantine) < 0 ? LOCAL_FALLBACK : claimQuarantine;
            fallback.put(runId, System.nanoTime() + skip.toNanos());
            while (fallback.size() > MAX_LOCAL_FALLBACK) fallback.remove(fallback.keySet().iterator().next());
        }
    }

    private Poll claimFailed(DurableRunEngine.FailedClaim failed, String code) {
        if (failed == null) return Poll.RETRY_LATER;
        UUID runId = failed.runId();
        long now = System.nanoTime();
        synchronized (claimFailures) {
            long[] failures = claimFailures.get(runId);
            // A failure more than the failure window after the previous one starts a new streak.
            if (failures == null || now - failures[1] > CLAIM_FAILURE_WINDOW.toNanos()) failures = new long[]{0, now};
            failures[0]++;
            failures[1] = now;
            if (failures[0] < MAX_CONSECUTIVE_CLAIM_FAILURES) {
                claimFailures.put(runId, failures);
                while (claimFailures.size() > MAX_TRACKED_CLAIM_FAILURES) {
                    claimFailures.remove(claimFailures.keySet().iterator().next());
                }
                return Poll.RETRY_LATER;
            }
            // The streak is kept until the quarantine is durably recorded, so a failed write is retried at once.
            claimFailures.put(runId, failures);
        }
        // The run's claim is always rejected: every node of this identity skips it for a while so the rest of the scope
        // is served.
        return quarantine(runId, failed.observed(), ClaimQuarantine.Reason.CLAIM_FAILING, code, claimQuarantine);
    }

    /** A successful claim of a run ends that run's streak of failures; other runs keep theirs. */
    private void claimSucceeded(UUID runId) {
        synchronized (claimFailures) {
            claimFailures.remove(runId);
        }
    }

    /**
     * Failures that say nothing about the run: Spring's transient and recoverable categories (timeouts, lock,
     * serialization, and deadlock failures) and resource failures such as a lost or unobtainable connection.
     */
    static boolean transientDatabaseFailure(org.springframework.dao.DataAccessException failure) {
        return failure instanceof org.springframework.dao.TransientDataAccessException
                || failure instanceof org.springframework.dao.RecoverableDataAccessException
                || failure instanceof org.springframework.dao.DataAccessResourceFailureException;
    }

    /**
     * Whether a database failure raised by one run's own claim counts toward that run's quarantine: every failure
     * except transient ones, races (a duplicate key), and environment errors that would fail any run the same way:
     * denied permissions, misuse of a resource (the whole {@code InvalidDataAccessResourceUsageException} family, such
     * as bad SQL, except a type mismatch, which is a fact of the run's own row), API misuse, and a failure to release a
     * resource after the work (including Spring's deprecated legacy cleanup exception, recognized by binary name so
     * compiled code does not depend on the removed API). Deterministic per-run failures such as an integrity
     * violation, a row that cannot be retrieved or converted, or a guard trigger's error count.
     */
    static boolean countsAgainstRun(org.springframework.dao.DataAccessException failure) {
        boolean resourceMisuse = failure instanceof org.springframework.dao.InvalidDataAccessResourceUsageException
                && !(failure instanceof org.springframework.dao.TypeMismatchDataAccessException);
        return !(transientDatabaseFailure(failure) || failure instanceof org.springframework.dao.DuplicateKeyException
                || failure instanceof org.springframework.dao.PermissionDeniedDataAccessException
                || resourceMisuse
                || failure instanceof org.springframework.dao.InvalidDataAccessApiUsageException
                || hasTypeNamed(failure, "org.springframework.dao.CleanupFailureDataAccessException"));
    }

    private static boolean hasTypeNamed(Throwable failure, String typeName) {
        for (Class<?> type = failure.getClass(); type != null; type = type.getSuperclass()) {
            if (typeName.equals(type.getName())) return true;
        }
        return false;
    }

    private static Duration nonNegative(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isNegative()) throw new IllegalArgumentException(name + " must not be negative");
        return value;
    }

    private final class Execution implements RunContext {
        private final ClaimedRun claimed;
        /** The thread the handler runs on; the only thread the heartbeat ever interrupts. */
        private volatile Thread handlerThread;
        /** Serializes each heartbeat with the start and end of effects, so its in-flight check cannot miss one. */
        private final ReentrantLock effectGate = new ReentrantLock();
        /** Serializes safe-boundary checks, whichever thread reaches them. */
        private final ReentrantLock boundary = new ReentrantLock();
        private volatile RunLease lease;
        private volatile boolean suspended;
        /**
         * The pause hint (#183, #194, #198) is one generation counter and a watermark: every observation of an
         * operator's request (the claim, a write, an effect's outcome, or a heartbeat) advances the generation, and a
         * boundary whose own database check found no standing request moves the watermark to the generation it saw
         * when the check began. A request is hinted while the generation is past the watermark, so an older read
         * never hides a newer request. Only a boundary, serialized by {@link #boundary}, moves the watermark.
         */
        private final java.util.concurrent.atomic.AtomicLong pauseObservations = new java.util.concurrent.atomic.AtomicLong();
        private volatile long pauseCleared;
        /** This run's executor may be paused mid-exchange (#194); fixed at the claim, from this node's registry. */
        private final boolean resumableMidExchange;
        /** This execution is pausing the run at a boundary right now, so a beat that finds the lease gone is late. */
        private volatile boolean pausing;
        /** This execution paused the run for an operator's request (#183); it writes nothing more. */
        private volatile boolean pausedByRequest;
        /** The run ended, or the lease was definitively lost: nothing more is written for this claim. */
        private volatile boolean ended;
        /** Child admission atomically blocked the run and released this claim. */
        private volatile boolean delegated;
        /** When {@link #ended}: true if a limit failed the run, false if the lease was lost. */
        private volatile boolean failedByLimit;
        private volatile ScheduledFuture<?> beat;
        /** Guarded by {@link #effectGate}. */
        private int effectsInFlight;
        /** Guarded by {@code this}. */
        private boolean handlerReturned;
        /** The handler returned, so no further beat may end the execution; checked under {@link #effectGate}. */
        private volatile boolean stopped;
        /** The worker gave up on this handler: its further writes are refused before they reach the engine. */
        private volatile boolean abandoned;

        private Execution(ClaimedRun claimed) {
            this.claimed = claimed;
            this.lease = claimed.lease();
            this.suspended = "SUSPENDED".equals(claimed.cohortState());
            observed(claimed.pauseRequested());
            this.resumableMidExchange = engine.resumableMidExchange(claimed.manifest());
        }

        /** Records an observation of the request; a false observation changes nothing. */
        private void observed(boolean requested) {
            if (requested) pauseObservations.incrementAndGet();
        }

        private boolean hinted() {
            return pauseObservations.get() > pauseCleared;
        }

        @Override
        public RunPins pins() {
            return claimed.run().pins();
        }

        @Override
        public RunManifest manifest() {
            return claimed.manifest();
        }

        @Override
        public JsonNode input() {
            return claimed.input();
        }

        @Override
        public Optional<JsonNode> checkpointState() {
            return claimed.checkpoint().map(RestoredCheckpoint::state);
        }

        @Override
        public boolean recovered() {
            return claimed.recovered();
        }

        @Override
        public Optional<com.dvarahq.agentworks.domain.run.RunInvoker> invoker() {
            return claimed.invoker();
        }

        @Override
        public Optional<com.dvarahq.agentworks.domain.run.PinnedAgentVersion> agentVersion() {
            return claimed.agentVersion();
        }

        @Override
        public Map<String, com.dvarahq.agentworks.domain.security.CredentialRef> credentialReferences() {
            return claimed.credentialReferences();
        }

        @Override
        public com.dvarahq.agentworks.child.ChildRunRecord startChild(
                com.dvarahq.agentworks.child.ChildRunRequest request) {
            refuseIfAbandoned();
            if (children == null) throw new RunEngineException("CHILD_RUN_UNAVAILABLE", "worker has no child authority");
            com.dvarahq.agentworks.child.ChildRunRecord child = children.start(lease, request);
            delegated = true;
            ended = true;
            return child;
        }

        @Override
        public AgentAsTool.Result invokeChild(com.dvarahq.agentworks.child.ChildRunRequest request) {
            refuseIfAbandoned();
            if (children == null) throw new RunEngineException("CHILD_RUN_UNAVAILABLE", "worker has no child authority");
            AgentAsTool.Result result = new AgentAsTool(children, engine).invoke(lease, request);
            if (result instanceof AgentAsTool.Result.Blocked) {
                delegated = true;
                ended = true;
            }
            return result;
        }

        @Override
        public void emit(List<EventDraft> events) {
            refuseIfAbandoned();
            accept(engine.append(lease, events));
        }

        /**
         * A checkpoint is a safe boundary: once it is recorded, a pending operator's request pauses the run here,
         * whatever the handler does (#183), and the handler is stopped with {@code PAUSE_REQUESTED}.
         */
        @Override
        public void checkpoint(Object state) {
            refuseIfAbandoned();
            accept(engine.checkpoint(lease, state));
            stopIfPaused();
        }

        private void refuseIfAbandoned() {
            if (abandoned) throw new RunEngineException("STALE_LEASE", "this worker abandoned the claim");
            if (pausedByRequest) throw paused();
        }

        @Override
        public boolean pauseIfRequested() {
            refuseIfAbandoned();
            if (!hinted()) return false;
            // Boundaries may be reached from more than one thread (the Spring AI guard can run on a Reactor thread),
            // so they are serialized: pausing and the watermark each have one writer at a time (PR #199 review).
            boundary.lock();
            try {
                return pauseAtThisBoundary();
            } finally {
                boundary.unlock();
            }
        }

        /** The body of {@link #pauseIfRequested()}; runs under {@link #boundary}. */
        private boolean pauseAtThisBoundary() {
            refuseIfAbandoned();
            if (!hinted()) return false;
            long seen = pauseObservations.get();
            DurableRunEngine.BoundaryPause answer;
            // A beat racing this pause finds the lease released; it must not end the execution (#194).
            pausing = true;
            try {
                answer = engine.pauseAtBoundary(lease, resumableMidExchange);
                if (answer.pausedNow()) pausedByRequest = true;
            } finally {
                pausing = false;
            }
            if (!answer.pausedNow()) {
                // The run stays active and the handler goes on. A request withdrawn by a resume is forgotten; one that
                // waits for an in-doubt effect still stands, so a later boundary pauses once its outcome is recorded
                // (#194).
                if (!answer.requestStands()) pauseCleared = Math.max(pauseCleared, seen);
                return false;
            }
            return true;
        }

        /** Pauses the run at this boundary when an operator's request is pending, and stops the handler. */
        private void stopIfPaused() {
            if (pauseIfRequested()) throw paused();
        }

        private static RunEngineException paused() {
            return new RunEngineException("PAUSE_REQUESTED", "the run paused at this safe boundary at an operator's request");
        }

        /** The start of an effect is a safe boundary: nothing is dispatched yet, so a pending request pauses here. */
        @Override
        public EffectOutcome effect(String key, Object request, EffectInvoker invoker) {
            refuseIfAbandoned();
            stopIfPaused();
            effectGate.lock();
            try {
                effectsInFlight++;
            } finally {
                effectGate.unlock();
            }
            try {
                EffectOutcome outcome = engine.executeEffect(lease, key, request, invoker);
                lease = outcome.lease();
                // A request made while the effect ran is observed at the next boundary (#194).
                observed(outcome.pauseRequested());
                return outcome;
            } catch (EffectInDoubtException inDoubt) {
                // The intent was committed before invocation, so the holder's token already advanced.
                lease = inDoubt.lease();
                throw inDoubt;
            } catch (RunEngineException failure) {
                // Recording the outcome past a limit failed the run in the same transaction: this write ended it.
                if ("LIMIT_EXCEEDED".equals(failure.code())) endedByLimit();
                throw failure;
            } finally {
                effectGate.lock();
                try {
                    effectsInFlight--;
                } finally {
                    effectGate.unlock();
                }
            }
        }

        @Override
        public boolean suspended() {
            return suspended;
        }

        @Override
        public boolean pauseRequested() {
            return hinted();
        }

        /**
         * Runs the handler on a dedicated thread and returns its outcome or rethrows its failure. The heartbeat
         * interrupts only that thread, so its interrupt never reaches, or leaks out of, the poll thread (#179); the
         * dedicated thread ends with the handler. An interrupt of the poll thread itself abandons the claim: the
         * handler is interrupted and waited for at most one lease duration.
         */
        private RunOutcome runHandler() throws Exception {
            AtomicReference<RunOutcome> outcome = new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread thread = Thread.ofPlatform().daemon().name("agentworks-run-" + workerId).unstarted(() -> {
                try {
                    outcome.set(handler.execute(this));
                } catch (Throwable thrown) {
                    failure.set(thrown);
                } finally {
                    synchronized (this) {
                        handlerReturned = true;
                    }
                }
            });
            handlerThread = thread;
            thread.start();
            long endedSince = -1;
            long slice = Math.max(1, Math.min(100, leaseDuration.toMillis()));
            while (thread.isAlive()) {
                try {
                    thread.join(slice);
                } catch (InterruptedException external) {
                    abandoned = true;
                    synchronized (this) {
                        if (!handlerReturned) thread.interrupt();
                    }
                    thread.join(leaseDuration);
                    throw new Abandoned();
                }
                if (ended && thread.isAlive()) {
                    long now = System.nanoTime();
                    if (endedSince < 0) {
                        endedSince = now;
                    } else if (now - endedSince > leaseDuration.toNanos()) {
                        abandoned = true;
                        throw new Unresponsive();
                    }
                }
            }
            Throwable thrown = failure.get();
            if (thrown instanceof Exception exception) throw exception;
            // A stack overflow is confined to the handler's own thread: it fails the run, never the worker (#34).
            if (thrown instanceof VirtualMachineError fatal && !(fatal instanceof StackOverflowError)) throw fatal;
            if (thrown instanceof Error error) throw new HandlerError(error);
            if (thrown != null) throw new IllegalStateException(thrown);
            return outcome.get();
        }

        private void accept(WriteResult result) {
            if (result.ended()) {
                endedByLimit();
                throw DurableRunEngine.limitExceeded();
            }
            lease = result.lease();
            suspended = "SUSPENDED".equals(result.cohortState());
            observed(result.pauseRequested());
        }

        /** A handler write failed the run for its limits; the first ending wins. */
        private synchronized void endedByLimit() {
            if (!ended) {
                failedByLimit = true;
                ended = true;
            }
        }

        private void heartbeat() {
            if (ended || pausedByRequest) return;
            effectGate.lock();
            try {
                // The handler has returned: whatever it reported is the outcome, so this beat does nothing.
                if (ended || stopped) return;
                // The database extends the expiry; the holder's write token keeps its row version. The gate is held
                // across the call, so an effect either started before this beat (and defers the limit) or after it.
                DurableRunEngine.Renewal renewal = engine.renew(lease, leaseDuration,
                        effectsInFlight > 0 ? effectRuntimeGrace : Duration.ZERO, resumableMidExchange);
                // A handler that writes nothing between boundaries still observes a request within one beat (#194).
                // A beat that read the request before a withdrawal costs at most one extra boundary check.
                observed(renewal.pauseRequested());
            } catch (RunEngineException failure) {
                switch (failure.code()) {
                    // The heartbeat failed the run for its runtime limit: end the execution (#175).
                    case "LIMIT_EXCEEDED" -> end(true);
                    // The run is no longer this holder's (cancelled, terminal, or claimed by another worker): every
                    // later write would fail as stale, so stop instead of heartbeating a lease that is gone. The
                    // boundary sets pausedByRequest before it clears pausing, so reading pausing first leaves no
                    // window in which a pause this execution made looks like a lost lease.
                    case "RUN_TERMINAL", "NOT_FOUND" -> {
                        if (!pausing && !pausedByRequest) end(false);
                    }
                    case "STALE_LEASE" -> {
                        // A pause this execution made, or is making, released the lease: the beat is simply late.
                        if (!pausing && !pausedByRequest && !(failure.getCause() instanceof RunStoreConflictException)) {
                            end(false);
                        }
                    }
                    default -> {
                        // Anything else may be temporary; the next beat tries again.
                    }
                }
            } catch (RuntimeException transientFailure) {
                // Database unavailability: the next beat tries again, and a lapsed lease fails the next write.
            } finally {
                effectGate.unlock();
            }
        }

        /**
         * Ends the execution from the heartbeat thread: no more beats, and the handler is interrupted. The first
         * ending wins, so a lost lease observed after a limit failure never downgrades the outcome.
         */
        private void end(boolean byLimit) {
            ScheduledFuture<?> current = beat;
            if (current != null) current.cancel(false);
            synchronized (this) {
                if (ended) return;
                failedByLimit = byLimit;
                ended = true;
                Thread thread = handlerThread;
                if (!handlerReturned && thread != null) thread.interrupt();
            }
        }

        /**
         * Runs on the poll thread once the handler returned or threw. It stops further beats and waits out a beat
         * already in flight (the beat holds {@link #effectGate}), so the first ending wins before the outcome is
         * recorded. The wait is bounded by one lease duration, so a hung heartbeat cannot hold the worker (#179).
         */
        private void stopBeating() {
            stopped = true;
            ScheduledFuture<?> current = beat;
            if (current != null) current.cancel(false);
            synchronized (this) {
                handlerReturned = true;
            }
            if (effectGate.isHeldByCurrentThread()) return;
            boolean interrupted = false;
            try {
                if (effectGate.tryLock(leaseDuration.toMillis(), TimeUnit.MILLISECONDS)) effectGate.unlock();
            } catch (InterruptedException external) {
                interrupted = true;
            }
            if (interrupted) Thread.currentThread().interrupt();
        }

        private Poll endedPoll() {
            return failedByLimit || pausedByRequest || delegated ? Poll.EXECUTED : Poll.LOST;
        }

        private Poll finish(RunOutcome outcome) {
            if (pausedByRequest) return Poll.EXECUTED;
            switch (outcome) {
                case RunOutcome.Completed completed -> engine.complete(lease, completed.output());
                case RunOutcome.Blocked blocked -> engine.block(lease, blocked.reason(), blocked.detail());
                case RunOutcome.Interaction interaction -> {
                    if (interactions == null) {
                        engine.fail(lease, "INTERACTION_UNAVAILABLE",
                                "the worker has no durable human-interaction authority");
                    } else {
                        interactions.block(lease, interaction.draft());
                    }
                }
                case RunOutcome.Paused paused when DurableRunEngine.OPERATOR_PAUSE.equals(paused.reason()) -> {
                    // The handler stopped for a request it observed but did not pause through the context: pause
                    // only if the request still stands. A resume withdrew it otherwise, and the handler already
                    // stopped, so the claim is left for recovery to continue the run from its last checkpoint.
                    if (engine.pauseIfRequested(lease).isEmpty()) return Poll.LOST;
                }
                case RunOutcome.Paused paused -> engine.pause(lease, paused.reason());
                case RunOutcome.Failed failed -> engine.fail(lease, failed.code(), failed.message());
                case RunOutcome.Delegated ignored -> { /* coordinator already blocked and released the lease */ }
            }
            return Poll.EXECUTED;
        }

        private Poll onEngineFailure(RunEngineException failure) {
            if (pausedByRequest || ended || "LIMIT_EXCEEDED".equals(failure.code())) return Poll.EXECUTED;
            return switch (failure.code()) {
                case "STALE_LEASE", "RUN_TERMINAL", "NOT_FOUND" -> Poll.LOST;
                case "COHORT_NOT_ADMITTING" -> String.valueOf(failure.getMessage()).contains("SUSPENDED")
                        ? pauseWith("COHORT_SUSPENDED") : terminate(lease, "COHORT_NOT_ADMITTING", failure.getMessage());
                // Transient database contention surfaced by the guards: let the lease lapse and recovery retry.
                case "COHORT_STATE_CHANGED" -> Poll.LOST;
                default -> terminate(lease, failure.code(), failure.getMessage());
            };
        }

        /** Pauses the run at this boundary; a run that ended meanwhile (or went past a limit) keeps that outcome. */
        private Poll pauseWith(String reason) {
            try {
                engine.pause(lease, reason);
                return Poll.EXECUTED;
            } catch (RunEngineException limit) {
                return "LIMIT_EXCEEDED".equals(limit.code()) ? Poll.EXECUTED : Poll.LOST;
            } catch (RuntimeException lost) {
                return Poll.LOST;
            }
        }

        private Poll terminate(RunLease current, String code, String message) {
            try {
                String bounded = message == null ? null : message.substring(0, Math.min(message.length(), 1024));
                engine.fail(current, code.matches("[A-Z][A-Z0-9_]{0,63}") ? code : "HANDLER_FAILED", bounded);
                return Poll.EXECUTED;
            } catch (RuntimeException lost) {
                return Poll.LOST;
            }
        }
    }
}
