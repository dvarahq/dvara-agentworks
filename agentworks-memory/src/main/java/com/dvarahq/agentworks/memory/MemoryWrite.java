package com.dvarahq.agentworks.memory;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/** Idempotent write request. The key is stable across checkpoint recovery. */
public record MemoryWrite(String idempotencyKey, MemoryKind kind, MemoryScope scope, UUID releaseId, UUID revisionId,
        String role, String content, Duration retention) {
    public MemoryWrite {
        if (idempotencyKey == null || !idempotencyKey.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new IllegalArgumentException("invalid idempotency key");
        }
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(scope, "scope").require(kind);
        Objects.requireNonNull(releaseId, "releaseId");
        Objects.requireNonNull(revisionId, "revisionId");
        if (role == null || !role.matches("[a-z][a-z0-9_-]{0,31}")) throw new IllegalArgumentException("invalid role");
        Objects.requireNonNull(content, "content");
        if (content.isBlank() || content.length() > 65_536) throw new IllegalArgumentException("invalid content");
        Objects.requireNonNull(retention, "retention");
        if (retention.isZero() || retention.isNegative() || retention.compareTo(Duration.ofDays(3650)) > 0) {
            throw new IllegalArgumentException("invalid retention");
        }
    }
}
