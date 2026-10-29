package com.dvarahq.agentworks.sandbox.kubernetes;

import com.dvarahq.agentworks.domain.sandbox.SandboxCapability;
import com.dvarahq.agentworks.domain.sandbox.SandboxOwner;
import com.dvarahq.agentworks.domain.sandbox.SandboxProvisionRequest;
import com.dvarahq.agentworks.domain.sandbox.SandboxRef;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KubernetesSandboxProviderTest {
    @Test
    void enforcesWorkspacePolicyBeforeAllocationAndUsesReservedIdentity() {
        UUID permitted = UUID.randomUUID();
        var control = new KubernetesSandboxTestSupport.FakeControl();
        try (var provider = new KubernetesSandboxProvider(KubernetesSandboxTestSupport.configuration(),
                permitted::equals, control)) {
            SandboxOwner denied = owner(UUID.randomUUID());
            assertThatThrownBy(() -> provider.provision(denied)).isInstanceOf(SecurityException.class);
            assertThat(control.resources).isEmpty();

            UUID reserved = UUID.randomUUID();
            SandboxOwner owner = owner(permitted);
            var request = new SandboxProvisionRequest(owner,
                    Optional.of(KubernetesSandboxTestSupport.configuration()), Set.of(
                            SandboxCapability.HOSTILE_CODE_ISOLATION,
                            SandboxCapability.NETWORK_EGRESS_CONTROL), Optional.of(reserved));
            var sandbox = provider.provision(request);

            assertThat(sandbox.reference()).isEqualTo(new SandboxRef(reserved, owner));
            assertThat(control.lastConfiguration.image()).contains(KubernetesSandboxTestSupport.IMAGE);
            assertThat(sandbox.resolveWorkspacePath("outputs/result.json").toString())
                    .isEqualTo("/workspace/outputs/result.json");
            assertThatThrownBy(() -> sandbox.resolveWorkspacePath("../escape")).isInstanceOf(SecurityException.class);
        }
    }

    @Test
    void advertisesOnlyPortableKubernetesAssurances() {
        var control = new KubernetesSandboxTestSupport.FakeControl();
        try (var provider = new KubernetesSandboxProvider(KubernetesSandboxTestSupport.configuration(),
                ignored -> true, control)) {
            assertThat(provider.capabilities()).contains(
                    SandboxCapability.HOSTILE_CODE_ISOLATION,
                    SandboxCapability.DIGEST_PINNED_IMAGE,
                    SandboxCapability.NON_ROOT_IDENTITY,
                    SandboxCapability.CPU_LIMIT,
                    SandboxCapability.MEMORY_LIMIT,
                    SandboxCapability.NETWORK_EGRESS_CONTROL);
            assertThat(provider.capabilities()).doesNotContain(
                    SandboxCapability.PID_LIMIT,
                    SandboxCapability.FILESYSTEM_SNAPSHOT_RESTORE,
                    SandboxCapability.DURABLE_SNAPSHOT_RESTORE);
        }
    }

    @Test
    void refusesCrossOwnerAdoptionAndCleanup() {
        var control = new KubernetesSandboxTestSupport.FakeControl();
        try (var provider = new KubernetesSandboxProvider(KubernetesSandboxTestSupport.configuration(),
                ignored -> true, control)) {
            SandboxOwner owner = owner(UUID.randomUUID());
            UUID id = UUID.randomUUID();
            SandboxRef actual = new SandboxRef(id, owner);
            control.resources.put(id, actual);
            SandboxRef forged = new SandboxRef(id, owner(UUID.randomUUID()));

            assertThatThrownBy(() -> provider.isPresent(forged))
                    .isInstanceOf(KubernetesSandboxException.class).hasMessageContaining("ownership mismatch");
            assertThatThrownBy(() -> provider.retire(forged))
                    .isInstanceOf(KubernetesSandboxException.class).hasMessageContaining("ownership mismatch");
            assertThat(control.resources).containsEntry(id, actual);
        }
    }

    @Test
    void retainsCleanupAuthorityWhenDeletionIsUncertain() {
        var control = new KubernetesSandboxTestSupport.FakeControl();
        var provider = new KubernetesSandboxProvider(KubernetesSandboxTestSupport.configuration(), ignored -> true,
                control);
        var sandbox = provider.provision(owner(UUID.randomUUID()));
        control.removeFailure = new KubernetesSandboxException("uncertain deletion");

        assertThatThrownBy(sandbox::close).isInstanceOf(KubernetesSandboxException.class);
        assertThat(provider.activeSandboxCount()).isOne();
        assertThat(sandbox.state()).isEqualTo(com.dvarahq.agentworks.domain.sandbox.SandboxState.ACTIVE);

        control.removeFailure = null;
        provider.close();
        assertThat(control.resources).isEmpty();
    }

    @Test
    void commandFailurePreservesHealthySandboxButTimeoutRetiresIt() {
        var control = new KubernetesSandboxTestSupport.FakeControl();
        try (var provider = new KubernetesSandboxProvider(KubernetesSandboxTestSupport.configuration(),
                ignored -> true, control)) {
            var sandbox = provider.provision(owner(UUID.randomUUID()));
            control.executionFailure = new KubernetesSandboxException("exit 2");

            assertThatThrownBy(() -> sandbox.execute(java.util.List.of("false"), Duration.ofSeconds(1)))
                    .isInstanceOf(KubernetesSandboxException.class).hasMessage("exit 2");
            assertThat(control.resources).containsKey(sandbox.reference().value());

            control.executionFailure = new KubernetesSandboxTimeoutException("timed out");
            assertThatThrownBy(() -> sandbox.execute(java.util.List.of("sleep"), Duration.ofSeconds(1)))
                    .isInstanceOf(KubernetesSandboxTimeoutException.class);
            assertThat(control.resources).doesNotContainKey(sandbox.reference().value());
        }
    }

    private static SandboxOwner owner(UUID workspace) {
        return new SandboxOwner(UUID.randomUUID(), workspace, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID());
    }
}
