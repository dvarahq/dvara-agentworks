package com.dvarahq.agentworks.autoconfigure;

import com.dvarahq.agentworks.runplane.compiled.CompiledReleaseCache;
import com.dvarahq.agentworks.runplane.compiled.SharedResourceRegistry;
import org.springframework.context.SmartLifecycle;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Deterministic idle expiry and failed-close retry, independent of request traffic. */
public final class SharedResourceMaintenanceLifecycle implements SmartLifecycle {
    private final SharedResourceRegistry resources;
    private final CompiledReleaseCache cache;
    private final Duration interval;
    private volatile boolean running;
    private ScheduledExecutorService worker;

    public SharedResourceMaintenanceLifecycle(SharedResourceRegistry resources, CompiledReleaseCache cache,
            Duration interval) {
        this.resources = Objects.requireNonNull(resources, "resources");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.interval = Objects.requireNonNull(interval, "interval");
        if (interval.isZero() || interval.isNegative()) throw new IllegalArgumentException("interval must be positive");
    }

    @Override public synchronized void start() {
        if (running) return;
        worker = Executors.newSingleThreadScheduledExecutor(task -> Thread.ofPlatform().daemon()
                .name("agentworks-resource-maintenance").unstarted(task));
        worker.scheduleWithFixedDelay(this::maintain, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
        running = true;
    }

    void maintain() {
        try { resources.maintain(); } catch (RuntimeException ignored) { }
        try { cache.retryCleanup(); } catch (RuntimeException ignored) { }
    }

    @Override public synchronized void stop() {
        running = false;
        if (worker != null) worker.shutdownNow();
    }
    @Override public boolean isRunning() { return running; }
}
