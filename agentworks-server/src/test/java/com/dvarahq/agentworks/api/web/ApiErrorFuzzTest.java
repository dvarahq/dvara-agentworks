package com.dvarahq.agentworks.api.web;

import com.dvarahq.agentworks.domain.identity.WorkspaceRole;
import com.dvarahq.agentworks.DvaraAgentWorksApplication;
import com.dvarahq.agentworks.identity.TestIdentities;
import com.dvarahq.agentworks.identity.web.IdentityTestConfiguration;
import com.dvarahq.agentworks.storage.DefaultTenantBootstrap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Error fuzzing of every documented operation (issue #35): seeded random malformed path variables, query parameters,
 * headers, and bodies, sent anonymously and with an administrator's token. Every refusal must be a problem document
 * with a stable code; no request may cause a server error; and no response may echo the injected marker, a stack
 * trace, SQL, or an internal class name.
 */
@SpringBootTest(classes = DvaraAgentWorksApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(IdentityTestConfiguration.class)
class ApiErrorFuzzTest {
    private static final String MARKER = "zzfuzzmarker";
    private static final List<String> LEAKS = List.of(MARKER, "Exception", "\tat ", "org.springframework",
            "com.dvarahq", "SQL", "select ", "jdbc", "tools.jackson", "trace\"");
    private static final Path ARTIFACT_ROOT = Path.of(System.getProperty("java.io.tmpdir"),
            "agentworks-api-error-fuzz-artifacts-" + UUID.randomUUID());

    @DynamicPropertySource
    static void artifactProperties(DynamicPropertyRegistry properties) {
        properties.add("agentworks.artifacts.enabled", () -> "true");
        properties.add("agentworks.artifacts.root", ARTIFACT_ROOT::toString);
        properties.add("agentworks.artifacts.maintenance.enabled", () -> "false");
    }

    @LocalServerPort
    int port;
    @Autowired
    TestIdentities identities;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Test
    void malformedRequestsToEveryOperationAreTypedProblemsThatNeverLeakOrFail() throws Exception {
        OpenApiContract contract = OpenApiContract.load();
        String admin = identities.member(DefaultTenantBootstrap.DEFAULT_WORKSPACE_ID.value(), WorkspaceRole.ADMIN).token();
        Random random = new Random(35);
        Map<String, String> failures = new TreeMap<>();
        int sent = 0;
        for (var entry : contract.operations().entrySet()) {
            String method = entry.getKey().substring(0, entry.getKey().indexOf(' '));
            String template = entry.getKey().substring(entry.getKey().indexOf(' ') + 1);
            for (int round = 0; round < 12; round++) {
                String path = template;
                while (path.contains("{")) {
                    int start = path.indexOf('{');
                    int end = path.indexOf('}', start);
                    path = path.substring(0, start) + encode(pathValue(random)) + path.substring(end + 1);
                }
                String query = query(random);
                HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path + query))
                        .timeout(Duration.ofSeconds(10));
                String body = body(random);
                request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
                if (body != null) request.header("Content-Type", contentType(random));
                request.header("Accept", random.nextBoolean() ? "application/json" : "*/*");
                headers(random).forEach(request::header);
                boolean authenticated = round % 4 != 3;
                if (authenticated) request.header("Authorization", "Bearer " + admin);
                if (entry.getKey().equals("GET /api/v1/runs/{id}/stream")) request.header("Accept", "text/event-stream");
                HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
                sent++;
                String problem = check(response);
                if (problem != null) failures.put(entry.getKey() + " #" + round + " " + method + " " + path + query, problem);
            }
        }
        assertThat(sent).isGreaterThan(500);
        assertThat(failures).isEmpty();
    }

    @Test
    void frameworkRefusalsKeepTheHeadersHttpRequiresAndTheirGenericCodes() throws Exception {
        String admin = identities.member(DefaultTenantBootstrap.DEFAULT_WORKSPACE_ID.value(), WorkspaceRole.ADMIN).token();
        HttpResponse<String> unsupported = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port
                        + "/api/v1/agents")).header("Authorization", "Bearer " + admin).header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString("x")).build(), HttpResponse.BodyHandlers.ofString());

        assertThat(unsupported.statusCode()).isEqualTo(415);
        assertThat(unsupported.headers().firstValue("Accept")).get().asString().contains("application/json");
        JsonNode problem = OpenApiContract.JSON.readTree(unsupported.body());
        assertThat(problem.path("code").asString()).isEqualTo("UNSUPPORTED_MEDIA_TYPE");
        assertThat(problem.has("instance")).isFalse();
    }

    /** Null when the response is acceptable, otherwise why not. */
    private static String check(HttpResponse<String> response) {
        int status = response.statusCode();
        String body = response.body() == null ? "" : response.body();
        if (status >= 500) return "server error " + status + " " + body;
        // A successful mutation legitimately returns what was sent; only refusals must never echo it.
        if (status < 400) return null;
        for (String leak : LEAKS) {
            if (body.contains(leak)) return "leaked " + leak + ": " + body;
        }
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        JsonNode problem;
        try {
            problem = OpenApiContract.JSON.readTree(body);
        } catch (RuntimeException unreadable) {
            return "not a problem document " + status + " " + contentType + " " + body;
        }
        // A blocked retirement is the documented JSON result, not a problem.
        if (!contentType.startsWith("application/problem+json") && !problem.has("blockers")) {
            return "not application/problem+json: " + contentType;
        }
        if (problem.has("blockers")) return null;
        if (!problem.path("code").asString().matches("[A-Z][A-Z0-9_]*")) return "no stable code: " + body;
        if (problem.path("status").asInt() != status) return "status mismatch: " + body;
        return null;
    }

    private static String pathValue(Random random) {
        return switch (random.nextInt(9)) {
            case 0 -> UUID.randomUUID().toString();
            case 1 -> MARKER + "-" + random.nextInt(1000);
            case 2 -> "00000000-0000-0000-0000-00000000000" + random.nextInt(10);
            case 3 -> "' or 1=1 --" + MARKER;
            case 4 -> MARKER.repeat(40);
            case 5 -> "suspend";
            case 6 -> "é中\u0000" + MARKER;
            case 7 -> "..%2f" + MARKER;
            default -> "latest";
        };
    }

    private static String query(Random random) {
        List<String> parameters = new ArrayList<>();
        switch (random.nextInt(8)) {
            case 0 -> parameters.add("limit=" + List.of("-1", "0", "999999", "abc", "1").get(random.nextInt(5)));
            case 1 -> parameters.add("cursor=" + encode(List.of("v1.zz", "v1." + MARKER, "-5", MARKER, "v1.0g.01",
                    "18446744073709551616").get(random.nextInt(6))));
            case 2 -> parameters.add("lifecycle=" + encode(MARKER));
            case 3 -> parameters.add("deploymentId=" + encode(MARKER));
            case 4 -> parameters.add("all=" + encode(MARKER));
            case 5 -> parameters.add("environmentId=" + UUID.randomUUID());
            default -> { }
        }
        return parameters.isEmpty() ? "" : "?" + String.join("&", parameters);
    }

    private static String body(Random random) {
        return switch (random.nextInt(9)) {
            case 0 -> null;
            case 1 -> "{";
            case 2 -> "[1,2,3]";
            case 3 -> "{\"name\":\"" + MARKER + "\",\"releaseId\":\"" + MARKER + "\",\"input\":\"" + MARKER + "\"}";
            case 4 -> "{\"reason\":" + "[".repeat(2000) + "]".repeat(2000) + "}";
            case 5 -> "{\"input\":\"" + MARKER.repeat(20_000) + "\"}";
            case 6 -> "{\"role\":\"" + MARKER + "\",\"gates\":[{\"type\":\"" + MARKER + "\"}]}";
            case 7 -> "null";
            default -> "\"" + MARKER + "\"";
        };
    }

    private static String contentType(Random random) {
        return List.of("application/json", "application/yaml", "text/plain", "application/xml")
                .get(random.nextInt(4));
    }

    private static Map<String, String> headers(Random random) {
        return switch (random.nextInt(7)) {
            case 0 -> Map.of("If-Match", "\"" + MARKER + "\"");
            case 1 -> Map.of("Idempotency-Key", MARKER.repeat(30));
            case 2 -> Map.of("Last-Event-ID", MARKER + ":" + MARKER);
            case 3 -> Map.of("traceparent", MARKER);
            case 4 -> Map.of("If-None-Match", MARKER);
            case 5 -> Map.of("Idempotency-Key", "k" + MARKER);
            default -> Map.of();
        };
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
