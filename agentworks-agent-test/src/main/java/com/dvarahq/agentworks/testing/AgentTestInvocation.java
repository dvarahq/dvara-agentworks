package com.dvarahq.agentworks.testing;

import com.dvarahq.agentworks.testing.fake.FakeEcosystem;
import com.dvarahq.agentworks.testing.simulation.SimulationSession;
import java.util.*;

public record AgentTestInvocation(String agent, String workspace, String userMessage, long seed,
        Map<String, Object> fixtures, FakeEcosystem fakes, EffectPolicy effects, SimulationSession simulation) {
    /** Source- and binary-compatible constructor for runtimes compiled against AgentTest report version 1. */
    public AgentTestInvocation(String agent, String workspace, String userMessage, long seed,
            Map<String, Object> fixtures, FakeEcosystem fakes, EffectPolicy effects) {
        this(agent, workspace, userMessage, seed, fixtures, fakes, effects, SimulationSession.direct(fakes, seed));
    }
    public AgentTestInvocation {
        if (agent == null || agent.isBlank() || workspace == null || workspace.isBlank() || userMessage == null || userMessage.isBlank())
            throw new IllegalArgumentException("agent, workspace, and userMessage are required");
        fixtures = Map.copyOf(Objects.requireNonNull(fixtures)); Objects.requireNonNull(fakes); Objects.requireNonNull(effects);
        Objects.requireNonNull(simulation);
    }
}
