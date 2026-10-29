package com.dvarahq.agentworks.testing;

import java.math.BigDecimal;

public record AgentTestUsage(long inputTokens, long outputTokens, int toolCalls, BigDecimal cost) {
    public AgentTestUsage {
        if (inputTokens < 0 || outputTokens < 0 || toolCalls < 0) throw new IllegalArgumentException("usage is negative");
        cost = cost == null ? BigDecimal.ZERO : cost;
        if (cost.signum() < 0) throw new IllegalArgumentException("cost is negative");
    }
    public long totalTokens() { return Math.addExact(inputTokens, outputTokens); }
}
