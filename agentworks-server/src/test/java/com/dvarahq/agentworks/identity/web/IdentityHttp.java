package com.dvarahq.agentworks.identity.web;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A browser-like test client: it keeps the session cookie set by the server (including {@code Secure} cookies,
 * which a real browser would only send over HTTPS) and sends the session's CSRF token on state-changing requests.
 * It can also act as a CLI with a bearer token. It never logs what it sends.
 */
public final class IdentityHttp {
    public static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Set<String> SAFE = Set.of("GET", "HEAD", "OPTIONS");

    private final HttpClient http = HttpClient.newHttpClient();
    private final String baseUrl;
    private String cookieName;
    private String cookieValue;
    private String csrfToken;
    private String bearer;

    public IdentityHttp(int port) {
        this.baseUrl = "http://localhost:" + port;
    }

    public record Response(int status, JsonNode json, String raw, HttpHeaders headers) {
        public String code() {
            return json.path("code").asString();
        }

        public String header(String name) {
            return headers.firstValue(name).orElse(null);
        }

        public String setCookie() {
            return headers.firstValue("Set-Cookie").orElse(null);
        }
    }

    public IdentityHttp bearer(String token) {
        this.bearer = token;
        return this;
    }

    public String cookie() {
        return cookieValue;
    }

    public String cookieName() {
        return cookieName;
    }

    public String csrf() {
        return csrfToken;
    }

    /** Replaces the held session cookie, for example with a value planted by an attacker. */
    public IdentityHttp cookie(String name, String value) {
        this.cookieName = name;
        this.cookieValue = value;
        return this;
    }

    public IdentityHttp csrf(String token) {
        this.csrfToken = token;
        return this;
    }

    public Response login(String username, String password) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", username);
        body.put("password", password);
        return send("POST", "/api/v1/auth/login", body);
    }

    public Response get(String path) {
        return send("GET", path, null, Map.of());
    }

    public Response send(String method, String path, Object body) {
        return send(method, path, body, Map.of());
    }

    public Response send(String method, String path, Object body, Map<String, String> headers) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path)).header("Accept", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body instanceof String text ? text
                        : JSON.writeValueAsString(body), StandardCharsets.UTF_8));
        if (body != null && !headers.containsKey("Content-Type")) request.header("Content-Type", "application/json");
        if (bearer != null) request.header("Authorization", "Bearer " + bearer);
        if (cookieValue != null) request.header("Cookie", cookieName + "=" + cookieValue);
        if (csrfToken != null && !SAFE.contains(method) && !headers.containsKey(SessionCookies.CSRF_HEADER)) {
            request.header(SessionCookies.CSRF_HEADER, csrfToken);
        }
        headers.forEach((name, value) -> {
            if (value != null) request.header(name, value);
        });
        try {
            HttpResponse<byte[]> response = http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
            String raw = new String(response.body(), StandardCharsets.UTF_8);
            JsonNode json = raw.isBlank() || !raw.strip().startsWith("{") ? JSON.missingNode() : JSON.readTree(raw);
            remember(response.headers().allValues("Set-Cookie"), json);
            return new Response(response.statusCode(), json, raw, response.headers());
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private void remember(List<String> setCookies, JsonNode json) {
        for (String header : setCookies) {
            String pair = header.split(";", 2)[0];
            int split = pair.indexOf('=');
            String name = pair.substring(0, split);
            if (!name.endsWith("agentworks-session")) continue;
            String value = pair.substring(split + 1);
            cookieName = name;
            cookieValue = value.isEmpty() ? null : value;
            if (cookieValue == null) csrfToken = null;
        }
        if (json.has("csrfToken") && !json.path("csrfToken").isNull()) csrfToken = json.path("csrfToken").asString();
    }
}
