package com.dvarahq.agentworks.spec.model;

import java.math.BigDecimal;

/** Numeric normalization so {@code 10}, {@code 10.0}, and {@code 10.00} are one semantic value. */
final class Decimals {
    private Decimals() {}

    static BigDecimal normalize(BigDecimal value) {
        if (value == null) return null;
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }
}
