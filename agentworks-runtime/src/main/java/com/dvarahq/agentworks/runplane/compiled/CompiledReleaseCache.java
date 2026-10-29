package com.dvarahq.agentworks.runplane.compiled;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Bounded process-local cache of immutable compiled release material. */
public final class CompiledReleaseCache implements AutoCloseable {
    public enum State { ACTIVE, RETIRING, RETIRED }
    public record Key(CompiledReleaseKey release, DeploymentOverlayKey overlay) {
        public Key { Objects.requireNonNull(release, "release"); Objects.requireNonNull(overlay, "overlay"); }
    }
    public record Snapshot(long hits, long misses, long compilations, long compileFailures,
            long compileNanos, long retirements, long closeFailures, long forcedCleanup,
            int entries, int activeReferences, long oldestRetirementLagMillis) {}
    public record Cleanup(Key key, String reason, int activeReferences, Instant observedAt) {}
    @FunctionalInterface public interface CleanupSink { void record(Cleanup cleanup); }

    public static final class CacheException extends RuntimeException {
        private final String code;
        CacheException(String code, String message) { super(message); this.code = code; }
        public String code() { return code; }
    }

    public final class Lease implements AutoCloseable {
        private final Entry entry;
        private final AtomicBoolean released = new AtomicBoolean();
        private Lease(Entry entry) { this.entry = entry; }
        public CompiledRelease value() {
            synchronized (CompiledReleaseCache.this) {
                if (released.get() || entry.state == State.RETIRED) {
                    throw new CacheException("COMPILED_RELEASE_LEASE_CLOSED", "the compiled-release lease is closed");
                }
                return entry.value;
            }
        }
        public Key key() { return entry.key; }
        @Override public void close() { if (released.compareAndSet(false, true)) release(entry); }
    }

    private static final class Entry {
        private final Key key;
        private final CompletableFuture<CompiledRelease> ready = new CompletableFuture<>();
        private CompiledRelease value;
        private State state = State.ACTIVE;
        private int references;
        private Instant retiringAt;
        private String retirementReason;
        private boolean closingCallback;
        private Entry(Key key) { this.key = key; }
        private boolean creating() { return !ready.isDone(); }
    }
    private record CloseWork(Entry entry, String reason, int references) {}

    private final int maximumEntries;
    private final Duration shutdownGrace;
    private final Clock clock;
    private final CleanupSink cleanup;
    private final LinkedHashMap<Key, Entry> entries = new LinkedHashMap<>(16, .75f, true);
    private boolean closing;
    private long hits, misses, compilations, compileFailures, compileNanos, retirements, closeFailures, forcedCleanup;

    public CompiledReleaseCache(int maximumEntries) {
        this(maximumEntries, Duration.ofSeconds(30), Clock.systemUTC(), ignored -> {});
    }

    public CompiledReleaseCache(int maximumEntries, Duration shutdownGrace, Clock clock, CleanupSink cleanup) {
        if (maximumEntries < 1 || maximumEntries > 100_000) throw new IllegalArgumentException("invalid maximumEntries");
        this.maximumEntries = maximumEntries;
        this.shutdownGrace = Objects.requireNonNull(shutdownGrace, "shutdownGrace");
        if (shutdownGrace.isNegative() || shutdownGrace.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("shutdownGrace must be between zero and five minutes");
        }
        this.clock = Objects.requireNonNull(clock, "clock");
        this.cleanup = Objects.requireNonNull(cleanup, "cleanup");
    }

    public Lease acquire(Key key, Supplier<CompiledRelease> compiler) {
        Objects.requireNonNull(key, "key"); Objects.requireNonNull(compiler, "compiler");
        for (;;) {
            Entry entry;
            boolean owner = false;
            List<CloseWork> evicted = List.of();
            synchronized (this) {
                if (closing) throw new CacheException("COMPILED_CACHE_CLOSED", "the compiled-release cache is shutting down");
                entry = entries.get(key);
                if (entry == null) {
                    evicted = makeRoomLocked();
                    if (evicted.isEmpty()) {
                        entry = new Entry(key);
                        entries.put(key, entry);
                        misses++;
                        owner = true;
                    }
                } else if (!entry.creating()) {
                    if (entry.state == State.RETIRED) {
                        throw new CacheException("COMPILED_RELEASE_CLEANUP_PENDING",
                                "the previous compiled release has not finished cleanup");
                    }
                    if (entry.state == State.RETIRING) {
                        entry.state = State.ACTIVE;
                        entry.retiringAt = null;
                        entry.retirementReason = null;
                    }
                    hits++;
                    entry.references++;
                    return new Lease(entry);
                }
            }
            closeAll(evicted);
            if (!owner && !evicted.isEmpty()) continue;
            if (owner) return compile(entry, compiler);
            try { entry.ready.join(); }
            catch (CompletionException failed) {
                if (failed.getCause() instanceof RuntimeException runtime) throw runtime;
                throw failed;
            }
        }
    }

