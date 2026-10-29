package com.dvarahq.agentworks.domain.run;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Normalized run usage. Counters only accumulate: every contribution is a non-negative delta recorded on a run
 * event, so the summary is reconstructible from history and a stale worker can never decrement it. Total tokens
 * are always input plus output tokens; cost is in US dollars with six decimal places.
 */
public record RunUsage(long inputTokens, long outputTokens, long totalTokens, long modelCalls, long toolCalls,
        long runtimeMillis, BigDecimal estimatedCostUsd) {
    public static final int COST_SCALE = 6;
    public static final RunUsage ZERO = new RunUsage(0, 0, 0, 0, 0, 0, BigDecimal.ZERO);

    public RunUsage {
        Objects.requireNonNull(estimatedCostUsd, "estimatedCostUsd");
        if (inputTokens < 0 || outputTokens < 0 || modelCalls < 0 || toolCalls < 0 || runtimeMillis < 0
                || estimatedCostUsd.signum() < 0) {
            throw new IllegalArgumentException("usage counters are non-negative");
        }
        if (totalTokens != inputTokens + outputTokens) {
            throw new IllegalArgumentException("total tokens must equal input plus output tokens");
        }
        estimatedCostUsd = cost(estimatedCostUsd);
    }

    public static RunUsage of(long inputTokens, long outputTokens, long modelCalls, long toolCalls, long runtimeMillis,
            BigDecimal estimatedCostUsd) {
        return new RunUsage(inputTokens, outputTokens, inputTokens + outputTokens, modelCalls, toolCalls, runtimeMillis,
                estimatedCostUsd);
    }

    public RunUsage plus(RunUsage delta) {
        return of(Math.addExact(inputTokens, delta.inputTokens), Math.addExact(outputTokens, delta.outputTokens),
                Math.addExact(modelCalls, delta.modelCalls), Math.addExact(toolCalls, delta.toolCalls),
                Math.addExact(runtimeMillis, delta.runtimeMillis), estimatedCostUsd.add(delta.estimatedCostUsd));
    }

    public RunUsage plusRuntime(long millis) {
        return plus(of(0, 0, 0, 0, millis, BigDecimal.ZERO));
    }

    public boolean isZero() {
        return equals(ZERO);
    }

    /** A dollar amount at the fixed cost scale; more than six significant decimal places are rejected. */
    public static BigDecimal cost(BigDecimal amount) {
        try {
            return amount.setScale(COST_SCALE, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException tooPrecise) {
            throw new IllegalArgumentException("cost has at most six decimal places", tooPrecise);
        }
    }
}
