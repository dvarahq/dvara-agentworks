package com.dvarahq.agentworks.runplane.compiled;

import com.dvarahq.agentworks.domain.agent.compiled.CompiledAgentDefinition;
import com.dvarahq.agentworks.domain.security.CredentialRef;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CompiledReleaseCacheTest {
    private static final String A = "sha256:" + "a".repeat(64);
    private static final String B = "sha256:" + "b".repeat(64);

    @Test void concurrentFirstUseCompilesExactlyOnce() throws Exception {
        CompiledReleaseCache cache = new CompiledReleaseCache(8);
        CompiledReleaseCache.Key key = key(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), A, A);
        AtomicInteger compilations = new AtomicInteger();
        Callable<Void> task = () -> {
            try (var lease = cache.acquire(key, () -> {
                compilations.incrementAndGet();
                return release(new AtomicInteger());
            })) { assertThat(lease.value().definition().executorType()).isEqualTo("spring-ai"); }
            return null;
        };
        try (var executor = Executors.newFixedThreadPool(16)) {
            List<Callable<Void>> tasks = java.util.stream.IntStream.range(0, 64).mapToObj(i -> task).toList();
            for (var future : executor.invokeAll(tasks)) future.get();
        }
        assertThat(compilations).hasValue(1);
        assertThat(cache.snapshot()).extracting(CompiledReleaseCache.Snapshot::hits,
                CompiledReleaseCache.Snapshot::misses, CompiledReleaseCache.Snapshot::activeReferences)
                .containsExactly(63L, 1L, 0);
    }

    @Test void blockedCompilationDoesNotBlockAnotherTenant() throws Exception {
        CompiledReleaseCache cache = new CompiledReleaseCache(2);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        var blockedKey = key(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), A, A);
        var otherKey = key(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), A, A);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var blocked = executor.submit(() -> cache.acquire(blockedKey, () -> {
                entered.countDown();
                try { release.await(); } catch (InterruptedException stopped) { throw new RuntimeException(stopped); }
                return release(new AtomicInteger());
            }));
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            var unrelated = executor.submit(() -> cache.acquire(otherKey, () -> release(new AtomicInteger())));
            try (var lease = unrelated.get(1, TimeUnit.SECONDS)) { assertThat(lease.key()).isEqualTo(otherKey); }
            release.countDown();
            blocked.get(1, TimeUnit.SECONDS).close();
        }
    }

    @Test void blockedCloseDoesNotBlockAnotherTenant() throws Exception {
        CompiledReleaseCache cache = new CompiledReleaseCache(2);
        CountDownLatch entered = new CountDownLatch(1), releaseClose = new CountDownLatch(1);
        var firstKey = key(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), A, A);
        try (var lease = cache.acquire(firstKey, () -> new CompiledRelease(
                new CompiledAgentDefinition("spring-ai", List.of()), List.of((AutoCloseable) () -> {
                    entered.countDown(); releaseClose.await();
                })))) { }
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var retiring = executor.submit(() -> cache.retire(firstKey::equals, "TEST"));
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            var key = key(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), A, A);
            try (var lease = executor.submit(() -> cache.acquire(key, () -> release(new AtomicInteger())))
                    .get(1, TimeUnit.SECONDS)) { assertThat(lease.key()).isEqualTo(key); }
            releaseClose.countDown();
            assertThat(retiring.get(1, TimeUnit.SECONDS)).isOne();
        }
    }

    @Test void workspaceReleaseRevisionAndSemanticHashesNeverCollide() {
        CompiledReleaseCache cache = new CompiledReleaseCache(8);
        UUID workspace = UUID.randomUUID(), release = UUID.randomUUID(), deployment = UUID.randomUUID(), revision = UUID.randomUUID();
        CompiledReleaseCache.Key base = new CompiledReleaseCache.Key(new CompiledReleaseKey(workspace, release, 12, A),
                new DeploymentOverlayKey(deployment, revision, A, A));
        List<CompiledReleaseCache.Key> keys = List.of(
                base,
                new CompiledReleaseCache.Key(new CompiledReleaseKey(UUID.randomUUID(), release, 12, A), base.overlay()),
                new CompiledReleaseCache.Key(new CompiledReleaseKey(workspace, UUID.randomUUID(), 12, A), base.overlay()),
                new CompiledReleaseCache.Key(new CompiledReleaseKey(workspace, release, 13, A), base.overlay()),
                new CompiledReleaseCache.Key(base.release(), new DeploymentOverlayKey(UUID.randomUUID(), revision, A, A)),
                new CompiledReleaseCache.Key(base.release(), new DeploymentOverlayKey(deployment, UUID.randomUUID(), A, A)),
                new CompiledReleaseCache.Key(base.release(), new DeploymentOverlayKey(deployment, revision, B, A)),
                new CompiledReleaseCache.Key(new CompiledReleaseKey(workspace, release, 12, B),
                        new DeploymentOverlayKey(deployment, revision, A, B)));
        AtomicInteger compiled = new AtomicInteger();
        keys.forEach(key -> { try (var ignored = cache.acquire(key, () -> { compiled.incrementAndGet(); return release(new AtomicInteger()); })) {} });
        assertThat(compiled).hasValue(keys.size());
        assertThat(cache.snapshot().entries()).isEqualTo(keys.size());
    }

    @Test void shutdownIsBoundedWhileCompilationAndCloseCallbacksAreBlocked() throws Exception {
        CountDownLatch compilerEntered = new CountDownLatch(1), finishCompiler = new CountDownLatch(1);
        List<CompiledReleaseCache.Cleanup> cleanup = new ArrayList<>();
        CompiledReleaseCache cache = new CompiledReleaseCache(2, Duration.ofMillis(50), Clock.systemUTC(), cleanup::add);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var acquire = executor.submit(() -> cache.acquire(key(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), A, A), () -> {
                compilerEntered.countDown();
                try { finishCompiler.await(); } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
                return release(new AtomicInteger());
            }));
            assertThat(compilerEntered.await(1, TimeUnit.SECONDS)).isTrue();
            long started = System.nanoTime(); cache.close();
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(300));
            assertThat(cleanup).singleElement().satisfies(record -> assertThat(record.reason())
                    .isEqualTo("CREATION_IN_FLIGHT"));
            finishCompiler.countDown();
            assertThatThrownBy(() -> acquire.get(1, TimeUnit.SECONDS)).hasCauseInstanceOf(CompiledReleaseCache.CacheException.class);
        }

        CountDownLatch closeEntered = new CountDownLatch(1), finishClose = new CountDownLatch(1);
        CompiledReleaseCache closing = new CompiledReleaseCache(1, Duration.ofMillis(50), Clock.systemUTC(), ignored -> {});
        try (var lease = closing.acquire(key(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), A, A),
                () -> new CompiledRelease(new CompiledAgentDefinition("spring-ai", List.of()), List.of((AutoCloseable) () -> {
                    closeEntered.countDown(); finishClose.await();
                })))) { }
        long started = System.nanoTime(); closing.close();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(300));
        assertThat(closeEntered.await(1, TimeUnit.SECONDS)).isTrue(); finishClose.countDown();
    }

    @Test void shutdownJournalsACloseCallbackAlreadyBlockedByMaintenance() throws Exception {
        List<CompiledReleaseCache.Cleanup> cleanup = new ArrayList<>();
        CountDownLatch closeEntered = new CountDownLatch(1), finishClose = new CountDownLatch(1);
        CompiledReleaseCache cache = new CompiledReleaseCache(1, Duration.ofMillis(50), Clock.systemUTC(), cleanup::add);
        var key = key(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), A, A);
        try (var lease = cache.acquire(key, () -> new CompiledRelease(
                new CompiledAgentDefinition("spring-ai", List.of()), List.of((AutoCloseable) () -> {
                    closeEntered.countDown(); finishClose.await();
                })))) { }
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var retiring = executor.submit(() -> cache.retire(key::equals, "MAINTENANCE"));
            assertThat(closeEntered.await(1, TimeUnit.SECONDS)).isTrue();
            long started = System.nanoTime(); cache.close();
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(300));
            assertThat(cleanup).singleElement().satisfies(record -> assertThat(record.reason())
                    .isEqualTo("CLOSE_IN_FLIGHT"));
            finishClose.countDown();
            assertThat(retiring.get(1, TimeUnit.SECONDS)).isOne();
        }
    }

    @Test void shutdownJournalsACloseStartedByTheFinalLeaseReleaseDuringGrace() throws Exception {
        List<CompiledReleaseCache.Cleanup> cleanup = new ArrayList<>();
        CountDownLatch closeEntered = new CountDownLatch(1), finishClose = new CountDownLatch(1);
        CompiledReleaseCache cache = new CompiledReleaseCache(1, Duration.ofMillis(100), Clock.systemUTC(), cleanup::add);
        var key = key(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), A, A);
        var lease = cache.acquire(key, () -> new CompiledRelease(new CompiledAgentDefinition("spring-ai", List.of()),
                List.of((AutoCloseable) () -> { closeEntered.countDown(); finishClose.await(); })));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var shutdown = executor.submit(cache::close);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (cache.states().get(key) != CompiledReleaseCache.State.RETIRING
                    && System.nanoTime() < deadline) Thread.sleep(5);
            var releasing = executor.submit(lease::close);
            assertThat(closeEntered.await(1, TimeUnit.SECONDS)).isTrue();
            shutdown.get(1, TimeUnit.SECONDS);
            assertThat(cleanup).singleElement().satisfies(record -> assertThat(record.reason())
                    .isEqualTo("CLOSE_IN_FLIGHT"));
            finishClose.countDown();
            releasing.get(1, TimeUnit.SECONDS);
        }
    }

    @Test void retirementKeepsActivePinAliveAndAResumablePinnedRunCanReactivateIt() {
        MutableClock clock = new MutableClock();
        AtomicInteger closed = new AtomicInteger();
        CompiledReleaseCache cache = new CompiledReleaseCache(2, Duration.ZERO, clock, ignored -> {});
        UUID workspace = UUID.randomUUID(), deployment = UUID.randomUUID();
        CompiledReleaseCache.Key key = key(workspace, UUID.randomUUID(), deployment, A, A);
        var first = cache.acquire(key, () -> release(closed));
        assertThat(cache.deploymentChanged(workspace, deployment)).isOne();
        assertThat(first.value().definition().executorType()).isEqualTo("spring-ai");
        try (var resumed = cache.acquire(key, () -> { throw new AssertionError("must reuse exact material"); })) {
            assertThat(resumed.value()).isSameAs(first.value());
        }
        assertThat(cache.states()).containsEntry(key, CompiledReleaseCache.State.ACTIVE);
        first.close();
        assertThat(closed).hasValue(0);
        cache.releaseRevoked(workspace, key.release().releaseId());
        assertThat(closed).hasValue(1);
        assertThat(cache.states()).doesNotContainKey(key);
    }

    @Test void capacityNeverEvictsAnActivelyPinnedEntry() {
        CompiledReleaseCache cache = new CompiledReleaseCache(1);
        var lease = cache.acquire(key(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), A, A),
                () -> release(new AtomicInteger()));
        assertThatThrownBy(() -> cache.acquire(
                key(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), A, A),
                () -> release(new AtomicInteger())))
                .isInstanceOf(CompiledReleaseCache.CacheException.class)
                .extracting(error -> ((CompiledReleaseCache.CacheException) error).code())
                .isEqualTo("COMPILED_CACHE_FULL");
        lease.close();
    }

    @Test void failedCleanupRetainsItsBoundedSlotUntilRetrySucceeds() {
        AtomicInteger attempts = new AtomicInteger();
        CompiledReleaseCache cache = new CompiledReleaseCache(1);
        var failed = key(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), A, A);
        try (var ignored = cache.acquire(failed, () -> new CompiledRelease(
                new CompiledAgentDefinition("spring-ai", List.of()), List.of((AutoCloseable) () -> {
                    if (attempts.incrementAndGet() == 1) throw new IOException("once");
                })))) { }
        cache.retire(failed::equals, "TEST");
        assertThat(cache.snapshot().entries()).isOne();
        assertThatThrownBy(() -> cache.acquire(key(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), A, A),
                () -> release(new AtomicInteger()))).isInstanceOf(CompiledReleaseCache.CacheException.class)
                .extracting(error -> ((CompiledReleaseCache.CacheException) error).code())
                .isEqualTo("COMPILED_CACHE_FULL");
        assertThat(cache.retryCleanup()).isOne();
        assertThat(cache.snapshot().entries()).isZero();
        assertThat(attempts).hasValue(2);
    }

    @Test void restartRebuildsFromExactKeyWithoutDependingOnGarbageCollection() {
        CompiledReleaseCache.Key key = key(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), A, B);
        AtomicInteger closed = new AtomicInteger();
        CompiledReleaseCache first = new CompiledReleaseCache(2);
        try (var ignored = first.acquire(key, () -> release(closed))) {}
        first.close();
        assertThat(closed).hasValue(1);
        AtomicInteger rebuilt = new AtomicInteger();
        try (CompiledReleaseCache second = new CompiledReleaseCache(2);
             var lease = second.acquire(key, () -> { rebuilt.incrementAndGet(); return release(new AtomicInteger()); })) {
            assertThat(lease.key()).isEqualTo(key);
        }
        assertThat(rebuilt).hasValue(1);
    }

    @Test void forcedShutdownRecordsOutstandingCleanupAndInvalidatesTheLease() {
        List<CompiledReleaseCache.Cleanup> cleanup = new ArrayList<>();
        AtomicInteger closed = new AtomicInteger();
        CountDownLatch closeAttempted = new CountDownLatch(1);
        CompiledReleaseCache cache = new CompiledReleaseCache(2, Duration.ZERO, Clock.systemUTC(), cleanup::add);
        var lease = cache.acquire(key(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), A, A),
                () -> new CompiledRelease(new CompiledAgentDefinition("spring-ai", List.of()), List.of((AutoCloseable) () -> {
                    closed.incrementAndGet(); closeAttempted.countDown();
                })));
        cache.close();
        assertThat(cleanup).singleElement().satisfies(record -> assertThat(record.activeReferences()).isOne());
        assertThat(cache.snapshot().forcedCleanup()).isOne();
        try { assertThat(closeAttempted.await(1, TimeUnit.SECONDS)).isTrue(); }
        catch (InterruptedException stopped) { Thread.currentThread().interrupt(); throw new AssertionError(stopped); }
        assertThat(closed).hasValue(1);
        assertThatThrownBy(lease::value).isInstanceOf(CompiledReleaseCache.CacheException.class);
    }

    @Test void cachedDefinitionCanHoldReferencesButHasNoSecretOrRunStateField() {
        var definition = new CompiledAgentDefinition("spring-ai", List.of(new CredentialRef("vault:workspace/key")));
        assertThat(definition.credentialRefs()).extracting(CredentialRef::id).containsExactly("vault:workspace/key");
        assertThat(CompiledAgentDefinition.class.getRecordComponents()).extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("executorType", "credentialRefs");
    }

    private static CompiledRelease release(AtomicInteger closed) {
        return new CompiledRelease(new CompiledAgentDefinition("spring-ai", List.of(new CredentialRef("ref:key"))),
                List.of((AutoCloseable) closed::incrementAndGet));
    }

    private static CompiledReleaseCache.Key key(UUID workspace, UUID release, UUID deployment,
            String policy, String dependency) {
        return new CompiledReleaseCache.Key(new CompiledReleaseKey(workspace, release, 12, dependency),
                new DeploymentOverlayKey(deployment, UUID.randomUUID(), policy, dependency));
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
