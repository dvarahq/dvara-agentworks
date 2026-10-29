package com.dvarahq.agentworks.autoconfigure;

import com.dvarahq.agentworks.artifact.ArtifactLifecycle;
import com.dvarahq.agentworks.domain.artifact.ArtifactMaintenanceReport;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Runs restart reconciliation immediately and bounded artifact expiry/integrity/GC passes thereafter. */
public final class ArtifactMaintenanceLifecycle implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(ArtifactMaintenanceLifecycle.class);

    private final ArtifactLifecycle artifacts;
    private final Duration interval;
    private final int batchSize;
    private final MeterRegistry metrics;
    private final Object monitor = new Object();
    private ScheduledExecutorService worker;
    private volatile boolean running;

    public ArtifactMaintenanceLifecycle(ArtifactLifecycle artifacts, Duration interval, int batchSize) {
        this(artifacts, interval, batchSize, null);
    }

    public ArtifactMaintenanceLifecycle(ArtifactLifecycle artifacts, Duration interval, int batchSize,
            MeterRegistry metrics) {
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
        this.interval = Objects.requireNonNull(interval, "interval");
        if (interval.isZero() || interval.isNegative() || interval.toMillis() < 1) {
            throw new IllegalArgumentException("artifact maintenance interval must be at least one millisecond");
        }
        if (batchSize < 1 || batchSize > 10_000) {
            throw new IllegalArgumentException("artifact maintenance batch size is invalid");
        }
        this.batchSize = batchSize;
        this.metrics = metrics;
    }

    @Override
    public void start() {
        synchronized (monitor) {
            if (running) return;
            running = true;
            worker = Executors.newSingleThreadScheduledExecutor(task ->
                    Thread.ofPlatform().daemon().name("agentworks-artifact-maintenance").unstarted(task));
            worker.scheduleWithFixedDelay(this::pass, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    private void pass() {
        try {
            ArtifactMaintenanceReport result = artifacts.maintain(batchSize);
            increment("agentworks.artifacts.maintenance.passes", "outcome", "success", 1);
            increment("agentworks.artifacts.maintenance.actions", "action", "stale_upload_removed",
                    result.staleUploadsRemoved());
            increment("agentworks.artifacts.maintenance.actions", "action", "metadata_expired",
                    result.metadataExpired());
            increment("agentworks.artifacts.maintenance.actions", "action", "metadata_deleted",
                    result.metadataDeleted());
            increment("agentworks.artifacts.maintenance.actions", "action", "content_removed",
                    result.contentObjectsRemoved());
            increment("agentworks.artifacts.maintenance.actions", "action", "integrity_failure",
                    result.integrityFailures());
            if (result.staleUploadsRemoved() + result.metadataExpired() + result.metadataDeleted()
                    + result.contentObjectsRemoved() + result.integrityFailures() > 0) {
                log.info("Artifact maintenance result: {}", result);
            }
        } catch (RuntimeException failure) {
            increment("agentworks.artifacts.maintenance.passes", "outcome", "failure", 1);
            log.warn("Artifact maintenance failed; durable state will be retried", failure);
        }
    }

    private void increment(String name, String tag, String value, int amount) {
        if (metrics != null && amount > 0) metrics.counter(name, tag, value).increment(amount);
    }

    @Override
    public void stop() {
        synchronized (monitor) {
            running = false;
            if (worker != null) {
                worker.shutdownNow();
                worker = null;
            }
        }
    }

    @Override public boolean isRunning() { return running; }
}
