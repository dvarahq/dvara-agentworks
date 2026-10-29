package com.dvarahq.agentworks.tools;

import com.dvarahq.agentworks.agent.springai.*;
import com.dvarahq.agentworks.capability.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;

class HttpToolsTest {
    static HttpTools.Policy policy(String method, Duration timeout, int bytes, int attempts) {
        return new HttpTools.Policy(URI.create("https://example.com"), "/items/{id}", method,
                Set.of("x-request-label"), timeout, bytes, attempts);
    }
    static final String INPUT = "{\"parameters\":{\"id\":\"abc\"}}";

    @Test void rejectsPrivateMetadataMappedAndTransitionAddresses() throws Exception {
        for (String ip : List.of("0.0.0.0", "10.0.0.1", "127.0.0.1", "169.254.169.254", "100.100.100.200",
                "172.16.1.1", "192.168.1.1", "198.18.0.1", "224.0.0.1", "255.255.255.255", "::1", "::",
                "fe80::1", "fd00::1", "::ffff:127.0.0.1", "64:ff9b::7f00:1", "2002:7f00:1::", "2001:db8::1",
                "3fff::1", "192.88.99.1")) {
            assertThat(PublicDestinations.allowed(InetAddress.getByName(ip))).as(ip).isFalse();
        }
        assertThat(PublicDestinations.allowed(InetAddress.getByName("8.8.8.8"))).isTrue();
        assertThat(PublicDestinations.allowed(InetAddress.getByName("2606:4700:4700::1111"))).isTrue();
    }

    @Test void rejectsUnsafeOperatorConfigurationAndPinsPolicyInSchema() {
        for (String origin : List.of("http://example.com", "https://user@example.com", "https://example.com:8443",
                "https://example.com/path", "https://example.com?x=1")) {
            assertThatIllegalArgumentException().isThrownBy(() -> new HttpTools.Policy(URI.create(origin), "/x", "GET",
                    Set.of(), Duration.ofSeconds(1), 100, 1));
        }
        assertThatIllegalArgumentException().isThrownBy(() -> policy("POST", Duration.ofSeconds(1), 100, 2));
        assertThatIllegalArgumentException().isThrownBy(() -> new HttpTools.Policy(URI.create("https://example.com"),
                "/x", "GET", Set.of("authorization"), Duration.ofSeconds(1), 100, 1));
        var one = HttpTools.register(new ToolId("test", "http", "1.0.0"), "http", policy("GET", Duration.ofSeconds(1), 100, 1), ToolEffect.unclassified(), null);
        var two = HttpTools.register(one.metadata().id(), "http", policy("GET", Duration.ofSeconds(2), 100, 1), ToolEffect.unclassified(), null);
        assertThat(one.metadata().schemaHash()).isNotEqualTo(two.metadata().schemaHash());
        var credentialed = HttpTools.register(one.metadata().id(), "http", policy("GET", Duration.ofSeconds(1), 100, 1), ToolEffect.unclassified(), "api");
        assertThat(credentialed.binding().callback()).isInstanceOf(CredentialedToolCallback.class).isInstanceOf(ArtifactToolCallback.class);
        assertThatThrownBy(() -> credentialed.binding().callback().call(INPUT)).isInstanceOf(IllegalStateException.class);
    }

    @Test void inputRefusalsHappenBeforeDnsOrCredentialsAreSent() {
        AtomicInteger dns = new AtomicInteger();
        var operation = new HttpTools.Operation("get", policy("GET", Duration.ofSeconds(1), 100, 1), host -> {
            dns.incrementAndGet(); return new InetAddress[0];
        }, address -> true, null);
        for (String input : List.of("{\"parameters\":{\"id\":\"../x\"}}", "{\"parameters\":{\"id\":\"%2f\"}}",
                "{\"parameters\":{\"id\":\"a\"},\"url\":\"http://localhost\"}", "{\"parameters\":{\"id\":\"a\",\"extra\":\"b\"}}",
                "{\"parameters\":{\"id\":\"a\"},\"headers\":{\"authorization\":\"bad\"}}",
                "{\"parameters\":{\"id\":\"a\"},\"headers\":{\"x-request-label\":\"a\\r\\nb\"}}",
                "{\"parameters\":{\"id\":\"a\"},\"body\":\"x\"}", "{} {}",
                "{\"parameters\":{\"id\":\"a\",\"id\":\"b\"}}")) {
            assertThatThrownBy(() -> operation.call(input)).isInstanceOf(ToolAdapterException.class);
        }
        assertThat(dns).hasValue(0);
    }

    @Test void checksEveryResolvedAddressAndChecksAgainAtConnection() throws Exception {
        InetAddress publicIp = InetAddress.getByName("8.8.8.8"), privateIp = InetAddress.getByName("127.0.0.1");
        for (boolean rebind : List.of(false, true)) {
            AtomicInteger resolves = new AtomicInteger();
            var operation = new HttpTools.Operation("get", policy("GET", Duration.ofSeconds(2), 100, 1), host -> {
                int call = resolves.incrementAndGet();
                return rebind && call == 1 ? new InetAddress[]{publicIp} : new InetAddress[]{publicIp, privateIp};
            }, PublicDestinations::allowed, null);
            assertThatThrownBy(() -> operation.call(INPUT)).hasMessage("HTTP_DESTINATION_DENIED");
            assertThat(resolves.get()).isEqualTo(rebind ? 2 : 1);
        }
    }

