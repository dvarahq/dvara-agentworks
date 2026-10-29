package com.dvarahq.agentworks.controlplane.agent.web;

import com.dvarahq.agentworks.autoconfigure.RunWorkerLifecycle;
import com.dvarahq.agentworks.DvaraAgentWorksApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #34 end to end on the shipped application: a run submitted through the REST API by an authenticated principal
 * is claimed by the opt-in worker pool, executed by the Spring AI executor through Core's gateway with the release's
 * pinned model and the principal's identity, and recorded as durable model events. The deterministic fake gateway is
 * used only under the loopback local-development gate.
 */
@SpringBootTest(classes = DvaraAgentWorksApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "agentworks.development.local=true", "server.address=127.0.0.1",
                "agentworks.gateway.gateways.direct.type=deterministic-fake",
                "agentworks.gateway.gateways.direct.models[0].alias=coding-premium",
                "agentworks.gateway.gateways.direct.models[0].model=fake-e2e-model",
                "agentworks.runs.worker.enabled=true", "agentworks.runs.worker.threads=2",
                "agentworks.runs.worker.lease-duration=PT10S", "agentworks.runs.worker.idle-backoff=PT0.05S"})
@Import(TestWorkspaces.class)
class SpringAiRunExecutionApiTest {
    private static final String JSON = "application/json";

    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    RunWorkerLifecycle workers;
    private AgentApiClient api;
    private UUID workspace;
    private String deployment;

    @BeforeEach
    void setUp() {
        api = new AgentApiClient(port);
        workspace = TestWorkspaces.createWorkspace(jdbc);
        String environment = api.post("/api/v1/environments", JSON, "{\"name\":\"prod\"}", workspace).body()
                .path("id").asString();
        String definition = api.post("/api/v1/agents", JSON, "{\"name\":\"agent\"}", workspace).body().path("id").asString();
        String yaml = "apiVersion: dvarahq.com/v1alpha1\nkind: Agent\nmetadata: {name: agent, version: 1.0.0}\n"
                + "spec:\n  executor: {type: spring-ai}\n  instructions: {system: Answer in one line.}\n"
                + "  models:\n    reasoning: {gateway: direct, model: coding-premium}\n";
        var version = api.post("/api/v1/agents/" + definition + "/versions", "application/yaml", yaml, workspace);
        assertThat(version.status()).isEqualTo(201);
        String release = api.post("/api/v1/agent-versions/" + version.body().path("id").asString() + "/releases", JSON,
                "{\"name\":\"agent-1\"}", workspace).body().path("id").asString();
        deployment = api.send("POST", "/api/v1/environments/" + environment + "/promotions",
                "{\"deployment\":\"api\",\"releaseId\":\"" + release + "\"}", workspace, Map.of()).body()
                .path("deploymentId").asString();
    }

    @Test
    void aSubmittedRunIsExecutedByTheAutoStartedWorkerAsItsSubmitter() throws Exception {
        assertThat(workers.isRunning()).isTrue();
        assertThat(workers.workers()).isEqualTo(2);

        var submitted = api.send("POST", "/api/v1/deployments/" + deployment + "/runs",
                "{\"input\":{\"prompt\":\"hello from the api\"}}", workspace, Map.of());
        assertThat(submitted.status()).isEqualTo(201);
        String run = submitted.body().path("id").asString();

        JsonNode view = null;
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            view = api.get("/api/v1/runs/" + run, workspace).body();
            if (List.of("SUCCEEDED", "FAILED", "CANCELLED").contains(view.path("lifecycle").asString())) break;
            Thread.sleep(50);
        }
        assertThat(view.path("lifecycle").asString()).as(view.toString()).isEqualTo("SUCCEEDED");

