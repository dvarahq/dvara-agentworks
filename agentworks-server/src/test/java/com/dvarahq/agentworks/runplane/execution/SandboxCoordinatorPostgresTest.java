package com.dvarahq.agentworks.runplane.execution;

import com.dvarahq.agentworks.domain.run.RunRecord;
import com.dvarahq.agentworks.domain.sandbox.SandboxConfiguration;
import com.dvarahq.agentworks.domain.sandbox.SandboxOwner;
import com.dvarahq.agentworks.domain.sandbox.SandboxProvisionRequest;
import com.dvarahq.agentworks.domain.sandbox.SandboxRef;
import com.dvarahq.agentworks.domain.sandbox.SandboxResource.State;
import com.dvarahq.agentworks.domain.sandbox.SandboxSnapshot;
import com.dvarahq.agentworks.domain.sandbox.SandboxState;
import com.dvarahq.agentworks.sandbox.ReconcilableSandboxProvider;
import com.dvarahq.agentworks.sandbox.SandboxCoordinator;
import com.dvarahq.agentworks.sandbox.SandboxInstance;
import com.dvarahq.agentworks.storage.sandbox.JdbcSandboxLifecycleAuthority;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Crash-window and cleanup evidence for the durable coordinator/provider boundary. */
class SandboxCoordinatorPostgresTest {
    @Test
    void rawToolProtocolRetainsDurableFencingWithoutPrematureArtifactStorage() throws Exception {
        var store = org.mockito.Mockito.mock(com.dvarahq.agentworks.artifact.ArtifactStore.class);
        var artifact = new com.dvarahq.agentworks.domain.artifact.ArtifactRef(UUID.randomUUID());
        org.mockito.Mockito.when(store.put(org.mockito.ArgumentMatchers.any())).thenReturn(artifact);
        var configured = new SandboxCoordinator(authority, provider,
                new SandboxCoordinator.Settings(providerId, "test-cleaner", Duration.ofMinutes(2), Duration.ofMinutes(1), Duration.ofMillis(1), 32), store);
        RunRecord run = scenario.submit(Map.of());
        SandboxInstance instance = configured.provision(request(owner(run)));
        String protocol = "x".repeat(40 * 1024);
        try {
            assertThat(instance.executeRaw(List.of(protocol), Duration.ofSeconds(1))).isEqualTo(protocol);
            org.mockito.Mockito.verifyNoInteractions(store);
            assertThat(instance.execute(List.of(protocol), Duration.ofSeconds(1))).contains(artifact.value().toString());
            org.mockito.Mockito.verify(store).put(org.mockito.ArgumentMatchers.any());
        } finally { instance.close(); configured.close(); }
        assertThatThrownBy(() -> instance.executeRaw(List.of(protocol), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(provider.resources).isEmpty();
    }

    private final RunScenario scenario = new RunScenario();
    private final String providerId = "test-" + UUID.randomUUID().toString().substring(0, 8);
    private final JdbcSandboxLifecycleAuthority authority = new JdbcSandboxLifecycleAuthority(scenario.fixture.jdbc,
            scenario.fixture.transactions);
    private final RecordingProvider provider = new RecordingProvider();
    private final SandboxCoordinator coordinator = new SandboxCoordinator(authority, provider,
            new SandboxCoordinator.Settings(providerId, "test-cleaner", Duration.ofMinutes(2), Duration.ofMinutes(1),
                    Duration.ofMillis(1), 32));

    @Test
    void provisionAndCancellationCrossOnlyShortDurableTransactions() {
        RunRecord run = scenario.submit(Map.of("task", "coordinated-sandbox"));

        SandboxInstance instance = coordinator.provision(request(owner(run)));

        assertThat(instance.reference().owner()).isEqualTo(owner(run));
        assertThat(authority.find(scenario.workspace, instance.reference().value()).orElseThrow().state())
                .isEqualTo(State.ACTIVE);
        assertThat(provider.resources).containsKey(instance.reference().value());

        instance.close();

        assertThat(authority.find(scenario.workspace, instance.reference().value()).orElseThrow().state())
                .isEqualTo(State.TERMINAL);
        assertThat(provider.resources).isEmpty();
        assertThat(instance.state()).isEqualTo(SandboxState.DESTROYED);
    }

    @Test
    void restartAdoptsOnlyAnExactReservationAndRetiresUnledgeredOwnedResources() {
        RunRecord run = scenario.submit(Map.of());
        SandboxOwner owner = owner(run);
        UUID reserved = UUID.randomUUID();
        authority.reserve(scenario.workspace, reserved, owner, providerId, Duration.ofMinutes(5), Duration.ofMinutes(2));
        provider.provision(request(owner).withReservedSandboxId(reserved));
        SandboxRef orphan = new SandboxRef(UUID.randomUUID(), owner);
        provider.resources.put(orphan.value(), orphan);

        SandboxCoordinator.ReconciliationResult result = coordinator.reconcile();

        assertThat(result.activatedReservations()).isOne();
        assertThat(result.orphanResourcesRetired()).isOne();
        assertThat(authority.find(scenario.workspace, reserved).orElseThrow().state()).isEqualTo(State.ACTIVE);
        assertThat(provider.resources.keySet()).containsExactly(reserved);
    }

    @Test
    void restartTerminalizesMissingActiveAndTimedOutProvisioningResources() {
        RunRecord run = scenario.submit(Map.of());
        SandboxOwner owner = owner(run);
        UUID missingActive = UUID.randomUUID();
        authority.reserve(scenario.workspace, missingActive, owner, providerId, Duration.ofMinutes(5),
                Duration.ofMinutes(2));
        authority.activate(scenario.workspace, missingActive);
        UUID timedOutReservation = UUID.randomUUID();
        authority.reserve(scenario.workspace, timedOutReservation, owner, providerId, Duration.ofMinutes(5),
                Duration.ofMinutes(2));
        scenario.fixture.bypassFence("update sandbox_resource set cleanup_after = clock_timestamp() - "
                + "interval '1 millisecond' where id = ?", timedOutReservation);

        SandboxCoordinator.ReconciliationResult result = coordinator.reconcile();

        assertThat(result.missingResourcesQueued()).isOne();
        assertThat(result.cleanupSucceeded()).isEqualTo(2);
        assertThat(authority.find(scenario.workspace, missingActive).orElseThrow().state()).isEqualTo(State.TERMINAL);
        assertThat(authority.find(scenario.workspace, timedOutReservation).orElseThrow().state())
                .isEqualTo(State.TERMINAL);
    }

    @Test
    void aProviderResourceAppearingAfterTheProvisioningDeadlineIsRetiredNotActivated() {
        RunRecord run = scenario.submit(Map.of());
        SandboxOwner owner = owner(run);
        UUID late = UUID.randomUUID();
        authority.reserve(scenario.workspace, late, owner, providerId, Duration.ofMinutes(5), Duration.ofMinutes(2));
        provider.provision(request(owner).withReservedSandboxId(late));
        scenario.fixture.bypassFence("update sandbox_resource set cleanup_after = clock_timestamp() - "
                + "interval '1 millisecond' where id = ?", late);

        SandboxCoordinator.ReconciliationResult result = coordinator.reconcile();

        assertThat(result.activatedReservations()).isZero();
        assertThat(result.expiredReservationsQueued()).isOne();
        assertThat(result.cleanupSucceeded()).isOne();
        assertThat(authority.find(scenario.workspace, late).orElseThrow().state()).isEqualTo(State.TERMINAL);
        assertThat(provider.resources).isEmpty();
    }

    @Test
    void uncertainCleanupIsRecordedAndRetriedWithoutFalseTerminalization() {
        RunRecord run = scenario.submit(Map.of());
        SandboxInstance instance = coordinator.provision(request(owner(run)));
        authority.requestCleanup(scenario.workspace, instance.reference().value());
        provider.failRetire.set(1);

        SandboxCoordinator.ReconciliationResult failed = coordinator.reconcile();

        var pending = authority.find(scenario.workspace, instance.reference().value()).orElseThrow();
        assertThat(failed.cleanupFailed()).isOne();
        assertThat(pending.state()).isEqualTo(State.CLEANUP_PENDING);
        assertThat(pending.cleanupFailures()).isOne();
        assertThat(pending.lastFailureCode()).contains("PROVIDER_CLEANUP_FAILED");
        assertThat(provider.resources).containsKey(instance.reference().value());

        scenario.fixture.bypassFence("update sandbox_resource set cleanup_after = clock_timestamp() - "
                + "interval '1 millisecond' where id = ?", instance.reference().value());
        SandboxCoordinator.ReconciliationResult retried = coordinator.reconcile();

        assertThat(retried.cleanupSucceeded()).isOne();
        assertThat(authority.find(scenario.workspace, instance.reference().value()).orElseThrow().state())
                .isEqualTo(State.TERMINAL);
        assertThat(provider.resources).isEmpty();
    }

    @Test
    void shutdownWaitsForInflightProvisioningThenRetiresTheAllocatedResource() throws Exception {
        RunRecord run = scenario.submit(Map.of());
        provider.provisionEntered = new CountDownLatch(1);
        provider.allowProvision = new CountDownLatch(1);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var provision = executor.submit(() -> coordinator.provision(request(owner(run))));
            assertThat(provider.provisionEntered.await(5, TimeUnit.SECONDS)).isTrue();
            var shutdown = executor.submit(coordinator::close);

            assertThatThrownBy(() -> shutdown.get(50, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            provider.allowProvision.countDown();
            SandboxInstance allocated = provision.get(5, TimeUnit.SECONDS);
            shutdown.get(5, TimeUnit.SECONDS);

            assertThat(authority.find(scenario.workspace, allocated.reference().value()).orElseThrow().state())
                    .isEqualTo(State.TERMINAL);
            assertThat(provider.resources).isEmpty();
        }
    }

    private SandboxProvisionRequest request(SandboxOwner owner) {
        SandboxConfiguration configuration = new SandboxConfiguration(providerId, "demand",
                Optional.of("example/sandbox@sha256:" + "a".repeat(64)), BigDecimal.ONE, "64Mi", 32, "NONE", "5m");
        return new SandboxProvisionRequest(owner, Optional.of(configuration), Set.of());
    }

    private SandboxOwner owner(RunRecord run) {
        UUID organization = scenario.fixture.jdbc.queryForObject(
                "select organization_id from workspace where id = ?", UUID.class, scenario.workspace.value());
        return new SandboxOwner(organization, scenario.workspace.value(), run.id(), run.pins().releaseId(),
                run.pins().revisionId(), run.pins().cohortId());
    }

    private static final class RecordingProvider implements ReconcilableSandboxProvider {
        private final Map<UUID, SandboxRef> resources = new ConcurrentHashMap<>();
        private final AtomicInteger failRetire = new AtomicInteger();
        private volatile CountDownLatch provisionEntered = new CountDownLatch(0);
        private volatile CountDownLatch allowProvision = new CountDownLatch(0);

        @Override
        public Set<com.dvarahq.agentworks.domain.sandbox.SandboxCapability> capabilities() { return Set.of(); }

        @Override
        public SandboxInstance provision(SandboxOwner owner) {
            throw new UnsupportedOperationException("reserved identity required");
        }

        @Override
        public SandboxInstance provision(SandboxProvisionRequest request) {
            UUID id = request.reservedSandboxId().orElseThrow();
            SandboxRef reference = new SandboxRef(id, request.owner());
            provisionEntered.countDown();
            try {
                if (!allowProvision.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("timed out waiting to finish provider allocation");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("provider allocation interrupted", interrupted);
            }
            resources.put(id, reference);
            return new RecordingInstance(reference);
        }

        @Override
        public SandboxInstance restore(SandboxSnapshot snapshot) { throw new UnsupportedOperationException(); }

        @Override
        public Set<SandboxRef> discoverOwnedSandboxes() { return Set.copyOf(resources.values()); }

        @Override
        public boolean isPresent(SandboxRef expected) {
            SandboxRef actual = resources.get(expected.value());
            if (actual != null && !actual.equals(expected)) throw new IllegalStateException("ownership mismatch");
            return actual != null;
        }

        @Override
        public void retire(SandboxRef expected) {
            SandboxRef actual = resources.get(expected.value());
            if (actual != null && !actual.equals(expected)) throw new IllegalStateException("ownership mismatch");
            if (failRetire.getAndUpdate(value -> Math.max(0, value - 1)) > 0) {
                throw new IllegalStateException("uncertain provider removal");
            }
            resources.remove(expected.value());
        }

        @Override
        public void close() { resources.clear(); }
    }

    private static final class RecordingInstance implements SandboxInstance {
        private final SandboxRef reference;

        private RecordingInstance(SandboxRef reference) { this.reference = reference; }
        @Override public SandboxRef reference() { return reference; }
        @Override public SandboxState state() { return SandboxState.ACTIVE; }
        @Override public Path resolveWorkspacePath(String relativePath) { return Path.of(relativePath); }
        @Override public String execute(List<String> command, Duration timeout) { return command.getFirst(); }
        @Override public SandboxSnapshot suspend() { throw new UnsupportedOperationException(); }
        @Override public void close() {}
    }
}
