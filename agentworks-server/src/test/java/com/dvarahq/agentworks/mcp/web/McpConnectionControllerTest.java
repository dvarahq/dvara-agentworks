package com.dvarahq.agentworks.mcp.web;

import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import com.dvarahq.agentworks.mcp.McpConnectionHealthSource;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class McpConnectionControllerTest {
    @Test
    void returnsOnlyTheAuthenticatedWorkspacesSafeOperationalState() {
        WorkspaceId workspace = new WorkspaceId(UUID.randomUUID());
        WorkspaceId other = new WorkspaceId(UUID.randomUUID());
        McpConnectionHealthSource visible = source(workspace, "alpha", "HEALTHY");
        McpConnectionHealthSource hidden = source(other, "other-tenant", "FAILED");

        var page = new McpConnectionController(List.of(hidden, visible), request -> workspace)
                .connections(new MockHttpServletRequest());

        assertThat(page.nextCursor()).isNull();
        assertThat(page.items()).singleElement().satisfies(item -> {
            assertThat(item.connection()).isEqualTo("alpha");
            assertThat(item.status()).isEqualTo("HEALTHY");
            assertThat(item.code()).isEqualTo("MCP_OK");
            assertThat(item.toString()).doesNotContain("credential", "secret", "token");
        });
    }

    private static McpConnectionHealthSource source(WorkspaceId workspace, String connection, String status) {
        UUID release = UUID.randomUUID();
        UUID revision = UUID.randomUUID();
        UUID cohort = UUID.randomUUID();
        return () -> new McpConnectionHealthSource.McpConnectionHealth(workspace, connection, "1.0.0", release,
                revision, cohort, status, "MCP_OK", Instant.parse("2026-10-14T12:00:00Z"), 0);
    }
}
