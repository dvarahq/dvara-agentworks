package com.dvarahq.agentworks.controlplane.agent.web;

import com.dvarahq.agentworks.DvaraAgentWorksApplication;
import com.dvarahq.agentworks.identity.TestIdentities;
import com.dvarahq.agentworks.runplane.stream.RunEventStreams;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #192: a run event stream writes nothing while the request thread is still inside the filter chain. Filters run on
 * the request thread after the controller returns (Spring Security's {@code HeaderWriterFilter} writes its headers
 * there), so a stream thread that wrote, failed on a vanished client, and completed the response in that window shared
 * the response with them, and the request thread failed inside Tomcat's header map.
 */
@SpringBootTest(classes = DvaraAgentWorksApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"agentworks.runs.streams.poll-interval=PT0.05S",
                "agentworks.runs.streams.heartbeat-interval=PT0.2S",
                "agentworks.runs.streams.max-streams-per-subject=64"})
@Import({TestWorkspaces.class, RunStreamReleaseOrderTest.ChainProbe.class})
@ExtendWith(OutputCaptureExtension.class)
@ResourceLock("output-capture")
@Tag("integration")
class RunStreamReleaseOrderTest {
    private static final String JSON = "application/json";
    private static final Duration WAIT = Duration.ofSeconds(5);
    /** How long the probe, like a filter that writes after the chain, stays on the request thread. */
    private static final Duration PROBE_HOLD = Duration.ofMillis(750);

    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TestIdentities identities;
    @Autowired
    RunEventStreams streams;
    private AgentApiClient api;
    private UUID workspace;
    private String deployment;
    private String token;

    /**
     * A filter inside the security chain that, after the chain returns for a stream request, stays on the request
     * thread for a while and records whether the stream's thread wrote (committed the response) meanwhile.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class ChainProbe {
        static final ConcurrentLinkedQueue<String> probed = new ConcurrentLinkedQueue<>();

        @Bean
        FilterRegistrationBean<Filter> runStreamChainProbe() {
            Filter probe = (request, response, chain) -> {
                chain.doFilter(request, response);
                HttpServletRequest http = (HttpServletRequest) request;
                if (!http.getRequestURI().endsWith("/stream") || !request.isAsyncStarted()) return;
                long deadline = System.nanoTime() + PROBE_HOLD.toNanos();
                boolean written = false;
                while (!written && System.nanoTime() - deadline < 0) {
                    written = ((HttpServletResponse) response).isCommitted();
                    if (!written) Thread.onSpinWait();
                }
                probed.add(written ? "written while the request thread was in the chain" : "untouched");
            };
            FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>(probe);
            registration.setOrder(0);
            registration.addUrlPatterns("/api/v1/runs/*");
            return registration;
        }
    }

    @BeforeEach
    void setUp() {
        ChainProbe.probed.clear();
        api = new AgentApiClient(port);
        workspace = TestWorkspaces.createWorkspace(jdbc);
        token = identities.token(workspace);
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

    @Test
    void aStreamWritesNothingUntilTheRequestThreadHasLeftTheFilterChain() {
        UUID run = submit();
        try (SseClient stream = open(run, Map.of())) {
            assertThat(stream.status()).isEqualTo(200);
            // The stream contract is unchanged: the security headers are there, and the events follow.
            assertThat(stream.header("Cache-Control")).contains("no-store");
            assertThat(stream.header("X-Content-Type-Options")).isEqualTo("nosniff");
            assertThat(stream.next(WAIT).name()).isEqualTo("RUN_CREATED");
            assertThat(stream.next(WAIT).name()).isEqualTo("RUN_QUEUED");
        }
        assertThat(ChainProbe.probed).containsExactly("untouched");
    }

    @Test
    void clientsThatLeaveRightAfterTheFirstEventLeaveNoErrorBehind(CapturedOutput output) throws Exception {
        UUID run = submit();
        for (int connection = 0; connection < 10; connection++) {
            String lastEventId;
            try (SseClient stream = open(run, Map.of())) {
                assertThat(stream.status()).isEqualTo(200);
                SseClient.Event first = stream.next(WAIT);
                assertThat(first.name()).isEqualTo("RUN_CREATED");
                lastEventId = first.id();
            }
            try (SseClient resumed = open(run, Map.of("Last-Event-ID", lastEventId))) {
                assertThat(resumed.next(WAIT).name()).isEqualTo("RUN_QUEUED");
            }
        }
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (streams.open() > 0 && System.nanoTime() - deadline < 0) Thread.sleep(20);
        assertThat(streams.open()).isZero();
        assertThat(ChainProbe.probed).hasSize(20).containsOnly("untouched");
        // The failures #192 logged: the request thread's exception in Tomcat's header map, the stream's sink
        // failure, and the error page Tomcat then rendered into the committed stream.
        assertThat(List.of(output.getAll().split("\n"))).noneMatch(line -> line.contains("NullPointerException")
                || line.contains("SinkFailedException")
                || line.contains("Exception Processing [ErrorPage") || line.contains("response is already committed"));
    }

    /**
     * #200: an encoded spelling of the stream path (here {@code %73tream}) reaches the same controller, so the release
     * filter must wrap it too; it used to match the raw URI, and the unwrapped request was a logged 500.
     */
    @Test
    void anEncodedStreamPathIsServedLikeAnyOther() {
        UUID run = submit();
        try (SseClient stream = SseClient.open(port, "/api/v1/runs/" + run + "/%73tream", token, Map.of())) {
            assertThat(stream.status()).isEqualTo(200);
            assertThat(stream.next(WAIT).name()).isEqualTo("RUN_CREATED");
        }
    }

    private SseClient open(UUID run, Map<String, String> headers) {
        return SseClient.open(port, "/api/v1/runs/" + run + "/stream", token, headers);
    }

    private UUID submit() {
        var response = api.send("POST", "/api/v1/deployments/" + deployment + "/runs", "{\"input\":{\"q\":1}}",
                workspace, Map.of());
        assertThat(response.status()).isEqualTo(201);
        return UUID.fromString(response.body().path("id").asString());
    }
}
