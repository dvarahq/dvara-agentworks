package com.dvarahq.agentworks.controlplane.agent.web;

import com.dvarahq.agentworks.DvaraAgentWorksApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keyset pagination and filtering of every paged collection (issue #35): pages follow the documented order, never
 * repeat or skip an item, end with a {@code null} cursor, stay inside the caller's workspace whatever cursor is
 * sent, and refuse malformed cursors and limits with typed problems.
 */
@SpringBootTest(classes = DvaraAgentWorksApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestWorkspaces.class)
class ApiPaginationTest {
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
    void everyPagedCollectionIsAGapFreeKeysetInItsDocumentedOrder() {
        List<String> names = List.of("delta", "alpha", "echo", "charlie", "bravo");
        List<String> definitions = new ArrayList<>();
        for (String name : names) {
            definitions.add(api.post("/api/v1/agents", JSON, "{\"name\":\"" + name + "\"}", workspace).body().path("id")
                    .asString());
        }
        assertThat(all("/api/v1/agents", 2)).extracting(item -> item.path("name").asString())
                .containsExactly("alpha", "bravo", "charlie", "delta", "echo");

        String definition = definitions.getFirst();
        List<String> releases = new ArrayList<>();
        for (int index = 1; index <= 3; index++) {
            String version = api.post("/api/v1/agents/" + definition + "/versions", "application/yaml",
                    "apiVersion: dvarahq.com/v1alpha1\nkind: Agent\nmetadata: {name: delta, version: 1.0." + index + "}\n"
                            + "spec:\n  executor: {type: spring-ai}\n", workspace).body().path("id").asString();
            releases.add(api.post("/api/v1/agent-versions/" + version + "/releases", JSON,
                    "{\"name\":\"delta-" + index + "\"}", workspace).body().path("id").asString());
        }
        assertThat(all("/api/v1/agents/" + definition + "/versions", 2)).extracting(item -> item.path("version").asString())
                .containsExactly("1.0.1", "1.0.2", "1.0.3");
        assertThat(all("/api/v1/releases", 2)).extracting(item -> item.path("id").asString())
                .containsExactlyInAnyOrderElementsOf(releases).hasSize(3);

        List<String> environments = new ArrayList<>();
        for (String name : List.of("staging", "prod", "dev")) {
            environments.add(api.post("/api/v1/environments", JSON, "{\"name\":\"" + name + "\"}", workspace).body()
                    .path("id").asString());
        }
        assertThat(all("/api/v1/environments", 1)).extracting(item -> item.path("name").asString())
                .containsExactly("dev", "prod", "staging");

        List<String> deployments = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            deployments.add(api.send("POST", "/api/v1/environments/" + environments.get(index % 2) + "/promotions",
                    "{\"deployment\":\"d" + index + "\",\"releaseId\":\"" + releases.get(index) + "\"}", workspace,
                    Map.of()).body().path("deploymentId").asString());
        }
        assertThat(all("/api/v1/deployments", 2)).extracting(item -> item.path("id").asString())
                .containsExactlyElementsOf(deployments);
        assertThat(all("/api/v1/deployments?environmentId=" + environments.getFirst(), 1))
                .extracting(item -> item.path("id").asString()).containsExactly(deployments.get(0), deployments.get(2));

        List<String> runs = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            runs.add(api.send("POST", "/api/v1/deployments/" + deployments.get(index % 3) + "/runs", "{}", workspace,
                    Map.of()).body().path("id").asString());
        }
        api.send("POST", "/api/v1/runs/" + runs.get(1) + "/cancel", "{}", workspace, Map.of());
        List<String> newestFirst = new ArrayList<>(runs);
        java.util.Collections.reverse(newestFirst);
        assertThat(all("/api/v1/runs", 2)).extracting(item -> item.path("id").asString())
                .containsExactlyElementsOf(newestFirst);
        assertThat(all("/api/v1/runs?deploymentId=" + deployments.getFirst(), 1))
                .extracting(item -> item.path("id").asString()).containsExactly(runs.get(3), runs.get(0));
        assertThat(all("/api/v1/runs?lifecycle=CANCELLED&lifecycle=SUCCEEDED", 1))
                .extracting(item -> item.path("id").asString()).containsExactly(runs.get(1));
        assertThat(all("/api/v1/runs?releaseId=" + releases.get(1), 5)).extracting(item -> item.path("id").asString())
                .containsExactly(runs.get(4), runs.get(1));

        // Event pages use the event sequence as their cursor.
        String run = runs.getFirst();
        List<JsonNode> events = all("/api/v1/runs/" + run + "/events", 1);
        assertThat(events).extracting(event -> event.path("sequence").asLong()).containsExactly(1L, 2L);
    }

    @Test
    void cursorsAndLimitsAreValidatedAndACursorNeverReachesAnotherWorkspace() {
        for (String name : List.of("a", "b", "c")) api.post("/api/v1/agents", JSON, "{\"name\":\"" + name + "\"}", workspace);
        UUID other = TestWorkspaces.createWorkspace(jdbc);
        api.post("/api/v1/agents", JSON, "{\"name\":\"z\"}", other);
        String cursor = api.get("/api/v1/agents?limit=1", workspace).body().path("nextCursor").asString();

        // Another workspace's caller replaying the cursor only reads its own workspace.
        var foreign = api.get("/api/v1/agents?cursor=" + encode(cursor), other).body().path("items");
        assertThat(foreign.size()).isOne();
        assertThat(foreign.get(0).path("name").asString()).isEqualTo("z");

        for (String bad : List.of("garbage", "v1.", "v1.zz", "v2." + cursor.substring(3), cursor + ".00", "x".repeat(2000))) {
            var refused = api.get("/api/v1/agents?cursor=" + encode(bad), workspace);
            assertThat(refused.status()).as(bad).isEqualTo(400);
            assertThat(refused.code()).isEqualTo("INVALID_CURSOR");
        }
        for (String path : List.of("/api/v1/runs?cursor=v1.00", "/api/v1/runs/" + UUID.randomUUID() + "/events?cursor=01",
                "/api/v1/releases?cursor=v1.31.32")) {
            var refused = api.get(path, workspace);
            assertThat(refused.status()).as(path).isEqualTo(400);
            assertThat(refused.code()).isEqualTo("INVALID_CURSOR");
        }
        for (String limit : List.of("0", "-1", "201")) {
            var refused = api.get("/api/v1/agents?limit=" + limit, workspace);
            assertThat(refused.status()).isEqualTo(400);
            assertThat(refused.code()).isEqualTo("INVALID_LIMIT");
        }
        assertThat(api.get("/api/v1/agents?limit=abc", workspace).code()).isEqualTo("MALFORMED_REQUEST");
        assertThat(api.get("/api/v1/runs?lifecycle=RUNNING", workspace).code()).isEqualTo("INVALID_FILTER");
        assertThat(api.get("/api/v1/agents?limit=200", workspace).body().path("items").size()).isEqualTo(3);
    }

    private List<JsonNode> all(String path, int limit) {
        List<JsonNode> items = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            String separator = path.contains("?") ? "&" : "?";
            var page = api.get(path + separator + "limit=" + limit + (cursor == null ? "" : "&cursor=" + encode(cursor)),
                    workspace);
            assertThat(page.status()).as(page.raw()).isEqualTo(200);
            page.body().path("items").forEach(items::add);
            assertThat(page.body().path("items").size()).isLessThanOrEqualTo(limit);
            cursor = page.body().path("nextCursor").isNull() ? null : page.body().path("nextCursor").asString();
            assertThat(++pages).isLessThan(100);
        } while (cursor != null);
        return items;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
