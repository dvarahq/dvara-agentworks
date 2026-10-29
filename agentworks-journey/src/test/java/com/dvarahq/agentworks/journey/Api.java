package com.dvarahq.agentworks.journey;

import tools.jackson.databind.JsonNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * A client of the server's public surfaces only: the {@code /api/v1} REST API with a bearer API token or a session
 * cookie plus its CSRF token, the run event stream (SSE), and the Console's HTML pages and form actions. The base URL
 * is read on every request, so a client survives a server restart on another port, as a client behind a load
 * balancer would.
 */
final class Api {
    private static final Set<String> SAFE = Set.of("GET", "HEAD", "OPTIONS");
    /** The longest a journey reads one event stream. */
    static final Duration STREAM_DEADLINE = Duration.ofMinutes(3);
    private static final Pattern HX_CSRF = Pattern.compile("hx-headers=\"\\{&quot;X-CSRF-Token&quot;:&quot;([^&]+)&quot;\\}\"");
    private static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(10)).build();

    private final Supplier<String> base;
    private String bearer;
    private String cookie;
    private String csrf;

    Api(Supplier<String> base) {
        this.base = base;
    }

    Api bearer(String token) {
        this.bearer = token;
        return this;
    }

    String base() {
        return base.get();
    }

    record Response(int status, JsonNode json, String raw, HttpHeaders headers) {
        String header(String name) {
            return headers.firstValue(name).orElse(null);
        }

        String code() {
            return json.path("code").asString();
        }
    }

    Response get(String path) {
        return send("GET", path, null, Map.of());
    }

    Response post(String path, Object body) {
        return send("POST", path, body, Map.of());
    }

    Response post(String path, Object body, Map<String, String> headers) {
        return send("POST", path, body, headers);
    }

    Response send(String method, String path, Object body, Map<String, String> headers) {
        String text = body == null ? null : body instanceof String string ? string : Evidence.JSON.writeValueAsString(body);
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base.get() + path)).timeout(Duration.ofSeconds(60))
                .header("Accept", "application/json")
                .method(method, text == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(text, StandardCharsets.UTF_8));
        Map<String, String> all = new LinkedHashMap<>();
        if (text != null) all.put("Content-Type", "application/json");
        if (bearer != null) all.put("Authorization", "Bearer " + bearer);
        if (cookie != null) all.put("Cookie", cookie);
        if (csrf != null && !SAFE.contains(method)) all.put("X-CSRF-Token", csrf);
        all.putAll(headers);
        all.forEach(request::setHeader);
        HttpResponse<String> response = exchange(request.build());
        remember(response);
        String raw = response.body();
        JsonNode json = raw != null && raw.strip().startsWith("{") ? Evidence.JSON.readTree(raw)
                : Evidence.JSON.missingNode();
        if (json.has("csrfToken") && !json.path("csrfToken").isNull()) csrf = json.path("csrfToken").asString();
        return new Response(response.statusCode(), json, raw, response.headers());
    }

    // ---- Console (HTML) ----

    /** Signs in through the Console's own form, as a browser does. */
    Response consoleSignIn(String username, String password) {
        return form("/console/login", Map.of("username", username, "password", password), false);
    }

    /** Loads a Console page as a browser does, and keeps its CSRF token as htmx would read it. */
    Response page(String path) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base.get() + path)).timeout(Duration.ofSeconds(60))
                .header("Accept", "text/html").GET();
        if (cookie != null) request.header("Cookie", cookie);
        HttpResponse<String> response = exchange(request.build());
        remember(response);
        Matcher token = HX_CSRF.matcher(response.body());
        if (token.find()) csrf = token.group(1);
        return new Response(response.statusCode(), Evidence.JSON.missingNode(), response.body(), response.headers());
    }

    private Response form(String path, Map<String, String> fields, boolean htmx) {
        String body = fields.entrySet().stream().map(field -> URLEncoder.encode(field.getKey(), StandardCharsets.UTF_8)
                + "=" + URLEncoder.encode(field.getValue(), StandardCharsets.UTF_8)).collect(Collectors.joining("&"));
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base.get() + path)).timeout(Duration.ofSeconds(60))
                .header("Accept", "text/html").header("Content-Type", "application/x-www-form-urlencoded")
                .header("Origin", base.get()).POST(HttpRequest.BodyPublishers.ofString(body));
        if (cookie != null) request.header("Cookie", cookie);
        if (htmx) {
            request.header("HX-Request", "true");
            if (csrf != null) request.header("X-CSRF-Token", csrf);
        }
        HttpResponse<String> response = exchange(request.build());
        remember(response);
        return new Response(response.statusCode(), Evidence.JSON.missingNode(), response.body(), response.headers());
    }

    // ---- SSE ----

    /** One SSE event as received: its name, id, and data. */
    record SseEvent(String name, String id, String data) {
        long sequence() {
            return Long.parseLong(id.substring(id.indexOf(':') + 1));
        }
    }

    record Stream(int status, List<SseEvent> events, String lastEventId, boolean ended) {
    }

    /**
     * Reads {@code path} as an {@code EventSource} does, until a {@code stream-end}, the end of the response, or
     * {@code stop} accepts an event; every raw line is passed to {@code transcript}. A connection the server drops
     * (for example because it was killed) ends the read with what was received.
     */
    Stream stream(String path, String lastEventId, java.util.function.Predicate<SseEvent> stop,
            Consumer<String> transcript) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base.get() + path))
                .header("Accept", "text/event-stream").timeout(Duration.ofSeconds(60));
        if (bearer != null) request.header("Authorization", "Bearer " + bearer);
        if (cookie != null) request.header("Cookie", cookie);
        if (lastEventId != null) request.header("Last-Event-ID", lastEventId);
        HttpResponse<InputStream> response;
        try {
            response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
        List<SseEvent> events = new ArrayList<>();
        String last = lastEventId;
        boolean ended = false;
        transcript.accept("# GET " + path + (lastEventId == null ? "" : " Last-Event-ID: " + lastEventId) + " -> "
                + response.statusCode() + "\n");
        if (response.statusCode() != 200) {
            try (InputStream body = response.body()) {
                transcript.accept(new String(body.readAllBytes(), StandardCharsets.UTF_8) + "\n");
            } catch (IOException ignored) {
                // The status alone is the result.
            }
            return new Stream(response.statusCode(), events, last, false);
        }
        // HttpRequest.timeout bounds only the response headers: a watchdog bounds the whole read, so a stream that
        // stalls fails the journey at the deadline instead of holding it until the server's own stream limit.
        InputStream body = response.body();
        Thread watchdog = Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(STREAM_DEADLINE);
                transcript.accept("# client deadline " + STREAM_DEADLINE + " reached; closing\n");
                body.close();
            } catch (InterruptedException | IOException finished) {
                // The read finished first.
            }
        });
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String name = null;
            String id = null;
            StringBuilder data = new StringBuilder();
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                transcript.accept(line + "\n");
                if (line.startsWith("event:")) name = line.substring(6).strip();
                else if (line.startsWith("id:")) id = line.substring(3).strip();
                else if (line.startsWith("data:")) data.append(line.substring(5).strip());
                else if (line.isEmpty() && name != null) {
                    SseEvent event = new SseEvent(name, id, data.toString());
                    events.add(event);
                    if (id != null) last = id;
                    if ("stream-end".equals(name)) {
                        ended = true;
                        break;
                    }
                    if (stop.test(event)) break;
                    name = null;
                    id = null;
                    data.setLength(0);
                }
            }
        } catch (IOException dropped) {
            transcript.accept("# connection dropped: " + dropped.getClass().getSimpleName() + "\n");
        } finally {
            watchdog.interrupt();
        }
        return new Stream(200, events, last, ended);
    }

    private void remember(HttpResponse<?> response) {
        for (String header : response.headers().allValues("Set-Cookie")) {
            String pair = header.split(";", 2)[0];
            int split = pair.indexOf('=');
            if (split < 0 || !pair.substring(0, split).endsWith("agentworks-session")) continue;
            cookie = pair.substring(split + 1).isEmpty() ? null : pair;
        }
    }

    private static HttpResponse<String> exchange(HttpRequest request) {
        try {
            return HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
