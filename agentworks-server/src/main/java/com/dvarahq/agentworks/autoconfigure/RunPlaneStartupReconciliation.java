package com.dvarahq.agentworks.autoconfigure;

import com.dvarahq.agentworks.controlplane.deployment.ControlPlaneOutboxDispatcher;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import com.dvarahq.agentworks.runplane.reconciliation.DeploymentReconciler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Run Plane startup reconciliation (ADR-008, #175): once the context starts, every deployment is announced with its
 * current generation and the {@link DeploymentReconciler} re-reads each one, because outbox rows this node's consumer
 * already received are not redelivered after a restart.
 *
 * <p>All work runs on a background thread, so {@link #start()} returns at once and a slow or unreachable database
 * never delays startup or readiness. Observations are a non-authoritative cache that no run reads for correctness:
 * <ul>
 *   <li>until the deployments can be listed, listing is retried with exponential backoff (from
 *       {@code initialBackoff} up to {@code maxBackoff}) until it succeeds or the context stops;</li>
 *   <li>after that, only the deployments that failed to reconcile are retried, with the same backoff, for at most
 *       {@value #MAX_DEPLOYMENT_RETRIES} retries. A deployment still failing then is reported once at ERROR and left to
 *       its next notification and to outbox recovery (#44).</li>
 * </ul>
 * Disable it with {@code agentworks.runs.startup-reconciliation.enabled=false}.
 */
public final class RunPlaneStartupReconciliation implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(RunPlaneStartupReconciliation.class);

    /** Starts before the Run Plane worker pool ({@link RunWorkerLifecycle#PHASE}) and stops after it. */
    public static final int PHASE = Integer.MAX_VALUE - 4096;

    public static final Duration DEFAULT_INITIAL_BACKOFF = Duration.ofSeconds(1);
    public static final Duration DEFAULT_MAX_BACKOFF = Duration.ofMinutes(1);
    /** Retries of the deployments that failed the first reconciliation before they are given up. */
    public static final int MAX_DEPLOYMENT_RETRIES = 10;

    private record Target(UUID workspaceId, UUID deploymentId) {}

    private final ControlPlaneOutboxDispatcher dispatcher;
    private final DeploymentReconciler reconciler;
    private final Duration initialBackoff;
    private final Duration maxBackoff;
    private final Object lifecycle = new Object();
    private final AtomicInteger passes = new AtomicInteger();
    /** Guarded by {@link #lifecycle}. */
    private ScheduledExecutorService worker;
    private volatile boolean running;
    private volatile boolean completed;
    private volatile boolean abandoned;
    private volatile int announced = -1;
    /**
     * Serializes passes. A pass left running by a stopped executor (it ignores interrupts while reading the database)
     * may overlap the first pass of a restart; the fields below are touched only while holding this lock (#179).
     */
    private final java.util.concurrent.locks.ReentrantLock passing = new java.util.concurrent.locks.ReentrantLock();
    /** Guarded by {@link #passing}: null until listing succeeds, then the deployments still to retry. */
    private Set<Target> failed;
    /** Guarded by {@link #passing}. */
    private int retries;
    /** Guarded by {@link #passing}. */
    private boolean listingWarned;

    public RunPlaneStartupReconciliation(ControlPlaneOutboxDispatcher dispatcher, DeploymentReconciler reconciler) {
        this(dispatcher, reconciler, DEFAULT_INITIAL_BACKOFF, DEFAULT_MAX_BACKOFF);
    }

    public RunPlaneStartupReconciliation(ControlPlaneOutboxDispatcher dispatcher, DeploymentReconciler reconciler,
            Duration initialBackoff, Duration maxBackoff) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.reconciler = Objects.requireNonNull(reconciler, "reconciler");
        this.initialBackoff = Objects.requireNonNull(initialBackoff, "initialBackoff");
        this.maxBackoff = Objects.requireNonNull(maxBackoff, "maxBackoff");
        if (initialBackoff.isNegative() || initialBackoff.isZero() || maxBackoff.compareTo(initialBackoff) < 0) {
            throw new IllegalArgumentException("backoff must be positive and maxBackoff at least initialBackoff");
        }
    }

    /** Schedules the first pass immediately on a background thread and returns without waiting for it. */
    @Override
    public void start() {
        synchronized (lifecycle) {
            if (running) return;
            running = true;
            if (completed || abandoned) return;
            worker = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "agentworks-startup-reconciliation");
                thread.setDaemon(true);
                return thread;
            });
            schedule(worker, Duration.ZERO, initialBackoff);
        }
    }

    /**
     * Schedules one attempt on {@code executor}. Each retry chain belongs to the executor that started it: an attempt
     * of an executor that a stop replaced neither runs nor schedules another, so a stop and restart never leaves two
     * chains running (#179). Guarded by {@link #lifecycle}.
     */
    private void schedule(ScheduledExecutorService executor, Duration delay, Duration nextDelay) {
        executor.schedule(() -> {
            if (!current(executor)) return;
            passes.incrementAndGet();
            boolean done;
            passing.lock();
            try {
                if (!current(executor)) return;
                done = pass();
            } finally {
                passing.unlock();
            }
            if (done) return;
            synchronized (lifecycle) {
                if (running && worker == executor) schedule(executor, nextDelay, next(nextDelay));
            }
        }, delay.toMillis(), TimeUnit.MILLISECONDS);
    }

    private boolean current(ScheduledExecutorService executor) {
        synchronized (lifecycle) {
            return running && worker == executor;
        }
    }

    private Duration next(Duration delay) {
        Duration doubled = delay.multipliedBy(2);
        return doubled.compareTo(maxBackoff) > 0 ? maxBackoff : doubled;
    }

    /** One attempt; true when no further attempt is needed (completed, or the remaining deployments were given up). */
    private boolean pass() {
        // A pass left over from before a restart may already have completed or given up the reconciliation.
        if (completed || abandoned) return true;
        if (failed == null) return list();
        Set<Target> still = new LinkedHashSet<>();
        for (Target target : failed) {
            if (!reconcile(target, false)) still.add(target);
        }
        failed = still;
        if (still.isEmpty()) return complete();
        if (++retries < MAX_DEPLOYMENT_RETRIES) return false;
        abandoned = true;
        log.error("Run Plane startup reconciliation gave up on {} deployments after {} retries: {}; they are observed "
                + "on their next notification", still.size(), retries, still.stream().map(Target::deploymentId).toList());
        return true;
    }

    private boolean list() {
        Set<Target> failures = new LinkedHashSet<>();
        int count;
        try {
            count = dispatcher.announceDeployments(notification -> {
                Target target = new Target(notification.workspaceId(), notification.deploymentId());
                // One deployment's failure never keeps the rest of the pass from reconciling.
                if (!reconcile(target, true)) failures.add(target);
            });
        } catch (RuntimeException unavailable) {
            if (!listingWarned) {
                listingWarned = true;
                log.warn("Run Plane startup reconciliation cannot list deployments yet; retrying with backoff",
                        unavailable);
            } else {
                log.debug("Run Plane startup reconciliation still cannot list deployments", unavailable);
            }
            return false;
        }
        announced = count;
        failed = failures;
        return failures.isEmpty() ? complete() : false;
    }

    private boolean reconcile(Target target, boolean first) {
        try {
            reconciler.reconcile(new WorkspaceId(target.workspaceId()), target.deploymentId());
            return true;
        } catch (RuntimeException failure) {
            if (first) {
                log.warn("Run Plane startup reconciliation could not re-read deployment {}; retrying",
                        target.deploymentId(), failure);
            } else {
                log.debug("Run Plane startup reconciliation retry of deployment {} failed", target.deploymentId(),
                        failure);
            }
            return false;
        }
    }

    private boolean complete() {
        completed = true;
        log.info("Run Plane startup reconciliation observed {} deployments", announced);
        return true;
    }

    @Override
    public void stop() {
        synchronized (lifecycle) {
            running = false;
            if (worker != null) {
                worker.shutdownNow();
                worker = null;
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }

    /** True once every deployment was reconciled. */
    public boolean completed() {
        return completed;
    }

    /** True when some deployments still failed after {@value #MAX_DEPLOYMENT_RETRIES} retries and were given up. */
    public boolean abandoned() {
        return abandoned;
    }

    /** Deployments listed by the first successful listing, or -1 before one succeeds. */
    public int announced() {
        return announced;
    }

    /** Attempts made so far, including retries. */
    public int passes() {
        return passes.get();
    }
}
