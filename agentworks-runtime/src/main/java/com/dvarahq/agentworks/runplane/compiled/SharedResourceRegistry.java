package com.dvarahq.agentworks.runplane.compiled;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Explicit, bounded lifecycle for shareable clients, independent from compiled release eviction. */
public final class SharedResourceRegistry implements AutoCloseable {
    public enum Kind { HTTP, MCP, GATEWAY, EXECUTOR, OTHER }
    public enum Isolation { GLOBAL, WORKSPACE }
    public enum State { ACTIVE, RETIRING, RETIRED }
    public enum Health { UNKNOWN, HEALTHY, DEGRADED, UNHEALTHY }

    public record Key(Kind kind, String owner, Isolation isolation, UUID workspaceId,
            String identity, String configurationHash) {
        public Key {
            Objects.requireNonNull(kind, "kind"); Objects.requireNonNull(isolation, "isolation");
            if (owner == null || !owner.matches("[a-zA-Z0-9][a-zA-Z0-9._:-]{0,127}")) throw new IllegalArgumentException("invalid resource owner");
            if (identity == null || identity.isBlank() || identity.length() > 256) throw new IllegalArgumentException("invalid resource identity");
            if ((isolation == Isolation.WORKSPACE) != (workspaceId != null)) throw new IllegalArgumentException("workspace isolation requires exactly one workspaceId");
            configurationHash = CompiledReleaseKey.digest(configurationHash, "configurationHash");
        }
    }
    public record Status(Key key, State state, Health health, int activeLeases, Instant lastUsedAt,
            Instant expiresAt, String failureCode) {}
    public record Snapshot(long hits, long misses, long creations, long creationFailures, long retirements,
            long closeFailures, long forcedCleanup, int resources, int activeLeases, List<Status> statuses) {}
    public record Cleanup(Key key, int activeLeases, Instant observedAt) {}
    @FunctionalInterface public interface CleanupSink { void record(Cleanup cleanup); }

    public static final class ResourceException extends RuntimeException {
        private final String code;
        ResourceException(String code, String message) { super(message); this.code = code; }
        public String code() { return code; }
    }

    public final class Lease<T extends AutoCloseable> implements AutoCloseable {
        private final Entry entry;
        private final Class<T> type;
        private final AtomicBoolean released = new AtomicBoolean();
        private Lease(Entry entry, Class<T> type) { this.entry = entry; this.type = type; }
        public T value() {
            synchronized (SharedResourceRegistry.this) {
                if (released.get() || entry.state == State.RETIRED) throw new ResourceException("SHARED_RESOURCE_LEASE_CLOSED", "the shared-resource lease is closed");
                return type.cast(entry.resource);
            }
        }
        public Key key() { return entry.key; }
        @Override public void close() { if (released.compareAndSet(false, true)) release(entry); }
    }

    private static final class Entry {
        private final Key key;
        private final Class<?> type;
        private final Duration idleTtl;
        private final CompletableFuture<AutoCloseable> ready = new CompletableFuture<>();
        private AutoCloseable resource;
        private State state = State.ACTIVE;
        private Health health = Health.UNKNOWN;
        private int leases;
        private Instant lastUsedAt;
        private String failureCode;
        private boolean closingCallback;
        private Entry(Key key, Class<?> type, Duration idleTtl, Instant now) {
            this.key = key; this.type = type; this.idleTtl = idleTtl; this.lastUsedAt = now;
        }
        private boolean creating() { return !ready.isDone(); }
    }
    private record CloseWork(Entry entry, int leases) {}

    private final int maximumResources;
    private final Duration shutdownGrace;
    private final Clock clock;
    private final CleanupSink cleanup;
    private final LinkedHashMap<Key, Entry> entries = new LinkedHashMap<>(16, .75f, true);
    private boolean closing;
    private long hits, misses, creations, creationFailures, retirements, closeFailures, forcedCleanup;

    public SharedResourceRegistry(int maximumResources) { this(maximumResources, Duration.ofSeconds(30), Clock.systemUTC(), ignored -> {}); }
    public SharedResourceRegistry(int maximumResources, Duration shutdownGrace, Clock clock, CleanupSink cleanup) {
        if (maximumResources < 1 || maximumResources > 100_000) throw new IllegalArgumentException("invalid maximumResources");
        this.maximumResources = maximumResources;
        this.shutdownGrace = Objects.requireNonNull(shutdownGrace, "shutdownGrace");
        if (shutdownGrace.isNegative() || shutdownGrace.compareTo(Duration.ofMinutes(5)) > 0) throw new IllegalArgumentException("shutdownGrace must be between zero and five minutes");
        this.clock = Objects.requireNonNull(clock, "clock"); this.cleanup = Objects.requireNonNull(cleanup, "cleanup");
    }

