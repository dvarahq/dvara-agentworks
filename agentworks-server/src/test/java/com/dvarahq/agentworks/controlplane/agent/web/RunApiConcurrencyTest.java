package com.dvarahq.agentworks.controlplane.agent.web;

import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import com.dvarahq.agentworks.DvaraAgentWorksApplication;
import com.dvarahq.agentworks.runplane.execution.DurableRunEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * API and persisted state stay consistent under concurrent requests (issue #35): racing submissions with one
 * idempotency key create one run, racing lifecycle commands record one outcome, and every run the API lists or shows
 * matches the persisted summary and its event history.
 */
@SpringBootTest(classes = DvaraAgentWorksApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestWorkspaces.class)
class RunApiConcurrencyTest {
    private static final String JSON = "application/json";

    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    DurableRunEngine engine;
    private AgentApiClient api;
    private UUID workspace;
    private String environment;
    private String deployment;

    @BeforeEach
    void setUp() {
        api = new AgentApiClient(port);
        workspace = TestWorkspaces.createWorkspace(jdbc);
        environment = api.post("/api/v1/environments", JSON, "{\"name\":\"prod\"}", workspace).body().path("id").asString();
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

    @Test
    void racingSubmissionsWithOneKeyCreateExactlyOneRun() throws Exception {
        List<AgentApiClient.Response> responses = race(12, index -> api.send("POST",
                "/api/v1/deployments/" + deployment + "/runs", "{\"input\":{\"q\":\"same\"}}", workspace,
                Map.of("Idempotency-Key", "one-run")));

        Set<String> ids = new HashSet<>();
        int created = 0;
        for (var response : responses) {
            assertThat(response.status()).as(response.raw()).isIn(200, 201);
            if (response.status() == 201) created++;
            else assertThat(response.header("Idempotent-Replayed")).isEqualTo("true");
            ids.add(response.body().path("id").asString());
        }
        assertThat(created).isEqualTo(1);
        assertThat(ids).hasSize(1);
        assertThat(jdbc.queryForObject("select count(*) from agent_run where workspace_id = ?", Long.class, workspace))
                .isOne();
        var conflict = api.send("POST", "/api/v1/deployments/" + deployment + "/runs", "{\"input\":{\"q\":\"other\"}}",
                workspace, Map.of("Idempotency-Key", "one-run"));
        assertThat(conflict.status()).isEqualTo(409);
        assertThat(conflict.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(jdbc.queryForObject("select count(*) from agent_run where workspace_id = ?", Long.class, workspace))
                .isOne();
    }

    @Test
    void racingCommandsRecordOneOutcomeAndTheApiMatchesPersistedState() throws Exception {
        List<String> runs = new ArrayList<>();
        for (int index = 0; index < 6; index++) {
            runs.add(api.send("POST", "/api/v1/deployments/" + deployment + "/runs", "{}", workspace, Map.of()).body()
                    .path("id").asString());
        }
        race(18, index -> api.send("POST", "/api/v1/runs/" + runs.get(index / 3)
                + (index % 3 == 0 ? "/resume" : "/cancel"), "{}", workspace, Map.of()));

        var listed = api.get("/api/v1/runs?limit=200", workspace).body().path("items");
        assertThat(listed.size()).isEqualTo(runs.size());
        for (var view : listed) {
            UUID run = UUID.fromString(view.path("id").asString());
            var persisted = engine.run(new WorkspaceId(workspace), run);
            assertThat(view.path("lifecycle").asString()).isEqualTo("CANCELLED").isEqualTo(persisted.lifecycle().name());
            assertThat(view.path("eventSequence").asLong()).isEqualTo(persisted.summary().eventSequence());
            var events = api.get("/api/v1/runs/" + run + "/events?limit=200", workspace).body().path("items");
            assertThat(events.size()).isEqualTo((int) persisted.summary().eventSequence());
            long cancellations = 0;
            for (var event : events) if (event.path("type").asString().equals("RUN_CANCELLED")) cancellations++;
            assertThat(cancellations).isOne();
            assertThat(engine.verify(new WorkspaceId(workspace), run).intact()).isTrue();
        }
    }

    private static List<AgentApiClient.Response> race(int parties, java.util.function.IntFunction<AgentApiClient.Response> task)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(parties);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<AgentApiClient.Response>> futures = new ArrayList<>();
            for (int index = 0; index < parties; index++) {
                int party = index;
                Callable<AgentApiClient.Response> call = () -> {
                    start.await();
                    return task.apply(party);
                };
                futures.add(pool.submit(call));
            }
            start.countDown();
            List<AgentApiClient.Response> responses = new ArrayList<>();
            for (Future<AgentApiClient.Response> future : futures) responses.add(future.get());
            return responses;
        } finally {
            pool.shutdownNow();
        }
    }
}
