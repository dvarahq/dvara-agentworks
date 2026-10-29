package com.dvarahq.agentworks.artifact.compute;

import com.dvarahq.agentworks.artifact.ArtifactComputeException;
import com.dvarahq.agentworks.artifact.ArtifactStore;
import com.dvarahq.agentworks.artifact.ArtifactStoreCapability;
import com.dvarahq.agentworks.artifact.ArtifactStoreException;
import com.dvarahq.agentworks.domain.artifact.ArtifactClassification;
import com.dvarahq.agentworks.domain.artifact.ArtifactComputeOperator;
import com.dvarahq.agentworks.domain.artifact.ArtifactComputeRequest;
import com.dvarahq.agentworks.domain.artifact.ArtifactContent;
import com.dvarahq.agentworks.domain.artifact.ArtifactDigest;
import com.dvarahq.agentworks.domain.artifact.ArtifactMetadata;
import com.dvarahq.agentworks.domain.artifact.ArtifactOwner;
import com.dvarahq.agentworks.domain.artifact.ArtifactProvenance;
import com.dvarahq.agentworks.domain.artifact.ArtifactRead;
import com.dvarahq.agentworks.domain.artifact.ArtifactRef;
import com.dvarahq.agentworks.domain.artifact.ArtifactRunBinding;
import com.dvarahq.agentworks.domain.artifact.ArtifactSearchPage;
import com.dvarahq.agentworks.domain.artifact.ArtifactSearchRequest;
import com.dvarahq.agentworks.domain.artifact.ArtifactValidation;
import com.dvarahq.agentworks.domain.artifact.ArtifactWrite;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class BoundedArtifactComputeTest {
    private static final ArtifactOwner OWNER = new ArtifactOwner(UUID.randomUUID(), UUID.randomUUID());
    private static final ArtifactRunBinding RUN = new ArtifactRunBinding(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

    @Test
    void concatenatesOnlyDeclaredInputsAndPublishesExactLineage() {
        MemoryStore store = new MemoryStore();
        ArtifactRef first = store.seed(OWNER, "hello ");
        ArtifactRef second = store.seed(OWNER, "world");
        ArtifactRef undeclared = store.seed(OWNER, "do not read");
        BoundedArtifactCompute compute = new BoundedArtifactCompute(store,
                new ArtifactComputeLimits(4, 64, 64, Duration.ofSeconds(1)));

        var result = compute.execute(request(ArtifactComputeOperator.CONCATENATE,
                List.of(first, second), "text/plain"));

        assertThat(store.text(result.output(), OWNER)).isEqualTo("hello world");
        assertThat(store.reads).containsExactly(first, second).doesNotContain(undeclared);
        ArtifactMetadata output = store.entries.get(result.output()).metadata();
        assertThat(output.provenance().source()).isEqualTo("ARTIFACT_COMPUTE");
        assertThat(output.provenance().run()).isEqualTo(RUN);
        assertThat(output.provenance().inputs()).containsExactly(first, second);
        assertThat(result.inputBytes()).isEqualTo(11);
        assertThat(result.outputBytes()).isEqualTo(11);
    }

    @Test
    void emitsDeterministicHashManifest() {
        MemoryStore store = new MemoryStore();
        ArtifactRef input = store.seed(OWNER, "abc");
        BoundedArtifactCompute compute = new BoundedArtifactCompute(store,
                new ArtifactComputeLimits(4, 64, 512, Duration.ofSeconds(1)));

        var result = compute.execute(request(ArtifactComputeOperator.SHA256_MANIFEST,
                List.of(input), "application/json"));

        assertThat(store.text(result.output(), OWNER)).isEqualTo("{\"inputs\":[{\"ref\":\"" + input.value()
                + "\",\"size\":3,\"digest\":\"" + digest("abc".getBytes(StandardCharsets.UTF_8))
                + "\"}],\"totalBytes\":3}");
    }

    @Test
    void failsClosedBeforePublishingWhenInputOrOutputBoundIsExceeded() {
        MemoryStore inputStore = new MemoryStore();
        ArtifactRef input = inputStore.seed(OWNER, "12345");
        BoundedArtifactCompute inputBound = new BoundedArtifactCompute(inputStore,
                new ArtifactComputeLimits(1, 4, 16, Duration.ofSeconds(1)));
        int before = inputStore.entries.size();

        assertThatThrownBy(() -> inputBound.execute(request(ArtifactComputeOperator.CONCATENATE,
                List.of(input), "text/plain")))
                .isInstanceOfSatisfying(ArtifactComputeException.class, failure ->
                        assertThat(failure.reason()).isEqualTo(ArtifactComputeException.Reason.INPUT_LIMIT_EXCEEDED));
        assertThat(inputStore.entries).hasSize(before);

        MemoryStore outputStore = new MemoryStore();
        ArtifactRef outputInput = outputStore.seed(OWNER, "12345");
        BoundedArtifactCompute outputBound = new BoundedArtifactCompute(outputStore,
                new ArtifactComputeLimits(1, 16, 4, Duration.ofSeconds(1)));
        assertThatThrownBy(() -> outputBound.execute(request(ArtifactComputeOperator.CONCATENATE,
                List.of(outputInput), "text/plain")))
                .isInstanceOfSatisfying(ArtifactComputeException.class, failure ->
                        assertThat(failure.reason()).isEqualTo(ArtifactComputeException.Reason.OUTPUT_LIMIT_EXCEEDED));
        assertThat(outputStore.entries).hasSize(1);
    }

    @Test
    void preservesTenantDenialAndEnforcesDeadline() {
        MemoryStore store = new MemoryStore();
        ArtifactRef input = store.seed(OWNER, "abc");
        ArtifactOwner other = new ArtifactOwner(UUID.randomUUID(), UUID.randomUUID());
        BoundedArtifactCompute compute = new BoundedArtifactCompute(store, ArtifactComputeLimits.DEFAULTS);

        ArtifactComputeRequest crossTenant = new ArtifactComputeRequest(other, "subject", RUN,
                ArtifactComputeOperator.CONCATENATE, List.of(input), "text/plain", null,
                ArtifactClassification.INTERNAL, false);
        assertThatThrownBy(() -> compute.execute(crossTenant))
                .isInstanceOfSatisfying(ArtifactStoreException.class, failure ->
                        assertThat(failure.reason()).isEqualTo(ArtifactStoreException.Reason.NOT_FOUND_OR_DENIED));

        AtomicLong clock = new AtomicLong();
        BoundedArtifactCompute expired = new BoundedArtifactCompute(store,
                new ArtifactComputeLimits(1, 16, 16, Duration.ofNanos(1)), () -> clock.getAndAdd(2));
        assertThatThrownBy(() -> expired.execute(request(ArtifactComputeOperator.CONCATENATE,
                List.of(input), "text/plain")))
                .isInstanceOfSatisfying(ArtifactComputeException.class, failure ->
                        assertThat(failure.reason()).isEqualTo(ArtifactComputeException.Reason.TIME_LIMIT_EXCEEDED));
    }

    private static ArtifactComputeRequest request(ArtifactComputeOperator operator, List<ArtifactRef> inputs,
            String mediaType) {
        return new ArtifactComputeRequest(OWNER, "subject", RUN, operator, inputs, mediaType, null,
                ArtifactClassification.INTERNAL, false);
    }

    private static String digest(byte[] bytes) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static final class MemoryStore implements ArtifactStore {
        private final Map<ArtifactRef, Entry> entries = new LinkedHashMap<>();
        private final List<ArtifactRef> reads = new ArrayList<>();

        ArtifactRef seed(ArtifactOwner owner, String value) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            return put(new ArtifactWrite(owner, "text/plain", bytes.length, new ArtifactDigest(digest(bytes)), null,
                    ArtifactClassification.INTERNAL, false, ArtifactProvenance.upload("fixture"),
                    new ByteArrayInputStream(bytes)));
        }

        String text(ArtifactRef reference, ArtifactOwner owner) {
            return new String(entries.get(reference).bytes(), StandardCharsets.UTF_8);
        }

        @Override public Set<ArtifactStoreCapability> capabilities() { return Set.of(); }

        @Override
        public ArtifactRef put(ArtifactWrite request) {
            try {
                byte[] bytes = request.content().readAllBytes();
                ArtifactRef reference = new ArtifactRef(UUID.randomUUID());
                ArtifactMetadata metadata = new ArtifactMetadata(reference, request.owner(), request.mediaType(),
                        bytes.length, new ArtifactDigest(digest(bytes)), Instant.parse("2026-10-10T00:00:00Z"),
                        request.expiresAt(), request.classification(), request.redacted(), ArtifactValidation.ACCEPTED,
                        request.provenance());
                entries.put(reference, new Entry(metadata, bytes));
                return reference;
            } catch (IOException failure) {
                throw new AssertionError(failure);
            }
        }

        @Override
        public ArtifactContent read(ArtifactRead request) {
            Entry entry = entries.get(request.reference());
            if (entry == null || !entry.metadata().owner().equals(request.owner())) {
                throw new ArtifactStoreException(ArtifactStoreException.Reason.NOT_FOUND_OR_DENIED);
            }
            reads.add(request.reference());
            return new ArtifactContent(entry.metadata(), 0, entry.bytes().length,
                    new ByteArrayInputStream(entry.bytes()));
        }

        @Override public ArtifactSearchPage search(ArtifactSearchRequest request) {
            return new ArtifactSearchPage(List.of(), false);
        }

        private record Entry(ArtifactMetadata metadata, byte[] bytes) {}
    }
}