    private Lease compile(Entry entry, Supplier<CompiledRelease> compiler) {
        long started = System.nanoTime();
        CompiledRelease value;
        try { value = Objects.requireNonNull(compiler.get(), "compiler returned null"); }
        catch (RuntimeException failure) {
            synchronized (this) {
                entries.remove(entry.key, entry);
                compileFailures++;
                compileNanos += Math.max(0, System.nanoTime() - started);
                notifyAll();
            }
            entry.ready.completeExceptionally(failure);
            throw failure;
        }
        boolean discard;
        synchronized (this) {
            entry.value = value;
            entry.references = closing ? 0 : 1;
            compilations++;
            compileNanos += Math.max(0, System.nanoTime() - started);
            discard = closing || entries.get(entry.key) != entry;
            if (discard) {
                entry.state = State.RETIRING;
                entry.retirementReason = "NODE_SHUTDOWN";
            }
            notifyAll();
        }
        entry.ready.complete(value);
        if (discard) {
            CloseWork close;
            synchronized (this) { close = scheduleCloseLocked(entry); }
            closeAll(close == null ? List.of() : List.of(close));
            throw new CacheException("COMPILED_CACHE_CLOSED", "the compiled-release cache is shutting down");
        }
        return new Lease(entry);
    }

    public int retire(Predicate<Key> selector, String reason) {
        Objects.requireNonNull(selector, "selector");
        if (reason == null || reason.isBlank() || reason.length() > 128) throw new IllegalArgumentException("invalid reason");
        List<CloseWork> close = new ArrayList<>();
        int changed = 0;
        synchronized (this) {
            for (Entry entry : List.copyOf(entries.values())) {
                if (!entry.creating() && entry.state == State.ACTIVE && selector.test(entry.key)) {
                    CloseWork work = beginRetirementLocked(entry, reason);
                    if (work != null) close.add(work);
                    changed++;
                }
            }
        }
        closeAll(close);
        return changed;
    }

