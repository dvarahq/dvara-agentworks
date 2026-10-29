package com.dvarahq.agentworks.controlplane.agent.web;

import com.dvarahq.agentworks.DvaraAgentWorksApplication;
import com.dvarahq.agentworks.identity.TestIdentities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stopping the shipped server with open run event streams (issue #35): the streams end with a {@code reconnect}
 * event whose reason is {@code SHUTDOWN} before the web server's graceful shutdown waits for in-flight requests, so an
 * open stream neither holds up the stop nor loses its reconnect signal. The server runs in its own application
 * context, which the test stops.
 */
@Isolated("starts and closes a full application context that reconfigures process-wide logging")
class RunStreamShutdownTest {
    private static final String JSON = "application/json";

    @Test
    void stoppingTheServerEndsOpenStreamsWithAReconnectPromptly() {
        ConfigurableApplicationContext context = new SpringApplicationBuilder(
                DvaraAgentWorksApplication.class, TestWorkspaces.class)
                .properties("server.port=0").run();
        try {
            int port = ((WebServerApplicationContext) context).getWebServer().getPort();
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            AgentApiClient api = new AgentApiClient(port);
            UUID workspace = TestWorkspaces.createWorkspace(jdbc);
            String environment = api.post("/api/v1/environments", JSON, "{\"name\":\"prod\"}", workspace).body()
                    .path("id").asString();
            String definition = api.post("/api/v1/agents", JSON, "{\"name\":\"agent\"}", workspace).body()
                    .path("id").asString();
            String version = api.post("/api/v1/agents/" + definition + "/versions", "application/yaml",
                    "apiVersion: dvarahq.com/v1alpha1\nkind: Agent\nmetadata: {name: agent, version: 1.0.0}\n"
                            + "spec:\n  executor: {type: spring-ai}\n", workspace).body().path("id").asString();
            String release = api.post("/api/v1/agent-versions/" + version + "/releases", JSON,
                    "{\"name\":\"agent-1\"}", workspace).body().path("id").asString();
            String deployment = api.send("POST", "/api/v1/environments/" + environment + "/promotions",
                    "{\"deployment\":\"api\",\"releaseId\":\"" + release + "\"}", workspace, Map.of()).body()
                    .path("deploymentId").asString();
            String run = api.send("POST", "/api/v1/deployments/" + deployment + "/runs", "{}", workspace, Map.of())
                    .body().path("id").asString();
            String token = TestIdentities.forPort(port).token(workspace);

            try (SseClient stream = SseClient.open(port, "/api/v1/runs/" + run + "/stream", token, Map.of())) {
                assertThat(stream.next(Duration.ofSeconds(5)).name()).isEqualTo("RUN_CREATED");
                assertThat(stream.next(Duration.ofSeconds(5)).name()).isEqualTo("RUN_QUEUED");

                long started = System.nanoTime();
                context.close();
                // Without the early stop, graceful shutdown would wait out its whole phase timeout (30 seconds).
                assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(15));
                SseClient.Event reconnect = stream.next(Duration.ofSeconds(5));
                assertThat(reconnect).isNotNull();
                assertThat(reconnect.name()).isEqualTo("reconnect");
                assertThat(reconnect.json().path("reason").asString()).isEqualTo("SHUTDOWN");
                assertThat(reconnect.json().path("lastSequence").asLong()).isEqualTo(2);
                assertThat(stream.ended(Duration.ofSeconds(5))).isTrue();
            }
        } finally {
            context.close();
        }
    }
}
