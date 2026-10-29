package com.dvarahq.agentworks.testing;

import tools.jackson.databind.JsonNode;
import java.util.*;

/** Immutable result returned by an {@link AgentTestRuntime}. */
public record AgentTestResult(AgentTestStatus status, String answer, JsonNode structuredOutput,
        AgentTestUsage usage, List<AgentTestEvent> events, Map<String, String> dependencyVersions) {
    public AgentTestResult {
        Objects.requireNonNull(status, "status"); answer = answer == null ? "" : answer;
        usage = usage == null ? new AgentTestUsage(0, 0, 0, null) : usage;
        events = List.copyOf(Objects.requireNonNull(events, "events"));
        dependencyVersions = Map.copyOf(Objects.requireNonNull(dependencyVersions, "dependencyVersions"));
        long expected = 1;
        for (AgentTestEvent event : events) if (event.sequence() != expected++) throw new IllegalArgumentException("events must be gap-free from 1");
    }
}
