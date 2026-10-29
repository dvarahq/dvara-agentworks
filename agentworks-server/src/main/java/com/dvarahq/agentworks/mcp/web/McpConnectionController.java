package com.dvarahq.agentworks.mcp.web;

import com.dvarahq.agentworks.api.web.Page;
import com.dvarahq.agentworks.controlplane.agent.web.WorkspaceScopeResolver;
import com.dvarahq.agentworks.mcp.McpConnectionHealthSource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Authenticated, workspace-filtered operational state from optional MCP adapters. */
@RestController
@RequestMapping("/api/v1")
public final class McpConnectionController {
    private final List<McpConnectionHealthSource> sources;
    private final WorkspaceScopeResolver workspaces;

    public McpConnectionController(List<McpConnectionHealthSource> sources, WorkspaceScopeResolver workspaces) {
        this.sources = List.copyOf(sources); this.workspaces = Objects.requireNonNull(workspaces);
    }

    @GetMapping(path = "/mcp-connections", produces = MediaType.APPLICATION_JSON_VALUE)
    public Page<ConnectionResource> connections(HttpServletRequest request) {
        var workspace = workspaces.resolve(request);
        return new Page<>(sources.stream().map(McpConnectionHealthSource::mcpConnectionHealth)
                .filter(item -> item.workspaceId().equals(workspace)).map(ConnectionResource::of)
                .sorted(Comparator.comparing(ConnectionResource::connection)
                        .thenComparing(ConnectionResource::version)).toList(), null);
    }

    public record ConnectionResource(String connection, String version, UUID releaseId, UUID deploymentRevisionId,
                                     UUID cohortId, String status, String code, Instant observedAt, long failures) {
        static ConnectionResource of(McpConnectionHealthSource.McpConnectionHealth health) {
            return new ConnectionResource(health.connection(), health.version(), health.releaseId(),
                    health.deploymentRevisionId(), health.cohortId(), health.status(), health.code(),
                    health.observedAt(), health.failures());
        }
    }
}
