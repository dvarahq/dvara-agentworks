package com.dvarahq.agentworks.controlplane.agent.web;

import com.dvarahq.agentworks.DvaraAgentWorksApplication;
import com.dvarahq.agentworks.gateway.PinnedModelRoles;
import com.dvarahq.agentworks.storage.lifecycle.Digests;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The provisional release API over a configured gateway: model roles resolve at release creation, the served manifest
 * pins concrete identities without endpoints or credentials, and unresolvable roles fail with stable codes.
 */
@SpringBootTest(classes = DvaraAgentWorksApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                // The deterministic fake is permitted only under the loopback-only local-development gate.
                "agentworks.development.local=true", "server.address=127.0.0.1",
                "agentworks.gateway.gateways.direct.type=deterministic-fake",
                "agentworks.gateway.gateways.direct.credential-ref=local-fake-key",
                "agentworks.gateway.gateways.direct.models[0].alias=coding-premium",
                "agentworks.gateway.gateways.direct.models[0].model=fake-coding-2026-09-01",
                "agentworks.gateway.gateways.direct.models[1].alias=economical-small",
                "agentworks.gateway.gateways.direct.models[1].model=fake-small-1",
                "agentworks.gateway.gateways.direct.capability-policy.streaming=REQUIRED"})
@Import({TestWorkspaces.class, GatewayReleaseApiTest.Credentials.class})
class GatewayReleaseApiTest {
    private static final String JSON = "application/json";

    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;
    private AgentApiClient api;
    private UUID workspace;

    @BeforeEach
    void setUp() {
        api = new AgentApiClient(port);
        workspace = TestWorkspaces.createWorkspace(jdbc);
    }

    @Test
    void releaseManifestsPinResolvedModelIdentitiesAndCapturedPolicy() {
        String version = version("agent", "1.0.0", """
                    reasoning: {gateway: direct, model: coding-premium}
                    compaction: {gateway: direct, model: economical-small}
                """);

        var release = api.post("/api/v1/agent-versions/" + version + "/releases", JSON, "{\"name\":\"agent-1\"}",
                workspace);
        var manifest = api.get("/api/v1/releases/" + release.body().path("id").asString() + "/manifest", workspace);

        assertThat(release.status()).isEqualTo(201);
        assertThat(manifest.status()).isEqualTo(200);
        assertThat(Digests.prefixed(manifest.raw())).isEqualTo(release.body().path("manifestDigest").asString());
        assertThat(manifest.body().path("manifestVersion").intValue())
                .isEqualTo(PinnedModelRoles.MODEL_RESILIENCE_MANIFEST_VERSION);
        assertThat(manifest.body().at("/models/reasoning/model").asString()).isEqualTo("fake-coding-2026-09-01");
        assertThat(manifest.body().at("/models/compaction/model").asString()).isEqualTo("fake-small-1");
        assertThat(manifest.body().at("/models/reasoning/capabilityPolicy/STREAMING").asString()).isEqualTo("REQUIRED");
        assertThat(manifest.raw()).doesNotContain("local-fake-key");
    }

    @Test
    void unresolvableModelRolesAreRefusedWithStableCodes() {
        String unknownGateway = version("agent", "1.0.0", """
                    reasoning: {gateway: dvarahq, model: coding-premium}
                """);
        String unknownAlias = version("agent", "2.0.0", """
                    evaluation: {gateway: direct, model: judge-model}
                """);

        var gateway = api.post("/api/v1/agent-versions/" + unknownGateway + "/releases", JSON, "{\"name\":\"a\"}",
                workspace);
        var alias = api.post("/api/v1/agent-versions/" + unknownAlias + "/releases", JSON, "{\"name\":\"b\"}",
                workspace);

        assertThat(gateway.status()).isEqualTo(409);
        assertThat(gateway.code()).isEqualTo("GATEWAY_NOT_CONFIGURED");
        assertThat(alias.status()).isEqualTo(409);
        assertThat(alias.code()).isEqualTo("MODEL_ALIAS_UNKNOWN");
        assertThat(api.get("/api/v1/releases", workspace).body().path("items")).isEmpty();
    }

    /** The configured credential-ref must be resolvable, or startup fails; releases never resolve it. */
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class Credentials {
        @org.springframework.context.annotation.Bean
        com.dvarahq.agentworks.credential.CredentialProvider testCredentialProvider() {
            return new com.dvarahq.agentworks.credential.CredentialProvider() {
                @Override
                public com.dvarahq.agentworks.credential.CredentialAuthorization authorize(
                        com.dvarahq.agentworks.domain.security.CredentialRef reference,
                        com.dvarahq.agentworks.domain.security.InvocationIdentity identity, java.time.Instant now) {
                    throw new com.dvarahq.agentworks.credential.CredentialResolutionException("not used by releases");
                }

                @Override
                public com.dvarahq.agentworks.credential.ResolvedCredential resolve(
                        com.dvarahq.agentworks.credential.CredentialAuthorization authorization, java.time.Instant now) {
                    throw new com.dvarahq.agentworks.credential.CredentialResolutionException("not used by releases");
                }
            };
        }

        @org.springframework.context.annotation.Bean
        com.dvarahq.agentworks.credential.CredentialAuditSink testCredentialAuditSink() {
            return event -> { };
        }
    }

    private String version(String agent, String version, String models) {
        String definition = api.get("/api/v1/agents", workspace).body().path("items").valueStream()
                .filter(item -> item.path("name").asString().equals(agent)).map(item -> item.path("id").asString())
                .findFirst().orElseGet(() -> api.post("/api/v1/agents", JSON, "{\"name\":\"" + agent + "\"}", workspace)
                        .body().path("id").asString());
        String yaml = "apiVersion: dvarahq.com/v1alpha1\nkind: Agent\nmetadata: {name: " + agent + ", version: "
                + version + "}\nspec:\n  executor: {type: spring-ai}\n  models:\n" + models;
        var published = api.post("/api/v1/agents/" + definition + "/versions", "application/yaml", yaml, workspace);
        assertThat(published.status()).isEqualTo(201);
        return published.body().path("id").asString();
    }
}
