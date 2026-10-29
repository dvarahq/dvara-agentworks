package com.dvarahq.agentworks.sdk.lifecycle;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** PostgreSQL-authoritative cohort observation. Process-local observations cannot construct an authoritative report. */
public record QuiescenceReport(UUID workspaceId, UUID cohortId, long transitionVersion,
                               Instant observedAt, Authority authority, Map<String, Long> blockers) {
    public enum Authority { POSTGRESQL }
    public QuiescenceReport {
        Objects.requireNonNull(workspaceId); Objects.requireNonNull(cohortId);
        Objects.requireNonNull(observedAt); Objects.requireNonNull(authority);
        if (transitionVersion <= 0) throw new IllegalArgumentException("transitionVersion");
        blockers = Map.copyOf(blockers);
        if (blockers.entrySet().stream().anyMatch(entry -> entry.getKey() == null || entry.getKey().isBlank()
                || entry.getValue() == null || entry.getValue() < 0)) throw new IllegalArgumentException("blockers");
    }
    public boolean quiescent() { return blockers.values().stream().mapToLong(Long::longValue).sum() == 0; }
}
