package com.dvarahq.agentworks.controlplane.agent.web;

import com.dvarahq.agentworks.DvaraAgentWorksApplication;
import com.dvarahq.agentworks.storage.lifecycle.Digests;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Provisional REST contract for releases, environments, promotion, cohorts, and root admission (#35 formalizes it). */
@SpringBootTest(classes = DvaraAgentWorksApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestWorkspaces.class)
class LifecycleApiTest {
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
    void promotionIsIdempotentVersionedAndDrivesRootAdmission() {
        String environment = createEnvironment("Customer Acceptance");
        String release = release("agent", "1.0.0");

        var promoted = api.send("POST", "/api/v1/environments/" + environment + "/promotions",
                "{\"deployment\":\"api\",\"releaseId\":\"" + release + "\"}", workspace,
                Map.of("Idempotency-Key", "promote-1", "If-Match", "\"0\""));
        var replay = api.send("POST", "/api/v1/environments/" + environment + "/promotions",
                "{\"deployment\":\"api\",\"releaseId\":\"" + release + "\"}", workspace,
                Map.of("Idempotency-Key", "promote-1", "If-Match", "\"0\""));
        var reused = api.send("POST", "/api/v1/environments/" + environment + "/promotions",
                "{\"deployment\":\"api\",\"releaseId\":\"" + release + "\",\"reason\":\"other\"}", workspace,
                Map.of("Idempotency-Key", "promote-1", "If-Match", "\"0\""));
        var stale = api.send("POST", "/api/v1/environments/" + environment + "/promotions",
                "{\"deployment\":\"api\",\"releaseId\":\"" + release + "\"}", workspace, Map.of("If-Match", "\"0\""));

        assertThat(promoted.status()).isEqualTo(201);
        assertThat(promoted.header("ETag")).isEqualTo("\"1\"");
        assertThat(promoted.header("Location"))
                .isEqualTo("/api/v1/decisions/" + promoted.body().path("decisionId").asString());
        assertThat(api.get(promoted.header("Location"), workspace).status()).isEqualTo(200);
        assertThat(replay.header("Idempotent-Replayed")).isEqualTo("true");
        assertThat(replay.body().path("decisionId")).isEqualTo(promoted.body().path("decisionId"));
        assertThat(reused.status()).isEqualTo(409);
        assertThat(reused.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(stale.status()).isEqualTo(412);

        String deployment = promoted.body().path("deploymentId").asString();
        var detail = api.get("/api/v1/deployments/" + deployment, workspace);
        assertThat(detail.header("ETag")).isEqualTo("\"1\"");
        assertThat(detail.body().at("/cohorts/0/state").asString()).isEqualTo("ROUTABLE");

        var run = api.send("POST", "/api/v1/deployments/" + deployment + "/runs", "{\"input\":\"hi\"}", workspace,
                Map.of("Idempotency-Key", "run-1"));
        var runReplay = api.send("POST", "/api/v1/deployments/" + deployment + "/runs", "{\"input\":\"hi\"}", workspace,
                Map.of("Idempotency-Key", "run-1"));
        var forged = api.send("POST", "/api/v1/deployments/" + deployment + "/runs",
                "{\"input\":\"hi\",\"parentRunId\":\"" + run.body().path("id").asString() + "\"}", workspace, Map.of());
        assertThat(run.status()).isEqualTo(201);
        assertThat(run.body().path("routingGenerationId").asString())
                .isEqualTo(promoted.body().path("routingGenerationId").asString());
        assertThat(runReplay.status()).isEqualTo(200);
        assertThat(runReplay.header("Idempotent-Replayed")).isEqualTo("true");
        assertThat(runReplay.body().path("id")).isEqualTo(run.body().path("id"));
        assertThat(run.raw()).doesNotContain("requestFingerprint", "idempotencyKeyDigest");
        assertThat(forged.status()).isEqualTo(422);
        assertThat(forged.code()).isEqualTo("CONTINUATION_NOT_EXTERNAL");
    }

    @Test
    void cohortCommandsRequirePreconditionsAndRetirementReportsTypedBlockers() {
        String environment = createEnvironment("prod");
        var first = promote(environment, release("agent", "1.0.0"));
        String deployment = first.path("deploymentId").asString();
        String cohort = first.path("cohortId").asString();
        String run = api.send("POST", "/api/v1/deployments/" + deployment + "/runs", "{}", workspace, Map.of())
                .body().path("id").asString();

        var missing = api.send("POST", "/api/v1/cohorts/" + cohort + "/suspend", "{}", workspace, Map.of());
        var suspended = api.send("POST", "/api/v1/cohorts/" + cohort + "/suspend", "{\"reason\":\"incident\"}", workspace,
                Map.of("If-Match", "\"2\""));
        var refused = api.send("POST", "/api/v1/deployments/" + deployment + "/runs", "{}", workspace, Map.of());
        var resumed = api.send("POST", "/api/v1/cohorts/" + cohort + "/resume", "{}", workspace, Map.of("If-Match", "\"3\""));
        assertThat(missing.status()).isEqualTo(428);
        assertThat(suspended.header("ETag")).isEqualTo("\"3\"");
        assertThat(refused.code()).isEqualTo("COHORT_NOT_ADMITTING");
        assertThat(resumed.body().path("state").asString()).isEqualTo("ROUTABLE");

        promote(environment, release("agent", "2.0.0"));
        String version = api.get("/api/v1/cohorts/" + cohort, workspace).header("ETag");
        var blocked = api.send("POST", "/api/v1/cohorts/" + cohort + "/retire", "{}", workspace, Map.of("If-Match", version));

        assertThat(blocked.status()).isEqualTo(409);
        assertThat(blocked.body().path("outcome").asString()).isEqualTo("BLOCKED");
        assertThat(blocked.body().at("/blockers/0/category").asString()).isEqualTo("NON_TERMINAL_RUN");
        assertThat(api.get("/api/v1/runs/" + run, workspace).body().path("cohortId").asString()).isEqualTo(cohort);
    }

    @Test
    void manifestsAliasesAndCredentialValidationAreExposed() {
        String release = release("agent", "1.0.0");
        var manifest = api.get("/api/v1/releases/" + release + "/manifest", workspace);
        var releaseView = api.get("/api/v1/releases/" + release, workspace);
        // The digest covers the exact bytes served, not a re-serialization of them.
        assertThat(Digests.prefixed(manifest.raw())).isEqualTo(releaseView.body().path("manifestDigest").asString());

        var alias = api.send("PUT", "/api/v1/release-aliases/latest", "{\"releaseId\":\"" + release + "\"}", workspace, Map.of());
        var staleAlias = api.send("PUT", "/api/v1/release-aliases/latest", "{\"releaseId\":\"" + release + "\"}", workspace,
                Map.of("If-Match", "\"7\""));
        assertThat(alias.header("ETag")).isEqualTo("\"1\"");
        assertThat(staleAlias.status()).isEqualTo(412);

        String environment = createEnvironment("prod");
        var secret = api.send("POST", "/api/v1/environments/" + environment + "/promotions",
                "{\"deployment\":\"api\",\"releaseId\":\"" + release + "\",\"configuration\":{\"credentialReferences\":"
                        + "{\"KEY\":\"sk-live-raw-secret\"}}}", workspace, Map.of());
        assertThat(secret.status()).isEqualTo(422);
        assertThat(secret.body().toString()).doesNotContain("sk-live-raw-secret");
    }

    @Test
    void anotherWorkspaceReceivesIndistinguishableNotFound() {
        String environment = createEnvironment("prod");
        var promotion = promote(environment, release("agent", "1.0.0"));
        UUID other = TestWorkspaces.createWorkspace(jdbc);

        for (var response : java.util.List.of(
                api.get("/api/v1/deployments/" + promotion.path("deploymentId").asString(), other),
                api.get("/api/v1/cohorts/" + promotion.path("cohortId").asString(), other),
                api.get("/api/v1/decisions/" + promotion.path("decisionId").asString(), other),
                api.get("/api/v1/environments/" + environment, other),
                api.send("POST", "/api/v1/deployments/" + promotion.path("deploymentId").asString() + "/runs", "{}", other,
                        Map.of()),
                api.get("/api/v1/deployments/" + UUID.randomUUID(), other))) {
            assertThat(response.status()).isEqualTo(404);
            assertThat(response.code()).isEqualTo("NOT_FOUND");
        }
    }

    private String createEnvironment(String name) {
        var response = api.post("/api/v1/environments", JSON, "{\"name\":\"" + name + "\"}", workspace);
        assertThat(response.status()).isEqualTo(201);
        return response.body().path("id").asString();
    }

    private String release(String agent, String version) {
        String definition = api.get("/api/v1/agents", workspace).body().path("items").valueStream()
                .filter(item -> item.path("name").asString().equals(agent)).map(item -> item.path("id").asString())
                .findFirst().orElseGet(() -> api.post("/api/v1/agents", JSON, "{\"name\":\"" + agent + "\"}", workspace)
                        .body().path("id").asString());
        String yaml = "apiVersion: dvarahq.com/v1alpha1\nkind: Agent\nmetadata: {name: " + agent + ", version: " + version
                + "}\nspec:\n  executor: {type: spring-ai}\n";
        String versionId = api.post("/api/v1/agents/" + definition + "/versions", "application/yaml", yaml, workspace)
                .body().path("id").asString();
        var release = api.post("/api/v1/agent-versions/" + versionId + "/releases", JSON,
                "{\"name\":\"" + agent + "-" + version + "\"}", workspace);
        assertThat(release.status()).isEqualTo(201);
        return release.body().path("id").asString();
    }

    private tools.jackson.databind.JsonNode promote(String environment, String release) {
        var response = api.send("POST", "/api/v1/environments/" + environment + "/promotions",
                "{\"deployment\":\"api\",\"releaseId\":\"" + release + "\"}", workspace, Map.of());
        assertThat(response.status()).isEqualTo(201);
        return response.body();
    }
}