    public int deploymentChanged(UUID workspaceId, UUID deploymentId) {
        Objects.requireNonNull(workspaceId, "workspaceId"); Objects.requireNonNull(deploymentId, "deploymentId");
        return retire(key -> key.release().workspaceId().equals(workspaceId)
                && key.overlay().deploymentId().equals(deploymentId), "DEPLOYMENT_CHANGED");
    }
    public int releaseRevoked(UUID workspaceId, UUID releaseId) {
        Objects.requireNonNull(workspaceId, "workspaceId"); Objects.requireNonNull(releaseId, "releaseId");
        return retire(key -> key.release().workspaceId().equals(workspaceId)
                && key.release().releaseId().equals(releaseId), "RELEASE_REVOKED");
    }
    public int dependencyChanged(UUID workspaceId, String dependencyHash) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        String current = CompiledReleaseKey.digest(dependencyHash, "dependencyHash");
        return retire(key -> key.release().workspaceId().equals(workspaceId)
                && (!key.release().dependencyHash().equals(current)
                || !key.overlay().dependencyHash().equals(current)), "DEPENDENCY_CHANGED");
    }

    public synchronized Snapshot snapshot() {
        int references = entries.values().stream().mapToInt(entry -> entry.references).sum();
        Instant now = clock.instant();
        long lag = entries.values().stream().filter(entry -> entry.retiringAt != null)
                .mapToLong(entry -> Math.max(0, Duration.between(entry.retiringAt, now).toMillis())).max().orElse(0);
        return new Snapshot(hits, misses, compilations, compileFailures, compileNanos, retirements, closeFailures,
                forcedCleanup, entries.size(), references, lag);
    }
    public synchronized Map<Key, State> states() {
        Map<Key, State> result = new LinkedHashMap<>();
        entries.forEach((key, value) -> result.put(key, value.state));
        return Map.copyOf(result);
    }
    public int retryCleanup() {
        List<CloseWork> work = new ArrayList<>();
        synchronized (this) {
            for (Entry entry : entries.values()) {
                if (entry.state == State.RETIRED && !entry.closingCallback && !entry.creating()) {
                    CloseWork close = scheduleCloseLocked(entry);
                    if (close != null) work.add(close);
                }
            }
        }
        closeAll(work);
        return work.size();
    }

    private List<CloseWork> makeRoomLocked() {
        if (entries.size() < maximumEntries) return List.of();
        for (Entry entry : List.copyOf(entries.values())) {
            if (!entry.creating() && entry.state == State.ACTIVE && entry.references == 0) {
                CloseWork work = beginRetirementLocked(entry, "CAPACITY_EVICTION");
                return work == null ? List.of() : List.of(work);
            }
        }
        throw new CacheException("COMPILED_CACHE_FULL", "the bounded compiled-release cache has no unreferenced entry to retire");
    }

    private void release(Entry entry) {
        CloseWork close = null;
        synchronized (this) {
            if (entry.references <= 0) return;
            entry.references--;
            if (entry.references == 0 && entry.state == State.RETIRING) close = scheduleCloseLocked(entry);
            notifyAll();
        }
        if (close != null) closeAll(List.of(close));
    }
    private CloseWork beginRetirementLocked(Entry entry, String reason) {
        entry.state = State.RETIRING;
        entry.retiringAt = clock.instant();
        entry.retirementReason = reason;
        retirements++;
        return entry.references == 0 ? scheduleCloseLocked(entry) : null;
    }
    private CloseWork scheduleCloseLocked(Entry entry) {
        if (entry.closingCallback || entry.value == null) return null;
        entry.state = State.RETIRED;
        entry.closingCallback = true;
        return new CloseWork(entry, entry.retirementReason, entry.references);
    }
    private void closeAll(List<CloseWork> work) {
        for (CloseWork item : work) {
            try {
                item.entry.value.close();
                synchronized (this) {
                    item.entry.closingCallback = false;
                    entries.remove(item.entry.key, item.entry);
                    notifyAll();
                }
            }
            catch (Exception failure) {
                synchronized (this) { closeFailures++; item.entry.closingCallback = false; notifyAll(); }
                recordCleanup(new Cleanup(item.entry.key, "CLOSE_FAILED", item.references, clock.instant()));
            }
        }
    }
    private void recordCleanup(Cleanup record) {
        try { cleanup.record(record); }
        catch (RuntimeException unavailable) { synchronized (this) { closeFailures++; } }
    }

    @Override public void close() {
        List<CloseWork> close = new ArrayList<>();
        List<Cleanup> evidence = new ArrayList<>();
        List<Entry> shutdownClosures = new ArrayList<>();
        long deadline = System.nanoTime() + shutdownGrace.toNanos();
        synchronized (this) {
            if (closing) return;
            closing = true;
            for (Entry entry : List.copyOf(entries.values())) {
                if (!entry.creating() && entry.state == State.ACTIVE) {
                    CloseWork work = beginRetirementLocked(entry, "NODE_SHUTDOWN");
                    if (work != null) { close.add(work); shutdownClosures.add(entry); }
                }
            }
            while (entries.values().stream().anyMatch(entry -> entry.references > 0 || entry.creating())
                    || entries.values().stream().anyMatch(entry -> entry.closingCallback
                    && !shutdownClosures.contains(entry))) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) break;
                try { wait(Math.max(1, Math.min(Duration.ofNanos(remaining).toMillis(), 100))); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
            }
            for (Entry entry : new ArrayList<>(entries.values())) {
                if (entry.creating()) {
                    forcedCleanup++;
                    evidence.add(new Cleanup(entry.key, "CREATION_IN_FLIGHT", 0, clock.instant()));
                } else if (entry.closingCallback && !shutdownClosures.contains(entry)) {
                    forcedCleanup++;
                    evidence.add(new Cleanup(entry.key, "CLOSE_IN_FLIGHT", entry.references, clock.instant()));
                } else if (entry.references > 0) {
                    forcedCleanup++;
                    evidence.add(new Cleanup(entry.key, entry.retirementReason == null ? "NODE_SHUTDOWN"
                            : entry.retirementReason, entry.references, clock.instant()));
                    CloseWork work = scheduleCloseLocked(entry);
                    if (work != null) close.add(work);
                } else if (!entry.closingCallback) {
                    CloseWork work = scheduleCloseLocked(entry);
                    if (work != null) close.add(work);
                }
            }
        }
        evidence.forEach(this::recordCleanup);
        boundedClose(close, deadline);
    }
    private void boundedClose(List<CloseWork> close, long deadline) {
        if (close.isEmpty()) return;
        Thread worker = Thread.ofVirtual().name("compiled-release-cleanup").start(() -> closeAll(close));
        long remaining = deadline - System.nanoTime();
        if (remaining > 0) try { worker.join(Duration.ofNanos(remaining)); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        if (worker.isAlive()) {
            worker.interrupt();
            for (CloseWork item : close) if (item.references == 0) {
                synchronized (this) { forcedCleanup++; }
                recordCleanup(new Cleanup(item.entry.key, "CLOSE_TIMEOUT", 0, clock.instant()));
            }
        }
    }
}
