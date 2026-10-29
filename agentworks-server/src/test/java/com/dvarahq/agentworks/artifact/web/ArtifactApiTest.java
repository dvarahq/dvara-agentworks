package com.dvarahq.agentworks.artifact.web;

import com.dvarahq.agentworks.domain.identity.WorkspaceRole;
import com.dvarahq.agentworks.DvaraAgentWorksApplication;
import com.dvarahq.agentworks.identity.TestIdentities;
import com.dvarahq.agentworks.identity.web.IdentityHttp;
import com.dvarahq.agentworks.identity.web.IdentityTestConfiguration;
import com.dvarahq.agentworks.storage.DefaultTenantBootstrap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static com.dvarahq.agentworks.identity.web.IdentityTestConfiguration.PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = DvaraAgentWorksApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(IdentityTestConfiguration.class)
final class ArtifactApiTest {
    private static final Path ROOT = Path.of(System.getProperty("java.io.tmpdir"),
            "agentworks-artifact-api-" + UUID.randomUUID());

    @DynamicPropertySource
    static void artifactProperties(DynamicPropertyRegistry properties) {
        properties.add("agentworks.artifacts.enabled", () -> "true");
        properties.add("agentworks.artifacts.root", ROOT::toString);
        properties.add("agentworks.artifacts.maintenance.enabled", () -> "false");
        properties.add("agentworks.artifacts.deletion-grace", () -> "0s");
    }

    @LocalServerPort
    int port;
    @Autowired
    TestIdentities identities;
    @Autowired
    JdbcTemplate jdbc;
    private UUID workspace;

    @BeforeEach
    void setUp() {
        workspace = DefaultTenantBootstrap.DEFAULT_WORKSPACE_ID.value();
    }

    @Test
    void uploadDiscoveryRangeAndLifecycleUseTenantScopedRoleAuthority() {
        var author = identities.create(workspace, WorkspaceRole.AUTHOR, PASSWORD, "MEMBER");
        var admin = identities.create(workspace, WorkspaceRole.ADMIN, PASSWORD, "MEMBER");
        var viewer = identities.create(workspace, WorkspaceRole.VIEWER, PASSWORD, "MEMBER");
        IdentityHttp authorApi = new IdentityHttp(port).bearer(author.token());
        IdentityHttp viewerApi = new IdentityHttp(port).bearer(viewer.token());
        IdentityHttp adminApi = new IdentityHttp(port).bearer(admin.token());

        var uploaded = authorApi.send("POST", "/api/v1/artifacts", "hello artifact",
                Map.of("Content-Type", "text/plain"));
        assertThat(uploaded.status()).as(uploaded.raw()).isEqualTo(201);
        String id = uploaded.json().path("id").asString();
        assertThat(uploaded.header("Location")).endsWith("/api/v1/artifacts/" + id);
        assertThat(viewerApi.get("/api/v1/artifacts").raw()).contains(id);
        assertThat(viewerApi.get("/api/v1/artifacts/" + id).json().path("validation").asString())
                .isEqualTo("ACCEPTED");

        var range = viewerApi.send("GET", "/api/v1/artifacts/" + id + "/content", null,
                Map.of("Range", "bytes=6-13"));
        assertThat(range.status()).isEqualTo(206);
        assertThat(range.raw()).isEqualTo("artifact");
        assertThat(range.header("Content-Range")).isEqualTo("bytes 6-13/14");
        assertThat(range.header("Content-Security-Policy")).contains("sandbox");
        assertThat(viewerApi.send("POST", "/api/v1/artifacts", "denied").status()).isEqualTo(403);
        assertThat(authorApi.send("POST", "/api/v1/artifacts/" + id + "/expire", null).status()).isEqualTo(403);

        UUID subject = UUID.randomUUID();
        var pinned = adminApi.send("POST", "/api/v1/artifacts/" + id + "/pins",
                Map.of("kind", "RETENTION", "subjectId", subject));
        assertThat(pinned.status()).as(pinned.raw()).isEqualTo(201);
        String pin = pinned.json().path("id").asString();
        assertThat(adminApi.send("POST", "/api/v1/artifacts/" + id + "/expire", null).code())
                .isEqualTo("ARTIFACT_PINNED");
        assertThat(adminApi.send("DELETE", "/api/v1/artifacts/" + id + "/pins/" + pin, null).status())
                .isEqualTo(204);
        assertThat(adminApi.send("POST", "/api/v1/artifacts/" + id + "/expire", null).json()
                .path("lifecycle").asString()).isEqualTo("EXPIRED");
    }

    @Test
    void quarantineAndCrossWorkspaceReferenceGuessingFailClosed() {
        var author = identities.create(workspace, WorkspaceRole.AUTHOR, PASSWORD, "MEMBER");
        var viewer = identities.create(workspace, WorkspaceRole.VIEWER, PASSWORD, "MEMBER");
        IdentityHttp ownerApi = new IdentityHttp(port).bearer(author.token());
        var uploaded = ownerApi.send("POST", "/api/v1/artifacts", "MZ executable",
                Map.of("Content-Type", "application/octet-stream"));
        assertThat(uploaded.status()).as(uploaded.raw()).isEqualTo(201);
        String id = uploaded.json().path("id").asString();
        assertThat(uploaded.json().path("validation").asString()).isEqualTo("QUARANTINED");
        assertThat(new IdentityHttp(port).bearer(viewer.token()).get("/api/v1/artifacts/" + id + "/content").code())
                .isEqualTo("ARTIFACT_QUARANTINED");

        UUID organization = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        jdbc.update("insert into organization(id, name) values (?, ?)", organization, "other-" + organization);
        jdbc.update("insert into workspace(id, organization_id, name) values (?, ?, ?)", other, organization, "other");
        var outsider = identities.create(other, WorkspaceRole.ADMIN, PASSWORD, "MEMBER");
        assertThat(new IdentityHttp(port).bearer(outsider.token()).get("/api/v1/artifacts/" + id).status())
                .isEqualTo(404);
    }
}