        var events = api.get("/api/v1/runs/" + run + "/events", workspace).body().path("items");
        List<String> types = new ArrayList<>();
        events.forEach(event -> types.add(event.path("type").asString()));
        assertThat(types).containsSubsequence("RUN_CREATED", "RUN_ACTIVATED", "CAPABILITY_RESOLVED", "MODEL_REQUESTED",
                "MODEL_STREAM_CHUNK", "MODEL_RESPONSE_RECEIVED", "CHECKPOINT_CREATED", "RUN_SUCCEEDED");
        JsonNode capabilities = java.util.stream.StreamSupport.stream(events.spliterator(), false)
                .filter(event -> "CAPABILITY_RESOLVED".equals(event.path("type").asString())).findFirst().orElseThrow()
                .path("payload");
        assertThat(capabilities.path("resolverVersion").asString()).isEqualTo("capability-resolver/v1");
        assertThat(capabilities.path("catalogTools").isEmpty()).isTrue();
        assertThat(capabilities.path("decisions").isEmpty()).isTrue();
        JsonNode invoker = events.get(0).path("payload").path("invoker");
        assertThat(invoker.path("workspaceId").asString()).isEqualTo(workspace.toString());
        assertThat(invoker.path("method").asString()).isEqualTo("api-token");
        assertThat(invoker.toString()).doesNotContain("secret").doesNotContain("token\":\"");
        JsonNode last = events.get(events.size() - 1);
        assertThat(last.path("payload").path("output").path("content").asString())
                .isEqualTo("echo(fake-e2e-model): [USER_INPUT:run-input]\nhello from the api");
        assertThat(view.path("usage").path("modelCalls").asLong()).isOne();

        long sourceSequence = view.path("eventSequence").asLong();
        var replay = api.send("POST", "/api/v1/runs/" + run + "/replay", "{\"mode\":\"MODEL_REPLAY\"}",
                workspace, Map.of());
        assertThat(replay.status()).as(replay.body().toString()).isEqualTo(200);
        assertThat(replay.body().path("plan").path("provenance").path("sourceRunId").asString()).isEqualTo(run);
        assertThat(replay.body().path("plan").path("liveEffectsAllowed").asBoolean()).isFalse();
        assertThat(replay.body().path("output").path("content").asString())
                .isEqualTo("echo(fake-e2e-model): [USER_INPUT:run-input]\nhello from the api");
        assertThat(api.get("/api/v1/runs/" + run, workspace).body().path("eventSequence").asLong())
                .isEqualTo(sourceSequence);
    }

    // Issue #35: the walking skeleton streamed live, from submission to the terminal event, over the public API.
    @Test
    void aClientStreamsTheLiveRunFromSubmissionToItsTerminalEvent() {
        var submitted = api.send("POST", "/api/v1/deployments/" + deployment + "/runs",
                "{\"input\":{\"prompt\":\"stream this\"}}", workspace, Map.of());
        String run = submitted.body().path("id").asString();
        String token = com.dvarahq.agentworks.identity.TestIdentities.forPort(port).token(workspace);

        try (SseClient stream = SseClient.open(port, "/api/v1/runs/" + run + "/stream", token, Map.of())) {
            assertThat(stream.status()).isEqualTo(200);
            List<SseClient.Event> events = stream.drain(Duration.ofSeconds(30));
            assertThat(stream.ended(Duration.ofSeconds(5))).isTrue();
            List<String> types = events.stream().map(SseClient.Event::name).toList();
            assertThat(types).containsSubsequence("RUN_CREATED", "RUN_QUEUED", "RUN_ACTIVATED",
                    "CAPABILITY_RESOLVED", "MODEL_REQUESTED", "MODEL_STREAM_CHUNK", "MODEL_RESPONSE_RECEIVED",
                    "RUN_SUCCEEDED", "stream-end");
            List<SseClient.Event> runEvents = events.subList(0, events.size() - 1);
            for (int index = 0; index < runEvents.size(); index++) {
                assertThat(runEvents.get(index).sequence()).isEqualTo(index + 1);
                assertThat(runEvents.get(index).json().path("deploymentId").asString()).isEqualTo(deployment);
            }
            // Prompts are recorded and streamed as digests only.
            assertThat(events.stream().map(SseClient.Event::data).toList().toString()).doesNotContain("Answer in one line.");
            assertThat(events.getLast().json().path("lifecycle").asString()).isEqualTo("SUCCEEDED");
        }
    }
}
