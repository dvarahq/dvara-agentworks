package com.dvarahq.agentworks.agent.springai;

import com.dvarahq.agentworks.artifact.ArtifactStore;
import com.dvarahq.agentworks.artifact.ArtifactStoreCapability;
import com.dvarahq.agentworks.domain.artifact.ArtifactContent;
import com.dvarahq.agentworks.domain.artifact.ArtifactRef;
import com.dvarahq.agentworks.domain.artifact.ArtifactSearchPage;
import com.dvarahq.agentworks.domain.artifact.ArtifactSearchRequest;
import com.dvarahq.agentworks.domain.artifact.ArtifactWrite;
import com.dvarahq.agentworks.domain.run.RunInvoker;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

final class ToolResultArtifactBindingTest {
    @Test
    void binaryEnvelopeIsOffloadedEvenBelowTheInlineThreshold() {
        RecordingRunContext context = new RecordingRunContext();
        CaptureStore store = new CaptureStore();
        ToolResultArtifactBinding binding = binding(context, store, 1024);
        String envelope = "{\"encoding\":\"base64\",\"body\":\"AAH/\"}";
        assertThat(binding.offload(envelope, true)).contains("artifactRef").doesNotContain("AAH/");
        assertThat(store.content).isEqualTo(envelope.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void leavesBoundedResultsInline() {
        RecordingRunContext context = new RecordingRunContext();
        CaptureStore store = new CaptureStore();
        ToolResultArtifactBinding binding = binding(context, store, 8);

        assertThat(binding.offloadIfLarge("small")).isEqualTo("small");
        assertThat(store.write).isNull();
    }

    @Test
    void offloadsLargeUtf8ResultWithExactRunProvenanceAndReturnsContentFreeSummary() {
        RecordingRunContext context = new RecordingRunContext();
        CaptureStore store = new CaptureStore();
        ToolResultArtifactBinding binding = binding(context, store, 4);
        String sensitive = "secret-canary-😀";

        String summary = binding.offloadIfLarge(sensitive);

        assertThat(summary).contains("\"artifactRef\":\"" + store.reference.value() + "\"")
                .contains("\"bytes\":" + sensitive.getBytes(StandardCharsets.UTF_8).length)
                .contains("\"digest\":\"sha256:")
                .doesNotContain("secret-canary");
        assertThat(store.content).isEqualTo(sensitive.getBytes(StandardCharsets.UTF_8));
        assertThat(store.write.owner().workspaceId()).isEqualTo(context.pins.workspaceId());
        assertThat(store.write.classification().name()).isEqualTo("CONFIDENTIAL");
        assertThat(store.write.provenance().source()).isEqualTo("TOOL_RESULT");
        assertThat(store.write.provenance().run().runId()).isEqualTo(context.pins.id());
        assertThat(store.write.provenance().run().releaseId()).isEqualTo(context.pins.releaseId());
        assertThat(store.write.provenance().run().deploymentRevisionId()).isEqualTo(context.pins.revisionId());
        assertThat(store.write.provenance().run().cohortId()).isEqualTo(context.pins.cohortId());
    }

    private static ToolResultArtifactBinding binding(RecordingRunContext context, CaptureStore store,
            int inlineBytes) {
        RunInvoker invoker = new RunInvoker(UUID.randomUUID(), context.pins.workspaceId(), UUID.randomUUID(),
                UUID.randomUUID(), "api-token");
        return new ToolResultArtifactBinding(store, context.pins, invoker, inlineBytes);
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

        @Override public ArtifactContent read(com.dvarahq.agentworks.domain.artifact.ArtifactRead request) {
            throw new UnsupportedOperationException();
        }

        @Override public ArtifactSearchPage search(ArtifactSearchRequest request) {
            throw new UnsupportedOperationException();
        }
    }
}
