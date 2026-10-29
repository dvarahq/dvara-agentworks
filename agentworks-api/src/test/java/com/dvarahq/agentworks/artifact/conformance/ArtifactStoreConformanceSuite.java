package com.dvarahq.agentworks.artifact.conformance;

import com.dvarahq.agentworks.artifact.ArtifactStore;
import com.dvarahq.agentworks.artifact.ArtifactStoreCapability;
import com.dvarahq.agentworks.artifact.ArtifactStoreException;
import com.dvarahq.agentworks.domain.artifact.ArtifactClassification;
import com.dvarahq.agentworks.domain.artifact.ArtifactDigest;
import com.dvarahq.agentworks.domain.artifact.ArtifactOwner;
import com.dvarahq.agentworks.domain.artifact.ArtifactProvenance;
import com.dvarahq.agentworks.domain.artifact.ArtifactRead;
import com.dvarahq.agentworks.domain.artifact.ArtifactRef;
import com.dvarahq.agentworks.domain.artifact.ArtifactSearchRequest;
import com.dvarahq.agentworks.domain.artifact.ArtifactWrite;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Backend-neutral ArtifactStore TCK. It proves atomic integrity publication, tenant isolation, bounded range reads,
 * quarantine, and stable keyset pagination without prescribing JDBC or content storage technology.
 */
public abstract class ArtifactStoreConformanceSuite {
    protected static final ArtifactDigest HELLO_DIGEST = new ArtifactDigest(
            "sha256:2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");

    private ArtifactStoreConformanceHarness harness;
    private ArtifactStore store;

    protected abstract ArtifactStoreConformanceHarness createHarness();

    @BeforeEach
    void startStore() {
        harness = createHarness();
        store = harness.store();
    }

    @AfterEach
    void stopStore() throws Exception {
        harness.close();
    }

    @Test
    void putPublishesOnlyVerifiedImmutableContent() throws Exception {
        ArtifactOwner owner = owner();
        ArtifactRef reference = store.put(write(owner, "hello", HELLO_DIGEST));

        try (var content = store.read(ArtifactRead.entire(owner, reference))) {
            assertThat(content.stream().readAllBytes()).isEqualTo("hello".getBytes(StandardCharsets.UTF_8));
            assertThat(content.metadata().digest()).isEqualTo(HELLO_DIGEST);
            assertThat(content.metadata().size()).isEqualTo(5);
        }
        assertThatThrownBy(() -> store.put(write(owner, "hello", digest('0'))))
                .isInstanceOfSatisfying(ArtifactStoreException.class, failure ->
                        assertThat(failure.reason()).isEqualTo(ArtifactStoreException.Reason.INTEGRITY_FAILED));
        assertThat(harness.publishedCount(owner)).isOne();
    }

    @Test
    void rangeReadsAreExactAndBounded() throws Exception {
        ArtifactOwner owner = owner();
        ArtifactRef reference = store.put(write(owner, "hello", HELLO_DIGEST));
        assertThat(store.capabilities()).contains(ArtifactStoreCapability.RANGE_READ);

        try (var content = store.read(new ArtifactRead(owner, reference, 1, 3L))) {
            assertThat(content.offset()).isOne();
            assertThat(content.length()).isEqualTo(3);
            assertThat(content.stream().readAllBytes()).isEqualTo("ell".getBytes(StandardCharsets.UTF_8));
        }
        assertThatThrownBy(() -> store.read(new ArtifactRead(owner, reference, 5, 1L)))
                .isInstanceOfSatisfying(ArtifactStoreException.class, failure ->
                        assertThat(failure.reason()).isEqualTo(ArtifactStoreException.Reason.INVALID_RANGE));
    }

    @Test
    void referenceGuessingAndSearchCannotCrossAWorkspace() {
        ArtifactOwner owner = owner();
        ArtifactOwner attacker = new ArtifactOwner(owner.organizationId(), UUID.randomUUID());
        ArtifactRef reference = store.put(write(owner, "hello", HELLO_DIGEST));

        assertThatThrownBy(() -> store.read(ArtifactRead.entire(attacker, reference)))
                .isInstanceOfSatisfying(ArtifactStoreException.class, failure ->
                        assertThat(failure.reason()).isEqualTo(ArtifactStoreException.Reason.NOT_FOUND_OR_DENIED));
        assertThatThrownBy(() -> store.read(ArtifactRead.entire(attacker, new ArtifactRef(UUID.randomUUID()))))
                .isInstanceOfSatisfying(ArtifactStoreException.class, failure ->
                        assertThat(failure.reason()).isEqualTo(ArtifactStoreException.Reason.NOT_FOUND_OR_DENIED));
        assertThat(store.search(ArtifactSearchRequest.first(attacker, 10)).entries()).isEmpty();
    }

    @Test
    void quarantinePreventsContentResolutionWithoutLeakingContent() {
        ArtifactOwner owner = owner();
        ArtifactRef reference = store.put(write(owner, "hello", HELLO_DIGEST));
        harness.quarantine(owner, reference);

        assertThatThrownBy(() -> store.read(ArtifactRead.entire(owner, reference)))
                .isInstanceOfSatisfying(ArtifactStoreException.class, failure -> {
                    assertThat(failure.reason()).isEqualTo(ArtifactStoreException.Reason.QUARANTINED);
                    assertThat(failure).hasMessageNotContaining("hello");
                });
    }

    @Test
    void keysetPagesAreStableWhenNewArtifactsArrive() {
        ArtifactOwner owner = owner();
        store.put(write(owner, "hello", HELLO_DIGEST));
        harness.advance(Duration.ofMillis(1));
        store.put(write(owner, "hello", HELLO_DIGEST));
        var first = store.search(ArtifactSearchRequest.first(owner, 1));
        var cursor = first.entries().getFirst();
        harness.advance(Duration.ofMillis(1));
        store.put(write(owner, "hello", HELLO_DIGEST));

        var second = store.search(new ArtifactSearchRequest(owner, Set.of(), null, cursor.createdAt(),
                cursor.reference().value(), 10));

        assertThat(first.hasMore()).isTrue();
        assertThat(second.entries()).hasSize(1);
        assertThat(second.entries().getFirst().createdAt()).isBefore(cursor.createdAt());
    }

    protected static ArtifactOwner owner() {
        return new ArtifactOwner(UUID.randomUUID(), UUID.randomUUID());
    }

    protected static ArtifactWrite write(ArtifactOwner owner, String value, ArtifactDigest digest) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        return new ArtifactWrite(owner, "text/plain", bytes.length, digest, null,
                ArtifactClassification.INTERNAL, false, ArtifactProvenance.upload("artifact-tck"),
                new ByteArrayInputStream(bytes));
    }

    private static ArtifactDigest digest(char digit) {
        return new ArtifactDigest("sha256:" + String.valueOf(digit).repeat(64));
    }
}
