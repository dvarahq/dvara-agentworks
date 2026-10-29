package com.dvarahq.agentworks.console.web;

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
import tools.jackson.databind.JsonNode;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.dvarahq.agentworks.identity.web.IdentityTestConfiguration.PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #36 end to end on the shipped application, through the Console alone: a signed-in author and operator creates
 * an agent, publishes a version from the Console's AgentSpec template, builds a release, creates an environment,
 * promotes the release, launches a run, and watches it live over the #35 stream exactly as the page's script does
 * (the stream URL, cursor, and a per-connection client id from the page, the session cookie, and a reconnect with
 * {@code Last-Event-ID} and the same id). The worker executes the run with the deterministic fake gateway, permitted
 * only under the loopback local-development gate. The finished run page then shows the same pins, trace, lifecycle,
 * usage, and model as the API.
 */
@SpringBootTest(classes = DvaraAgentWorksApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "agentworks.development.local=true", "server.address=127.0.0.1",
                "agentworks.gateway.gateways.direct.type=deterministic-fake",
                "agentworks.gateway.gateways.direct.models[0].alias=coding-premium",
                "agentworks.gateway.gateways.direct.models[0].model=fake-console-model",
                "agentworks.runs.worker.enabled=true", "agentworks.runs.worker.threads=2",
                "agentworks.runs.worker.lease-duration=PT10S", "agentworks.runs.worker.idle-backoff=PT0.05S"})
@Import(IdentityTestConfiguration.class)
class ConsoleEndToEndTest {
    private static final Pattern STREAM = Pattern.compile("data-stream-url=\"([^\"]+)\"");
    private static final Pattern CURSOR = Pattern.compile("data-cursor=\"(\\d+)\"");

    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TestIdentities identities;

