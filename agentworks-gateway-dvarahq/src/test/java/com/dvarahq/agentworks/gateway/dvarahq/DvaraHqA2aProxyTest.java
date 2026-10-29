package com.dvarahq.agentworks.gateway.dvarahq;

import com.dvarahq.agentworks.credential.CredentialUseRequest;
import com.dvarahq.agentworks.credential.conformance.DeterministicFakeCredentialProvider;
import com.dvarahq.agentworks.domain.external.ExternalInvocationContext;
import com.dvarahq.agentworks.domain.security.CredentialRef;
import com.dvarahq.agentworks.domain.security.InvocationIdentity;
import com.dvarahq.agentworks.gateway.CancellationSignal;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

final class DvaraHqA2aProxyTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Instant NOW = Instant.parse("2026-10-16T10:00:00Z");
    private final InvocationIdentity identity = new InvocationIdentity(UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    private final ExternalInvocationContext context = new ExternalInvocationContext(identity.organizationId(),
            identity.workspaceId(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "cohort-1",
            UUID.randomUUID(), "trace-1", "invocation-1");
    private final CredentialRef gatewayRef = new CredentialRef("env:dvarahq-key");
    private final CredentialRef delegatedRef = new CredentialRef("env:delegated-token");
    private final DeterministicFakeCredentialProvider credentials = new DeterministicFakeCredentialProvider();
    private final List<RequestView> requests = new CopyOnWriteArrayList<>();
    private final List<DvaraHqObservation> observations = new CopyOnWriteArrayList<>();
    private final AtomicReference<Response> response = new AtomicReference<>(Response.success("rpc-1"));
    private final AtomicReference<CountDownLatch> bodyGate = new AtomicReference<>();
    private final AtomicReference<CountDownLatch> headersSent = new AtomicReference<>();
    private HttpServer server;
    private DvaraHqA2aProxy proxy;

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/a2a/agent-one", this::handle);
        server.start();
        URI root = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        DvaraHqGatewayConfig base = DvaraHqGatewayConfig.of("dvarahq", root.resolve("/v1"), Map.of("standard", "route"));
        DvaraHqGatewayConfig config = new DvaraHqGatewayConfig(base.gatewayId(), base.modelBaseUri(), base.models(),
                base.modelCapabilities(), Optional.of(root.resolve("/mcp")), Optional.of(root.resolve("/a2a")),
                base.connectTimeout(), base.maxModelResponseBytes(), base.maxModelStreamBytes());
        credentials.put(gatewayRef, identity, "gateway-secret-canary", NOW.plusSeconds(600));
        credentials.put(delegatedRef, identity, "delegated-secret-canary", NOW.plusSeconds(600));
        proxy = new DvaraHqA2aProxy(config, credentials, 64 * 1024, 64 * 1024, observations::add,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach void stop() { if (server != null) server.stop(0); credentials.close(); }

    @Test void sendsBoundedAuthenticatedCorrelatedJsonRpcAndMapsResult() {
        DvaraHqA2aResult result = proxy.exchange(call(Optional.of(use(delegatedRef)), CancellationSignal.create()));

        assertThat(result.rpcId()).isEqualTo("rpc-1");
        assertThat(result.taskId()).isEqualTo("task-1");
        assertThat(result.contextId()).isEqualTo("remote-context-1");
        assertThat(result.status()).isEqualTo("completed");
        assertThat(requests).singleElement().satisfies(request -> {
            assertThat(request.authorization()).isEqualTo("Bearer gateway-secret-canary");
            assertThat(request.delegated()).isEqualTo("Bearer delegated-secret-canary");
            assertThat(request.trace()).isEqualTo(context.traceId());
            assertThat(request.session()).isEqualTo(context.invocationId());
            assertThat(request.idempotency()).isEqualTo(context.invocationId());
            assertThat(request.version()).isEqualTo("0.3");
            assertThat(request.body().path("method").stringValue()).isEqualTo("message/send");
        });
        assertThat(proxy.health().status()).isEqualTo(DvaraHqHealth.Status.HEALTHY);
        assertThat(observations).singleElement().extracting(DvaraHqObservation::code).isEqualTo("DVARAHQ_A2A_OK");
        assertThat(proxy.toString() + observations).doesNotContain("gateway-secret-canary", "delegated-secret-canary");
    }

    @Test void observesCredentialRotationOnTheNextCallAndDoesNotRetryAnOutage() {
        proxy.exchange(call(Optional.empty(), CancellationSignal.create()));
        credentials.put(gatewayRef, identity, "rotated-gateway-secret", NOW.plusSeconds(600));
        response.set(new Response(503, "{\"error\":\"down\"}"));

        DvaraHqProxyException failure = catchThrowableOfType(DvaraHqProxyException.class,
                () -> proxy.exchange(call(Optional.empty(), CancellationSignal.create())));

        assertThat(failure.code()).isEqualTo(DvaraHqProxyException.Code.UNAVAILABLE);
        assertThat(requests).hasSize(2);
        assertThat(requests.get(1).authorization()).isEqualTo("Bearer rotated-gateway-secret");
        assertThat(failure.getMessage() + proxy.health() + observations)
                .doesNotContain("rotated-gateway-secret", "down");
    }

    @Test void refusesCrossTenantAndCancelledCallsBeforeCredentialOrNetworkUse() {
        InvocationIdentity other = new InvocationIdentity(identity.organizationId(), UUID.randomUUID(),
                identity.userId(), identity.sessionId(), identity.delegatedSubjectId());
        DvaraHqA2aCall mismatched = new DvaraHqA2aCall(context, "agent-one", "rpc-1", "message/send", "{}",
                CredentialUseRequest.withoutAgent(gatewayRef, other), Optional.empty(), "0.3",
                Duration.ofSeconds(2), CancellationSignal.create());
        CancellationSignal cancelled = CancellationSignal.create(); cancelled.cancel();

        assertThat(catchThrowableOfType(DvaraHqProxyException.class, () -> proxy.exchange(mismatched)).code())
                .isEqualTo(DvaraHqProxyException.Code.TENANT_MISMATCH);
        assertThat(catchThrowableOfType(DvaraHqProxyException.class,
                () -> proxy.exchange(call(Optional.empty(), cancelled))).code())
                .isEqualTo(DvaraHqProxyException.Code.CANCELLED);
        assertThat(requests).isEmpty();
        assertThat(credentials.resolveCalls()).isZero();
    }

    @Test void rejectsMismatchedAndOversizedResponsesWithoutSurfacingBodies() {
        response.set(Response.success("wrong-id"));
        DvaraHqProxyException mismatch = catchThrowableOfType(DvaraHqProxyException.class,
                () -> proxy.exchange(call(Optional.empty(), CancellationSignal.create())));
        assertThat(mismatch.code()).isEqualTo(DvaraHqProxyException.Code.PROTOCOL_MISMATCH);

        response.set(new Response(200, ""));
        DvaraHqProxyException empty = catchThrowableOfType(DvaraHqProxyException.class,
                () -> proxy.exchange(call(Optional.empty(), CancellationSignal.create())));
        assertThat(empty.code()).isEqualTo(DvaraHqProxyException.Code.INVALID_RESPONSE);

        response.set(new Response(200, "{\"jsonrpc\":\"2.0\",\"id\":\"rpc-1\",\"id\":\"rpc-1\","
                + "\"result\":{}}"));
        DvaraHqProxyException duplicate = catchThrowableOfType(DvaraHqProxyException.class,
                () -> proxy.exchange(call(Optional.empty(), CancellationSignal.create())));
        assertThat(duplicate.code()).isEqualTo(DvaraHqProxyException.Code.INVALID_RESPONSE);

        response.set(new Response(200, "{\"jsonrpc\":\"2.0\",\"id\":\"rpc-1\",\"result\":{\"x\":\""
                + "a".repeat(70_000) + "\"}}"));
        DvaraHqProxyException large = catchThrowableOfType(DvaraHqProxyException.class,
                () -> proxy.exchange(call(Optional.empty(), CancellationSignal.create())));
        assertThat(large.code()).isEqualTo(DvaraHqProxyException.Code.RESPONSE_LIMIT);
    }

    @Test void rejectsHeaderInjectionAndOversizedRequestsBeforeNetworkUse() {
        ExternalInvocationContext injected = new ExternalInvocationContext(context.organizationId(),
                context.workspaceId(), context.runId(), context.releaseId(), context.deploymentRevisionId(),
                context.cohortId(), context.routingGenerationId(), "trace\r\ninjected", context.invocationId());
        assertThatIllegalArgumentException().isThrownBy(() -> new DvaraHqA2aCall(injected, "agent-one", "rpc-1",
                "message/send", "{}", use(gatewayRef), Optional.empty(), "0.3", Duration.ofSeconds(2),
                CancellationSignal.create()));

        DvaraHqA2aCall oversized = new DvaraHqA2aCall(context, "agent-one", "rpc-1", "message/send",
                "{\"value\":\"" + "a".repeat(70_000) + "\"}", use(gatewayRef), Optional.empty(), "0.3",
                Duration.ofSeconds(2), CancellationSignal.create());
        assertThat(catchThrowableOfType(DvaraHqProxyException.class, () -> proxy.exchange(oversized)).code())
                .isEqualTo(DvaraHqProxyException.Code.INVALID_REQUEST);
        assertThat(requests).isEmpty();
        assertThat(credentials.resolveCalls()).isZero();
    }

    @Test void wholeCallDeadlineIncludesBodyConsumptionAfterHeaders() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch headers = new CountDownLatch(1);
        bodyGate.set(gate);
        headersSent.set(headers);
        long started = System.nanoTime();
        CompletableFuture<DvaraHqProxyException.Code> result = CompletableFuture.supplyAsync(() -> {
            DvaraHqProxyException failure = catchThrowableOfType(DvaraHqProxyException.class,
                    () -> proxy.exchange(call(Optional.empty(), CancellationSignal.create(), Duration.ofMillis(500))));
            return failure.code();
        });
        try {
            assertThat(headers.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(result.get(1, TimeUnit.SECONDS)).isEqualTo(DvaraHqProxyException.Code.TIMEOUT);
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
        } finally {
            gate.countDown();
        }
    }

    @Test void cancellationAfterHeadersAbandonsAWithheldBody() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch headers = new CountDownLatch(1);
        bodyGate.set(gate);
        headersSent.set(headers);
        CancellationSignal cancellation = CancellationSignal.create();
        CompletableFuture<DvaraHqProxyException.Code> result = CompletableFuture.supplyAsync(() -> {
            DvaraHqProxyException failure = catchThrowableOfType(DvaraHqProxyException.class,
                    () -> proxy.exchange(call(Optional.empty(), cancellation, Duration.ofSeconds(5))));
            return failure.code();
        });
        try {
            assertThat(headers.await(1, TimeUnit.SECONDS)).isTrue();
            cancellation.cancel();
            assertThat(result.get(1, TimeUnit.SECONDS)).isEqualTo(DvaraHqProxyException.Code.CANCELLED);
        } finally {
            gate.countDown();
        }
    }

    @Test void malformedRequestAndResponseNeverRetainJacksonPayloadCauses() {
        String requestCanary = "request-json-secret-canary";
        DvaraHqA2aCall malformedRequest = new DvaraHqA2aCall(context, "agent-one", "rpc-1", "message/send",
                "{\"value\":\"" + requestCanary, use(gatewayRef), Optional.empty(), "0.3",
                Duration.ofSeconds(2), CancellationSignal.create());
        DvaraHqProxyException requestFailure = catchThrowableOfType(DvaraHqProxyException.class,
                () -> proxy.exchange(malformedRequest));
        assertThat(requestFailure.code()).isEqualTo(DvaraHqProxyException.Code.INVALID_REQUEST);
        assertThat(requestFailure.getCause()).isNull();
        assertThat(rendered(requestFailure)).doesNotContain(requestCanary);

        String responseCanary = "response-json-secret-canary";
        response.set(new Response(200, "{\"jsonrpc\":\"2.0\",\"id\":\"rpc-1\",\"result\":{\"value\":\""
                + responseCanary));
        DvaraHqProxyException responseFailure = catchThrowableOfType(DvaraHqProxyException.class,
                () -> proxy.exchange(call(Optional.empty(), CancellationSignal.create())));
        assertThat(responseFailure.code()).isEqualTo(DvaraHqProxyException.Code.INVALID_RESPONSE);
        assertThat(responseFailure.getCause()).isNull();
        assertThat(rendered(responseFailure)).doesNotContain(responseCanary);
    }

    @Test void malformedHttpResponseNeverRetainsTransportPayloadCause() throws Exception {
        String transportCanary = "secretCanary123";
        try (ServerSocket peer = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            CompletableFuture<Void> responseSent = CompletableFuture.runAsync(() -> {
                try (Socket connection = peer.accept()) {
                    connection.getOutputStream().write(("HTTP/1.1 " + transportCanary
                            + "\r\nContent-Length: 0\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    connection.getOutputStream().flush();
                } catch (IOException failure) {
                    throw new IllegalStateException(failure);
                }
            });
            URI root = URI.create("http://127.0.0.1:" + peer.getLocalPort());
            DvaraHqGatewayConfig base = DvaraHqGatewayConfig.of(
                    "dvarahq", root.resolve("/v1"), Map.of("standard", "route"));
            DvaraHqGatewayConfig config = new DvaraHqGatewayConfig(base.gatewayId(), base.modelBaseUri(),
                    base.models(), base.modelCapabilities(), Optional.of(root.resolve("/mcp")),
                    Optional.of(root.resolve("/a2a")), base.connectTimeout(), base.maxModelResponseBytes(),
                    base.maxModelStreamBytes());
            DvaraHqA2aProxy malformedPeerProxy = new DvaraHqA2aProxy(config, credentials, 64 * 1024,
                    64 * 1024, observations::add, Clock.fixed(NOW, ZoneOffset.UTC));

            DvaraHqProxyException failure = catchThrowableOfType(DvaraHqProxyException.class,
                    () -> malformedPeerProxy.exchange(call(Optional.empty(), CancellationSignal.create())));
            responseSent.get(1, TimeUnit.SECONDS);

            assertThat(failure.code()).isEqualTo(DvaraHqProxyException.Code.UNAVAILABLE);
            assertThat(failure.getCause()).isNull();
            assertThat(rendered(failure)).doesNotContain(transportCanary);
        }
    }

    private DvaraHqA2aCall call(Optional<CredentialUseRequest> delegated, CancellationSignal cancellation) {
        return call(delegated, cancellation, Duration.ofSeconds(2));
    }

    private DvaraHqA2aCall call(Optional<CredentialUseRequest> delegated, CancellationSignal cancellation,
            Duration timeout) {
        return new DvaraHqA2aCall(context, "agent-one", "rpc-1", "message/send",
                "{\"message\":{\"role\":\"user\",\"parts\":[{\"kind\":\"text\",\"text\":\"hello\"}]}}",
                use(gatewayRef), delegated, "0.3", timeout, cancellation);
    }

    private CredentialUseRequest use(CredentialRef reference) {
        return CredentialUseRequest.withoutAgent(reference, identity);
    }

    private void handle(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readAllBytes();
        requests.add(new RequestView(exchange.getRequestHeaders().getFirst("Authorization"),
                exchange.getRequestHeaders().getFirst("X-Dvara-Delegated-Authorization"),
                exchange.getRequestHeaders().getFirst("X-Trace-ID"),
                exchange.getRequestHeaders().getFirst("X-Session-Id"),
                exchange.getRequestHeaders().getFirst("Idempotency-Key"),
                exchange.getRequestHeaders().getFirst("A2A-Version"), JSON.readTree(body)));
        Response next = response.get();
        byte[] bytes = next.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(next.status(), bytes.length);
        CountDownLatch headers = headersSent.get();
        if (headers != null) headers.countDown();
        CountDownLatch gate = bodyGate.get();
        if (gate != null) {
            try {
                gate.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
            }
        }
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static String rendered(Throwable failure) {
        StringWriter text = new StringWriter();
        failure.printStackTrace(new PrintWriter(text));
        return text.toString();
    }

    private record RequestView(String authorization, String delegated, String trace, String session,
            String idempotency, String version, JsonNode body) { }
    private record Response(int status, String body) {
        static Response success(String id) {
            return new Response(200, "{\"jsonrpc\":\"2.0\",\"id\":\"" + id
                    + "\",\"result\":{\"id\":\"task-1\",\"contextId\":\"remote-context-1\","
                    + "\"status\":{\"state\":\"completed\"}}}");
        }
    }
}
