package com.dvarahq.agentworks.sandbox.docker;

import com.dvarahq.agentworks.domain.sandbox.SandboxOwner;
import com.dvarahq.agentworks.domain.sandbox.SandboxRef;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DockerCliTest {
    @Test
    void createCommandClosesEveryHostAndResourceBoundary() {
        SandboxOwner owner = new SandboxOwner(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        Path workspace = Path.of("/private/provider/sandboxes/one");

        UUID sandboxId = UUID.randomUUID();
        List<String> command = DockerCli.createCommand("dvara-sandbox-one", workspace, new SandboxRef(sandboxId, owner),
                DockerSandboxTestSupport.configuration(), new DockerCli.DockerRuntimeIdentity(65532, 65532, true));

        assertThat(command).containsSubsequence("docker", "create", "--name", "dvara-sandbox-one");
        assertThat(command).containsSubsequence("--network", "none", "--ipc", "none", "--read-only", "--cap-drop", "ALL",
                "--security-opt", "no-new-privileges:true", "--user", "65532:65532");
        assertThat(command).containsSubsequence("--pids-limit", "32", "--cpus", "0.5", "--memory", "67108864");
        assertThat(command).contains("--mount",
                "type=bind,src=" + workspace + ",dst=/workspace,bind-propagation=rprivate");
        assertThat(command).contains("--tmpfs", "/tmp:rw,noexec,nosuid,nodev,size=67108864");
        assertThat(command).contains("--label", "io.dvara.sandbox-id=" + sandboxId,
                "--label", "io.dvara.organization=" + owner.organizationId(),
                "--label", "io.dvara.workspace=" + owner.workspaceId(),
                "--label", "io.dvara.run=" + owner.runId(),
                "--label", "io.dvara.release=" + owner.releaseId(),
                "--label", "io.dvara.deployment-revision=" + owner.deploymentRevisionId(),
                "--label", "io.dvara.cohort=" + owner.cohortId());
        assertThat(command).contains(DockerSandboxTestSupport.ALPINE_IMAGE);
    }

    @Test
    void discoveryReconstructsEveryImmutableOwnerPin() {
        SandboxRef expected = reference();
        RecordingDockerCli docker = new RecordingDockerCli(expected);

        assertThat(docker.discoverOwnedSandboxes()).isEqualTo(Set.of(expected));
        assertThat(docker.isPresent(expected)).isTrue();
    }

    @Test
    void retirementRefusesAContainerWhoseOwnerLabelsDoNotMatch() {
        SandboxRef expected = reference();
        SandboxRef otherOwner = new SandboxRef(expected.value(), owner());
        RecordingDockerCli docker = new RecordingDockerCli(otherOwner);

        assertThatThrownBy(() -> docker.remove(expected)).isInstanceOf(DockerSandboxException.class)
                .hasMessageContaining("ownership mismatch");
        assertThat(docker.removed).isFalse();
    }

    @Test
    void malformedOwnedLabelsFailDiscoveryInsteadOfHidingAResource() {
        RecordingDockerCli docker = new RecordingDockerCli(null);
        docker.malformed = true;

        assertThatThrownBy(docker::discoverOwnedSandboxes).isInstanceOf(DockerSandboxException.class)
                .hasMessageContaining("malformed ownership labels");
    }

    private static SandboxRef reference() {
        return new SandboxRef(UUID.randomUUID(), owner());
    }

    private static SandboxOwner owner() {
        return new SandboxOwner(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID());
    }

    private static final class RecordingDockerCli extends DockerCli {
        private final SandboxRef actual;
        private boolean malformed;
        private boolean removed;

        private RecordingDockerCli(SandboxRef actual) {
            this.actual = actual;
        }

        @Override
        Result runControl(List<String> command) {
            if (command.contains("ps")) return new Result(0, "container-one", false);
            if (malformed) return new Result(0, "not-an-owner", false);
            SandboxOwner owner = actual.owner();
            return new Result(0, String.join("|", actual.value().toString(), owner.organizationId().toString(),
                    owner.workspaceId().toString(), owner.runId().toString(), owner.releaseId().toString(),
                    owner.deploymentRevisionId().toString(), owner.cohortId().toString()), false);
        }

        @Override
        void remove(String name) {
            removed = true;
        }
    }
}
