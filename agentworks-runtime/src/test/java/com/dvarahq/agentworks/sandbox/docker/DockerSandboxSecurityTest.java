package com.dvarahq.agentworks.sandbox.docker;

import com.dvarahq.agentworks.domain.sandbox.SandboxOwner;
import com.dvarahq.agentworks.domain.sandbox.SandboxProvisionRequest;
import com.dvarahq.agentworks.domain.sandbox.SandboxState;
import com.dvarahq.agentworks.sandbox.SandboxInstance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DockerSandboxSecurityTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void liveContainerEnforcesIdentityFilesystemResourceAndNetworkPolicy() throws Exception {
        try (DockerSandboxProvider provider = provider(); SandboxInstance sandbox = provider.provision(owner())) {
            assertThat(sandbox.execute(List.of("id", "-u"), Duration.ofSeconds(5)).trim()).isNotEqualTo("0");
            assertThat(sandbox.execute(List.of("/bin/sh", "-c", "grep '^CapEff:' /proc/self/status"),
                    Duration.ofSeconds(5))).contains("0000000000000000");
            assertThat(provider.inspect(sandbox.reference().value(), "{{.HostConfig.NetworkMode}}"))
                    .isEqualTo("none");
            assertThat(provider.inspect(sandbox.reference().value(), "{{.Config.User}}"))
                    .isEqualTo(provider.runtimeUser());
            assertThat(provider.inspect(sandbox.reference().value(), "{{.HostConfig.ReadonlyRootfs}}"))
                    .isEqualTo("true");
            assertThat(provider.inspect(sandbox.reference().value(), "{{.HostConfig.PidsLimit}}"))
                    .isEqualTo("32");
            assertThat(provider.inspect(sandbox.reference().value(), "{{.HostConfig.NanoCpus}}"))
                    .isEqualTo("500000000");
            assertThat(provider.inspect(sandbox.reference().value(), "{{.HostConfig.Memory}}"))
                    .isEqualTo("67108864");
            assertThat(provider.inspect(sandbox.reference().value(), "{{len .Mounts}}"))
                    .isEqualTo("1");
            assertThat(provider.inspect(sandbox.reference().value(), "{{(index .Mounts 0).Destination}}"))
                    .isEqualTo("/workspace");

            assertThatThrownBy(() -> sandbox.execute(List.of("touch", "/etc/escape"), Duration.ofSeconds(5)))
                    .isInstanceOf(DockerSandboxException.class);
            assertThatThrownBy(() -> sandbox.execute(List.of("mount", "-t", "tmpfs", "none", "/workspace"),
                    Duration.ofSeconds(5))).isInstanceOf(DockerSandboxException.class);
            assertThatThrownBy(() -> sandbox.execute(List.of("/bin/sh", "-c",
                    "wget -T 1 -qO- http://192.0.2.1"), Duration.ofSeconds(5)))
                    .isInstanceOf(DockerSandboxException.class);
        }
    }

    @Test
    void traversalAndSymlinkEscapeProbesFail() throws Exception {
        try (DockerSandboxProvider provider = provider(); SandboxInstance sandbox = provider.provision(owner())) {
            assertThatThrownBy(() -> sandbox.resolveWorkspacePath("../../etc/passwd"))
                    .isInstanceOf(SecurityException.class);
            sandbox.execute(List.of("ln", "-s", "/etc", "/workspace/outside"), Duration.ofSeconds(5));
            assertThatThrownBy(() -> sandbox.resolveWorkspacePath("outside/passwd"))
                    .isInstanceOf(SecurityException.class);
        }
    }

    @Test
    void boundedTmpfsRefusesOversizedWrites() {
        try (DockerSandboxProvider provider = provider(); SandboxInstance sandbox = provider.provision(owner())) {
            assertThatThrownBy(() -> sandbox.execute(List.of("dd", "if=/dev/zero", "of=/tmp/oversized",
                    "bs=1M", "count=80"), Duration.ofSeconds(10))).isInstanceOf(DockerSandboxException.class);
        }
    }

    @Test
    void commandOutputIsDrainedButBoundedInProcess() throws Exception {
        try (DockerSandboxProvider provider = provider(); SandboxInstance sandbox = provider.provision(owner())) {
            String output = sandbox.execute(List.of("/bin/sh", "-c", "yes x | head -c 2097152"),
                    Duration.ofSeconds(10));

            assertThat(output.length()).isLessThan(1_048_600);
            assertThat(output).endsWith("[output truncated]");
        }
    }

    @Test
    void callerInterruptionRetiresTheContainerAndItsCommand() throws Exception {
        try (DockerSandboxProvider provider = provider(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            SandboxInstance sandbox = provider.provision(owner());
            var execution = executor.submit(() -> sandbox.execute(List.of("sleep", "30"), Duration.ofSeconds(60)));
            long started = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (provider.liveExecutionCount() == 0 && System.nanoTime() < started) Thread.sleep(10);

            assertThat(execution.cancel(true)).isTrue();
            long retired = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (provider.activeSandboxCount() != 0 && System.nanoTime() < retired) Thread.sleep(10);

            assertThat(provider.liveExecutionCount()).isZero();
            assertThat(provider.activeSandboxCount()).isZero();
            assertThat(sandbox.state()).isEqualTo(SandboxState.DESTROYED);
        }
    }

    @Test
    void hostilePermissionsCannotPreventWorkspaceRetirement() throws Exception {
        DockerSandboxProvider provider = provider();
        SandboxInstance sandbox = provider.provision(owner());
        Path workspace = sandbox.resolveWorkspacePath("");
        sandbox.execute(List.of("/bin/sh", "-c",
                "mkdir /workspace/locked && touch /workspace/locked/secret && chmod 000 /workspace/locked"),
                Duration.ofSeconds(5));

        sandbox.close();

        assertThat(provider.activeSandboxCount()).isZero();
        assertThat(workspace).doesNotExist();
        provider.close();
    }

    @Test
    void maximumRuntimeRetiresAnIdleContainerWithoutGarbageCollection() throws Exception {
        var expiring = new com.dvarahq.agentworks.domain.sandbox.SandboxConfiguration("docker", "demand",
                Optional.of(DockerSandboxTestSupport.ALPINE_IMAGE), new BigDecimal("0.5"), "64Mi", 32, "NONE",
                "200ms");
        try (DockerSandboxProvider provider = new DockerSandboxProvider(
                temporaryDirectory.resolve(UUID.randomUUID().toString()), expiring)) {
            SandboxInstance sandbox = provider.provision(owner());

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (provider.activeSandboxCount() != 0 && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }

            assertThat(provider.activeSandboxCount()).isZero();
            assertThat(sandbox.state()).isEqualTo(SandboxState.DESTROYED);
        }
    }

    @Test
    void aFreshProviderDiscoversAndRetiresTheExactReservedContainer() {
        Path root = temporaryDirectory.resolve(UUID.randomUUID().toString());
        UUID reserved = UUID.randomUUID();
        SandboxOwner owner = owner();
        try (DockerSandboxProvider original = new DockerSandboxProvider(root,
                DockerSandboxTestSupport.configuration());
                DockerSandboxProvider restarted = new DockerSandboxProvider(root,
                        DockerSandboxTestSupport.configuration())) {
            SandboxInstance sandbox = original.provision(new SandboxProvisionRequest(owner, Set.of())
                    .withReservedSandboxId(reserved));

            assertThat(restarted.discoverOwnedSandboxes()).contains(sandbox.reference());
            assertThat(restarted.isPresent(sandbox.reference())).isTrue();

            restarted.retire(sandbox.reference());

            assertThat(restarted.isPresent(sandbox.reference())).isFalse();
        }
    }

    private DockerSandboxProvider provider() {
        return new DockerSandboxProvider(temporaryDirectory.resolve(UUID.randomUUID().toString()),
                DockerSandboxTestSupport.configuration());
    }

    private static SandboxOwner owner() {
        return new SandboxOwner(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID());
    }
}
