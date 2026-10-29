package com.dvarahq.agentworks.runplane.compiled;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SharedResourceRegistryTest {
    private static final String HASH = "sha256:" + "c".repeat(64);

    @Test void poolsReuseExactIdentityButNeverCrossWorkspaceOrKind() {
        SharedResourceRegistry registry = new SharedResourceRegistry(8);
        UUID firstWorkspace = UUID.randomUUID(), secondWorkspace = UUID.randomUUID();
        var first = key(SharedResourceRegistry.Kind.HTTP, firstWorkspace, "origin-a");
        var second = key(SharedResourceRegistry.Kind.HTTP, secondWorkspace, "origin-a");
        var mcp = key(SharedResourceRegistry.Kind.MCP, firstWorkspace, "origin-a");
        AtomicInteger created = new AtomicInteger();
        try (var one = registry.acquire(first, Client.class, Duration.ofMinutes(5), () -> new Client(created));
             var same = registry.acquire(first, Client.class, Duration.ofMinutes(5), () -> new Client(created));
             var foreign = registry.acquire(second, Client.class, Duration.ofMinutes(5), () -> new Client(created));
             var otherKind = registry.acquire(mcp, Client.class, Duration.ofMinutes(5), () -> new Client(created))) {
            assertThat(one.value()).isSameAs(same.value()).isNotSameAs(foreign.value()).isNotSameAs(otherKind.value());
        }
        assertThat(created).hasValue(3);
        assertThat(registry.snapshot()).extracting(SharedResourceRegistry.Snapshot::hits,
                SharedResourceRegistry.Snapshot::misses).containsExactly(1L, 3L);
    }

    @Test void blockedFactoryAndCloseNeverBlockUnrelatedWorkspace() throws Exception {
        SharedResourceRegistry registry = new SharedResourceRegistry(3);
        CountDownLatch factoryEntered = new CountDownLatch(1), releaseFactory = new CountDownLatch(1);
        UUID one = UUID.randomUUID(), two = UUID.randomUUID();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var blocked = executor.submit(() -> registry.acquire(key(SharedResourceRegistry.Kind.HTTP, one, "one"),
                    Client.class, Duration.ofMinutes(1), () -> {
                        factoryEntered.countDown();
                        try { releaseFactory.await(); } catch (InterruptedException stopped) { throw new RuntimeException(stopped); }
                        return new Client();
                    }));
            assertThat(factoryEntered.await(1, TimeUnit.SECONDS)).isTrue();
            try (var lease = executor.submit(() -> registry.acquire(key(SharedResourceRegistry.Kind.HTTP, two, "two"),
                    Client.class, Duration.ofMinutes(1), Client::new)).get(1, TimeUnit.SECONDS)) {
                assertThat(lease.value()).isNotNull();
            }
            releaseFactory.countDown(); blocked.get(1, TimeUnit.SECONDS).close();

            CountDownLatch closeEntered = new CountDownLatch(1), releaseClose = new CountDownLatch(1);
            var closeKey = key(SharedResourceRegistry.Kind.MCP, one, "close");
            try (var lease = registry.acquire(closeKey, Client.class, Duration.ofMinutes(1), () -> new Client() {
                @Override public void close() {
                    closeEntered.countDown();
                    try { releaseClose.await(); } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
                }
            })) { }
            var retiring = executor.submit(() -> registry.retire(closeKey));
            assertThat(closeEntered.await(1, TimeUnit.SECONDS)).isTrue();
            try (var lease = executor.submit(() -> registry.acquire(key(SharedResourceRegistry.Kind.OTHER, two, "other"),
                    Client.class, Duration.ofMinutes(1), Client::new)).get(1, TimeUnit.SECONDS)) {
                assertThat(lease.value()).isNotNull();
            }
            releaseClose.countDown(); assertThat(retiring.get(1, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test void acquisitionChecksIdleExpiryEvenWithoutMaintenanceTick() {
        MutableClock clock = new MutableClock();
        SharedResourceRegistry registry = new SharedResourceRegistry(2, Duration.ZERO, clock, ignored -> {});
        var key = key(SharedResourceRegistry.Kind.HTTP, UUID.randomUUID(), "ttl");
        AtomicInteger created = new AtomicInteger(), closed = new AtomicInteger();
        Client first;
        try (var lease = registry.acquire(key, Client.class, Duration.ofSeconds(5), () -> {
            created.incrementAndGet(); return new Client(closed, true);
        })) { first = lease.value(); }
        clock.advance(Duration.ofSeconds(5));
        try (var lease = registry.acquire(key, Client.class, Duration.ofSeconds(5), () -> {
            created.incrementAndGet(); return new Client(closed, true);
        })) { assertThat(lease.value()).isNotSameAs(first); }
        assertThat(created).hasValue(2);
        assertThat(closed).hasValue(1);
    }

    @Test void failedCloseIsRetriedAndShutdownIsBounded() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        SharedResourceRegistry retrying = new SharedResourceRegistry(1);
        var key = key(SharedResourceRegistry.Kind.HTTP, UUID.randomUUID(), "retry");
        try (var lease = retrying.acquire(key, Client.class, Duration.ofMinutes(1), () -> new Client() {
            @Override public void close() { if (attempts.incrementAndGet() == 1) throw new IllegalStateException("once"); }
        })) { }
        retrying.retire(key);
        assertThat(retrying.snapshot().closeFailures()).isOne();
        assertThat(retrying.snapshot().resources()).isOne();
        assertThatThrownBy(() -> retrying.acquire(key(SharedResourceRegistry.Kind.HTTP, UUID.randomUUID(), "other"),
                Client.class, Duration.ofMinutes(1), Client::new))
                .isInstanceOf(SharedResourceRegistry.ResourceException.class)
                .extracting(error -> ((SharedResourceRegistry.ResourceException) error).code())
                .isEqualTo("SHARED_RESOURCE_CAPACITY_EXHAUSTED");
        assertThat(retrying.retryCleanup()).isOne();
        assertThat(attempts).hasValue(2);
        assertThat(retrying.snapshot().resources()).isZero();

        CountDownLatch entered = new CountDownLatch(1), finish = new CountDownLatch(1);
        SharedResourceRegistry bounded = new SharedResourceRegistry(1, Duration.ofMillis(50), Clock.systemUTC(), ignored -> {});
        try (var lease = bounded.acquire(key(SharedResourceRegistry.Kind.MCP, UUID.randomUUID(), "blocked"),
                Client.class, Duration.ofMinutes(1), () -> new Client() {
                    @Override public void close() {
                        entered.countDown();
                        try { finish.await(); } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
                    }
                })) { }
        long started = System.nanoTime(); bounded.close();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(300));
        assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue(); finish.countDown();
    }

    @Test void shutdownRecordsAFactoryStillInFlightAndClosesItsLateResource() throws Exception {
        List<SharedResourceRegistry.Cleanup> cleanup = new ArrayList<>();
        CountDownLatch entered = new CountDownLatch(1), finish = new CountDownLatch(1);
        AtomicInteger closed = new AtomicInteger();
        SharedResourceRegistry registry = new SharedResourceRegistry(1, Duration.ofMillis(50), Clock.systemUTC(),
                cleanup::add);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var acquire = executor.submit(() -> registry.acquire(
                    key(SharedResourceRegistry.Kind.MCP, UUID.randomUUID(), "creating"), Client.class,
                    Duration.ofMinutes(1), () -> {
                        entered.countDown();
                        try { finish.await(); } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
                        return new Client(closed, true);
                    }));
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            registry.close();
            assertThat(cleanup).singleElement().satisfies(record -> assertThat(record.activeLeases()).isZero());
            finish.countDown();
            assertThatThrownBy(() -> acquire.get(1, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(SharedResourceRegistry.ResourceException.class);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (closed.get() == 0 && System.nanoTime() < deadline) Thread.sleep(5);
            assertThat(closed).hasValue(1);
        }
    }

    @Test void shutdownJournalsACloseCallbackAlreadyBlockedByMaintenance() throws Exception {
        List<SharedResourceRegistry.Cleanup> cleanup = new ArrayList<>();
        CountDownLatch closeEntered = new CountDownLatch(1), finishClose = new CountDownLatch(1);
        SharedResourceRegistry registry = new SharedResourceRegistry(1, Duration.ofMillis(50), Clock.systemUTC(),
                cleanup::add);
        var key = key(SharedResourceRegistry.Kind.MCP, UUID.randomUUID(), "blocked-maintenance");
        try (var lease = registry.acquire(key, Client.class, Duration.ofMinutes(1), () -> new Client() {
            @Override public void close() {
                closeEntered.countDown();
                try { finishClose.await(); } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
            }
        })) { }
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var retiring = executor.submit(() -> registry.retire(key));
            assertThat(closeEntered.await(1, TimeUnit.SECONDS)).isTrue();
            long started = System.nanoTime(); registry.close();
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(300));
            assertThat(cleanup).singleElement().satisfies(record -> assertThat(record.activeLeases()).isZero());
            finishClose.countDown();
            assertThat(retiring.get(1, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test void shutdownJournalsACloseStartedByTheFinalLeaseReleaseDuringGrace() throws Exception {
        List<SharedResourceRegistry.Cleanup> cleanup = new ArrayList<>();
        CountDownLatch closeEntered = new CountDownLatch(1), finishClose = new CountDownLatch(1);
        SharedResourceRegistry registry = new SharedResourceRegistry(1, Duration.ofMillis(100), Clock.systemUTC(),
                cleanup::add);
        var key = key(SharedResourceRegistry.Kind.MCP, UUID.randomUUID(), "release-during-shutdown");
        var lease = registry.acquire(key, Client.class, Duration.ofMinutes(1), () -> new Client() {
            @Override public void close() {
                closeEntered.countDown();
                try { finishClose.await(); } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
            }
        });
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var shutdown = executor.submit(registry::close);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (registry.snapshot().statuses().stream().noneMatch(status -> status.key().equals(key)
                    && status.state() == SharedResourceRegistry.State.RETIRING) && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            var releasing = executor.submit(lease::close);
            assertThat(closeEntered.await(1, TimeUnit.SECONDS)).isTrue();
            shutdown.get(1, TimeUnit.SECONDS);
            assertThat(cleanup).singleElement().satisfies(record -> assertThat(record.activeLeases()).isZero());
            finishClose.countDown();
            releasing.get(1, TimeUnit.SECONDS);
        }
    }

    @Test void compiledCacheRetirementNeverClosesAnIndependentlyLeasedClient() {
        SharedResourceRegistry registry = new SharedResourceRegistry(2);
        AtomicInteger clientClosed = new AtomicInteger();
        var client = registry.acquire(key(SharedResourceRegistry.Kind.GATEWAY, UUID.randomUUID(), "gateway"),
                Client.class, Duration.ofMinutes(5), () -> new Client(clientClosed, true));
        CompiledReleaseCache cache = new CompiledReleaseCache(1);
        AtomicInteger releaseClosed = new AtomicInteger();
        UUID workspace = UUID.randomUUID();
        var cacheKey = new CompiledReleaseCache.Key(new CompiledReleaseKey(workspace, UUID.randomUUID(), 12, HASH),
                new DeploymentOverlayKey(UUID.randomUUID(), UUID.randomUUID(), HASH, HASH));
        try (var ignored = cache.acquire(cacheKey, () -> new CompiledRelease(
                new com.dvarahq.agentworks.domain.agent.compiled.CompiledAgentDefinition("spring-ai", List.of()),
                List.of((AutoCloseable) releaseClosed::incrementAndGet)))) {}
        cache.releaseRevoked(workspace, cacheKey.release().releaseId());
        assertThat(releaseClosed).hasValue(1);
        assertThat(clientClosed).hasValue(0);
        assertThat(client.value()).isNotNull();
        client.close();
        registry.close();
        assertThat(clientClosed).hasValue(1);
    }

    @Test void retireAndExpiryWaitForFinalLeaseAndExposeHealth() {
        MutableClock clock = new MutableClock();
        SharedResourceRegistry registry = new SharedResourceRegistry(2, Duration.ZERO, clock, ignored -> {});
        var key = key(SharedResourceRegistry.Kind.MCP, UUID.randomUUID(), "mcp-v1");
        AtomicInteger closed = new AtomicInteger();
        var lease = registry.acquire(key, Client.class, Duration.ofSeconds(10), () -> new Client(closed, true));
        assertThat(registry.health(key, SharedResourceRegistry.Health.DEGRADED, "UPSTREAM_SLOW")).isTrue();
        clock.advance(Duration.ofSeconds(20));
        assertThat(registry.expireIdle()).isZero();
        assertThat(registry.retire(key)).isTrue();
        assertThat(lease.value()).isNotNull();
        lease.close();
        assertThat(closed).hasValue(1);
        assertThat(registry.snapshot().resources()).isZero();
    }

    @Test void capacityAndCloseUseRacesFailClosedWithoutDoubleClose() {
        SharedResourceRegistry registry = new SharedResourceRegistry(1);
        AtomicInteger closed = new AtomicInteger();
        var lease = registry.acquire(key(SharedResourceRegistry.Kind.EXECUTOR, null, "shared-executor"), Client.class,
                Duration.ofMinutes(1), () -> new Client(closed, true));
        assertThatThrownBy(() -> registry.acquire(
                key(SharedResourceRegistry.Kind.OTHER, null, "other"), Client.class, Duration.ofMinutes(1), Client::new))
                .isInstanceOf(SharedResourceRegistry.ResourceException.class)
                .extracting(error -> ((SharedResourceRegistry.ResourceException) error).code())
                .isEqualTo("SHARED_RESOURCE_CAPACITY_EXHAUSTED");
        lease.close();
        assertThatThrownBy(lease::value).isInstanceOf(SharedResourceRegistry.ResourceException.class);
        registry.retire(lease.key());
        registry.close();
        assertThat(closed).hasValue(1);
    }

    @Test void forcedShutdownRecordsOutstandingLeaseAndAttemptsEveryClose() {
        List<SharedResourceRegistry.Cleanup> cleanup = new ArrayList<>();
        AtomicInteger closed = new AtomicInteger();
        CountDownLatch closeAttempted = new CountDownLatch(1);
        SharedResourceRegistry registry = new SharedResourceRegistry(2, Duration.ZERO, Clock.systemUTC(), cleanup::add);
        var lease = registry.acquire(key(SharedResourceRegistry.Kind.HTTP, UUID.randomUUID(), "http"), Client.class,
                Duration.ofMinutes(1), () -> new Client() {
                    @Override public void close() { closed.incrementAndGet(); closeAttempted.countDown(); }
                });
        registry.close();
        assertThat(cleanup).singleElement().satisfies(item -> assertThat(item.activeLeases()).isOne());
        assertThat(registry.snapshot().forcedCleanup()).isOne();
        try { assertThat(closeAttempted.await(1, TimeUnit.SECONDS)).isTrue(); }
        catch (InterruptedException stopped) { Thread.currentThread().interrupt(); throw new AssertionError(stopped); }
        assertThat(closed).hasValue(1);
        assertThatThrownBy(lease::value).isInstanceOf(SharedResourceRegistry.ResourceException.class);
    }

    @Test void workspaceIsolationIsStructural() {
        assertThatThrownBy(() -> new SharedResourceRegistry.Key(SharedResourceRegistry.Kind.HTTP, "owner",
                SharedResourceRegistry.Isolation.WORKSPACE, null, "id", HASH)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SharedResourceRegistry.Key(SharedResourceRegistry.Kind.HTTP, "owner",
                SharedResourceRegistry.Isolation.GLOBAL, UUID.randomUUID(), "id", HASH)).isInstanceOf(IllegalArgumentException.class);
    }

    private static SharedResourceRegistry.Key key(SharedResourceRegistry.Kind kind, UUID workspace, String identity) {
        return new SharedResourceRegistry.Key(kind, "agentworks", workspace == null
                ? SharedResourceRegistry.Isolation.GLOBAL : SharedResourceRegistry.Isolation.WORKSPACE,
                workspace, identity, HASH);
    }

    private static class Client implements AutoCloseable {
        private final AtomicInteger counter;
        private final boolean closeCounter;
        private Client() { this(new AtomicInteger(), false); }
        private Client(AtomicInteger counter) { this(counter, false); counter.incrementAndGet(); }
        private Client(AtomicInteger counter, boolean closeCounter) { this.counter = counter; this.closeCounter = closeCounter; }
        @Override public void close() { if (closeCounter) counter.incrementAndGet(); }
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");
        void advance(Duration duration) { now = now.plus(duration); }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
