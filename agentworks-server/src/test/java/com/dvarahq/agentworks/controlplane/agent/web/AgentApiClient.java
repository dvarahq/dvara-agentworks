package com.dvarahq.agentworks.controlplane.agent.web;

import com.dvarahq.agentworks.identity.TestIdentities;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Minimal HTTP client for REST contract tests. When its server context provides {@link TestIdentities}, each
 * request carries the API token of an administrator of the requested workspace ({@code null} is the default
 * workspace). Without it, requests are unauthenticated and only carry an untrusted workspace claim header, which
 * the server must ignore.
 */
final class AgentApiClient {
    static final String TEST_WORKSPACE_HEADER = "X-Test-Workspace";
    static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpClient http = HttpClient.newHttpClient();
    private final String baseUrl;
    private final TestIdentities identities;

    AgentApiClient(int port) {
        this.baseUrl = "http://localhost:" + port;
        this.identities = TestIdentities.forPort(port);
    }

    Response post(String path, String contentType, byte[] body, UUID workspace) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", contentType).header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        return send(request, workspace);
    }

    Response post(String path, String contentType, String body, UUID workspace) {
        return post(path, contentType, body.getBytes(StandardCharsets.UTF_8), workspace);
    }

    /** Sends any method with extra request headers such as Idempotency-Key and If-Match. */
    Response send(String method, String path, String body, UUID workspace, java.util.Map<String, String> headers) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path)).header("Accept", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (body != null) request.header("Content-Type", "application/json");
        headers.forEach(request::header);
        return send(request, workspace);
    }

    Response get(String path, UUID workspace) {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + path)).header("Accept", "application/json").GET(),
                workspace);
    }

    /** Sends a request authenticated by {@code bearer} instead of the workspace administrator's token. */
    Response sendAs(String bearer, String method, String path, String body) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path)).header("Accept", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (body != null) request.header("Content-Type", "application/json");
        request.header("Authorization", "Bearer " + bearer);
        return execute(request);
    }

    private Response send(HttpRequest.Builder request, UUID workspace) {
        if (identities != null) request.header("Authorization", "Bearer " + identities.token(workspace));
        else if (workspace != null) request.header(TEST_WORKSPACE_HEADER, workspace.toString());
        return execute(request);
    }

    private Response execute(HttpRequest.Builder request) {
        try {
            HttpResponse<byte[]> response = http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
            JsonNode body = response.body().length == 0 ? JSON.missingNode() : JSON.readTree(response.body());
            return new Response(response.statusCode(), body, response.headers().firstValue("Location").orElse(null),
                    response.headers(), new String(response.body(), StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new java.io.UncheckedIOException(exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    record Response(int status, JsonNode body, String location, java.net.http.HttpHeaders headers, String raw) {
        String code() {
            return body.path("code").asString();
        }

        String header(String name) {
            return headers.firstValue(name).orElse(null);
        }
    }
}
