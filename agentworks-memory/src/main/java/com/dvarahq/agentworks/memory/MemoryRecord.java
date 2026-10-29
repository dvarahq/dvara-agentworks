package com.dvarahq.agentworks.memory;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** One bounded memory record with immutable provenance and explicit expiry. */
public record MemoryRecord(UUID id, MemoryKind kind, MemoryScope scope, UUID releaseId, UUID revisionId,
        String role, Optional<String> content, String contentDigest, Instant createdAt, Instant expiresAt) {
    public MemoryRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(scope, "scope").require(kind);
        Objects.requireNonNull(releaseId, "releaseId");
        Objects.requireNonNull(revisionId, "revisionId");
        if (role == null || !role.matches("[a-z][a-z0-9_-]{0,31}")) throw new IllegalArgumentException("invalid role");
        content = Objects.requireNonNull(content, "content");
        if (content.isPresent() && (content.orElseThrow().isBlank() || content.orElseThrow().length() > 65_536)) {
            throw new IllegalArgumentException("invalid content");
        }
        if (contentDigest == null || !contentDigest.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invalid content digest");
        }
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (!expiresAt.isAfter(createdAt)) throw new IllegalArgumentException("expiry must follow creation");
    }
}
