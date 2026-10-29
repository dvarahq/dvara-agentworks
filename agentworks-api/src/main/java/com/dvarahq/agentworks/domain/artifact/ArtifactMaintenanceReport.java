package com.dvarahq.agentworks.domain.artifact;

import java.time.Instant;
import java.util.Objects;

/** Content-free, auditable result of one bounded maintenance pass. */
public record ArtifactMaintenanceReport(Instant completedAt, int staleUploadsRemoved,
        int metadataExpired, int metadataDeleted, int contentObjectsRemoved, int integrityFailures) {
    public ArtifactMaintenanceReport {
        Objects.requireNonNull(completedAt, "completedAt");
        if (staleUploadsRemoved < 0 || metadataExpired < 0 || metadataDeleted < 0
                || contentObjectsRemoved < 0 || integrityFailures < 0) {
            throw new IllegalArgumentException("artifact maintenance counts cannot be negative");
        }
    }
}
