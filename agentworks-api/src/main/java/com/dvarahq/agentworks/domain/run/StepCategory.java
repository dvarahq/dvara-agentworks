package com.dvarahq.agentworks.domain.run;

/** Normalized {@link AgentStep} categories (master §10.9). */
public enum StepCategory {
    MODEL, TOOL, HUMAN, OPERATION, SUB_AGENT, CONTEXT, MEMORY, ARTIFACT, RELEASE, SYSTEM
}
