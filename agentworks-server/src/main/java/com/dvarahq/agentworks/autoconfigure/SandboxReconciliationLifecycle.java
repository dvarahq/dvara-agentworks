package com.dvarahq.agentworks.autoconfigure;

import com.dvarahq.agentworks.sandbox.SandboxCoordinator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Runs sandbox startup convergence before run workers and continues expiry/leak cleanup at a bounded cadence. */
public final class SandboxReconciliationLifecycle implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(SandboxReconciliationLifecycle.class);

    public static final int PHASE = RunPlaneStartupReconciliation.PHASE + 512;
    public static final Duration DEFAULT_INTERVAL = Duration.ofSeconds(5);

    private final SandboxCoordinator coordinator;
    private final Duration interval;
    private final Object lifecycle = new Object();
    private ScheduledExecutorService worker;
    private volatile boolean running;

    public SandboxReconciliationLifecycle(SandboxCoordinator coordinator, Duration interval) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.interval = Objects.requireNonNull(interval, "interval");
        if (interval.isZero() || interval.isNegative() || interval.toMillis() < 1) {
            throw new IllegalArgumentException("sandbox reconciliation interval must be at least one millisecond");
        }
    }

    @Override
    public void start() {
        synchronized (lifecycle) {
            if (running) return;
            running = true;
            worker = Executors.newSingleThreadScheduledExecutor(task ->
                    Thread.ofPlatform().daemon().name("agentworks-sandbox-reconciliation").unstarted(task));
            worker.scheduleWithFixedDelay(this::pass, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    private void pass() {
        try {
            SandboxCoordinator.ReconciliationResult result = coordinator.reconcile();
            if (result.activatedReservations() + result.expiredReservationsQueued() + result.missingResourcesQueued()
                    + result.orphanResourcesRetired() + result.cleanupSucceeded() + result.cleanupFailed()
                    + result.identityMismatches() > 0) {
                log.info("Sandbox reconciliation result: {}", result);
            }
        } catch (RuntimeException failure) {
            log.warn("Sandbox reconciliation pass failed; durable state will be retried", failure);
        }
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

    @Override public boolean isRunning() { return running; }
    @Override public int getPhase() { return PHASE; }
}
