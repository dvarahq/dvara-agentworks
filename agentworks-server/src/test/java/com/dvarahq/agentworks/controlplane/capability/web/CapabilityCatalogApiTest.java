package com.dvarahq.agentworks.controlplane.capability.web;

import com.dvarahq.agentworks.domain.identity.WorkspaceRole;
import com.dvarahq.agentworks.DvaraAgentWorksApplication;
import com.dvarahq.agentworks.identity.TestIdentities;
import com.dvarahq.agentworks.identity.web.IdentityHttp;
import com.dvarahq.agentworks.identity.web.IdentityTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = DvaraAgentWorksApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(IdentityTestConfiguration.class)
class CapabilityCatalogApiTest {
    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TestIdentities identities;

    @Test
    void authorManagesExactVersionsWhileReadersReceiveOnlyActiveSchemaFreeDiscovery() {
        UUID workspaceA = workspace("cap-a");
        UUID workspaceB = workspace("cap-b");
        IdentityHttp author = client(workspaceA, WorkspaceRole.AUTHOR);
        IdentityHttp viewer = client(workspaceA, WorkspaceRole.VIEWER);
        IdentityHttp otherAuthor = client(workspaceB, WorkspaceRole.AUTHOR);
        Map<String, Object> tool = tool("search");

        assertThat(viewer.send("POST", "/api/v1/tools", tool).status()).isEqualTo(403);
        IdentityHttp.Response created = author.send("POST", "/api/v1/tools", tool);
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.json().path("state").asString()).isEqualTo("AVAILABLE");
        assertThat(created.json().path("effect").path("resource").asString()).isEqualTo("public-web");
        assertThat(author.send("POST", "/api/v1/tools", tool).code()).isEqualTo("CAPABILITY_CONFLICT");

        Map<String, Object> legacyTool = new HashMap<>(tool("legacy-search"));
        legacyTool.remove("effect");
        IdentityHttp.Response legacyCreated = author.send("POST", "/api/v1/tools", legacyTool);
        assertThat(legacyCreated.status()).isEqualTo(201);
        assertThat(legacyCreated.json().at("/effect/resource").asString()).isEqualTo("legacy-unclassified");
        assertThat(legacyCreated.json().at("/effect/risk").asString()).isEqualTo("CRITICAL");

        assertThat(viewer.get("/api/v1/tools").json().path("items")).isEmpty();
        assertThat(author.send("POST", "/api/v1/tools/core/search/versions/1.0.0/lifecycle",
                Map.of("state", "ACTIVE")).status()).isEqualTo(200);

        IdentityHttp.Response discovery = viewer.get("/api/v1/tools?q=search&type=MCP&tag=web&limit=1");
        assertThat(discovery.status()).isEqualTo(200);
        assertThat(discovery.json().path("items")).hasSize(1);
        assertThat(discovery.json().at("/items/0/effect/risk").asString()).isEqualTo("LOW");
        assertThat(discovery.raw()).doesNotContain("inputSchema").doesNotContain("description");
        assertThat(viewer.get("/api/v1/tools/core/search/versions/1.0.0").status()).isEqualTo(403);
        IdentityHttp.Response detail = author.get("/api/v1/tools/core/search/versions/1.0.0");
        assertThat(detail.status()).isEqualTo(200);
        assertThat(detail.json().path("inputSchema").path("type").asString()).isEqualTo("object");
        assertThat(otherAuthor.get("/api/v1/tools").json().path("items")).isEmpty();
        assertThat(otherAuthor.get("/api/v1/tools/core/search/versions/1.0.0").status()).isEqualTo(404);
        assertThat(viewer.get("/api/v1/tools?cursor=bad").code()).isEqualTo("INVALID_CAPABILITY");

        Map<String, Object> pack = Map.of("id", "research", "version", "1",
                "tools", List.of(Map.of("namespace", "core", "name", "search", "version", "1.0.0")));
        assertThat(author.send("POST", "/api/v1/capability-packs", pack).status()).isEqualTo(201);
        assertThat(viewer.get("/api/v1/capability-packs/research/versions/1").status()).isEqualTo(404);
        assertThat(author.send("POST", "/api/v1/capability-packs/research/versions/1/lifecycle",
                Map.of("state", "ACTIVE")).status()).isEqualTo(200);
        assertThat(viewer.get("/api/v1/capability-packs/research/versions/1").status()).isEqualTo(200);

        assertThat(author.send("POST", "/api/v1/tools/core/search/versions/1.0.0/lifecycle",
                Map.of("state", "DENIED")).status()).isEqualTo(200);
        assertThat(viewer.get("/api/v1/tools").json().path("items")).isEmpty();
        assertThat(author.send("POST", "/api/v1/tools/core/search/versions/1.0.0/lifecycle",
                Map.of("state", "ACTIVE")).status()).isEqualTo(409);
    }

    private IdentityHttp client(UUID workspace, WorkspaceRole role) {
        return new IdentityHttp(port).bearer(identities.create(workspace, role).token());
    }

    private UUID workspace(String name) {
        UUID organization = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        jdbc.update("insert into organization(id, name) values (?, ?)", organization, "org-" + name);
        jdbc.update("insert into workspace(id, organization_id, name) values (?, ?, ?)", workspace, organization, name);
        return workspace;
    }

    private static Map<String, Object> tool(String name) {
        return Map.ofEntries(Map.entry("namespace", "core"), Map.entry("name", name),
                Map.entry("version", "1.0.0"), Map.entry("type", "MCP"), Map.entry("summary", "Search the web"),
                Map.entry("description", "Search public web pages"), Map.entry("inputSchema",
                        Map.of("type", "object", "properties", Map.of("query", Map.of("type", "string")))),
                Map.entry("effect", Map.of("resource", "public-web", "action", "read", "risk", "LOW")),
                Map.entry("tags", Set.of("web")), Map.entry("compatibility", Set.of("runtime-v1")),
                Map.entry("summaryTokens", 8), Map.entry("detailTokens", 32));
    }
}
