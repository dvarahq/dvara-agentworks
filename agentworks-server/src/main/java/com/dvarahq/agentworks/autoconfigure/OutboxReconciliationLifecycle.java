package com.dvarahq.agentworks.autoconfigure;

import com.dvarahq.agentworks.controlplane.deployment.ControlPlaneOutboxDispatcher;
import com.dvarahq.agentworks.controlplane.deployment.ControlPlaneOutboxDispatcher.DispatchReport;
import com.dvarahq.agentworks.controlplane.deployment.ControlPlaneOutboxDispatcher.RetryPolicy;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import com.dvarahq.agentworks.runplane.reconciliation.DeploymentReconciler;
import com.dvarahq.agentworks.runplane.compiled.CompiledReleaseCache;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Continuously converges desired deployment state from durable outbox hints (#44). */
public final class OutboxReconciliationLifecycle implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(OutboxReconciliationLifecycle.class);

    public static final String DEFAULT_CONSUMER = "run-plane-deployments";
    public static final Duration DEFAULT_INTERVAL = Duration.ofSeconds(1);
    public static final int DEFAULT_BATCH_SIZE = 100;
    public static final int PHASE = RunPlaneStartupReconciliation.PHASE + 128;

    private final ControlPlaneOutboxDispatcher dispatcher;
    private final DeploymentReconciler reconciler;
    private final String consumer;
    private final Duration interval;
    private final int batchSize;
    private final RetryPolicy retry;
    private final MeterRegistry metrics;
    private final CompiledReleaseCache compiledReleases;
    private final Object lifecycle = new Object();
    private ScheduledExecutorService worker;
    private volatile boolean running;

    public OutboxReconciliationLifecycle(ControlPlaneOutboxDispatcher dispatcher, DeploymentReconciler reconciler,
            String consumer, Duration interval, int batchSize, RetryPolicy retry, MeterRegistry metrics) {
        this(dispatcher, reconciler, consumer, interval, batchSize, retry, metrics, null);
    }

    public OutboxReconciliationLifecycle(ControlPlaneOutboxDispatcher dispatcher, DeploymentReconciler reconciler,
            String consumer, Duration interval, int batchSize, RetryPolicy retry, MeterRegistry metrics,
            CompiledReleaseCache compiledReleases) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.reconciler = Objects.requireNonNull(reconciler, "reconciler");
        this.consumer = Objects.requireNonNull(consumer, "consumer");
        this.interval = Objects.requireNonNull(interval, "interval");
        this.retry = Objects.requireNonNull(retry, "retry");
        this.metrics = metrics;
        this.compiledReleases = compiledReleases;
        if (interval.isZero() || interval.isNegative() || interval.toMillis() < 1) {
            throw new IllegalArgumentException("outbox reconciliation interval must be at least one millisecond");
        }
        if (batchSize < 1 || batchSize > 1000) {
            throw new IllegalArgumentException("outbox reconciliation batch size must be 1 to 1000");
        }
        this.batchSize = batchSize;
        if (metrics != null) {
            metrics.gauge("agentworks.recovery.outbox.backlog", java.util.List.of(
                    io.micrometer.core.instrument.Tag.of("consumer", consumer)), dispatcher,
                    source -> safe(() -> source.pending(consumer)));
            metrics.gauge("agentworks.recovery.outbox.quarantined", java.util.List.of(
                    io.micrometer.core.instrument.Tag.of("consumer", consumer)), dispatcher,
                    source -> safe(() -> source.quarantined(consumer)));
            io.micrometer.core.instrument.Gauge.builder("agentworks.recovery.outbox.oldest.age", dispatcher,
                            source -> safe(() -> source.oldestPendingAgeSeconds(consumer)))
                    .tag("consumer", consumer).baseUnit("seconds").register(metrics);
        }
    }

    public String consumer() {
        return consumer;
    }

    @Override
    public void start() {
        synchronized (lifecycle) {
            if (running) return;
            running = true;
            worker = Executors.newSingleThreadScheduledExecutor(task -> Thread.ofPlatform().daemon()
                    .name("agentworks-outbox-reconciliation").unstarted(task));
            worker.scheduleWithFixedDelay(this::pass, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    void pass() {
        long started = System.nanoTime();
        try {
            DispatchReport result = dispatcher.dispatchRecovering(consumer, batchSize, retry, notification -> {
                if (notification.deploymentId() != null) {
                    reconciler.reconcile(new WorkspaceId(notification.workspaceId()), notification.deploymentId());
                    // The notification is only a hint. Reconciliation first re-reads PostgreSQL authority; only then
                    // does the node retire its local compiled overlay. Existing exact-pin leases remain valid.
                    if (compiledReleases != null) compiledReleases.deploymentChanged(
                            notification.workspaceId(), notification.deploymentId());
                }
                if (compiledReleases != null && notification.releaseId() != null
                        && java.util.Set.of("RELEASE_SUSPENDED", "RELEASE_REVOKED", "RELEASE_RETIRED")
                                .contains(notification.eventType())) {
                    compiledReleases.releaseRevoked(notification.workspaceId(), notification.releaseId());
                }
            });
            counter("agentworks.recovery.outbox.deliveries", "outcome", "delivered", result.delivered());
            counter("agentworks.recovery.outbox.deliveries", "outcome", "failed", result.failed());
            counter("agentworks.recovery.outbox.deliveries", "outcome", "quarantined", result.quarantined());
            counter("agentworks.recovery.outbox.passes", "outcome", "success", 1);
            if (result.failed() > 0) {
                log.warn("Outbox reconciliation delivered {}, failed {}, and quarantined {} notifications",
                        result.delivered(), result.failed(), result.quarantined());
            }
        } catch (RuntimeException failure) {
            counter("agentworks.recovery.outbox.passes", "outcome", "failure", 1);
            log.warn("Outbox reconciliation scan failed; durable state will be retried", failure);
        } finally {
            if (metrics != null) metrics.timer("agentworks.recovery.outbox.pass.duration", "consumer", consumer)
                    .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        }
    }

    private void counter(String name, String tag, String value, int amount) {
        if (metrics != null && amount > 0) metrics.counter(name, tag, value, "consumer", consumer).increment(amount);
    }

    private static double safe(java.util.function.DoubleSupplier measurement) {
        try {
            return measurement.getAsDouble();
        } catch (RuntimeException unavailable) {
            return Double.NaN;
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
