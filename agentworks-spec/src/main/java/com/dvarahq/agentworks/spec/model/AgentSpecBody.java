package com.dvarahq.agentworks.spec.model;

import java.util.List;

public record AgentSpecBody(
        ExecutorSpec executor,
        ModelRoles models,
        InstructionsSpec instructions,
        ExecutionSpec execution,
        ContextSpec context,
        List<String> capabilityPacks,
        List<String> skills,
        SandboxSpec sandbox,
        MemorySpec memory,
        LimitsSpec limits) {
    public AgentSpecBody {
        capabilityPacks = capabilityPacks == null ? null : List.copyOf(capabilityPacks);
        skills = skills == null ? null : List.copyOf(skills);
    }

    public static AgentSpecBody of(ExecutorSpec executor) {
        return new AgentSpecBody(executor, null, null, null, null, null, null, null, null, null);
    }
}
