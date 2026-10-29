package com.dvarahq.agentworks.context;

import java.util.EnumMap;
import java.util.Map;

/** Total token ceiling plus minimum space reserved for selected categories. */
public record ContextBudget(int maximumTokens, Map<ContextItemType, Integer> reservedTokens) {
    public ContextBudget {
        if (maximumTokens < 1 || maximumTokens > 1_000_000) throw new IllegalArgumentException("invalid maximumTokens");
        EnumMap<ContextItemType, Integer> copy = new EnumMap<>(ContextItemType.class);
        if (reservedTokens != null) reservedTokens.forEach((type, tokens) -> {
            if (type == null || tokens == null || tokens < 0 || tokens > maximumTokens) {
                throw new IllegalArgumentException("invalid reservation");
            }
            copy.put(type, tokens);
        });
        if (copy.values().stream().mapToInt(Integer::intValue).sum() > maximumTokens) {
            throw new IllegalArgumentException("reservations exceed maximumTokens");
        }
        reservedTokens = Map.copyOf(copy);
    }
}
