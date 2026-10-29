package com.dvarahq.agentworks.artifact.conformance;

import com.dvarahq.agentworks.artifact.ArtifactStore;
import com.dvarahq.agentworks.artifact.ArtifactStoreCapability;
import com.dvarahq.agentworks.artifact.ArtifactStoreException;
import com.dvarahq.agentworks.domain.artifact.ArtifactContent;
import com.dvarahq.agentworks.domain.artifact.ArtifactDigest;
import com.dvarahq.agentworks.domain.artifact.ArtifactMetadata;
import com.dvarahq.agentworks.domain.artifact.ArtifactOwner;
import com.dvarahq.agentworks.domain.artifact.ArtifactRead;
import com.dvarahq.agentworks.domain.artifact.ArtifactRef;
import com.dvarahq.agentworks.domain.artifact.ArtifactSearchPage;
import com.dvarahq.agentworks.domain.artifact.ArtifactSearchRequest;
import com.dvarahq.agentworks.domain.artifact.ArtifactValidation;
import com.dvarahq.agentworks.domain.artifact.ArtifactWrite;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Executes the published TCK against a deliberately small reference harness so the suite itself is non-vacuous. */
final class InMemoryArtifactStoreConformanceTest extends ArtifactStoreConformanceSuite {
    @Override
    protected ArtifactStoreConformanceHarness createHarness() {
        return new Harness();
    }

    private static final class Harness implements ArtifactStoreConformanceHarness {
        private final ReferenceStore store = new ReferenceStore();

        @Override public ArtifactStore store() { return store; }
        @Override public void advance(Duration duration) { store.now = store.now.plus(duration); }
        @Override public long publishedCount(ArtifactOwner owner) { return store.count(owner); }

        @Override
        public void quarantine(ArtifactOwner owner, ArtifactRef reference) {
            Stored stored = store.authorized(owner, reference);
            ArtifactMetadata current = stored.metadata;
            stored.metadata = new ArtifactMetadata(current.reference(), current.owner(), current.mediaType(),
                    current.size(), current.digest(), current.createdAt(), current.expiresAt(),
                    current.classification(), current.redacted(), ArtifactValidation.QUARANTINED,
                    current.provenance());
        }
    }

    private static final class ReferenceStore implements ArtifactStore {
        private final Map<UUID, Stored> artifacts = new LinkedHashMap<>();
        private Instant now = Instant.parse("2026-10-09T12:00:00Z");

        @Override
        public Set<ArtifactStoreCapability> capabilities() {
            return Set.of(ArtifactStoreCapability.RANGE_READ, ArtifactStoreCapability.SEARCH,
                    ArtifactStoreCapability.EXPIRY);
        }

        @Override
        public ArtifactRef put(ArtifactWrite request) {
            byte[] bytes;
            try {
                bytes = request.content().readAllBytes();
            } catch (IOException failure) {
                throw new ArtifactStoreException(ArtifactStoreException.Reason.UNAVAILABLE);
            }
            ArtifactDigest digest = digest(bytes);
            if (bytes.length != request.declaredSize()
                    || request.expectedDigest() != null && !request.expectedDigest().equals(digest)) {
                throw new ArtifactStoreException(ArtifactStoreException.Reason.INTEGRITY_FAILED);
            }
            ArtifactRef reference = new ArtifactRef(UUID.randomUUID());
            ArtifactMetadata metadata = new ArtifactMetadata(reference, request.owner(), request.mediaType(),
                    bytes.length, digest, now, request.expiresAt(), request.classification(), request.redacted(),
                    ArtifactValidation.ACCEPTED, request.provenance());
            artifacts.put(reference.value(), new Stored(metadata, bytes));
            return reference;
        }

        @Override
        public ArtifactContent read(ArtifactRead request) {
            Stored stored = authorized(request.owner(), request.reference());
            if (stored.metadata.validation() != ArtifactValidation.ACCEPTED) {
                throw new ArtifactStoreException(ArtifactStoreException.Reason.QUARANTINED);
            }
            long available = stored.bytes.length - request.offset();
            long length = request.length() == null ? available : request.length();
            if (request.offset() > stored.bytes.length || length > available) {
                throw new ArtifactStoreException(ArtifactStoreException.Reason.INVALID_RANGE);
            }
            int offset = Math.toIntExact(request.offset());
            int count = Math.toIntExact(length);
            return new ArtifactContent(stored.metadata, request.offset(), length,
                    new ByteArrayInputStream(stored.bytes, offset, count));
        }

        @Override
        public ArtifactSearchPage search(ArtifactSearchRequest request) {
            Comparator<ArtifactMetadata> newestFirst = Comparator.comparing(ArtifactMetadata::createdAt)
                    .thenComparing(metadata -> metadata.reference().value()).reversed();
            List<ArtifactMetadata> matching = artifacts.values().stream().map(stored -> stored.metadata)
                    .filter(metadata -> metadata.owner().equals(request.owner()))
                    .filter(metadata -> request.mediaTypes().isEmpty()
                            || request.mediaTypes().contains(metadata.mediaType()))
                    .filter(metadata -> request.classification() == null
                            || request.classification() == metadata.classification())
                    .filter(metadata -> before(metadata, request))
                    .sorted(newestFirst).toList();
            boolean hasMore = matching.size() > request.limit();
            return new ArtifactSearchPage(new ArrayList<>(matching.subList(0,
                    Math.min(request.limit(), matching.size()))), hasMore);
        }

        private boolean before(ArtifactMetadata metadata, ArtifactSearchRequest request) {
            if (request.beforeCreatedAt() == null) return true;
            int time = metadata.createdAt().compareTo(request.beforeCreatedAt());
            return time < 0 || time == 0 && metadata.reference().value().compareTo(request.beforeId()) < 0;
        }

        private Stored authorized(ArtifactOwner owner, ArtifactRef reference) {
            Stored stored = artifacts.get(reference.value());
            if (stored == null || !stored.metadata.owner().equals(owner)) {
                throw new ArtifactStoreException(ArtifactStoreException.Reason.NOT_FOUND_OR_DENIED);
            }
            return stored;
        }

        private long count(ArtifactOwner owner) {
            return artifacts.values().stream().filter(stored -> stored.metadata.owner().equals(owner)).count();
        }

        private static ArtifactDigest digest(byte[] bytes) {
            try {
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
                return new ArtifactDigest("sha256:" + HexFormat.of().formatHex(digest));
            } catch (java.security.NoSuchAlgorithmException impossible) {
                throw new IllegalStateException(impossible);
            }
        }
    }

    private static final class Stored {
        private ArtifactMetadata metadata;
        private final byte[] bytes;

        private Stored(ArtifactMetadata metadata, byte[] bytes) {
            this.metadata = metadata;
            this.bytes = bytes.clone();
        }
    }
}
