package com.dvarahq.agentworks.spec.model;

import java.math.BigDecimal;

public record CompactionSpec(Boolean enabled, BigDecimal threshold, CompactionStrategy strategy) {
    public CompactionSpec {
        threshold = Decimals.normalize(threshold);
    }
}
