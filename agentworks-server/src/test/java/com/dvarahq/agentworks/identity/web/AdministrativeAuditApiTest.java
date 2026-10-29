package com.dvarahq.agentworks.identity.web;

import com.dvarahq.agentworks.domain.identity.WorkspaceRole;
import com.dvarahq.agentworks.DvaraAgentWorksApplication;
import com.dvarahq.agentworks.identity.TestIdentities;
import com.dvarahq.agentworks.storage.DefaultTenantBootstrap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Workspace isolation, redaction, filtering, and keyset pagination of issue #79's audit API. */
@SpringBootTest(classes = DvaraAgentWorksApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(IdentityTestConfiguration.class)
class AdministrativeAuditApiTest {
    private static final UUID WORKSPACE = DefaultTenantBootstrap.DEFAULT_WORKSPACE_ID.value();

    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TestIdentities identities;

    @Test
    void aViewerCanReadOnlyRedactedFactsForItsWorkspaceAndFilterThem() {
        var admin = identities.create(WORKSPACE, WorkspaceRole.ADMIN);
        var viewer = identities.create(WORKSPACE, WorkspaceRole.VIEWER);
        var writer = new IdentityHttp(port).bearer(admin.token());
        String target = writer.send("POST", "/api/v1/agents", Map.of("name", "audited-" + UUID.randomUUID()))
                .json().path("id").asString();

        var response = new IdentityHttp(port).bearer(viewer.token()).get("/api/v1/administrative-audit"
                + "?eventType=AGENT_DEFINITION_CREATED&targetType=AGENT_DEFINITION&targetId=" + target);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.json().path("items")).hasSize(1);
        var fact = response.json().path("items").get(0);
        assertThat(fact.path("targetId").asString()).isEqualTo(target);
        assertThat(fact.path("eventType").asString()).isEqualTo("AGENT_DEFINITION_CREATED");
        assertThat(fact.path("integrityDigest").asString()).matches("[0-9a-f]{64}");
        assertThat(fact.has("credentialId")).isFalse();
        assertThat(fact.has("remoteAddress")).isFalse();
        assertThat(response.json().path("nextCursor").isNull()).isTrue();
    }

    @Test
    void pagesAreExclusiveAndAnInvalidFilterIsRejected() {
        var admin = identities.create(WORKSPACE, WorkspaceRole.ADMIN);
        var client = new IdentityHttp(port).bearer(admin.token());
        client.send("POST", "/api/v1/agents", Map.of("name", "page-a-" + UUID.randomUUID()));
        client.send("POST", "/api/v1/agents", Map.of("name", "page-b-" + UUID.randomUUID()));

        var first = client.get("/api/v1/administrative-audit?eventType=AGENT_DEFINITION_CREATED&limit=1");
        String cursor = first.json().path("nextCursor").asString();
        var second = client.get("/api/v1/administrative-audit?eventType=AGENT_DEFINITION_CREATED&limit=1&cursor="
                + URLEncoder.encode(cursor, StandardCharsets.UTF_8));
        var invalid = client.get("/api/v1/administrative-audit?eventType=not-a-code");

        assertThat(first.status()).isEqualTo(200);
        assertThat(cursor).isNotBlank();
        assertThat(second.status()).isEqualTo(200);
        assertThat(second.json().path("items").get(0).path("id").asString())
                .isNotEqualTo(first.json().path("items").get(0).path("id").asString());
        assertThat(invalid.status()).isEqualTo(400);
        assertThat(invalid.code()).isEqualTo("INVALID_AUDIT_FILTER");
    }

    @Test
    void anotherWorkspaceCannotObserveTheFact() {
        var admin = identities.create(WORKSPACE, WorkspaceRole.ADMIN);
        String target = new IdentityHttp(port).bearer(admin.token())
                .send("POST", "/api/v1/agents", Map.of("name", "private-" + UUID.randomUUID()))
                .json().path("id").asString();
        UUID organization = UUID.randomUUID();
        UUID otherWorkspace = UUID.randomUUID();
        jdbc.update("insert into organization(id, name) values (?, ?)", organization, "org-" + organization);
        jdbc.update("insert into workspace(id, organization_id, name) values (?, ?, ?)", otherWorkspace, organization,
                "workspace-" + otherWorkspace);
        var outsider = identities.create(otherWorkspace, WorkspaceRole.VIEWER);

        var response = new IdentityHttp(port).bearer(outsider.token()).get("/api/v1/administrative-audit?targetId="
                + target);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.json().path("items")).isEmpty();
    }
}
