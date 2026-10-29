package com.dvarahq.agentworks.testing;

/** Stable mismatch code plus useful expected/actual detail. */
public record AgentTestMismatch(String code, String expected, String actual) {
    public AgentTestMismatch {
        if (code == null || code.isBlank()) throw new IllegalArgumentException("code is required");
        expected = expected == null ? "null" : expected; actual = actual == null ? "null" : actual;
    }
}
