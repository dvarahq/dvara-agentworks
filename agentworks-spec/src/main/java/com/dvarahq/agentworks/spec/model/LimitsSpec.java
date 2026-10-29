package com.dvarahq.agentworks.spec.model;

import java.math.BigDecimal;

/** Run-level limits. {@code maxRuntime} uses the authored duration form, for example {@code 30m}. */
public record LimitsSpec(Integer maxSteps, Integer maxToolCalls, String maxRuntime, BigDecimal maxCostUsd) {
    public LimitsSpec {
        maxCostUsd = Decimals.normalize(maxCostUsd);
    }
}
