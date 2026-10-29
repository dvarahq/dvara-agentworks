package com.dvarahq.agentworks.domain.run;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Run limits pinned when the run is created. A {@code null} limit is unbounded. A limit is exceeded only when
 * usage is strictly greater than it; reaching it exactly is allowed.
 */
public record RunLimits(Long maxTotalTokens, Long maxModelCalls, Long maxToolCalls, Long maxRuntimeMillis,
        BigDecimal maxCostUsd) {
    public static final RunLimits UNBOUNDED = new RunLimits(null, null, null, null, null);
    /** Fraction of a limit at which a single {@code LIMIT_WARNING} is recorded. */
    public static final double WARNING_FRACTION = 0.8;

    public RunLimits {
        for (Long value : new Long[]{maxTotalTokens, maxModelCalls, maxToolCalls, maxRuntimeMillis}) {
            if (value != null && value < 0) throw new IllegalArgumentException("limits are non-negative");
        }
        if (maxCostUsd != null) {
            if (maxCostUsd.signum() < 0) throw new IllegalArgumentException("limits are non-negative");
            maxCostUsd = RunUsage.cost(maxCostUsd);
        }
    }

    /** Names of the limits that {@code usage} exceeds, in a stable order. */
    public List<String> exceeded(RunUsage usage) {
        List<String> names = new ArrayList<>();
        if (maxTotalTokens != null && usage.totalTokens() > maxTotalTokens) names.add("maxTotalTokens");
        if (maxModelCalls != null && usage.modelCalls() > maxModelCalls) names.add("maxModelCalls");
        if (maxToolCalls != null && usage.toolCalls() > maxToolCalls) names.add("maxToolCalls");
        if (maxRuntimeMillis != null && usage.runtimeMillis() > maxRuntimeMillis) names.add("maxRuntimeMillis");
        if (maxCostUsd != null && usage.estimatedCostUsd().compareTo(maxCostUsd) > 0) names.add("maxCostUsd");
        return names;
    }

    /** Names of the limits that moved across the warning threshold between {@code before} and {@code after}. */
    public List<String> warnings(RunUsage before, RunUsage after) {
        List<String> names = new ArrayList<>();
        crossed(names, "maxTotalTokens", maxTotalTokens, before.totalTokens(), after.totalTokens());
        crossed(names, "maxModelCalls", maxModelCalls, before.modelCalls(), after.modelCalls());
        crossed(names, "maxToolCalls", maxToolCalls, before.toolCalls(), after.toolCalls());
        crossed(names, "maxRuntimeMillis", maxRuntimeMillis, before.runtimeMillis(), after.runtimeMillis());
        if (maxCostUsd != null) {
            BigDecimal threshold = maxCostUsd.multiply(BigDecimal.valueOf(WARNING_FRACTION));
            if (before.estimatedCostUsd().compareTo(threshold) < 0 && after.estimatedCostUsd().compareTo(threshold) >= 0) {
                names.add("maxCostUsd");
            }
        }
        return names;
    }

    private static void crossed(List<String> names, String name, Long limit, long before, long after) {
        if (limit == null) return;
        double threshold = limit * WARNING_FRACTION;
        if (before < threshold && after >= threshold) names.add(name);
    }
}
