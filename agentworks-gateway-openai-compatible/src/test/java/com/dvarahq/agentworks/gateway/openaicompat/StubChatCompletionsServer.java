package com.dvarahq.agentworks.gateway.openaicompat;

import com.dvarahq.agentworks.gateway.ToolCall;
import com.dvarahq.agentworks.gateway.conformance.ConformanceHarness.ProviderView;
import com.dvarahq.agentworks.gateway.conformance.ProviderScript;
import com.dvarahq.agentworks.gateway.fake.DeterministicFakeGatewayAdapter;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A deterministic, loopback-only OpenAI-compatible Chat Completions server for tests. It renders a
 * {@link ProviderScript} in the real wire format (JSON or Server-Sent Events), records what it received, and on error
 * paths deliberately echoes the bearer token and prompt in its bodies so tests can prove the adapter never surfaces
 * provider bodies.
 */
public final class StubChatCompletionsServer implements AutoCloseable {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final CountDownLatch stopping = new CountDownLatch(1);
    private final AtomicReference<ProviderScript> script = new AtomicReference<>(ProviderScript.text(
            java.util.List.of("ok"), null));
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicInteger disconnects = new AtomicInteger();
    private final AtomicReference<ProviderView> lastView = new AtomicReference<>();
    private final AtomicReference<JsonNode> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastCredentialDigest = new AtomicReference<>();
    private final AtomicReference<Integer> fixedStatus = new AtomicReference<>();
    private final AtomicReference<String> retryAfter = new AtomicReference<>();
    private final AtomicReference<String> redirectTo = new AtomicReference<>();
    private final AtomicReference<byte[]> rawBody = new AtomicReference<>();
    private final AtomicReference<List<byte[]>> rawEvents = new AtomicReference<>();
    private final AtomicReference<Duration> rawEventDelay = new AtomicReference<>(Duration.ZERO);

