package com.dvarahq.agentworks.testing;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/** Content-bounded normalized event used by assertions and golden traces. */
public record AgentTestEvent(long sequence, Instant at, Type type, String name, Map<String, Object> attributes) {
    public enum Type { MODEL, TOOL_REQUESTED, TOOL_EXECUTED, HUMAN_INTERACTION, POLICY_DECISION, ARTIFACT, OUTPUT }
    public AgentTestEvent {
        if (sequence < 1) throw new IllegalArgumentException("sequence must be positive");
        Objects.requireNonNull(at, "at"); Objects.requireNonNull(type, "type");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name is required");
        attributes = Map.copyOf(Objects.requireNonNull(attributes, "attributes"));
    }
}
