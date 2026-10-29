package com.dvarahq.agentworks.context;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Tenant, subject, classification, redaction, retention, and destination constraints. */
public record ContextConstraints(UUID workspaceId, String subject, ContextClassification classification,
        Set<ContextDestination> destinations, boolean redacted, String retention) {
    public ContextConstraints {
        Objects.requireNonNull(workspaceId, "workspaceId");
        if (subject != null && (subject.isBlank() || subject.length() > 256)) {
            throw new IllegalArgumentException("subject is invalid");
        }
        Objects.requireNonNull(classification, "classification");
        destinations = Set.copyOf(Objects.requireNonNull(destinations, "destinations"));
        if (destinations.isEmpty()) throw new IllegalArgumentException("destinations are required");
        retention = Objects.requireNonNull(retention, "retention");
        if (retention.isBlank() || retention.length() > 64) throw new IllegalArgumentException("retention is invalid");
    }
}
