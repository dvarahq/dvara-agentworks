package com.dvarahq.agentworks.autoconfigure;

import com.dvarahq.agentworks.runplane.compiled.CompiledReleaseCache;
import com.dvarahq.agentworks.runplane.compiled.CompiledReleaseKey;
import com.dvarahq.agentworks.runplane.compiled.DeploymentOverlayKey;
import com.dvarahq.agentworks.runplane.compiled.SharedResourceRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.Duration;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ResourceCleanupJournalTest {
    private static final String HASH = "sha256:" + "e".repeat(64);

    @Test void cleanupEvidenceSurvivesRestartAndHasExclusiveProcessOwnership(@TempDir Path directory) throws Exception {
        Path pending = directory.resolve("cleanup.pending");
        Clock clock = Clock.fixed(Instant.parse("2026-10-16T12:00:00Z"), ZoneOffset.UTC);
        ResourceCleanupJournal first = new ResourceCleanupJournal(pending, clock);
        UUID workspace = UUID.randomUUID();
        first.record(new CompiledReleaseCache.Cleanup(new CompiledReleaseCache.Key(
                new CompiledReleaseKey(workspace, UUID.randomUUID(), 1, HASH),
                new DeploymentOverlayKey(UUID.randomUUID(), UUID.randomUUID(), HASH, HASH)),
                "CLOSE_FAILED", 1, clock.instant()));
        first.record(new SharedResourceRegistry.Cleanup(new SharedResourceRegistry.Key(
                SharedResourceRegistry.Kind.EXECUTOR, "test", SharedResourceRegistry.Isolation.WORKSPACE,
                workspace, "origin", HASH), 2, clock.instant()));
        assertThat(Files.readAllLines(pending)).hasSize(2)
                .allMatch(line -> !line.contains("origin") && line.matches(".*\\|[0-9a-f]{64}\\|.*"));
        ResourceCleanupJournal secondContext = new ResourceCleanupJournal(pending, clock);
        secondContext.record(new SharedResourceRegistry.Cleanup(new SharedResourceRegistry.Key(
                SharedResourceRegistry.Kind.HTTP, "test", SharedResourceRegistry.Isolation.GLOBAL,
                null, "second-context", HASH), 1, clock.instant()));
        first.close();
        secondContext.record(new SharedResourceRegistry.Cleanup(new SharedResourceRegistry.Key(
                SharedResourceRegistry.Kind.HTTP, "test", SharedResourceRegistry.Isolation.GLOBAL,
                null, "still-owned", HASH), 1, clock.instant()));
        assertThat(Files.readAllLines(pending)).hasSize(4);

        secondContext.close();
        try (ResourceCleanupJournal restarted = new ResourceCleanupJournal(pending, clock)) {
            assertThat(restarted.unresolvedAtStartup()).isEqualTo(4);
            assertThat(Files.readString(pending)).contains("COMPILED").contains("SHARED_EXECUTOR")
                    .doesNotContain("RECOVERED_BY_PROCESS_RESTART");
        }
    }

    @Test void invalidExistingEvidenceReleasesOwnershipForRepair(@TempDir Path directory) throws Exception {
        Path pending = directory.resolve("cleanup.pending");
        Files.writeString(pending, "x".repeat(ResourceCleanupJournal.MAX_ROW_BYTES + 1));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new ResourceCleanupJournal(pending, Clock.systemUTC()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("oversized row");
        Files.writeString(pending, "");
        try (ResourceCleanupJournal repaired = new ResourceCleanupJournal(pending, Clock.systemUTC())) {
            assertThat(repaired.unresolvedAtStartup()).isZero();
        }
    }

    @Test void maintenanceDeterministicallyExpiresIdleResourcesWithoutTraffic() {
        MutableClock clock = new MutableClock();
        SharedResourceRegistry resources = new SharedResourceRegistry(2, Duration.ZERO, clock, ignored -> {});
        CompiledReleaseCache cache = new CompiledReleaseCache(2);
        AtomicInteger closed = new AtomicInteger();
        var key = new SharedResourceRegistry.Key(SharedResourceRegistry.Kind.HTTP, "test",
                SharedResourceRegistry.Isolation.WORKSPACE, UUID.randomUUID(), "idle", HASH);
        try (var ignored = resources.acquire(key, Client.class, Duration.ofSeconds(5), () -> new Client(closed))) { }
        clock.advance(Duration.ofSeconds(5));
        new SharedResourceMaintenanceLifecycle(resources, cache, Duration.ofMinutes(1)).maintain();
        assertThat(resources.snapshot().resources()).isZero();
        assertThat(closed).hasValue(1);
    }

    @Test void maintenanceStopNeverInvokesArbitraryCleanupOnTheShutdownThread() {
        MutableClock clock = new MutableClock();
        AtomicInteger closed = new AtomicInteger();
        SharedResourceRegistry resources = new SharedResourceRegistry(1, Duration.ofSeconds(1), clock, ignored -> {});
        var key = new SharedResourceRegistry.Key(SharedResourceRegistry.Kind.HTTP, "test",
                SharedResourceRegistry.Isolation.WORKSPACE, UUID.randomUUID(), "idle", HASH);
        try (var ignored = resources.acquire(key, Client.class, Duration.ofSeconds(1), () -> new Client(closed))) { }
        clock.advance(Duration.ofSeconds(1));
        SharedResourceMaintenanceLifecycle lifecycle = new SharedResourceMaintenanceLifecycle(resources,
                new CompiledReleaseCache(1), Duration.ofDays(1));
        lifecycle.start();
        lifecycle.stop();
        assertThat(closed).hasValue(0);
        resources.close();
        assertThat(closed).hasValue(1);
    }

    private record Client(AtomicInteger closed) implements AutoCloseable {
        @Override public void close() { closed.incrementAndGet(); }
    }

    private static final class MutableClock extends Clock {
        private Instant instant = Instant.parse("2026-10-16T12:00:00Z");
        void advance(Duration duration) { instant = instant.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
