package com.dvarahq.agentworks.sandbox.docker;

import com.dvarahq.agentworks.domain.sandbox.SandboxCapability;
import com.dvarahq.agentworks.domain.sandbox.SandboxConfiguration;
import com.dvarahq.agentworks.domain.sandbox.SandboxOwner;
import com.dvarahq.agentworks.domain.sandbox.SandboxProvisionRequest;
import com.dvarahq.agentworks.domain.sandbox.SandboxRef;
import com.dvarahq.agentworks.domain.sandbox.SandboxState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DockerSandboxProviderTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void requestSpecificPinnedConfigurationReachesTheDockerBoundary() {
        RecordingDockerCli docker = new RecordingDockerCli();
        SandboxConfiguration requested = new SandboxConfiguration("docker", "demand",
                Optional.of(DockerSandboxTestSupport.ALPINE_IMAGE), new BigDecimal("1.25"), "96Mi", 47, "NONE",
                "30s");
        try (DockerSandboxProvider provider = new DockerSandboxProvider(temporaryDirectory.resolve("provider"),
                DockerSandboxTestSupport.configuration(), docker)) {
            var sandbox = provider.provision(new SandboxProvisionRequest(owner(), Optional.of(requested),
                    Set.of(SandboxCapability.HOSTILE_CODE_ISOLATION)));

            assertThat(docker.configuration).isEqualTo(requested);
            sandbox.close();
        }
    }

    @Test
    void durableReservationControlsTheProviderAndContainerIdentity() {
        RecordingDockerCli docker = new RecordingDockerCli();
        SandboxOwner owner = owner();
        UUID reserved = UUID.randomUUID();
        try (DockerSandboxProvider provider = new DockerSandboxProvider(temporaryDirectory.resolve("provider"),
                DockerSandboxTestSupport.configuration(), docker)) {
            SandboxProvisionRequest request = new SandboxProvisionRequest(owner, Set.of())
                    .withReservedSandboxId(reserved);

            var sandbox = provider.provision(request);

            assertThat(sandbox.reference()).isEqualTo(new SandboxRef(reserved, owner));
            assertThat(docker.createdReference).isEqualTo(sandbox.reference());
            assertThat(docker.createdName).isEqualTo("dvara-sandbox-" + reserved);
            sandbox.close();
        }
    }

    @Test
    void reconciliationDelegatesOnlyExactOwnerIdentity() {
        RecordingDockerCli docker = new RecordingDockerCli();
        SandboxRef expected = new SandboxRef(UUID.randomUUID(), owner());
        docker.discovered = Set.of(expected);
        docker.present = expected;
        try (DockerSandboxProvider provider = new DockerSandboxProvider(temporaryDirectory.resolve("provider"),
                DockerSandboxTestSupport.configuration(), docker)) {
            assertThat(provider.discoverOwnedSandboxes()).containsExactly(expected);
            assertThat(provider.isPresent(expected)).isTrue();

            provider.retire(expected);

            assertThat(docker.retired).isEqualTo(expected);
        }
    }

    @Test
    void uncertainContainerRemovalStaysVisibleAndCanBeRetried() {
        RecordingDockerCli docker = new RecordingDockerCli();
        docker.failFirstRemoval = true;
        DockerSandboxProvider provider = new DockerSandboxProvider(temporaryDirectory.resolve("provider"),
                DockerSandboxTestSupport.configuration(), docker);
        var sandbox = provider.provision(owner());

        assertThatThrownBy(provider::close).isInstanceOf(DockerSandboxException.class)
                .hasMessageContaining("uncertain removal");
        assertThat(provider.activeSandboxCount()).isOne();
        assertThat(sandbox.state()).isEqualTo(SandboxState.ACTIVE);
        assertThat(sandbox.resolveWorkspacePath("retained").getParent()).exists();

        provider.close();
        assertThat(provider.activeSandboxCount()).isZero();
        assertThat(sandbox.state()).isEqualTo(SandboxState.DESTROYED);
        assertThat(docker.removals).hasValue(2);
    }

    @Test
    void providerSpecificMemoryMinimumFailsBeforeDockerAllocation() {
        RecordingDockerCli docker = new RecordingDockerCli();
        SandboxConfiguration tooSmall = new SandboxConfiguration("docker", "demand",
                Optional.of(DockerSandboxTestSupport.ALPINE_IMAGE), BigDecimal.ONE, "1Mi", 32, "NONE", "5m");
        try (DockerSandboxProvider provider = new DockerSandboxProvider(temporaryDirectory.resolve("provider"),
                DockerSandboxTestSupport.configuration(), docker)) {
            assertThatThrownBy(() -> provider.provision(new SandboxProvisionRequest(owner(), Optional.of(tooSmall),
                    Set.of()))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("at least 6Mi");
            assertThat(provider.activeSandboxCount()).isZero();
            assertThat(docker.configuration).isNull();
        }
    }

    private static SandboxOwner owner() {
        return new SandboxOwner(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID());
    }

    private static final class RecordingDockerCli extends DockerCli {
        private final AtomicInteger removals = new AtomicInteger();
        private SandboxConfiguration configuration;
        private SandboxRef createdReference;
        private String createdName;
        private Set<SandboxRef> discovered = Set.of();
        private SandboxRef present;
        private SandboxRef retired;
        private boolean failFirstRemoval;

        @Override
        void create(String name, Path workspace, SandboxRef reference, SandboxConfiguration configuration,
                DockerRuntimeIdentity identity) {
            this.configuration = configuration;
            this.createdReference = reference;
            this.createdName = name;
        }

        @Override
        void start(String name) {}

        @Override
        String execute(String name, List<String> command, Duration timeout) {
            return "";
        }

        @Override
        void remove(String name) {
            if (failFirstRemoval && removals.incrementAndGet() == 1) {
                throw new DockerSandboxException("uncertain removal");
            }
            if (!failFirstRemoval) removals.incrementAndGet();
        }

        @Override
        Set<SandboxRef> discoverOwnedSandboxes() {
            return discovered;
        }

        @Override
        boolean isPresent(SandboxRef expected) {
            return expected.equals(present);
        }

        @Override
        void remove(SandboxRef expected) {
            retired = expected;
            if (failFirstRemoval && removals.incrementAndGet() == 1) {
                throw new DockerSandboxException("uncertain removal");
            }
            if (!failFirstRemoval) removals.incrementAndGet();
        }
    }
}