    @Test void operatorBodyAllowlistRejectsUnexpectedFieldsAndValuesBeforeNetwork() {
        var policy = new HttpTools.Policy(URI.create("https://example.com"), "/items/{id}", "POST", Set.of(),
                Duration.ofSeconds(1), 100, 1, Map.of("label", "[a-z]{1,16}"));
        var operation = new HttpTools.Operation("post", policy, host -> { throw new AssertionError("network touched"); }, address -> false, null);
        for (String body : List.of("{\"role\":\"admin\"}", "{\"label\":\"bad value\"}", "{\"label\":{\"nested\":true}}")) {
            assertThatThrownBy(() -> operation.call("{\"parameters\":{\"id\":\"abc\"},\"body\":" + body + "}"))
                    .isInstanceOf(ToolAdapterException.class);
        }
    }

    @Test void boundedBinaryResponseRetriesAndStableMutationKey() throws Exception {
        try (Server server = new Server()) {
            AtomicInteger calls = new AtomicInteger();
            AtomicReference<String> key = new AtomicReference<>();
            server.http.createContext("/items/abc", exchange -> {
                key.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
                byte[] body = new byte[]{0, 1, (byte)255};
                exchange.sendResponseHeaders(calls.incrementAndGet() == 1 ? 503 : 200, body.length);
                exchange.getResponseBody().write(body); exchange.close();
            });
            var get = server.operation("GET", Duration.ofSeconds(3), 100, 2);
            var response = AdapterSupport.input(get.call(INPUT));
            assertThat(response.path("attempts").asInt()).isEqualTo(2);
            assertThat(response.path("body").asString()).isEqualTo("AAH/");
            assertThat(get.requiresArtifact(response.toString())).isTrue();
            var post = server.operation("POST", Duration.ofSeconds(2), 100, 1);
            assertThatThrownBy(() -> post.call(INPUT)).hasMessage("HTTP_IDEMPOTENCY_REQUIRED");
            assertThat(calls).hasValue(2);
            var context = new ToolContext(Map.of(ToolEffectContext.IDEMPOTENCY_KEY, "run-effect-stable"));
            post.call(INPUT, context);
            assertThat(key).hasValue("run-effect-stable");
            assertThat(calls).hasValue(3);
        }
    }

    @Test void redirectsOversizeAndCredentialEchoFailWithoutContentLeak() throws Exception {
        for (String mode : List.of("redirect", "size", "secret", "json-secret")) {
            try (Server server = new Server()) {
                AtomicInteger other = new AtomicInteger();
                server.http.createContext("/other", exchange -> { other.incrementAndGet(); exchange.close(); });
                server.http.createContext("/items/abc", exchange -> {
                    byte[] body = (mode.equals("secret") ? "canary-token" : mode.equals("json-secret")
                            ? "{\"value\":\"\\u0063anary-token\"}" : "x".repeat(101)).getBytes(StandardCharsets.UTF_8);
                    if (mode.equals("redirect")) exchange.getResponseHeaders().set("Location", "/other");
                    exchange.sendResponseHeaders(mode.equals("redirect") ? 302 : 200, 0);
                    exchange.getResponseBody().write(body); exchange.close();
                });
                var operation = server.operation("GET", Duration.ofSeconds(2), 100, 1);
                assertThatThrownBy(() -> operation.invoke(INPUT, null, "canary-token".toCharArray()))
                        .hasMessage(switch (mode) { case "redirect" -> "HTTP_REDIRECT_DENIED"; case "size" -> "TOOL_OUTPUT_LIMIT"; default -> "TOOL_SECRET_IN_OUTPUT"; })
                        .hasMessageNotContaining("canary-token");
                assertThat(other).hasValue(0);
            }
        }
    }

    @Test void absoluteDeadlineAndCallerCancellationAreBounded() throws Exception {
        try (Server server = new Server()) {
            CountDownLatch received = new CountDownLatch(1), release = new CountDownLatch(1);
            server.http.createContext("/items/", exchange -> {
                if (exchange.getRequestURI().getPath().endsWith("/second")) received.countDown();
                try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                exchange.close();
            });
            try {
                long start = System.nanoTime();
                assertThatThrownBy(() -> server.operation("GET", Duration.ofMillis(100), 100, 1).call(INPUT))
                        .isInstanceOf(ToolAdapterException.class);
                assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
                AtomicReference<Throwable> error = new AtomicReference<>();
                Thread caller = Thread.ofVirtual().start(() -> {
                    try { server.operation("GET", Duration.ofSeconds(5), 100, 1).call("{\"parameters\":{\"id\":\"second\"}}"); }
                    catch (Throwable failure) { error.set(failure); }
                });
                assertThat(received.await(2, TimeUnit.SECONDS)).isTrue();
                caller.interrupt(); caller.join(Duration.ofSeconds(2));
                assertThat(caller.isAlive()).isFalse();
                assertThat(error.get()).hasMessage("TOOL_CANCELLED");
            } finally { release.countDown(); }
        }
    }

    private static final class Server implements AutoCloseable {
        final HttpServer http;
        final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        Server() throws Exception {
            http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            http.setExecutor(executor); http.start();
        }
        HttpTools.Operation operation(String method, Duration timeout, int bytes, int attempts) {
            return new HttpTools.Operation("http", policy(method, timeout, bytes, attempts), InetAddress::getAllByName,
                    address -> address.isLoopbackAddress(), URI.create("http://127.0.0.1:" + http.getAddress().getPort()));
        }
        public void close() { http.stop(0); executor.shutdownNow(); }
    }
}
