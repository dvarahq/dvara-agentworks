package com.dvarahq.agentworks.identity.web;

import com.dvarahq.agentworks.DvaraAgentWorksApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** Security regression probes for declared and chunked oversized request bodies (issue #60). */
@SpringBootTest(classes = DvaraAgentWorksApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "agentworks.security.requests.max-body-size=64")
@Import(IdentityTestConfiguration.class)
class RequestBodyLimitTest {
    @LocalServerPort int port;
    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void declaredOversizedBodyIsRejectedBeforeParsing() throws Exception {
        var response = send(HttpRequest.BodyPublishers.ofString("x".repeat(65)), true);

        assertRefused(response);
    }

    @Test
    void chunkedOversizedBodyCannotBypassTheLimit() throws Exception {
        byte[] body = "x".repeat(65).getBytes(StandardCharsets.UTF_8);
        var response = send(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body)), true);

        assertRefused(response);
    }

    @Test
    void bodyAtTheLimitContinuesToNormalRequestValidation() throws Exception {
        var response = send(HttpRequest.BodyPublishers.ofString("x".repeat(64)), true);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("MALFORMED_REQUEST").doesNotContain("PAYLOAD_TOO_LARGE");
    }

    private HttpResponse<String> send(HttpRequest.BodyPublisher body, boolean json) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/api/v1/auth/login"))
                .POST(body);
        if (json) request.header("Content-Type", "application/json");
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void assertRefused(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.headers().firstValue("Content-Type").orElse(""))
                .startsWith("application/problem+json");
        assertThat(response.body()).contains("PAYLOAD_TOO_LARGE").doesNotContain("x".repeat(16));
    }
}
