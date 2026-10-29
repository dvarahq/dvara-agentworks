package com.dvarahq.agentworks.controlplane.agent.web;

import com.dvarahq.agentworks.controlplane.agent.AgentDefinitionService;
import com.dvarahq.agentworks.domain.lifecycle.AgentDefinitionId;
import com.dvarahq.agentworks.DvaraAgentWorksApplication;
import com.dvarahq.agentworks.storage.DefaultTenantBootstrap;
import com.dvarahq.agentworks.spec.AgentSpecCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

import static com.dvarahq.agentworks.controlplane.agent.web.TestWorkspaces.OTHER_WORKSPACE;
import static org.assertj.core.api.Assertions.assertThat;

/** REST contract for AgentDefinition lineage and immutable AgentVersion publication (UC-001, TC-001, TC-003). */
@SpringBootTest(classes = DvaraAgentWorksApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestWorkspaces.class)
class AgentDefinitionApiTest {
    static final String GOLDEN_DIGEST = "sha256:32b39b28a2fa5d9f91d921b8960e4e652a0a49d65696080a8f0a45c9e4a48a04";
    private static final String YAML = "application/yaml";
    private static final String JSON = "application/json";

    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    AgentDefinitionService agents;
    private AgentApiClient api;

    @BeforeEach
    void setUp() {
        api = new AgentApiClient(port);
        TestWorkspaces.ensureOtherWorkspace(jdbc);
    }

    @Test
    void yamlAndJsonPublishOneImmutableVersionWithTheGoldenDigest() throws IOException {
        String name = uniqueName();
        String definition = createDefinition(name, null);

        var fromYaml = api.post(versions(definition), YAML, fixture("software-engineer.yaml", name), null);
        var fromJson = api.post(versions(definition), JSON, fixture("software-engineer.json", name), null);

        assertThat(fromYaml.status()).isEqualTo(201);
        assertThat(fromYaml.location()).endsWith("/api/v1/agents/" + definition + "/versions/1.0.0");
        assertThat(fromJson.status()).as("identical canonical content is an idempotent re-publication").isEqualTo(200);
        assertThat(fromJson.body().get("id")).isEqualTo(fromYaml.body().get("id"));
        assertThat(fromYaml.body().path("sequence").asLong()).isOne();
        assertThat(fromYaml.body().path("workspaceId").asString())
                .isEqualTo("00000000-0000-0000-0000-000000000002");
        assertThat(fromYaml.body().path("apiVersion").asString()).isEqualTo("dvarahq.com/v1alpha1");
        String digest = fromYaml.body().path("digest").asString();
        assertThat(AgentSpecCodec.digest(canonical(fromYaml.body().get("spec")))).isEqualTo(digest);
        assertThat(api.get(versions(definition), null).body().path("items")).hasSize(1);
        assertThat(api.get(versions(definition) + "/1.0.0", null).body()).isEqualTo(fromYaml.body());
    }

    @Test
    void goldenSpecificationKeepsItsPinnedDigestThroughRest() throws IOException {
        // The pinned digest covers metadata.name, so publish the verbatim golden documents in a fresh workspace.
        UUID workspace = TestWorkspaces.createWorkspace(jdbc);
        String definition = api.post("/api/v1/agents", JSON, "{\"name\":\"software-engineer\"}", workspace)
                .body().path("id").asString();

        var fromYaml = api.post(versions(definition), YAML, fixture("software-engineer.yaml", "software-engineer"),
                workspace);
        var fromJson = api.post(versions(definition), JSON, fixture("software-engineer.json", "software-engineer"),
                workspace);

        assertThat(fromYaml.status()).isEqualTo(201);
        assertThat(fromJson.status()).isEqualTo(200);
        assertThat(fromYaml.body().path("digest").asString()).isEqualTo(GOLDEN_DIGEST);
        assertThat(fromJson.body().path("digest").asString()).isEqualTo(GOLDEN_DIGEST);
    }

    @Test
    void editingAPublishedSpecificationRequiresANewVersion() throws IOException {
        String name = uniqueName();
        String definition = createDefinition(name, "Lineage");
        String original = fixture("software-engineer.yaml", name);
        var first = api.post(versions(definition), YAML, original, null);

        String edited = original.replace("maxSteps: 100", "maxSteps: 120");
        var conflicting = api.post(versions(definition), YAML, edited, null);
        var second = api.post(versions(definition), YAML, edited.replace("version: 1.0.0", "version: 1.1.0"), null);

        assertThat(conflicting.status()).isEqualTo(409);
        assertThat(conflicting.code()).isEqualTo("AGENT_VERSION_EXISTS");
        assertThat(second.status()).isEqualTo(201);
        assertThat(second.body().path("sequence").asLong()).isEqualTo(2);
        assertThat(second.body().path("digest").asString()).isNotEqualTo(first.body().path("digest").asString());
        var reread = api.get(versions(definition) + "/1.0.0", null);
        assertThat(reread.body().path("digest")).isEqualTo(first.body().path("digest"));
        assertThat(reread.body().at("/spec/spec/limits/maxSteps").asInt()).isEqualTo(100);
        assertThat(api.get(versions(definition), null).body().path("items").valueStream()
                .map(item -> item.path("version").asString())).containsExactly("1.0.0", "1.1.0");
    }