    public <T extends AutoCloseable> Lease<T> acquire(Key key, Class<T> type, Duration idleTtl,
            Supplier<? extends T> factory) {
        Objects.requireNonNull(key, "key"); Objects.requireNonNull(type, "type"); validateTtl(idleTtl); Objects.requireNonNull(factory, "factory");
        for (;;) {
            Entry entry;
            boolean owner = false;
            List<CloseWork> expired = List.of();
            synchronized (this) {
                if (closing) throw new ResourceException("SHARED_RESOURCE_REGISTRY_CLOSED", "the registry is shutting down");
                entry = entries.get(key);
                if (entry != null && !entry.creating()) {
                    requirePolicy(entry, type, idleTtl);
                    Instant now = clock.instant();
                    if (entry.leases == 0 && !now.isBefore(entry.lastUsedAt.plus(entry.idleTtl))) {
                        entry.state = State.RETIRING;
                        CloseWork close = scheduleCloseLocked(entry);
                        expired = close == null ? List.of() : List.of(close);
                        retirements++;
                        entry = null;
                    } else {
                        hits++; entry.leases++; entry.lastUsedAt = now;
                        return new Lease<>(entry, type);
                    }
                }
                if (entry == null && expired.isEmpty()) {
                    expired = makeRoomLocked();
                    if (expired.isEmpty()) {
                        entry = new Entry(key, type, idleTtl, clock.instant());
                        entries.put(key, entry); misses++; owner = true;
                    }
                }
            }
            closeAll(expired);
            if (!owner && !expired.isEmpty()) continue;
            if (owner) return create(entry, type, factory);
            try { entry.ready.join(); }
            catch (CompletionException failed) {
                if (failed.getCause() instanceof RuntimeException runtime) throw runtime;
                throw failed;
            }
        }
    }

    private <T extends AutoCloseable> Lease<T> create(Entry entry, Class<T> type, Supplier<? extends T> factory) {
        T resource;
        try { resource = Objects.requireNonNull(factory.get(), "factory returned null"); }
        catch (RuntimeException failure) {
            synchronized (this) { entries.remove(entry.key, entry); creationFailures++; notifyAll(); }
            entry.ready.completeExceptionally(failure);
            throw failure;
        }
        boolean discard;
        synchronized (this) {
            entry.resource = resource; entry.leases = closing ? 0 : 1; creations++;
            discard = closing || entries.get(entry.key) != entry;
            if (discard) entry.state = State.RETIRING;
            notifyAll();
        }
        entry.ready.complete(resource);
        if (discard) {
            CloseWork close;
            synchronized (this) { close = scheduleCloseLocked(entry); }
            closeAll(close == null ? List.of() : List.of(close));
            throw new ResourceException("SHARED_RESOURCE_REGISTRY_CLOSED", "the registry is shutting down");
        }
        return new Lease<>(entry, type);
    }

    public synchronized boolean health(Key key, Health health, String failureCode) {
        Objects.requireNonNull(key, "key"); Objects.requireNonNull(health, "health");
        if (failureCode != null && (!failureCode.matches("[A-Z][A-Z0-9_]{0,63}") || health == Health.HEALTHY)) throw new IllegalArgumentException("invalid failureCode");
        Entry entry = entries.get(key);
        if (entry == null || entry.creating() || entry.state == State.RETIRED) return false;
        entry.health = health; entry.failureCode = failureCode; return true;
    }

    public boolean retire(Key key) {
        CloseWork close;
        synchronized (this) {
            Entry entry = entries.get(Objects.requireNonNull(key, "key"));
            if (entry == null || entry.creating() || entry.state != State.ACTIVE) return false;
            entry.state = State.RETIRING; retirements++;
            close = entry.leases == 0 ? scheduleCloseLocked(entry) : null;
        }
        if (close != null) closeAll(List.of(close));
        return true;
    }

    public int expireIdle() {
        List<CloseWork> close = new ArrayList<>();
        synchronized (this) {
            Instant now = clock.instant();
            for (Entry entry : List.copyOf(entries.values())) {
                if (!entry.creating() && entry.state == State.ACTIVE && entry.leases == 0
                        && !now.isBefore(entry.lastUsedAt.plus(entry.idleTtl))) {
                    entry.state = State.RETIRING; retirements++;
                    CloseWork work = scheduleCloseLocked(entry);
                    if (work != null) close.add(work);
                }
            }
        }
        closeAll(close);
        return close.size();
    }

