package com.dvarahq.agentworks.testing.fake;

import java.util.*;

/** Operation-local fake MCP tool registry with the same discover-then-invoke boundary as Direct MCP. */
public final class FakeMcpServer {
    private final String protocolVersion;
    private final Map<String, FakeTool> tools = new LinkedHashMap<>();
    public FakeMcpServer(String protocolVersion) {
        if (protocolVersion == null || protocolVersion.isBlank()) throw new IllegalArgumentException("protocolVersion");
        this.protocolVersion = protocolVersion;
    }
    public FakeMcpServer tool(FakeTool tool) { if (tools.putIfAbsent(tool.name(), tool) != null) throw new IllegalArgumentException("duplicate tool"); return this; }
    public Set<String> discover() { return Collections.unmodifiableSet(tools.keySet()); }
    public Object invoke(String tool, Map<String, Object> arguments) {
        FakeTool fake = tools.get(tool); if (fake == null) throw new IllegalArgumentException("MCP_TOOL_UNKNOWN: " + tool);
        return fake.invoke(arguments);
    }
    public String protocolVersion() { return protocolVersion; }
    public FakeMcpServer fresh() {
        FakeMcpServer copy = new FakeMcpServer(protocolVersion); tools.values().forEach(tool -> copy.tool(tool.fresh())); return copy;
    }
}
