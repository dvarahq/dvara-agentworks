package com.dvarahq.agentworks.sandbox;

import com.dvarahq.agentworks.artifact.ArtifactStore;
import com.dvarahq.agentworks.artifact.ArtifactStoreCapability;
import com.dvarahq.agentworks.domain.artifact.ArtifactContent;
import com.dvarahq.agentworks.domain.artifact.ArtifactRead;
import com.dvarahq.agentworks.domain.artifact.ArtifactRef;
import com.dvarahq.agentworks.domain.artifact.ArtifactSearchPage;
import com.dvarahq.agentworks.domain.artifact.ArtifactSearchRequest;
import com.dvarahq.agentworks.domain.artifact.ArtifactWrite;
import com.dvarahq.agentworks.domain.sandbox.SandboxOwner;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

final class SandboxResultArtifactBindingTest {
    @Test
    void offloadsLargeOutputUnderExactSandboxOwnerAndRunPins() {
        SandboxOwner owner = new SandboxOwner(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        CaptureStore store = new CaptureStore();
        SandboxResultArtifactBinding binding = new SandboxResultArtifactBinding(store, owner, "sandbox:test", 4);

        String summary = binding.offloadIfLarge("sandbox-secret-output");

        assertThat(summary).contains(store.reference.value().toString()).doesNotContain("sandbox-secret-output");
        assertThat(store.content).isEqualTo("sandbox-secret-output".getBytes(StandardCharsets.UTF_8));
        assertThat(store.write.owner().organizationId()).isEqualTo(owner.organizationId());
        assertThat(store.write.owner().workspaceId()).isEqualTo(owner.workspaceId());
        assertThat(store.write.provenance().source()).isEqualTo("SANDBOX_OUTPUT");
        assertThat(store.write.provenance().run().runId()).isEqualTo(owner.runId());
        assertThat(store.write.provenance().run().releaseId()).isEqualTo(owner.releaseId());
        assertThat(store.write.provenance().run().deploymentRevisionId()).isEqualTo(owner.deploymentRevisionId());
        assertThat(store.write.provenance().run().cohortId()).isEqualTo(owner.cohortId());
    }

    private static final class CaptureStore implements ArtifactStore {
        private final ArtifactRef reference = new ArtifactRef(UUID.randomUUID());
        private ArtifactWrite write;
        private byte[] content;

        @Override public Set<ArtifactStoreCapability> capabilities() { return Set.of(); }

        @Override
        public ArtifactRef put(ArtifactWrite request) {
            write = request;
            try {
                content = request.content().readAllBytes();
            } catch (IOException failure) {
                throw new AssertionError(failure);
            }
            return reference;
        }

        @Override public ArtifactContent read(ArtifactRead request) { throw new UnsupportedOperationException(); }

        @Override public ArtifactSearchPage search(ArtifactSearchRequest request) {
            throw new UnsupportedOperationException();
        }
    }
}
