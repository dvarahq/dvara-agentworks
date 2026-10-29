package com.dvarahq.agentworks.autoconfigure;

import com.dvarahq.agentworks.operation.DurableOperationAuthorityException;
import com.dvarahq.agentworks.runplane.execution.DurableOperationCoordinator;
import com.dvarahq.agentworks.storage.operation.JdbcDurableOperationAuthority;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Replays uncertain starts and polls running durable operations after restart without occupying a run worker. */
public final class DurableOperationReconciliationLifecycle implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(DurableOperationReconciliationLifecycle.class);

    public static final int PHASE = RunPlaneStartupReconciliation.PHASE + 256;
    public static final Duration DEFAULT_INTERVAL = Duration.ofSeconds(5);

    private final DurableOperationCoordinator coordinator;
    private final JdbcDurableOperationAuthority operations;
    private final Duration interval;
    private final int batchSize;
    private final MeterRegistry metrics;
    private final Object lifecycle = new Object();
    private ScheduledExecutorService worker;
    private volatile boolean running;

    public DurableOperationReconciliationLifecycle(DurableOperationCoordinator coordinator,
            JdbcDurableOperationAuthority operations, Duration interval, int batchSize) {
        this(coordinator, operations, interval, batchSize, null);
    }

    public DurableOperationReconciliationLifecycle(DurableOperationCoordinator coordinator,
            JdbcDurableOperationAuthority operations, Duration interval, int batchSize, MeterRegistry metrics) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.operations = Objects.requireNonNull(operations, "operations");
        this.interval = Objects.requireNonNull(interval, "interval");
        if (interval.isZero() || interval.isNegative() || interval.toMillis() < 1) {
            throw new IllegalArgumentException("operation reconciliation interval must be at least one millisecond");
        }
        if (batchSize < 1 || batchSize > 1000) {
            throw new IllegalArgumentException("operation reconciliation batch size must be 1 to 1000");
        }
        this.batchSize = batchSize;
        this.metrics = metrics;
    }

    @Override
    public void start() {
        synchronized (lifecycle) {
            if (running) return;
            running = true;
            worker = Executors.newSingleThreadScheduledExecutor(task -> Thread.ofPlatform().daemon()
                    .name("agentworks-operation-reconciliation").unstarted(task));
            worker.scheduleWithFixedDelay(this::pass, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    void pass() {
        Timer.Sample sample = metrics == null ? null : Timer.start(metrics);
        String outcome = "success";
        try {
            int reconciled = 0;
            for (var candidate : operations.reconciliationCandidates(batchSize)) {
                try {
                    coordinator.reconcile(candidate.workspace(), candidate.operationId());
                    reconciled++;
                } catch (DurableOperationAuthorityException race) {
                    if (race.code() != DurableOperationAuthorityException.Code.ALREADY_TERMINAL) {
                        outcome = "partial";
                        log.warn("Durable operation {} reconciliation failed; durable state will be retried",
                                candidate.operationId(), race);
                    }
                } catch (RuntimeException failure) {
                    outcome = "partial";
                    log.warn("Durable operation {} reconciliation failed; durable state will be retried",
                            candidate.operationId(), failure);
                }
            }
            if (reconciled > 0) log.info("Durable operation reconciliation observed {} operations", reconciled);
        } catch (RuntimeException failure) {
            outcome = "failure";
            log.warn("Durable operation reconciliation scan failed; durable state will be retried", failure);
        } finally {
            if (metrics != null) {
                metrics.counter("agentworks.operation.reconciliation.passes", "outcome", outcome).increment();
                sample.stop(metrics.timer("agentworks.operation.reconciliation.duration", "outcome", outcome));
            }
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
