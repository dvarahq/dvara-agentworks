package com.dvarahq.agentworks.memory;

import com.dvarahq.agentworks.context.ContextClassification;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/** Tenant-scoped, bounded provider query with an explicit destination policy. */
public record KnowledgeQuery(UUID workspaceId, UUID delegatedSubjectId, String query, int maximumItems,
        int maximumTokens, Duration timeout, ContextClassification maximumClassification) {
    public KnowledgeQuery {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(delegatedSubjectId, "delegatedSubjectId");
        Objects.requireNonNull(query, "query");
        if (query.isBlank() || query.length() > 16_384) throw new IllegalArgumentException("invalid query");
        if (maximumItems < 1 || maximumItems > 100) throw new IllegalArgumentException("invalid maximumItems");
        if (maximumTokens < 1 || maximumTokens > 100_000) throw new IllegalArgumentException("invalid maximumTokens");
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("invalid timeout");
        }
        Objects.requireNonNull(maximumClassification, "maximumClassification");
    }
}