    @Test
    void invalidSpecificationsReturnTypedFieldDiagnosticsAndPublishNothing() throws IOException {
        String name = uniqueName();
        String definition = createDefinition(name, null);
        String invalid = fixture("software-engineer.yaml", name)
                .replace("type: spring-ai", "type: Spring AI")
                .replace("maxSteps: 100", "maxSteps: 0")
                .replace("memory: 8Gi", "memory: 8GB")
                .replace("loadingPolicy: ON_DEMAND", "loadingPolicy: LAZY");

        var response = api.post(versions(definition), YAML, invalid, null);

        assertThat(response.status()).isEqualTo(422);
        assertThat(response.code()).isEqualTo("AGENT_SPEC_INVALID");
        assertThat(response.body().path("diagnostics").valueStream().map(diagnostic ->
                diagnostic.path("section").asString() + " " + diagnostic.path("code").asString() + " "
                        + diagnostic.path("path").asString())).containsExactly(
                "CONTEXT INVALID_VALUE /spec/context/loadingPolicy",
                "EXECUTOR INVALID_VALUE /spec/executor/type",
                "LIMITS OUT_OF_RANGE /spec/limits/maxSteps",
                "SANDBOX INVALID_VALUE /spec/sandbox/memory");
        assertThat(api.get(versions(definition), null).body().path("items")).isEmpty();
    }

    @Test
    void deploymentEnvironmentAndTenancyConfigurationIsRejected() throws IOException {
        String name = uniqueName();
        String definition = createDefinition(name, null);
        String withDeployment = fixture("software-engineer.yaml", name)
                + "  deployment:\n    environment: production\n";
        String withWorkspace = fixture("software-engineer.yaml", name)
                .replace("  namespace: default\n", "  namespace: default\n  workspaceId: " + OTHER_WORKSPACE + "\n");

        var deployment = api.post(versions(definition), YAML, withDeployment, null);
        var workspace = api.post(versions(definition), YAML, withWorkspace, null);

        assertThat(deployment.status()).isEqualTo(422);
        assertThat(deployment.body().at("/diagnostics/0/code").asString()).isEqualTo("FORBIDDEN_FIELD");
        assertThat(deployment.body().at("/diagnostics/0/path").asString()).isEqualTo("/spec/deployment");
        assertThat(workspace.status()).isEqualTo(422);
        assertThat(workspace.body().at("/diagnostics/0/path").asString()).isEqualTo("/metadata/workspaceId");
    }

    @Test
    void documentAndRequestFailuresAreTyped() throws IOException {
        String name = uniqueName();
        String definition = createDefinition(name, null);
        String valid = fixture("software-engineer.yaml", name);

        var malformed = api.post(versions(definition), YAML, "spec: [", null);
        var tooLarge = api.post(versions(definition), YAML,
                new byte[AgentSpecCodec.MAX_DOCUMENT_BYTES + 1], null);
        var unsupportedMedia = api.post(versions(definition), "text/plain", valid, null);
        var wrongName = api.post(versions(definition), YAML, fixture("software-engineer.yaml", uniqueName()), null);
        var unknownVersion = api.post(versions(definition), YAML,
                valid.replace("dvarahq.com/v1alpha1", "dvarahq.com/v2"), null);

        assertThat(malformed.status()).isEqualTo(400);
        assertThat(malformed.code()).isEqualTo("MALFORMED_DOCUMENT");
        assertThat(tooLarge.status()).isEqualTo(413);
        assertThat(tooLarge.code()).isEqualTo("DOCUMENT_TOO_LARGE");
        assertThat(unsupportedMedia.status()).isEqualTo(415);
        assertThat(wrongName.status()).isEqualTo(422);
        assertThat(wrongName.body().at("/diagnostics/0/path").asString()).isEqualTo("/metadata/name");
        assertThat(unknownVersion.status()).isEqualTo(422);
        assertThat(unknownVersion.body().at("/diagnostics/0/code").asString()).isEqualTo("UNSUPPORTED_API_VERSION");
        assertThat(api.get(versions(definition), null).body().path("items")).isEmpty();
    }