    @Test
    void aUserCreatesDeploysLaunchesAndWatchesARunThroughTheConsole() throws Exception {
        UUID organization = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        jdbc.update("insert into organization(id, name) values (?, ?)", organization, "e2e-" + organization);
        jdbc.update("insert into workspace(id, organization_id, name) values (?, ?, ?)", workspace, organization, "e2e");
        var admin = identities.create(workspace, WorkspaceRole.ADMIN, PASSWORD, "MEMBER");
        ConsoleBrowser browser = new ConsoleBrowser(port);
        assertThat(browser.signIn(admin.username(), PASSWORD).status()).isEqualTo(303);

        // Create an agent and publish a version from the Console's own AgentSpec template.
        browser.open("/console/agents");
        String agentPath = browser.action("/console/agents", Map.of("name", "console-e2e")).redirect();
        assertThat(agentPath).startsWith("/console/agents/");
        String agentPage = browser.open(agentPath).body();
        assertThat(agentPage).contains("apiVersion: dvarahq.com/v1alpha1");
        var published = browser.action(agentPath + "/versions",
                Map.of("document", ConsoleAgentsController.specTemplate("console-e2e")));
        assertThat(published.redirect()).as(published.body()).isEqualTo(agentPath);
        String versionId = jdbc.queryForObject("select v.id from agent_version v join agent_definition d on "
                + "d.id = v.definition_id where d.workspace_id = ?", UUID.class, workspace).toString();

        // Build a release, create an environment, and promote the release.
        browser.open(agentPath);
        String releasePath = browser.action("/console/agent-versions/" + versionId + "/releases",
                Map.of("name", "console-e2e-1")).redirect();
        assertThat(releasePath).startsWith("/console/deployments?release=");
        String releaseId = releasePath.substring(releasePath.indexOf('=') + 1);
        browser.open("/console/deployments");
        String environmentPath = browser.action("/console/environments", Map.of("name", "production")).redirect();
        String environmentId = environmentPath.substring(environmentPath.indexOf('=') + 1);
        String deploymentsPage = browser.open(environmentPath).body();
        assertThat(deploymentsPage).contains("console-e2e-1").contains("production");
        String deploymentPath = browser.action("/console/promotions", Map.of("environmentId", environmentId,
                "deployment", "web", "releaseId", releaseId, "reason", "first release")).redirect();
        assertThat(deploymentPath).startsWith("/console/deployments/");
        String deploymentId = deploymentPath.substring("/console/deployments/".length());

        // Launch a run, and watch it as the page's script does.
        String deploymentPage = browser.open(deploymentPath).body();
        assertThat(deploymentPage).contains("console-e2e-1").contains("Launch a run");
        String runPath = browser.action(deploymentPath + "/runs", Map.of("input", "{\"prompt\": \"hello console\"}"))
                .redirect();
        assertThat(runPath).startsWith("/console/runs/");
        String runId = runPath.substring("/console/runs/".length());
        String runPage = browser.open(runPath).body();
        Matcher stream = STREAM.matcher(runPage);
        Matcher cursor = CURSOR.matcher(runPage);
        assertThat(stream.find()).isTrue();
        assertThat(cursor.find()).isTrue();
        assertThat(runPage).contains(deploymentId).contains(releaseId).contains("fake-console-model");

        String client = UUID.randomUUID().toString();
        long after = Long.parseLong(cursor.group(1));
        List<String[]> events = new ArrayList<>();
        if (runPage.contains("data-terminal=\"true\"") && watch(browser, stream.group(1) + "?cursor=" + after
                + "&client=" + client, null, events, Integer.MAX_VALUE) == null && !events.isEmpty()
                && "204".equals(events.getFirst()[0])) {
            // The fake gateway finished before the page loaded: the caught-up stream is 204, which stops an
            // EventSource. Watch the same run again from its first event, as a newly opened page would.
            events.clear();
            after = 1;
        }
        String url = stream.group(1) + "?cursor=" + after + "&client=" + client;
        // The first connection drops after one event; the browser reconnects with Last-Event-ID and the same id.
        String lastId = watch(browser, url, null, events, 1);
        if (!events.isEmpty() && !"stream-end".equals(events.getLast()[0])) {
            watch(browser, url, lastId, events, Integer.MAX_VALUE);
        }
        List<String> names = events.stream().map(event -> event[0]).toList();
        assertThat(names).endsWith("stream-end").contains("RUN_SUCCEEDED");
        List<Long> sequences = events.stream().filter(event -> event[1] != null)
                .map(event -> Long.parseLong(event[1].substring(event[1].indexOf(':') + 1))).toList();
        assertThat(sequences).isSorted().doesNotHaveDuplicates();
        assertThat(sequences.getFirst()).isEqualTo(after + 1);

        // The finished run page and the API agree on the final state and every pin.
        var api = new IdentityHttp(port).bearer(admin.token());
        JsonNode view = api.get("/api/v1/runs/" + runId).json();
        assertThat(view.path("lifecycle").asString()).isEqualTo("SUCCEEDED");
        String finished = browser.open(runPath).body();
        for (String field : List.of("lifecycle", "traceId", "releaseId", "deploymentId", "revisionId", "cohortId",
                "routingGenerationId", "environmentId", "versionId", "definitionId")) {
            assertThat(finished).as(field).contains(view.path(field).asString());
        }
        // Every event of the ended run is on the page, so the page opens no stream (review of #36).
        assertThat(finished).contains("data-complete=\"true\"");
        assertThat(finished).contains("fake-console-model").contains("RUN_SUCCEEDED").contains("MODEL_RESPONSE_RECEIVED")
                .contains(view.path("usage").path("modelCalls").asString() + "</dd>");
        assertThat(view.path("usage").path("modelCalls").asLong()).isOne();
        // The run was launched by the signed-in user, through the same admission as the API.
        JsonNode first = api.get("/api/v1/runs/" + runId + "/events?limit=1").json().path("items").get(0);
        assertThat(first.path("payload").path("invoker").path("method").asString()).isEqualTo("session");
        assertThat(api.get("/api/v1/runs?deploymentId=" + deploymentId).json().path("items").get(0).path("id")
                .asString()).isEqualTo(runId);
    }

    /**
     * Reads the stream as a browser EventSource does, with the session cookie, until {@code take} run events, a
     * {@code stream-end}, or the end of the response; returns the last event id seen.
     */
    private String watch(ConsoleBrowser browser, String path, String lastEventId, List<String[]> events, int take)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(browser.origin + path))
                .header("Accept", "text/event-stream").header("Cookie", browser.cookie()).timeout(Duration.ofSeconds(30));
        if (lastEventId != null) request.header("Last-Event-ID", lastEventId);
        HttpResponse<java.io.InputStream> response = HttpClient.newHttpClient().send(request.build(),
                HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() == 204) {
            events.add(new String[]{"204", null});
            return lastEventId;
        }
        assertThat(response.statusCode()).isEqualTo(200);
        String last = lastEventId;
        int taken = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String name = null;
            String id = null;
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                if (line.startsWith("event:")) name = line.substring(6).strip();
                else if (line.startsWith("id:")) id = line.substring(3).strip();
                else if (line.isEmpty() && name != null) {
                    events.add(new String[]{name, id});
                    if (id != null) {
                        last = id;
                        taken++;
                    }
                    if ("stream-end".equals(name) || taken >= take) break;
                    name = null;
                    id = null;
                }
            }
        }
        return last;
    }
}
