package com.dvarahq.agentworks.journey;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** A harness-owned loopback endpoint; it is never loaded into the packaged server. */
final class OpenAiCompatibleStub implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<JsonNode> lastRequest = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();

    OpenAiCompatibleStub() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException failure) {
            throw new IllegalStateException("could not bind the production journey model stub", failure);
        }
        server.createContext("/v1/chat/completions", this::handle);
        server.setExecutor(executor);
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    int requests() {
        return requests.get();
    }

    JsonNode lastRequest() {
        return lastRequest.get();
    }

    String authorization() {
        return authorization.get();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            JsonNode request = Evidence.JSON.readTree(exchange.getRequestBody());
            requests.incrementAndGet();
            lastRequest.set(request);
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            if (!request.path("stream").asBoolean()) {
                exchange.sendResponseHeaders(400, -1);
                return;
            }
            String model = request.path("model").asString();
            String body = "data: {\"id\":\"chatcmpl-production-journey\","
                    + "\"object\":\"chat.completion.chunk\",\"model\":\"" + model + "\","
                    + "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"production model ok\"},"
                    + "\"finish_reason\":null}]}\n\n"
                    + "data: {\"id\":\"chatcmpl-production-journey\","
                    + "\"object\":\"chat.completion.chunk\",\"model\":\"" + model + "\","
                    + "\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                    + "data: {\"id\":\"chatcmpl-production-journey\",\"model\":\"" + model + "\","
                    + "\"choices\":[],\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":3,"
                    + "\"total_tokens\":10}}\n\n"
                    + "data: [DONE]\n\n";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
        executor.close();
    }
}
