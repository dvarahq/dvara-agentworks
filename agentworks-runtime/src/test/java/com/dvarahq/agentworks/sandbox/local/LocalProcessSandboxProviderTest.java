package com.dvarahq.agentworks.sandbox.local;

import com.dvarahq.agentworks.domain.sandbox.SandboxCapability;
import com.dvarahq.agentworks.domain.sandbox.SandboxOwner;
import com.dvarahq.agentworks.domain.sandbox.SandboxSnapshot;
import com.dvarahq.agentworks.domain.sandbox.SandboxState;
import com.dvarahq.agentworks.sandbox.SandboxInstance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalProcessSandboxProviderTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void provisionsOnlyOnDemandAndSuccessfulExecutionIsExplicitlyRetired() throws Exception {
        Path root = temporaryDirectory.resolve("provider");
        try (LocalProcessSandboxProvider provider = new LocalProcessSandboxProvider(root)) {
            assertThat(provider.activeSandboxCount()).isZero();
            assertThat(provider.openClientCount()).isZero();

            SandboxInstance sandbox = provider.provision(owner(UUID.randomUUID(), UUID.randomUUID()));
            Path workspaceFile = sandbox.resolveWorkspacePath("result.txt");
            assertThat(provider.activeSandboxCount()).isOne();
            assertThat(provider.openClientCount()).isOne();
            assertThat(sandbox.execute(List.of("/bin/sh", "-c", "printf deterministic > result.txt"),
                    Duration.ofSeconds(2))).isEmpty();
            assertThat(Files.readString(workspaceFile)).isEqualTo("deterministic");
            assertThat(provider.liveProcessCount()).isZero();

            sandbox.close();
            sandbox.close();
            assertThat(sandbox.state()).isEqualTo(SandboxState.DESTROYED);
            assertThat(workspaceFile).doesNotExist();
            assertNoLiveResources(provider);
        }
    }

    @Test
    void snapshotCapabilityIsAdvertisedAndFilesystemStateRestoresToNewRunOwnedInstance() throws Exception {
        try (LocalProcessSandboxProvider provider = provider()) {
            assertThat(provider.capabilities()).contains(
                    SandboxCapability.PROCESS_EXECUTION,
                    SandboxCapability.FILESYSTEM_SNAPSHOT_RESTORE);
            SandboxOwner owner = owner(UUID.randomUUID(), UUID.randomUUID());
            SandboxInstance original = provider.provision(owner);
            Files.writeString(original.resolveWorkspacePath("checkpoint.txt"), "step-4");

            SandboxSnapshot snapshot = original.suspend();
            assertThat(original.state()).isEqualTo(SandboxState.DESTROYED);
            SandboxInstance restored = provider.restore(snapshot);

            assertThat(restored.reference().value()).isNotEqualTo(original.reference().value());
            assertThat(restored.reference().owner()).isEqualTo(owner);
            assertThat(Files.readString(restored.resolveWorkspacePath("checkpoint.txt"))).isEqualTo("step-4");
            restored.close();
            assertNoLiveResources(provider);
        }
    }

    @Test
    void timeoutKillsProcessAndExplicitCloseReleasesEveryOwnedHandle() {
        try (LocalProcessSandboxProvider provider = provider()) {
            SandboxInstance sandbox = provider.provision(owner(UUID.randomUUID(), UUID.randomUUID()));

            assertThatThrownBy(() -> sandbox.execute(List.of("/bin/sh", "-c", "sleep 10"),
                    Duration.ofMillis(100)))
                    .isInstanceOf(SandboxExecutionException.class)
                    .hasMessageContaining("timed out");
            assertThat(provider.liveProcessCount()).isZero();

            sandbox.close();
            assertNoLiveResources(provider);
        }
    }

    @Test
    void cancellationByDestroyKillsRunningProcessWithoutGarbageCollection() throws Exception {
        try (LocalProcessSandboxProvider provider = provider();
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            SandboxInstance sandbox = provider.provision(owner(UUID.randomUUID(), UUID.randomUUID()));
            CountDownLatch started = new CountDownLatch(1);
            var execution = executor.submit(() -> {
                started.countDown();
                return sandbox.execute(List.of("/bin/sh", "-c", "sleep 10"), Duration.ofSeconds(20));
            });
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            awaitProcess(provider);

            sandbox.close();

            assertThatThrownBy(() -> execution.get(2, TimeUnit.SECONDS)).hasCauseInstanceOf(RuntimeException.class);
            assertNoLiveResources(provider);
        }
    }

    @Test
    void partialProvisioningFailureCleansWorkspaceAndPreviouslyAcquiredResources() throws Exception {
        Path root = temporaryDirectory.resolve("partial-provider");
        AtomicBoolean clientClosed = new AtomicBoolean();
        try (LocalProcessSandboxProvider provider = new LocalProcessSandboxProvider(root,
                () -> () -> clientClosed.set(true),
                () -> { throw new SandboxExecutionException("injected post-acquisition failure"); })) {
            assertThatThrownBy(() -> provider.provision(owner(UUID.randomUUID(), UUID.randomUUID())))
                    .isInstanceOf(SandboxExecutionException.class)
                    .hasMessageContaining("injected");
            assertThat(clientClosed).isTrue();
            assertNoLiveResources(provider);
            Path sandboxes = root.resolve("sandboxes");
            try (var children = Files.list(sandboxes)) {
                assertThat(children).isEmpty();
            }
        }
    }

    @Test
    void cleanupExceptionDoesNotPreventWorkspaceProcessOrClientRetirement() {
        AtomicBoolean closeAttempted = new AtomicBoolean();
        LocalProcessSandboxProvider provider = new LocalProcessSandboxProvider(
                temporaryDirectory.resolve("cleanup-provider"), () -> () -> {
                    closeAttempted.set(true);
                    throw new IOException("injected cleanup failure");
                });
        SandboxInstance sandbox = provider.provision(owner(UUID.randomUUID(), UUID.randomUUID()));
        Path workspace = sandbox.resolveWorkspacePath("owned.txt").getParent();

        assertThatThrownBy(sandbox::close)
                .isInstanceOf(SandboxExecutionException.class)
                .hasMessageContaining("Client cleanup failed");
        assertThat(closeAttempted).isTrue();
        assertThat(workspace).doesNotExist();
        assertNoLiveResources(provider);
        provider.close();
    }

    @Test
    void providerOwnershipKeepsResourceLiveUntilExplicitRetirementEvenIfGcIsRequested() {
        LocalProcessSandboxProvider provider = provider();
        provider.provision(owner(UUID.randomUUID(), UUID.randomUUID()));

        System.gc();
        assertThat(provider.activeSandboxCount()).isOne();
        assertThat(provider.openClientCount()).isOne();

        provider.close();
        assertNoLiveResources(provider);
    }

    @Test
    void runOwnershipIsIndependentForTwoRunsPinnedToTheSameRelease() {
        try (LocalProcessSandboxProvider provider = provider()) {
            UUID releaseId = UUID.randomUUID();
            SandboxInstance first = provider.provision(owner(UUID.randomUUID(), releaseId));
            SandboxInstance second = provider.provision(owner(UUID.randomUUID(), releaseId));

            first.close();
            assertThat(provider.activeSandboxCount()).isOne();
            assertThat(second.state()).isEqualTo(SandboxState.ACTIVE);
            second.close();
            assertNoLiveResources(provider);
        }
    }

    @Test
    void workspaceRejectsTraversalAbsolutePathsAndSymlinkEscape() throws Exception {
        try (LocalProcessSandboxProvider provider = provider()) {
            SandboxInstance sandbox = provider.provision(owner(UUID.randomUUID(), UUID.randomUUID()));
            assertThatThrownBy(() -> sandbox.resolveWorkspacePath("../escape"))
                    .isInstanceOf(SecurityException.class);
            assertThatThrownBy(() -> sandbox.resolveWorkspacePath(temporaryDirectory.resolve("escape").toString()))
                    .isInstanceOf(SecurityException.class);

            Path outside = Files.createDirectory(temporaryDirectory.resolve("outside"));
            Path link = sandbox.resolveWorkspacePath("link");
            Files.createSymbolicLink(link, outside);
            assertThatThrownBy(() -> sandbox.resolveWorkspacePath("link/escape.txt"))
                    .isInstanceOf(SecurityException.class);
            sandbox.close();
        }
    }

    @Test
    void unknownSnapshotFailsHonestlyWithoutProvisioningAnything() {
        try (LocalProcessSandboxProvider provider = provider()) {
            var source = new com.dvarahq.agentworks.domain.sandbox.SandboxRef(
                    UUID.randomUUID(), owner(UUID.randomUUID(), UUID.randomUUID()));
            SandboxSnapshot unknown = new SandboxSnapshot(UUID.randomUUID(), source);

            assertThatThrownBy(() -> provider.restore(unknown))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Unknown snapshot");
            assertNoLiveResources(provider);
        }
    }

    @Test
    void restoreFailureRetiresThePartiallyProvisionedReplacement() throws Exception {
        Path root = temporaryDirectory.resolve("restore-provider");
        try (LocalProcessSandboxProvider provider = new LocalProcessSandboxProvider(root)) {
            SandboxInstance original = provider.provision(owner(UUID.randomUUID(), UUID.randomUUID()));
            Files.writeString(original.resolveWorkspacePath("checkpoint.txt"), "checkpoint");
            SandboxSnapshot snapshot = original.suspend();
            deleteTree(root.resolve("snapshots").resolve(snapshot.value().toString()));

            assertThatThrownBy(() -> provider.restore(snapshot))
                    .isInstanceOf(SandboxExecutionException.class)
                    .hasMessageContaining("Cannot copy sandbox snapshot");
            assertNoLiveResources(provider);
        }
    }

    private LocalProcessSandboxProvider provider() {
        return new LocalProcessSandboxProvider(temporaryDirectory.resolve(UUID.randomUUID().toString()));
    }

    private static SandboxOwner owner(UUID runId, UUID releaseId) {
        return new SandboxOwner(UUID.randomUUID(), UUID.randomUUID(), runId, releaseId, UUID.randomUUID(),
                UUID.randomUUID());
    }

    private static void assertNoLiveResources(LocalProcessSandboxProvider provider) {
        assertThat(provider.activeSandboxCount()).isZero();
        assertThat(provider.liveProcessCount()).isZero();
        assertThat(provider.openClientCount()).isZero();
    }

    private static void awaitProcess(LocalProcessSandboxProvider provider) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (provider.liveProcessCount() == 0 && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(provider.liveProcessCount()).isOne();
    }

    private static void deleteTree(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }
}
