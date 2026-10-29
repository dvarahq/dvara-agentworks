package com.dvarahq.agentworks.sandbox.conformance;

import com.dvarahq.agentworks.domain.sandbox.SandboxCapability;
import com.dvarahq.agentworks.domain.sandbox.SandboxOwner;
import com.dvarahq.agentworks.domain.sandbox.SandboxProvisionRequest;
import com.dvarahq.agentworks.domain.sandbox.SandboxState;
import com.dvarahq.agentworks.sandbox.SandboxCapabilityUnavailableException;
import com.dvarahq.agentworks.sandbox.SandboxInstance;
import com.dvarahq.agentworks.sandbox.SandboxProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Provider-neutral sandbox lifecycle TCK. Every provider must prove demand provisioning, honest capability
 * negotiation, run ownership, timeout/cancellation cleanup, and the exact snapshot assurance it advertises.
 */
public abstract class SandboxProviderConformanceSuite {
    private SandboxProviderConformanceHarness harness;
    private SandboxProvider provider;

    protected abstract SandboxProviderConformanceHarness createHarness();

    @BeforeEach
    void startProvider() {
        harness = createHarness();
        provider = harness.provider();
    }

    @AfterEach
    void stopProvider() throws Exception {
        harness.close();
    }

    @Test
    void resourcesExistOnlyOnDemandAndNormalCloseRetiresThem() throws Exception {
        assertNoResources();

        SandboxInstance sandbox = provider.provision(owner());
        assertThat(harness.activeResourceCount()).isOne();
        assertThat(sandbox.execute(harness.outputCommand("sandbox-tck"), Duration.ofSeconds(2)))
                .contains("sandbox-tck");
        assertThat(harness.liveExecutionCount()).isZero();

        sandbox.close();
        sandbox.close();
        assertThat(sandbox.state()).isEqualTo(SandboxState.DESTROYED);
        assertNoResources();
    }

    @Test
    void unsupportedAssuranceIsRejectedBeforeAnyResourceIsAcquired() {
        SandboxCapability unsupported = java.util.Arrays.stream(SandboxCapability.values())
                .filter(capability -> !provider.capabilities().contains(capability))
                .findFirst().orElse(null);
        if (unsupported == null) {
            return;
        }

        assertThatThrownBy(() -> provider.provision(SandboxProvisionRequest.requiring(owner(), unsupported)))
                .isInstanceOf(SandboxCapabilityUnavailableException.class)
                .hasMessageContaining(unsupported.name());
        assertNoResources();
    }

    @Test
    void twoRunOwnersRemainIndependent() {
        SandboxInstance first = provider.provision(owner());
        SandboxInstance second = provider.provision(owner());
        assertThat(first.reference().owner()).isNotEqualTo(second.reference().owner());

        first.close();
        assertThat(second.state()).isEqualTo(SandboxState.ACTIVE);
        assertThat(harness.activeResourceCount()).isOne();
        second.close();
        assertNoResources();
    }

    @Test
    void timeoutKillsExecutionAndExplicitCloseRetiresTheSandbox() {
        SandboxInstance sandbox = provider.provision(owner());

        assertThatThrownBy(() -> sandbox.execute(harness.longRunningCommand(), Duration.ofMillis(100)))
                .isInstanceOf(RuntimeException.class);
        assertThat(harness.liveExecutionCount()).isZero();

        sandbox.close();
        assertNoResources();
    }

    @Test
    void providerCloseCancelsExecutionAndRetiresEveryResource() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            SandboxInstance sandbox = provider.provision(owner());
            var execution = executor.submit(() -> sandbox.execute(harness.longRunningCommand(),
                    Duration.ofSeconds(20)));
            awaitExecution();

            provider.close();

            assertThatThrownBy(() -> execution.get(2, TimeUnit.SECONDS)).isInstanceOf(Exception.class);
            assertNoResources();
        }
    }

    @Test
    void advertisedFilesystemSnapshotRestoresUnderTheSameOwner() {
        if (!provider.capabilities().contains(SandboxCapability.FILESYSTEM_SNAPSHOT_RESTORE)) {
            return;
        }
        SandboxInstance original = provider.provision(owner());
        var snapshot = original.suspend();
        assertThat(original.state()).isEqualTo(SandboxState.DESTROYED);

        SandboxInstance restored = provider.restore(snapshot);
        assertThat(restored.reference().value()).isNotEqualTo(original.reference().value());
        assertThat(restored.reference().owner()).isEqualTo(original.reference().owner());
        restored.close();
        assertNoResources();
    }

    private void awaitExecution() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (harness.liveExecutionCount() == 0 && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(harness.liveExecutionCount()).isOne();
    }

    private void assertNoResources() {
        assertThat(harness.activeResourceCount()).isZero();
        assertThat(harness.liveExecutionCount()).isZero();
    }

    protected static SandboxOwner owner() {
        return new SandboxOwner(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID());
    }
}
