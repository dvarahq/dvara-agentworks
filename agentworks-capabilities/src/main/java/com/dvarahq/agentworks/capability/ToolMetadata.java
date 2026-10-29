package com.dvarahq.agentworks.capability;

import java.util.Set;

/** Schema-free facts used for discovery and resolution. */
public sealed interface ToolMetadata permits AgentTool, ToolSummary {
    ToolId id();
    ToolType type();
    String summary();
    String schemaHash();
    ToolEffect effect();
    Set<String> tags();
    Set<String> compatibility();
    int summaryTokens();
    int detailTokens();
    AgentTool.Availability availability();
}
