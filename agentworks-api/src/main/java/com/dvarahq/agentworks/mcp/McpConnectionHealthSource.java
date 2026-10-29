package com.dvarahq.agentworks.mcp;

import com.dvarahq.agentworks.domain.tenant.WorkspaceId;

import java.time.Instant;
import java.util.UUID;

/** Transport-neutral operational view; optional MCP adapters expose no SDK or secret material through this SPI. */
public interface McpConnectionHealthSource {
    McpConnectionHealth mcpConnectionHealth();

    record McpConnectionHealth(WorkspaceId workspaceId, String connection, String version, UUID releaseId,
                               UUID deploymentRevisionId, UUID cohortId, String status, String code,
                               Instant observedAt, long failures) { }
}
