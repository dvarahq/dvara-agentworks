package com.dvarahq.agentworks.autoconfigure;

import com.dvarahq.agentworks.domain.run.ClaimScope;
import com.dvarahq.agentworks.runplane.execution.DurableRunEngine;
import com.dvarahq.agentworks.runplane.execution.DurableHumanInteractionCoordinator;
import com.dvarahq.agentworks.runplane.execution.DurableChildRunCoordinator;
import com.dvarahq.agentworks.runplane.execution.ReleaseManifestReader;
import com.dvarahq.agentworks.runplane.execution.RunHandler;
import com.dvarahq.agentworks.runplane.execution.RunWorker;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The opt-in Run Plane worker pool (issue #34, {@code agentworks.runs.worker.enabled=true}): {@code threads} stateless
 * {@link RunWorker}s, each polling for any eligible run of any agent, release, or tenant on its own thread and
 * backing off by {@code idleBackoff} when there is nothing to do. Its workers claim only runs whose release pins an
 * executor type this node runs. It starts after startup reconciliation ({@link #PHASE} is later than
 * {@link RunPlaneStartupReconciliation#PHASE}) and stops before it.
 *
 * <p>A JVM failure ({@link VirtualMachineError}) in a poll is logged at ERROR and the thread keeps polling after a
 * backoff, so no failure silently shrinks the pool; failures confined to a run's handler thread fail that run.
 *
 * <p>Stopping interrupts the poll threads: a worker abandons its current claim without another write, and the lease
 * lapses so any healthy worker recovers the run from its last checkpoint. Stop waits at most one lease duration for
 * each thread.
 */
public final class RunWorkerLifecycle implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(RunWorkerLifecycle.class);

    /** Pool configuration. */
    public record Settings(int threads, String workerIdPrefix, Duration leaseDuration, Duration idleBackoff,
            Duration claimQuarantine, Duration effectRuntimeGrace) {
        public Settings {
            if (threads < 1 || threads > 256) throw new IllegalArgumentException("threads is 1 to 256");
            Objects.requireNonNull(workerIdPrefix, "workerIdPrefix");
            Objects.requireNonNull(leaseDuration, "leaseDuration");
            Objects.requireNonNull(idleBackoff, "idleBackoff");
            if (idleBackoff.isNegative() || idleBackoff.isZero()) {
                throw new IllegalArgumentException("idleBackoff must be positive");
            }
            Objects.requireNonNull(claimQuarantine, "claimQuarantine");
            Objects.requireNonNull(effectRuntimeGrace, "effectRuntimeGrace");
        }
    }

    /** Start after startup reconciliation and stop before it. */
    public static final int PHASE = RunPlaneStartupReconciliation.PHASE + 1024;

    private final DurableRunEngine engine;
    private final RunHandler handler;
    private final Set<String> executorTypes;
    private final Settings settings;
    private final DurableHumanInteractionCoordinator interactions;
    private final DurableChildRunCoordinator children;
    private final MeterRegistry metrics;
    private final Object lifecycle = new Object();
    /** The node's local claim state, shared by all its workers (#185). */
    private final RunWorker.NodeClaimState node = new RunWorker.NodeClaimState();
    /** Guarded by {@link #lifecycle}. */
    private final List<Thread> threads = new ArrayList<>();
    /** Guarded by {@link #lifecycle}. */
    private final List<RunWorker> workers = new ArrayList<>();
    private volatile boolean running;
    /** Each start is a new generation; a thread of an earlier generation never polls again, even after a restart. */
    private volatile long generation;

    /**
     * @param executorTypes the executor types {@code handler} runs; a worker claims only runs pinned to one of them
     * @throws IllegalArgumentException when there is no executor type, or the configuration cannot make a worker
     */
    public RunWorkerLifecycle(DurableRunEngine engine, RunHandler handler, Set<String> executorTypes, Settings settings) {
        this(engine, handler, executorTypes, settings, null);
    }

    public RunWorkerLifecycle(DurableRunEngine engine, RunHandler handler, Set<String> executorTypes, Settings settings,
            DurableHumanInteractionCoordinator interactions) {
        this(engine, handler, executorTypes, settings, interactions, null);
    }

    public RunWorkerLifecycle(DurableRunEngine engine, RunHandler handler, Set<String> executorTypes, Settings settings,
            DurableHumanInteractionCoordinator interactions, DurableChildRunCoordinator children) {
        this(engine, handler, executorTypes, settings, interactions, children, null);
    }

    public RunWorkerLifecycle(DurableRunEngine engine, RunHandler handler, Set<String> executorTypes, Settings settings,
            DurableHumanInteractionCoordinator interactions, DurableChildRunCoordinator children,
            MeterRegistry metrics) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.handler = Objects.requireNonNull(handler, "handler");
        this.executorTypes = Set.copyOf(Objects.requireNonNull(executorTypes, "executorTypes"));
        if (this.executorTypes.isEmpty()) {
            throw new IllegalArgumentException("the Run Plane worker is enabled but no executor can run any run; "
                    + "configure an AgentExecutor or disable agentworks.runs.worker.enabled");
        }
        this.settings = Objects.requireNonNull(settings, "settings");
        this.interactions = interactions;
        this.children = children;
        this.metrics = metrics;
        // Fail at construction, not on the first poll, if the configuration cannot make a worker.
        new RunWorker(engine, handler, settings.workerIdPrefix() + "-0", scope(), settings.leaseDuration(),
                settings.claimQuarantine(), settings.effectRuntimeGrace(), new RunWorker.NodeClaimState(),
                interactions, children).close();
    }

    private ClaimScope scope() {
        return ClaimScope.any(ReleaseManifestReader.SUPPORTED_VERSIONS).executing(executorTypes);
    }

    @Override
    public void start() {
        synchronized (lifecycle) {
            if (running) return;
            running = true;
            long current = ++generation;
            for (int index = 0; index < settings.threads(); index++) {
                RunWorker worker = new RunWorker(engine, handler, settings.workerIdPrefix() + "-" + index, scope(),
                        settings.leaseDuration(), settings.claimQuarantine(), settings.effectRuntimeGrace(), node,
                        interactions, children);
                workers.add(worker);
                threads.add(Thread.ofPlatform().name("agentworks-run-worker-" + index)
                        .start(() -> poll(worker, current)));
            }
        }
        log.info("Run Plane worker pool started with {} workers ({})", settings.threads(), settings.workerIdPrefix());
    }

    private void poll(RunWorker worker, long mine) {
        while (running && generation == mine && !Thread.currentThread().isInterrupted()) {
            RunWorker.Poll poll;
            Timer.Sample sample = metrics == null ? null : Timer.start(metrics);
            try {
                poll = worker.pollOnce();
            } catch (VirtualMachineError fatal) {
                // Loud, and the thread survives: a pool never shrinks silently. Its claim, if any, is recovered after
                // its lease lapses.
                log.error("Run worker poll failed with a JVM failure; backing off", fatal);
                poll = RunWorker.Poll.RETRY_LATER;
            } catch (Throwable unexpected) {
                // Nothing a run does may silently end a pool thread.
                log.warn("Run worker poll failed; backing off", unexpected);
                poll = RunWorker.Poll.RETRY_LATER;
            }
            if (metrics != null) {
                String outcome = poll.name().toLowerCase(java.util.Locale.ROOT);
                metrics.counter("agentworks.run.worker.polls", "outcome", outcome).increment();
                sample.stop(metrics.timer("agentworks.run.worker.poll.duration", "outcome", outcome));
                if (poll == RunWorker.Poll.LOST) metrics.counter("agentworks.run.lease.losses").increment();
            }
            if (poll == RunWorker.Poll.EXECUTED || poll == RunWorker.Poll.LOST) continue;
            try {
                Thread.sleep(settings.idleBackoff());
            } catch (InterruptedException stopped) {
                return;
            }
        }
    }

    @Override
    public void stop() {
        List<Thread> stopping;
        List<RunWorker> closing;
        synchronized (lifecycle) {
            if (!running) return;
            running = false;
            stopping = List.copyOf(threads);
            closing = List.copyOf(workers);
            threads.clear();
            workers.clear();
        }
        stopping.forEach(Thread::interrupt);
        for (Thread thread : stopping) {
            try {
                if (!thread.join(settings.leaseDuration())) {
                    log.warn("Run worker thread {} did not stop within one lease; its run is recovered after the lease "
                            + "lapses", thread.getName());
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        closing.forEach(RunWorker::close);
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** Start last and stop first, so workers never run without the gateway, reconciliation, or the database. */
    @Override
    public int getPhase() {
        return PHASE;
    }

    public int workers() {
        synchronized (lifecycle) {
            return workers.size();
        }
    }
}
