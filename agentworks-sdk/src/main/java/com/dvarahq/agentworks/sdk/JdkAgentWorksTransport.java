package com.dvarahq.agentworks.sdk;

import com.dvarahq.agentworks.sdk.stream.RunStreamException;
import com.dvarahq.agentworks.sdk.stream.RunStreamOptions;
import com.dvarahq.agentworks.sdk.stream.RunStreamTransport;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;

/** JDK-only HTTP transport. It resolves credentials per request and retains no secret value. */
final class JdkAgentWorksTransport implements AgentWorksTransport, RunStreamTransport {
    private final URI baseUri;
    private final HttpClient client;
    private final AccessTokenProvider tokens;
    private final Duration timeout;
    private final tools.jackson.databind.ObjectMapper json = JsonMapper.builder().build();

    JdkAgentWorksTransport(URI baseUri, HttpClient client, AccessTokenProvider tokens, Duration timeout) {
        this.baseUri = normalize(baseUri);
        this.client = Objects.requireNonNull(client, "client");
        this.tokens = tokens;
        this.timeout = Objects.requireNonNull(timeout, "timeout");
    }

    @Override public ApiDocument execute(ApiCall call) {
        Objects.requireNonNull(call, "call");
        HttpRequest.Builder builder = authorized(HttpRequest.newBuilder(resolve(call.pathAndQuery())).timeout(timeout)
                .header("Accept", "application/json"));
        if (call.idempotencyKey() != null) builder.header("Idempotency-Key", call.idempotencyKey());
        if (call.ifMatch() != null) builder.header("If-Match", call.ifMatch());
        boolean hasBody = call.body() != null && !call.body().isNull();
        HttpRequest.BodyPublisher body = hasBody
                ? HttpRequest.BodyPublishers.ofString(json.writeValueAsString(call.body()), StandardCharsets.UTF_8)
                : HttpRequest.BodyPublishers.noBody();
        if (hasBody) builder.header("Content-Type", "application/json");
        try {
            HttpResponse<String> response = client.send(builder.method(call.operation().method(), body).build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode payload = response.body().isBlank() ? JsonNodeFactory.instance.nullNode() : json.readTree(response.body());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ApiProblemException(response.statusCode(), payload);
            }
            return new ApiDocument(response.statusCode(), payload, response.headers().map());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("AgentWorks API request interrupted", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("AgentWorks API transport unavailable", exception);
        }
    }

    @Override public Connection open(String runId, RunStreamOptions options, long afterSequence) {
        String path = "/api/v1/runs/" + encodeSegment(runId) + "/stream";
        String separator = "?";
        if (options.clientId() != null) {
            path += separator + "client=" + encodeSegment(options.clientId());
            separator = "&";
        }
        if (afterSequence > 0) path += separator + "cursor=" + afterSequence;
        HttpRequest.Builder request = authorized(HttpRequest.newBuilder(resolve(path)).timeout(timeout)
                .header("Accept", "text/event-stream"));
        if (afterSequence > 0) request.header("Last-Event-ID", runId + ":" + afterSequence);
        try {
            HttpResponse<InputStream> response = client.send(request.GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            return new InputConnection(response.statusCode(), response.body());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new RunStreamException("STREAM_INTERRUPTED", "stream connection interrupted", exception);
        } catch (IOException exception) {
            throw new RunStreamException("STREAM_IO_ERROR", "stream connection unavailable", exception);
        }
    }

    private HttpRequest.Builder authorized(HttpRequest.Builder builder) {
        if (tokens == null) return builder;
        char[] token = tokens.resolve();
        if (token == null || token.length == 0) return builder;
        try {
            for (char value : token) {
                if (value <= 0x20 || value >= 0x7f) throw new IllegalArgumentException("access token contains invalid characters");
            }
            return builder.header("Authorization", "Bearer " + new String(token));
        } finally {
            Arrays.fill(token, '\0');
        }
    }

    private URI resolve(String path) { return baseUri.resolve(path); }
    private static URI normalize(URI value) {
        Objects.requireNonNull(value, "baseUri");
        if (value.getUserInfo() != null || value.getFragment() != null || value.getQuery() != null) {
            throw new IllegalArgumentException("baseUri must not contain user info, query, or fragment");
        }
        return value;
    }
    private static String encodeSegment(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static final class InputConnection implements Connection {
        private final int status;
        private final InputStream input;
        private final BufferedReader reader;
        private InputConnection(int status, InputStream input) {
            this.status = status;
            this.input = input;
            this.reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        }
        @Override public int status() { return status; }
        @Override public BufferedReader reader() { return reader; }
        @Override public void close() { try { input.close(); } catch (IOException ignored) { } }
    }
}
