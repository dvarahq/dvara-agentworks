package com.dvarahq.agentworks.gateway.dvarahq;

import com.dvarahq.agentworks.mcp.DirectMcpClient;
import com.dvarahq.agentworks.mcp.SdkMcpSessions;

import java.time.Duration;
import java.util.Objects;

/** Factory for DvaraHQ's native Streamable HTTP MCP surface. */
public final class DvaraHqMcpProxy {
    private DvaraHqMcpProxy() { }

    public static DirectMcpClient.SessionFactory sessions(DvaraHqGatewayConfig config,
            Duration connectTimeout, Duration requestTimeout, int maxMessageBytes) {
        Objects.requireNonNull(config, "config");
        return SdkMcpSessions.streamableHttp(config.mcpEndpoint().orElseThrow(
                () -> new IllegalStateException("DVARAHQ_MCP_NOT_CONFIGURED")),
                connectTimeout, requestTimeout, maxMessageBytes);
    }
}
