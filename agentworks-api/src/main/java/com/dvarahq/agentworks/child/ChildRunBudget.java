package com.dvarahq.agentworks.child;

import java.math.BigDecimal;

/** A child's allocation. It must be no greater than both platform policy and the parent's remaining allowance. */
public record ChildRunBudget(long maxTokens, int maxModelCalls, int maxToolCalls, long maxRuntimeMillis,
        BigDecimal maxCostUsd) {
    public ChildRunBudget {
        if (maxTokens < 0 || maxModelCalls < 0 || maxToolCalls < 0 || maxRuntimeMillis < 1) {
            throw new IllegalArgumentException("child budget values are out of range");
        }
        if (maxCostUsd == null || maxCostUsd.signum() < 0) {
            throw new IllegalArgumentException("child cost budget is required and nonnegative");
        }
    }
}
