package com.dvarahq.agentworks.observability;

import com.dvarahq.agentworks.domain.identity.WorkspaceRole;
import com.dvarahq.agentworks.DvaraAgentWorksApplication;
import com.dvarahq.agentworks.identity.TestIdentities;
import com.dvarahq.agentworks.identity.web.IdentityHttp;
import com.dvarahq.agentworks.identity.web.IdentityTestConfiguration;
import com.dvarahq.agentworks.storage.DefaultTenantBootstrap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = DvaraAgentWorksApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(IdentityTestConfiguration.class)
class DiagnosticsApiTest {
    @LocalServerPort int port;
    @Autowired TestIdentities identities;
    @Autowired JdbcTemplate jdbc;

    @Test
    void workspaceReaderDownloadsABoundedRedactedBundle() {
        var admin = identities.create(DefaultTenantBootstrap.DEFAULT_WORKSPACE_ID.value(), WorkspaceRole.ADMIN);
        String canary = "secret-pii-canary-" + UUID.randomUUID();
        new IdentityHttp(port).bearer(admin.token()).send("POST", "/api/v1/agents", Map.of("name", canary));

        var response = new IdentityHttp(port).bearer(admin.token()).get("/api/v1/diagnostics/support-bundle");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.header("Content-Disposition")).contains("agentworks-support-bundle.json");
        assertThat(response.header("Cache-Control")).contains("no-store");
        assertThat(response.raw()).contains("workspaceDigest", "redaction", "snapshot").doesNotContain(canary);
        assertThat(response.raw().getBytes(java.nio.charset.StandardCharsets.UTF_8)).hasSizeLessThanOrEqualTo(262144);
    }

    @Test
    void invalidCorrelationInputIsAStableRedactedProblem() {
        var viewer = identities.create(DefaultTenantBootstrap.DEFAULT_WORKSPACE_ID.value(), WorkspaceRole.VIEWER);
        var response = new IdentityHttp(port).bearer(viewer.token())
                .get("/api/v1/diagnostics/support-bundle?traceId=secret-pii-canary");
        assertThat(response.status()).isEqualTo(400);
        assertThat(response.code()).isEqualTo("INVALID_TRACE_ID");
        assertThat(response.raw()).doesNotContain("secret-pii-canary");
    }

    @Test
    void anotherWorkspaceReceivesOnlyItsOwnOpaqueDiagnosticScope() {
        UUID first = DefaultTenantBootstrap.DEFAULT_WORKSPACE_ID.value();
        UUID organization = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        jdbc.update("insert into organization(id, name) values (?, ?)", organization, "org-" + organization);
        jdbc.update("insert into workspace(id, organization_id, name) values (?, ?, ?)", second, organization,
                "workspace-" + second);
        var firstViewer = identities.create(first, WorkspaceRole.VIEWER);
        var secondViewer = identities.create(second, WorkspaceRole.VIEWER);

        var firstBundle = new IdentityHttp(port).bearer(firstViewer.token())
                .get("/api/v1/diagnostics/support-bundle");
        var secondBundle = new IdentityHttp(port).bearer(secondViewer.token())
                .get("/api/v1/diagnostics/support-bundle");

        assertThat(firstBundle.status()).isEqualTo(200);
        assertThat(secondBundle.status()).isEqualTo(200);
        assertThat(firstBundle.json().path("workspaceDigest").asString())
                .isNotEqualTo(secondBundle.json().path("workspaceDigest").asString());
        assertThat(secondBundle.raw()).doesNotContain(first.toString(), second.toString());
    }
}
