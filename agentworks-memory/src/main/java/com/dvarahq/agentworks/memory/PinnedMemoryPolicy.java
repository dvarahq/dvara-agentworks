package com.dvarahq.agentworks.memory;

import java.time.Duration;
import java.util.Objects;

/** Closed release-pinned memory and knowledge limits. */
public record PinnedMemoryPolicy(boolean conversationEnabled, Duration conversationRetention, int conversationItems,
        boolean workingEnabled, Duration workingRetention, int workingItems, boolean semanticEnabled,
        int knowledgeItems, int knowledgeTokens, Duration knowledgeTimeout, String policyHash) {
    public PinnedMemoryPolicy {
        conversationRetention = retention(conversationRetention, "conversationRetention");
        workingRetention = retention(workingRetention, "workingRetention");
        if (conversationItems < 1 || conversationItems > 200) throw new IllegalArgumentException("conversationItems");
        if (workingItems < 1 || workingItems > 200) throw new IllegalArgumentException("workingItems");
        if (knowledgeItems < 1 || knowledgeItems > 100) throw new IllegalArgumentException("knowledgeItems");
        if (knowledgeTokens < 1 || knowledgeTokens > 100_000) throw new IllegalArgumentException("knowledgeTokens");
        Objects.requireNonNull(knowledgeTimeout, "knowledgeTimeout");
        if (knowledgeTimeout.isZero() || knowledgeTimeout.isNegative()
                || knowledgeTimeout.compareTo(Duration.ofMinutes(5)) > 0) throw new IllegalArgumentException("knowledgeTimeout");
        if (policyHash == null || !policyHash.matches("sha256:[0-9a-f]{64}")) throw new IllegalArgumentException("policyHash");
    }

    private static Duration retention(Duration value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isZero() || value.isNegative() || value.compareTo(Duration.ofDays(3650)) > 0) {
            throw new IllegalArgumentException(field);
        }
        return value;
    }
}
