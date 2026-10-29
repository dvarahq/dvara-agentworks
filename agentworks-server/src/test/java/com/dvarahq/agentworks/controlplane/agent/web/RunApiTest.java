package com.dvarahq.agentworks.controlplane.agent.web;

import com.dvarahq.agentworks.domain.run.ClaimScope;
import com.dvarahq.agentworks.domain.run.RunBlockReason;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import com.dvarahq.agentworks.DvaraAgentWorksApplication;
import com.dvarahq.agentworks.gateway.PinnedModelRoles;
import com.dvarahq.agentworks.runplane.execution.DurableRunEngine;
import com.dvarahq.agentworks.runplane.execution.ReleaseManifestReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Provisional run API over the durable engine (#35 formalizes it): submission, trace propagation, events, steps,
 * cancellation, and resumption. The Spring Boot context runs on H2 for local development; PostgreSQL authority is
 * proven by the PostgreSQL suites.
 */
@SpringBootTest(classes = DvaraAgentWorksApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestWorkspaces.class)
class RunApiTest {
    private static final String JSON = "application/json";
    private static final String TRACE = "0af7651916cd43dd8448eb211c80319c";

    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    DurableRunEngine engine;
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
        String version = api.post("/api/v1/agents/" + definition + "/versions", "application/yaml",
                "apiVersion: dvarahq.com/v1alpha1\nkind: Agent\nmetadata: {name: agent, version: 1.0.0}\n"
                        + "spec:\n  executor: {type: spring-ai}\n", workspace).body().path("id").asString();
        String release = api.post("/api/v1/agent-versions/" + version + "/releases", JSON, "{\"name\":\"agent-1\"}",
                workspace).body().path("id").asString();
        deployment = api.send("POST", "/api/v1/environments/" + environment + "/promotions",
                "{\"deployment\":\"api\",\"releaseId\":\"" + release + "\"}", workspace, Map.of()).body()
                .path("deploymentId").asString();
    }

    // #175 medium 4: the shipped application runs startup reconciliation against its migrated schema.
    @Test
    void theApplicationReconcilesDeploymentsAtRunPlaneStartup(
            @Autowired com.dvarahq.agentworks.autoconfigure.RunPlaneStartupReconciliation startup) throws Exception {
        assertThat(startup.isRunning()).isTrue();
        // The pass runs in the background after start(); it completes against the migrated schema.
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!startup.completed() && System.nanoTime() < deadline) Thread.sleep(10);
        assertThat(startup.completed()).isTrue();
        assertThat(startup.announced()).isNotNegative();
    }

    @Test
    void submittedRunsAreQueuedWithThePropagatedTraceAndExposeEventsStepsAndSummary() {
        var submitted = api.send("POST", "/api/v1/deployments/" + deployment + "/runs", "{\"input\":{\"q\":\"hi\"}}",
                workspace, Map.of("traceparent", "00-" + TRACE + "-b7ad6b7169203331-01"));
        String run = submitted.body().path("id").asString();

        assertThat(submitted.status()).isEqualTo(201);
        assertThat(submitted.body().path("lifecycle").asString()).isEqualTo("QUEUED");
        assertThat(submitted.body().path("traceId").asString()).isEqualTo(TRACE);
        assertThat(submitted.body().path("manifestVersion").asInt())
                .isEqualTo(PinnedModelRoles.MODEL_RESILIENCE_MANIFEST_VERSION);
        assertThat(submitted.body().path("usage").path("totalTokens").asLong()).isZero();
        var events = api.get("/api/v1/runs/" + run + "/events", workspace).body().path("items");
        assertThat(events.get(0).path("type").asString()).isEqualTo("RUN_CREATED");
        assertThat(events.get(0).path("payload").path("input").path("q").asString()).isEqualTo("hi");
        assertThat(events.get(0).path("traceId").asString()).isEqualTo(TRACE);
        assertThat(events.get(1).path("type").asString()).isEqualTo("RUN_QUEUED");
        var steps = api.get("/api/v1/runs/" + run + "/steps", workspace).body().path("items");
        assertThat(steps.size()).isEqualTo(2);
        assertThat(steps.get(0).path("category").asString()).isEqualTo("SYSTEM");
        assertThat(api.get("/api/v1/runs/" + run, workspace).body().path("eventSequence").asLong()).isEqualTo(2);
        var context = api.get("/api/v1/runs/" + run + "/context", workspace);
        assertThat(context.status()).isEqualTo(200);
        assertThat(context.body().path("runId").asString()).isEqualTo(run);
        assertThat(context.body().path("events").size()).isZero();
    }

    @Test
    void operatorsCancelRunsAndResumeOnlyPausedOnes() {
        String cancelled = submit();
        var cancel = api.send("POST", "/api/v1/runs/" + cancelled + "/cancel", "{\"reason\":\"not needed\"}", workspace,
                Map.of());
        var again = api.send("POST", "/api/v1/runs/" + cancelled + "/cancel", "{}", workspace, Map.of());
        var resumeTerminal = api.send("POST", "/api/v1/runs/" + cancelled + "/resume", "{}", workspace, Map.of());
        assertThat(cancel.status()).isEqualTo(200);
        assertThat(cancel.body().path("lifecycle").asString()).isEqualTo("CANCELLED");
        assertThat(cancel.body().path("terminalCode").asString()).isEqualTo("CANCELLED");
        assertThat(again.body().path("eventSequence")).isEqualTo(cancel.body().path("eventSequence"));
        assertThat(resumeTerminal.status()).isEqualTo(409);
        assertThat(resumeTerminal.code()).isEqualTo("RUN_TERMINAL");

        String blocked = submit();
        var scope = ClaimScope.any(ReleaseManifestReader.SUPPORTED_VERSIONS).in(new WorkspaceId(workspace));
        var claimed = engine.claimNext("api-test-worker", scope, Duration.ofMinutes(1)).orElseThrow();
        assertThat(claimed.run().id().toString()).isEqualTo(blocked);
        engine.block(claimed.lease(), RunBlockReason.HUMAN_INTERACTION, null);
        var refused = api.send("POST", "/api/v1/runs/" + blocked + "/resume", "{}", workspace, Map.of());
        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.code()).isEqualTo("RUN_BLOCKED");

        String paused = submit();
        var second = engine.claimNext("api-test-worker", scope.excluding(Set.of(UUID.fromString(blocked))),
                Duration.ofMinutes(1)).orElseThrow();
        engine.pause(second.lease(), "operator");
        var resumed = api.send("POST", "/api/v1/runs/" + paused + "/resume", "{}", workspace, Map.of());
        assertThat(resumed.status()).isEqualTo(200);
        assertThat(resumed.body().path("lifecycle").asString()).isEqualTo("QUEUED");
    }

    @Test
    void runEndpointsFailClosedAcrossWorkspacesAndRejectMalformedRequests() {
        String run = submit();
        UUID other = TestWorkspaces.createWorkspace(jdbc);

        for (var response : java.util.List.of(api.get("/api/v1/runs/" + run, other),
                api.get("/api/v1/runs/" + run + "/events", other), api.get("/api/v1/runs/" + run + "/context", other),
                api.get("/api/v1/runs/" + run + "/steps", other),
                api.send("POST", "/api/v1/runs/" + run + "/cancel", "{}", other, Map.of()),
                api.send("POST", "/api/v1/runs/" + run + "/resume", "{}", other, Map.of()))) {
            assertThat(response.status()).isEqualTo(404);
            assertThat(response.code()).isEqualTo("NOT_FOUND");
        }
        // Per W3C Trace Context an invalid traceparent is ignored and the run starts a new trace.
        var badTrace = api.send("POST", "/api/v1/deployments/" + deployment + "/runs", "{}", workspace,
                Map.of("traceparent", "00-" + "0".repeat(32) + "-b7ad6b7169203331-01"));
        var malformedTrace = api.send("POST", "/api/v1/deployments/" + deployment + "/runs", "{}", workspace,
                Map.of("traceparent", "not-a-traceparent"));
        var forbiddenVersion = api.send("POST", "/api/v1/deployments/" + deployment + "/runs", "{}", workspace,
                Map.of("traceparent", "ff-" + TRACE + "-b7ad6b7169203331-01"));
        var oversized = api.send("POST", "/api/v1/deployments/" + deployment + "/runs",
                "{\"input\":\"" + "x".repeat(140_000) + "\"}", workspace, Map.of());
        for (var ignored : java.util.List.of(badTrace, malformedTrace, forbiddenVersion)) {
            assertThat(ignored.status()).isEqualTo(201);
            assertThat(ignored.body().path("traceId").asString()).matches("[0-9a-f]{32}").isNotEqualTo(TRACE)
                    .isNotEqualTo("0".repeat(32));
        }
        assertThat(oversized.status()).isEqualTo(422);
        assertThat(oversized.code()).isEqualTo("PAYLOAD_TOO_LARGE");
        assertThat(api.get("/api/v1/runs/" + run, workspace).body().path("lifecycle").asString()).isEqualTo("QUEUED");
    }

    @Test
    void exactReplayReturnsRecordedOutputAndNeverChangesSourceHistory() {
        String source = submit();
        var scope = ClaimScope.any(ReleaseManifestReader.SUPPORTED_VERSIONS).in(new WorkspaceId(workspace));
        var claimed = engine.claimNext("replay-test-worker", scope, Duration.ofMinutes(1)).orElseThrow();
        engine.complete(claimed.lease(), Map.of("type", "text", "content", "recorded"));
        long sequence = api.get("/api/v1/runs/" + source, workspace).body().path("eventSequence").asLong();

        var plan = api.get("/api/v1/runs/" + source + "/replay/plan?mode=EXACT", workspace);
        var replay = api.send("POST", "/api/v1/runs/" + source + "/replay", "{\"mode\":\"EXACT\"}",
                workspace, Map.of());

        assertThat(plan.status()).isEqualTo(200);
        assertThat(plan.body().path("executable").asBoolean()).isTrue();
        assertThat(plan.body().path("provenance").path("sourceRunId").asString()).isEqualTo(source);
        assertThat(plan.body().path("effectPolicy").asString()).isEqualTo("RECORDED_RESULTS_ONLY");
        assertThat(plan.body().path("liveEffectsAllowed").asBoolean()).isFalse();
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.body().path("output").path("content").asString()).isEqualTo("recorded");
        assertThat(api.get("/api/v1/runs/" + source, workspace).body().path("eventSequence").asLong())
                .isEqualTo(sequence);

        UUID other = TestWorkspaces.createWorkspace(jdbc);
        assertThat(api.get("/api/v1/runs/" + source + "/replay/plan?mode=EXACT", other).status()).isEqualTo(404);
        assertThat(api.send("POST", "/api/v1/runs/" + source + "/replay", "{\"mode\":\"EXACT\"}", other,
                Map.of()).status()).isEqualTo(404);
    }

    private String submit() {
        var response = api.send("POST", "/api/v1/deployments/" + deployment + "/runs", "{}", workspace, Map.of());
        assertThat(response.status()).isEqualTo(201);
        return response.body().path("id").asString();
    }
}
