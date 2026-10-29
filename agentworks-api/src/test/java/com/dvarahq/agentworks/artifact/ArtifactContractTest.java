package com.dvarahq.agentworks.artifact;

import com.dvarahq.agentworks.domain.artifact.ArtifactClassification;
import com.dvarahq.agentworks.domain.artifact.ArtifactDigest;
import com.dvarahq.agentworks.domain.artifact.ArtifactOwner;
import com.dvarahq.agentworks.domain.artifact.ArtifactProvenance;
import com.dvarahq.agentworks.domain.artifact.ArtifactRead;
import com.dvarahq.agentworks.domain.artifact.ArtifactRef;
import com.dvarahq.agentworks.domain.artifact.ArtifactRunBinding;
import com.dvarahq.agentworks.domain.artifact.ArtifactSearchRequest;
import com.dvarahq.agentworks.domain.artifact.ArtifactWrite;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArtifactContractTest {

    @Test
    void digestMediaTypeRangesAndPageBoundsFailBeforeAStoreCall() {
        ArtifactOwner owner = owner();
        ArtifactRef reference = new ArtifactRef(UUID.randomUUID());

        assertThatThrownBy(() -> new ArtifactDigest("sha256:abc")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> write(owner, "text/plain; charset=utf-8"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ArtifactRead(owner, reference, -1, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ArtifactRead(owner, reference, 0, 0L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ArtifactSearchRequest.first(owner, ArtifactSearchRequest.MAX_LIMIT + 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void pageCursorIsCompleteAndMediaFiltersAreImmutable() {
        ArtifactOwner owner = owner();
        Set<String> mediaTypes = new java.util.HashSet<>(Set.of("text/plain"));
        Instant position = Instant.parse("2026-10-09T12:00:00Z");
        UUID id = UUID.randomUUID();
        ArtifactSearchRequest request = new ArtifactSearchRequest(owner, mediaTypes,
                ArtifactClassification.INTERNAL, position, id, 50);
        mediaTypes.clear();

        assertThat(request.mediaTypes()).containsExactly("text/plain");
        assertThatThrownBy(() -> new ArtifactSearchRequest(owner, Set.of(), null, position, null, 10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void runtimeProvenanceKeepsExactGenerationAndImmutableDistinctInputs() {
        ArtifactRunBinding run = new ArtifactRunBinding(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID());
        ArtifactRef input = new ArtifactRef(UUID.randomUUID());
        List<ArtifactRef> inputs = new java.util.ArrayList<>(List.of(input));
        ArtifactProvenance provenance = new ArtifactProvenance("ARTIFACT_COMPUTE", "run-plane:test", run, inputs);
        inputs.clear();

        assertThat(provenance.run()).isEqualTo(run);
        assertThat(provenance.inputs()).containsExactly(input);
        assertThatThrownBy(() -> new ArtifactProvenance("ARTIFACT_COMPUTE", "run-plane:test", run,
                List.of(input, input))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void contentStreamNeverAppearsInWriteDiagnostics() {
        String secret = "artifact-secret-canary-41b2";
        ArtifactWrite write = new ArtifactWrite(owner(), "text/plain", secret.length(), null, null,
                ArtifactClassification.RESTRICTED, true, ArtifactProvenance.upload("user:test"),
                new ByteArrayInputStream(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        assertThat(write.toString()).doesNotContain(secret, "ByteArrayInputStream");
    }

    @Test
    void deniedAndMissingShareOneTypedFailure() {
        ArtifactStoreException failure = new ArtifactStoreException(
                ArtifactStoreException.Reason.NOT_FOUND_OR_DENIED);

        assertThat(failure.reason()).isEqualTo(ArtifactStoreException.Reason.NOT_FOUND_OR_DENIED);
        assertThat(failure).hasMessage("artifact operation failed: NOT_FOUND_OR_DENIED");
        assertThat(failure.getCause()).isNull();
    }

    private static ArtifactWrite write(ArtifactOwner owner, String mediaType) {
        return new ArtifactWrite(owner, mediaType, 0, null, null, ArtifactClassification.INTERNAL, false,
                ArtifactProvenance.upload("user:test"), new ByteArrayInputStream(new byte[0]));
    }

    private static ArtifactOwner owner() {
        return new ArtifactOwner(UUID.randomUUID(), UUID.randomUUID());
    }
}