    public StubChatCompletionsServer() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
        server.setExecutor(executor);
        server.createContext("/v1/chat/completions", this::handle);
        server.start();
    }

    public URI baseUri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
    }

    public void script(ProviderScript next) {
        script.set(next);
    }

    /** Answers every request with this status and an echoing error body. */
    void respondWithStatus(int status) {
        fixedStatus.set(status);
    }

    void respondWithStatus(int status, String retryAfterValue) {
        fixedStatus.set(status);
        retryAfter.set(retryAfterValue);
    }

    /** Answers with a 307 redirect to {@code location}. */
    void redirectTo(String location) {
        redirectTo.set(location);
    }

    /** Answers 200 with exactly this body. */
    void respondWithRawBody(String body) {
        rawBody.set(body.getBytes(StandardCharsets.UTF_8));
    }

    /** Answers 200 with exactly these bytes, which need not be valid UTF-8. */
    void respondWithRawBytes(byte[] body) {
        rawBody.set(body.clone());
    }

    /** Answers 200 by writing and flushing each part in turn, pausing {@code delay} between parts. */
    void respondWithRawEvents(List<String> parts, Duration delay) {
        rawEvents.set(parts.stream().map(part -> part.getBytes(StandardCharsets.UTF_8)).toList());
        rawEventDelay.set(delay);
    }

    public int requests() {
        return requests.get();
    }

    /** Responses the server could not finish because the client closed the connection. */
    int disconnects() {
        return disconnects.get();
    }

    public ProviderView lastView() {
        return lastView.get();
    }

    JsonNode lastBody() {
        return lastBody.get();
    }

    public String lastCredentialDigest() {
        return lastCredentialDigest.get();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            JsonNode body = JSON.readTree(exchange.getRequestBody().readAllBytes());
            requests.incrementAndGet();
            lastBody.set(body);
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            String token = authorization != null && authorization.startsWith("Bearer ")
                    ? authorization.substring(7) : null;
            lastCredentialDigest.set(token == null ? null : DeterministicFakeGatewayAdapter.digest(token.toCharArray()));
            boolean stream = body.path("stream").asBoolean(false);
            lastView.set(new ProviderView(body.path("model").asString(), stream, body.path("tools").size(),
                    body.has("response_format"), body.path("messages").size()));
            if (redirectTo.get() != null) {
                exchange.getResponseHeaders().add("Location", redirectTo.get());
                exchange.sendResponseHeaders(307, -1);
                return;
            }
            if (fixedStatus.get() != null) {
                if (retryAfter.get() != null) exchange.getResponseHeaders().add("Retry-After", retryAfter.get());
                error(exchange, fixedStatus.get(), token, body);
                return;
            }
            if (rawBody.get() != null) {
                byte[] bytes = rawBody.get();
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                return;
            }
            if (rawEvents.get() != null) {
                exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                OutputStream out = exchange.getResponseBody();
                boolean first = true;
                for (byte[] part : rawEvents.get()) {
                    if (!first) {
                        pause(rawEventDelay.get());
                    }
                    first = false;
                    out.write(part);
                    out.flush();
                }
                return;
            }
            respond(exchange, script.get(), body, token, stream);
        } catch (IOException clientWentAway) {
            // The adapter aborted (cancellation or deadline); record that the connection was really released.
            disconnects.incrementAndGet();
        }
    }

    private void respond(HttpExchange exchange, ProviderScript script, JsonNode request, String token, boolean stream)
            throws IOException {
        String model = script.servedModel() == null ? request.path("model").asString() : script.servedModel();
        switch (script.failure()) {
            case AUTHENTICATION -> error(exchange, 401, token, request);
            case RATE_LIMITED -> error(exchange, 429, token, request);
            case UNAVAILABLE -> error(exchange, 503, token, request);
            case REJECTED -> error(exchange, 400, token, request);
            case MALFORMED -> write(exchange, 200, stream ? "data: {not json " + token + "\n\n" : "{not json " + token);
            case TRUNCATED_STREAM -> write(exchange, 200, stream
                    ? "data: " + chunk(model, script, JSON.createObjectNode().put("content", "partial"), null) + "\n\n"
                    : "{\"choices\":[");
            case HANG -> {
                try {
                    stopping.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            case NONE -> {
                if (stream) {
                    stream(exchange, script, model, request.path("stream_options").path("include_usage").asBoolean());
                } else {
                    pause(script.chunkDelay().multipliedBy(Math.max(1, script.chunks().size())));
                    write(exchange, 200, completion(script, model).toString());
                }
            }
        }
    }

    private void stream(HttpExchange exchange, ProviderScript script, String model, boolean includeUsage)
            throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        OutputStream out = exchange.getResponseBody();
        event(out, ": keep-alive comment");
        for (String delta : script.chunks()) {
            pause(script.chunkDelay());
            event(out, "data: " + chunk(model, script, JSON.createObjectNode().put("content", delta), null));
        }
        int index = 0;
        for (ToolCall call : script.toolCalls()) {
            String arguments = call.argumentsJson();
            int half = arguments.length() / 2;
            ObjectNode first = JSON.createObjectNode();
            ObjectNode firstCall = first.putArray("tool_calls").addObject().put("index", index).put("id", call.id())
                    .put("type", "function");
            firstCall.putObject("function").put("name", call.name()).put("arguments", arguments.substring(0, half));
            event(out, "data: " + chunk(model, script, first, null));
            ObjectNode second = JSON.createObjectNode();
            second.putArray("tool_calls").addObject().put("index", index).putObject("function")
                    .put("arguments", arguments.substring(half));
            event(out, "data: " + chunk(model, script, second, null));
            index++;
        }
        event(out, "data: " + chunk(model, script, JSON.createObjectNode(),
                script.toolCalls().isEmpty() ? "stop" : "tool_calls"));
        if (includeUsage && script.usage() != null) {
            ObjectNode usageChunk = JSON.createObjectNode().put("id", "chatcmpl-stub").put("model", model);
            usageChunk.putArray("choices");
            usageChunk.set("usage", usage(script));
            event(out, "data: " + usageChunk);
        }
        event(out, "data: [DONE]");
    }

    private static ObjectNode chunk(String model, ProviderScript script, ObjectNode delta, String finish) {
        ObjectNode chunk = JSON.createObjectNode().put("id", "chatcmpl-stub").put("object", "chat.completion.chunk")
                .put("model", model);
        if (script.fingerprint() != null) {
            chunk.put("system_fingerprint", script.fingerprint());
        }
        ObjectNode choice = chunk.putArray("choices").addObject().put("index", 0);
        choice.set("delta", delta);
        if (finish == null) {
            choice.putNull("finish_reason");
        } else {
            choice.put("finish_reason", finish);
        }
        return chunk;
    }

    private static ObjectNode completion(ProviderScript script, String model) {
        ObjectNode completion = JSON.createObjectNode().put("id", "chatcmpl-stub").put("object", "chat.completion")
                .put("model", model);
        if (script.fingerprint() != null) {
            completion.put("system_fingerprint", script.fingerprint());
        }
        ObjectNode choice = completion.putArray("choices").addObject().put("index", 0)
                .put("finish_reason", script.toolCalls().isEmpty() ? "stop" : "tool_calls");
        ObjectNode message = choice.putObject("message").put("role", "assistant");
        if (script.chunks().isEmpty()) {
            message.putNull("content");
        } else {
            message.put("content", script.content());
        }
        if (!script.toolCalls().isEmpty()) {
            ArrayNode calls = message.putArray("tool_calls");
            for (ToolCall call : script.toolCalls()) {
                calls.addObject().put("id", call.id()).put("type", "function").putObject("function")
                        .put("name", call.name()).put("arguments", call.argumentsJson());
            }
        }
        if (script.usage() != null) {
            completion.set("usage", usage(script));
        }
        return completion;
    }

    private static ObjectNode usage(ProviderScript script) {
        ObjectNode usage = JSON.createObjectNode().put("prompt_tokens", script.usage().inputTokens())
                .put("completion_tokens", script.usage().outputTokens());
        if (script.usage().totalTokens() != null) {
            usage.put("total_tokens", script.usage().totalTokens());
        }
        if (script.usage().cachedInputTokens() != null) {
            usage.putObject("prompt_tokens_details").put("cached_tokens", script.usage().cachedInputTokens());
        }
        if (script.usage().reasoningTokens() != null) {
            usage.putObject("completion_tokens_details").put("reasoning_tokens", script.usage().reasoningTokens());
        }
        return usage;
    }

    /** Error bodies echo the token and prompt, as some real servers do, to prove the adapter never surfaces them. */
    private static void error(HttpExchange exchange, int status, String token, JsonNode request) throws IOException {
        ObjectNode error = JSON.createObjectNode();
        error.putObject("error").put("message", "rejected key " + token + " for prompt " + request.path("messages"));
        write(exchange, status, error.toString());
    }

    private static void write(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static void event(OutputStream out, String line) throws IOException {
        out.write((line + "\n\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private void pause(Duration duration) {
        if (duration.isZero()) {
            return;
        }
        try {
            stopping.await(duration.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        stopping.countDown();
        server.stop(0);
        executor.shutdownNow();
    }
}
