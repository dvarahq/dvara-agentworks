package com.dvarahq.agentworks.autoconfigure;

import com.dvarahq.agentworks.runplane.execution.DurableChildRunCoordinator;
import com.dvarahq.agentworks.runplane.execution.ScheduledWakeupCoordinator;
import com.dvarahq.agentworks.storage.child.JdbcChildRunAuthority;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Restart-safe polling for terminal children and database-time scheduled wakeups. */
public final class ChildRunWakeupReconciliationLifecycle implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(ChildRunWakeupReconciliationLifecycle.class);
    public static final int PHASE = DurableOperationReconciliationLifecycle.PHASE + 1;
    public static final Duration DEFAULT_INTERVAL = Duration.ofSeconds(1);

    private final DurableChildRunCoordinator children;
    private final ScheduledWakeupCoordinator wakeups;
    private final JdbcChildRunAuthority authority;
    private final Duration interval;
    private final int batchSize;
    private final Object monitor = new Object();
    private ScheduledExecutorService worker;
    private volatile boolean running;
    private final WorkspaceScan scan = new WorkspaceScan();

    public ChildRunWakeupReconciliationLifecycle(DurableChildRunCoordinator children,
            ScheduledWakeupCoordinator wakeups, JdbcChildRunAuthority authority, Duration interval, int batchSize) {
        this.children = Objects.requireNonNull(children, "children");
        this.wakeups = Objects.requireNonNull(wakeups, "wakeups");
        this.authority = Objects.requireNonNull(authority, "authority");
        this.interval = Objects.requireNonNull(interval, "interval");
        if (interval.isNegative() || interval.isZero() || interval.toMillis() < 1) {
            throw new IllegalArgumentException("child reconciliation interval must be at least one millisecond");
        }
        if (batchSize < 1 || batchSize > 1000) throw new IllegalArgumentException("batch size must be 1 to 1000");
        this.batchSize = batchSize;
    }

    @Override public void start() {
        synchronized (monitor) {
            if (running) return;
            running = true;
            worker = Executors.newSingleThreadScheduledExecutor(task -> Thread.ofPlatform().daemon()
                    .name("agentworks-child-wakeup-reconciliation").unstarted(task));
            worker.scheduleWithFixedDelay(this::pass, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    void pass() {
        try {
            var candidates = scan.next(authority::reconciliationCandidates, batchSize);
            for (var candidate : candidates) {
                try {
                    if (candidate.children()) children.reconcile(candidate.workspace(), batchSize);
                    if (candidate.dueWakeups()) {
                        authority.cancelTerminalWakeups(candidate.workspace(), batchSize);
                        for (int delivered = 0; delivered < batchSize && authority.hasDueWakeup(candidate.workspace())
                                && wakeups.deliverOne(candidate.workspace()); delivered++) {
                            // bounded drain; every delivery has its own all-or-nothing transaction
                        }
                    }
                } catch (RuntimeException failure) {
                    log.warn("Child/wakeup reconciliation for workspace {} failed; durable state will be retried",
                            candidate.workspace().value(), failure);
                }
                scan.advanced(candidate.workspace().value());
            }
        } catch (RuntimeException failure) {
            log.warn("Child/wakeup reconciliation scan failed; durable state will be retried", failure);
        }
    }

    @Override public void stop() {
        synchronized (monitor) {
            running = false;
            if (worker != null) { worker.shutdownNow(); worker = null; }
        }
    }
    @Override public boolean isRunning() { return running; }
    @Override public int getPhase() { return PHASE; }

    /** Keyset state kept independently from a pass, so a small batch cannot pin polling to early workspaces. */
    static final class WorkspaceScan {
        private java.util.UUID afterWorkspace;
        java.util.List<JdbcChildRunAuthority.ReconciliationCandidate> next(
                java.util.function.BiFunction<java.util.UUID, Integer,
                        java.util.List<JdbcChildRunAuthority.ReconciliationCandidate>> query,
                int limit) {
            var candidates = query.apply(afterWorkspace, limit);
            if (candidates.isEmpty() && afterWorkspace != null) {
                afterWorkspace = null;
                candidates = query.apply(null, limit);
            }
            return candidates;
        }
        void advanced(java.util.UUID workspace) { afterWorkspace = workspace; }
    }
}