    @Test
    void definitionNamesAreValidatedAndUniquePerWorkspace() {
        String name = uniqueName();
        createDefinition(name, null);

        var duplicate = api.post("/api/v1/agents", JSON, "{\"name\":\"" + name + "\"}", null);
        var invalid = api.post("/api/v1/agents", JSON, "{\"name\":\"Not Valid\"}", null);
        var missing = api.post("/api/v1/agents", JSON, "{}", null);
        var unreadable = api.post("/api/v1/agents", JSON, "{", null);
        var otherWorkspace = api.post("/api/v1/agents", JSON, "{\"name\":\"" + name + "\"}", OTHER_WORKSPACE);

        assertThat(duplicate.status()).isEqualTo(409);
        assertThat(duplicate.code()).isEqualTo("AGENT_DEFINITION_EXISTS");
        assertThat(invalid.status()).isEqualTo(422);
        assertThat(invalid.body().at("/diagnostics/0/path").asString()).isEqualTo("/name");
        assertThat(missing.status()).isEqualTo(422);
        assertThat(unreadable.status()).isEqualTo(400);
        assertThat(otherWorkspace.status()).isEqualTo(201);
        assertThat(otherWorkspace.body().path("workspaceId").asString()).isEqualTo(OTHER_WORKSPACE.toString());
    }

    @Test
    void crossWorkspaceReadsAndWritesFailIndistinguishablyFromMissingResources() throws IOException {
        String name = uniqueName();
        String definition = createDefinition(name, null);
        String spec = fixture("software-engineer.yaml", name);
        assertThat(api.post(versions(definition), YAML, spec, null).status()).isEqualTo(201);
        String missing = UUID.randomUUID().toString();

        var readDefinition = api.get("/api/v1/agents/" + definition, OTHER_WORKSPACE);
        var readVersions = api.get(versions(definition), OTHER_WORKSPACE);
        var readVersion = api.get(versions(definition) + "/1.0.0", OTHER_WORKSPACE);
        var write = api.post(versions(definition), YAML, spec.replace("version: 1.0.0", "version: 9.0.0"),
                OTHER_WORKSPACE);
        var absent = api.get("/api/v1/agents/" + missing, OTHER_WORKSPACE);

        for (var response : java.util.List.of(readDefinition, readVersions, readVersion, write)) {
            assertThat(response.status()).isEqualTo(404);
            assertThat(response.body().path("code")).isEqualTo(absent.body().path("code"));
            assertThat(response.body().path("detail")).isEqualTo(absent.body().path("detail"));
            assertThat(response.body().toString()).doesNotContain(name, "software engineer");
        }
        assertThat(api.get("/api/v1/agents", OTHER_WORKSPACE).body().path("items").valueStream()
                .map(item -> item.path("id").asString())).doesNotContain(definition);
        assertThat(api.get(versions(definition), null).body().path("items").valueStream()
                .map(item -> item.path("version").asString())).containsExactly("1.0.0");
    }

    @Test
    void serviceRecomputesCanonicalContentInsteadOfTrustingCallerBuiltRecords() throws IOException {
        String name = uniqueName();
        String definition = createDefinition(name, null);
        var codec = AgentSpecCodec.create();
        var genuine = codec.read(fixture("software-engineer.yaml", name).getBytes(StandardCharsets.UTF_8),
                com.dvarahq.agentworks.spec.SpecFormat.YAML);
        String forgedJson = genuine.canonicalJson().replace("\"version\":\"1.0.0\"", "\"version\":\"9.9.9\"");
        var forged = new com.dvarahq.agentworks.spec.CanonicalAgentSpec(genuine.spec(), forgedJson,
                AgentSpecCodec.digest(forgedJson));

        var published = agents.publishVersion(DefaultTenantBootstrap.DEFAULT_WORKSPACE_ID,
                new AgentDefinitionId(UUID.fromString(definition)), forged);

        assertThat(published.version().version()).isEqualTo("1.0.0");
        assertThat(published.version().digest()).isEqualTo(genuine.digest());
        assertThat(published.version().spec().canonicalJson()).isEqualTo(genuine.canonicalJson());
    }

    private String createDefinition(String name, String description) {
        String body = description == null ? "{\"name\":\"" + name + "\"}"
                : "{\"name\":\"" + name + "\",\"description\":\"" + description + "\"}";
        var response = api.post("/api/v1/agents", JSON, body, null);
        assertThat(response.status()).isEqualTo(201);
        assertThat(response.location()).endsWith("/api/v1/agents/" + response.body().path("id").asString());
        return response.body().path("id").asString();
    }

    private static String versions(String definition) {
        return "/api/v1/agents/" + definition + "/versions";
    }

    static String uniqueName() {
        return "agent-" + UUID.randomUUID().toString().substring(0, 8);
    }

    static String fixture(String resource, String name) throws IOException {
        try (InputStream input = AgentDefinitionApiTest.class.getResourceAsStream("/agentspec/" + resource)) {
            String content = new String(Objects.requireNonNull(input, resource).readAllBytes(), StandardCharsets.UTF_8);
            return content.replace("name: software-engineer", "name: " + name)
                    .replace("\"name\": \"software-engineer\"", "\"name\": \"" + name + "\"");
        }
    }

    private static String canonical(JsonNode spec) {
        return AgentSpecCodec.create().read(AgentApiClient.JSON.writeValueAsBytes(spec),
                com.dvarahq.agentworks.spec.SpecFormat.JSON).canonicalJson();
    }
}