    public int maintain() { int expired = expireIdle(); retryCleanup(); return expired; }
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
        closeAll(work); return work.size();
    }

    public synchronized Snapshot snapshot() {
        List<Status> statuses = entries.values().stream().filter(entry -> !entry.creating()).map(entry -> new Status(entry.key, entry.state,
                entry.health, entry.leases, entry.lastUsedAt, entry.lastUsedAt.plus(entry.idleTtl), entry.failureCode)).toList();
        int leases = entries.values().stream().mapToInt(entry -> entry.leases).sum();
        return new Snapshot(hits, misses, creations, creationFailures, retirements, closeFailures, forcedCleanup,
                entries.size(), leases, statuses);
    }

    private static void validateTtl(Duration idleTtl) {
        Objects.requireNonNull(idleTtl, "idleTtl");
        if (idleTtl.isNegative() || idleTtl.isZero() || idleTtl.compareTo(Duration.ofDays(30)) > 0) throw new IllegalArgumentException("idleTtl must be greater than zero and at most 30 days");
    }
    private static void requirePolicy(Entry entry, Class<?> type, Duration ttl) {
        if (entry.state != State.ACTIVE) throw new ResourceException("SHARED_RESOURCE_STALE", "the resource is retiring");
        if (!entry.type.equals(type)) throw new ResourceException("SHARED_RESOURCE_TYPE_CONFLICT", "the resource identity is already registered with another type");
        if (!entry.idleTtl.equals(ttl)) throw new ResourceException("SHARED_RESOURCE_POLICY_CONFLICT", "the resource identity is already registered with another expiry policy");
    }
    private List<CloseWork> makeRoomLocked() {
        if (entries.size() < maximumResources) return List.of();
        for (Entry entry : List.copyOf(entries.values())) {
            if (!entry.creating() && entry.state == State.ACTIVE && entry.leases == 0) {
                entry.state = State.RETIRING; retirements++;
                CloseWork close = scheduleCloseLocked(entry);
                return close == null ? List.of() : List.of(close);
            }
        }
        throw new ResourceException("SHARED_RESOURCE_CAPACITY_EXHAUSTED", "the bounded registry has no unleased resource to retire");
    }
    private void release(Entry entry) {
        CloseWork close = null;
        synchronized (this) {
            if (entry.leases <= 0) return;
            entry.leases--; entry.lastUsedAt = clock.instant();
            if (entry.leases == 0 && entry.state == State.RETIRING) close = scheduleCloseLocked(entry);
            notifyAll();
        }
        if (close != null) closeAll(List.of(close));
    }
    private CloseWork scheduleCloseLocked(Entry entry) {
        if (entry.closingCallback || entry.resource == null) return null;
        entry.state = State.RETIRED;
        entry.closingCallback = true;
        return new CloseWork(entry, entry.leases);
    }
    private void closeAll(List<CloseWork> work) {
        for (CloseWork item : work) {
            try {
                item.entry.resource.close();
                synchronized (this) {
                    item.entry.closingCallback = false;
                    entries.remove(item.entry.key, item.entry);
                    notifyAll();
                }
            }
            catch (Exception failure) {
                synchronized (this) { closeFailures++; item.entry.closingCallback = false; notifyAll(); }
                recordCleanup(new Cleanup(item.entry.key, item.leases, clock.instant()));
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
            if (closing) return; closing = true;
            for (Entry entry : List.copyOf(entries.values())) if (!entry.creating() && entry.state == State.ACTIVE) {
                entry.state = State.RETIRING; retirements++;
                if (entry.leases == 0) {
                    CloseWork work = scheduleCloseLocked(entry);
                    if (work != null) { close.add(work); shutdownClosures.add(entry); }
                }
            }
            while (entries.values().stream().anyMatch(entry -> entry.leases > 0 || entry.creating())
                    || entries.values().stream().anyMatch(entry -> entry.closingCallback
                    && !shutdownClosures.contains(entry))) {
                long remaining = deadline - System.nanoTime(); if (remaining <= 0) break;
                try { wait(Math.max(1, Math.min(Duration.ofNanos(remaining).toMillis(), 100))); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
            }
            for (Entry entry : new ArrayList<>(entries.values())) {
                if (entry.creating()) {
                    forcedCleanup++;
                    evidence.add(new Cleanup(entry.key, 0, clock.instant()));
                } else if (entry.closingCallback && !shutdownClosures.contains(entry)) {
                    forcedCleanup++;
                    evidence.add(new Cleanup(entry.key, entry.leases, clock.instant()));
                } else if (entry.leases > 0) {
                    forcedCleanup++;
                    evidence.add(new Cleanup(entry.key, entry.leases, clock.instant()));
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
        Thread worker = Thread.ofVirtual().name("shared-resource-cleanup").start(() -> closeAll(close));
        long remaining = deadline - System.nanoTime();
        if (remaining > 0) try { worker.join(Duration.ofNanos(remaining)); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        if (worker.isAlive()) {
            worker.interrupt();
            for (CloseWork item : close) if (item.leases == 0) {
                synchronized (this) { forcedCleanup++; }
                recordCleanup(new Cleanup(item.entry.key, 0, clock.instant()));
            }
        }
    }
}
