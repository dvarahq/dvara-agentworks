package com.dvarahq.agentworks.a2a;

import com.dvarahq.agentworks.domain.external.*;
import com.dvarahq.agentworks.runplane.compiled.SharedResourceRegistry;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RemoteExecutorHttpClientTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Instant NOW = Instant.parse("2026-10-14T12:00:00Z");
    private static final String DIGEST = "sha256:" + "a".repeat(64);
    private HttpServer server;
    private final AtomicInteger handshakes = new AtomicInteger();
    private final AtomicInteger executions = new AtomicInteger();
    private final List<String> interactions = new CopyOnWriteArrayList<>();
    private final AtomicReference<ExternalExecutorDescriptor> descriptor = new AtomicReference<>();
    private final AtomicReference<List<ExternalEventEnvelope>> events = new AtomicReference<>();
    private final AtomicReference<char[]> suppliedCredential = new AtomicReference<>();
    private final AtomicReference<Boolean> hang = new AtomicReference<>(false);
    private RemoteExecutorHttpClient client;

    @BeforeEach
    void start() throws Exception {
        descriptor.set(descriptor(ExternalAssurance.INSTRUMENTED, ExternalAccessMode.DVARAOS_MEDIATED,
                Set.of(ExternalCapability.EVENT_STREAMING, ExternalCapability.CANCELLATION,
                        ExternalCapability.TOOL_MEDIATION, ExternalCapability.ARTIFACT_TRANSFER)));
        ExternalInvocationRequest request = request();
        events.set(List.of(event(request.context(), 1, "started", "RUN_STARTED", "{}", false),
                event(request.context(), 2, "tool", "TOOL_REQUESTED", "lookup", false),
                event(request.context(), 3, "artifact", "ARTIFACT_TRANSFER_REQUESTED", "artifact:one", false),
                event(request.context(), 4, "done", "RUN_SUCCEEDED", "ok", true)));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/v1/executors/handshake", exchange -> {
            handshakes.incrementAndGet(); assertAuth(exchange); sendJson(exchange, 200, descriptor.get());
        });
        server.createContext("/v1/executions", exchange -> {
            assertAuth(exchange);
            if (exchange.getRequestURI().getPath().equals("/v1/executions")) {
                executions.incrementAndGet();
                if (hang.get()) {
                    exchange.sendResponseHeaders(200, 0);
                    exchange.getResponseBody().write('\n');
                    exchange.getResponseBody().flush();
                    try { Thread.sleep(Duration.ofSeconds(10)); } catch (InterruptedException stopped) {
                        Thread.currentThread().interrupt();
                    } finally { exchange.close(); }
                    return;
                }
                StringBuilder sse = new StringBuilder();
                for (ExternalEventEnvelope event : events.get()) {
                    sse.append("data: ").append(JSON.writeValueAsString(event)).append("\n\n");
                }
                byte[] bytes = sse.toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes); exchange.close();
            } else {
                interactions.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                exchange.sendResponseHeaders(202, -1); exchange.close();
            }
        });
        server.start();
        RemoteExecutorCredentials credentials = () -> {
            char[] value = "short-lived-test-secret".toCharArray(); suppliedCredential.set(value); return value;
        };
        client = RemoteExecutorHttpClient.loopback(URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                "test-key", credentials, 64 * 1024, 16, Duration.ofSeconds(3), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach void stop() { if (client != null) client.close(); if (server != null) server.stop(0); }

    @Test
    void negotiatesBeforeExecutionAndMediatesToolsAndArtifacts() {
        ExternalInvocationResult result = client.execute(request(), new ExternalInteractionHandler() {
            @Override public String invokeTool(String request) { return "tool-result:" + request; }
            @Override public String transferArtifact(String reference) { return "stored:" + reference; }
        });

        assertThat(handshakes).hasValue(1);
        assertThat(executions).hasValue(1);
        assertThat(result.events()).hasSize(4);
        assertThat(interactions).hasSize(2).anyMatch(value -> value.contains("tool-result:lookup"))
                .anyMatch(value -> value.contains("stored:artifact:one"));
        assertThat(suppliedCredential.get()).containsOnly('\0');
    }

    @Test
    void productionClientPathReusesOnlyTheExactWorkspaceIsolatedConnectionPool() {
        SharedResourceRegistry resources = new SharedResourceRegistry(8);
        URI origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        UUID firstWorkspace = UUID.randomUUID(), secondWorkspace = UUID.randomUUID();
        RemoteExecutorCredentials credentials = () -> "short-lived-test-secret".toCharArray();
        try (var one = RemoteExecutorHttpClient.loopback(origin, "one", credentials, 64 * 1024, 16,
                    Duration.ofSeconds(3), Clock.fixed(NOW, ZoneOffset.UTC), firstWorkspace, resources, Duration.ofMinutes(2));
             var same = RemoteExecutorHttpClient.loopback(origin, "two", credentials, 64 * 1024, 16,
                    Duration.ofSeconds(3), Clock.fixed(NOW, ZoneOffset.UTC), firstWorkspace, resources, Duration.ofMinutes(2));
             var foreign = RemoteExecutorHttpClient.loopback(origin, "three", credentials, 64 * 1024, 16,
                    Duration.ofSeconds(3), Clock.fixed(NOW, ZoneOffset.UTC), secondWorkspace, resources, Duration.ofMinutes(2))) {
            assertThat(one.pooledHttpClient()).isSameAs(same.pooledHttpClient()).isNotSameAs(foreign.pooledHttpClient());
            assertThat(resources.snapshot()).extracting(SharedResourceRegistry.Snapshot::hits,
                    SharedResourceRegistry.Snapshot::misses, SharedResourceRegistry.Snapshot::resources)
                    .containsExactly(1L, 2L, 2);
        }
        resources.close();
    }

    @Test
    void pooledClientRejectsUseAfterCloseWhileAnotherClientKeepsThePoolAlive() {
        SharedResourceRegistry resources = new SharedResourceRegistry(2);
        URI origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        UUID workspace = UUID.randomUUID();
        RemoteExecutorCredentials credentials = () -> "short-lived-test-secret".toCharArray();
        var closed = RemoteExecutorHttpClient.loopback(origin, "test-key", credentials, 64 * 1024, 16,
                Duration.ofSeconds(3), Clock.fixed(NOW, ZoneOffset.UTC), workspace, resources, Duration.ofMinutes(2));
        try (var live = RemoteExecutorHttpClient.loopback(origin, "test-key", credentials, 64 * 1024, 16,
                Duration.ofSeconds(3), Clock.fixed(NOW, ZoneOffset.UTC), workspace, resources, Duration.ofMinutes(2))) {
            closed.close();
            assertThatThrownBy(() -> closed.negotiate(request().requirements()))
                    .isInstanceOf(ExternalProtocolException.class).hasMessageContaining("closed");
            assertThat(live.negotiate(request().requirements())).isEqualTo(descriptor.get());
        }
        resources.close();
    }

    @Test
    void closeWaitsForAnInflightPooledOperationBeforeReleasingItsLease() throws Exception {
        SharedResourceRegistry resources = new SharedResourceRegistry(1);
        URI origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        RemoteExecutorCredentials credentials = () -> "short-lived-test-secret".toCharArray();
        var pooled = RemoteExecutorHttpClient.loopback(origin, "test-key", credentials, 64 * 1024, 16,
                Duration.ofMillis(400), Clock.fixed(NOW, ZoneOffset.UTC), UUID.randomUUID(), resources,
                Duration.ofMinutes(2));
        hang.set(true);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var operation = executor.submit(() -> assertThatThrownBy(() -> pooled.execute(request(), noInteractions()))
                    .isInstanceOf(ExternalProtocolException.class));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (executions.get() == 0 && System.nanoTime() < deadline) Thread.sleep(5);
            assertThat(executions).hasValue(1);
            var closing = executor.submit(pooled::close);
            Thread.sleep(50);
            assertThat(closing.isDone()).isFalse();
            operation.get(2, TimeUnit.SECONDS);
            closing.get(2, TimeUnit.SECONDS);
        } finally {
            pooled.close();
            resources.close();
        }
    }

    @Test
    void deterministicFakeProvesBlackBoxAsWellAsInstrumentedConformance() {
        ExternalExecutorDescriptor blackBox = descriptor(ExternalAssurance.BLACK_BOX,
                ExternalAccessMode.FRAMEWORK_DIRECT, Set.of(ExternalCapability.EVENT_STREAMING));
        descriptor.set(blackBox);
        ExternalInvocationRequest instrumented = request();
        ExternalInvocationRequest request = new ExternalInvocationRequest(instrumented.context(),
                new ExternalProtocolRequirements(RemoteExecutorHttpClient.PROTOCOL_VERSION,
                        RemoteExecutorHttpClient.SCHEMA_VERSION, ExternalAssurance.BLACK_BOX,
                        ExternalAccessMode.FRAMEWORK_DIRECT, Set.of(ExternalCapability.EVENT_STREAMING)),
                blackBox, instrumented.input());

        ExternalInvocationResult result = client.execute(request, new ExternalInteractionHandler() {
            @Override public String invokeTool(String value) { return "tool:" + value; }
            @Override public String transferArtifact(String value) { return "artifact:" + value; }
        });

        assertThat(result.executor().assurance()).isEqualTo(ExternalAssurance.BLACK_BOX);
        assertThat(result.executor().accessMode()).isEqualTo(ExternalAccessMode.FRAMEWORK_DIRECT);
    }

    @Test
    void rejectsNegotiationMismatchBeforeExecution() {
        descriptor.set(descriptor(ExternalAssurance.BLACK_BOX, ExternalAccessMode.DVARAOS_MEDIATED,
                Set.of(ExternalCapability.EVENT_STREAMING)));

        assertThatThrownBy(() -> client.execute(request(), noInteractions()))
                .isInstanceOfSatisfying(ExternalProtocolException.class,
                        failure -> assertThat(failure.error()).isEqualTo(ExternalProtocolError.NEGOTIATION_MISMATCH));
        assertThat(executions).hasValue(0);
    }

    @Test
    void rejectsCrossTenantGapDuplicateAndLateEvents() {
        ExternalInvocationRequest request = request();
        List<List<ExternalEventEnvelope>> invalid = List.of(
                List.of(new ExternalEventEnvelope(UUID.randomUUID(), request.context().workspaceId(),
                        request.context().runId(), request.context().releaseId(), request.context().deploymentRevisionId(),
                        request.context().cohortId(), request.context().routingGenerationId(), request.context().traceId(),
                        request.context().invocationId(), RemoteExecutorHttpClient.PROTOCOL_VERSION,
                        RemoteExecutorHttpClient.SCHEMA_VERSION, "bad-tenant", 1, "RUN_SUCCEEDED", "", true)),
                List.of(event(request.context(), 2, "gap", "RUN_SUCCEEDED", "", true)),
                List.of(event(request.context(), 1, "same", "RUN_STARTED", "", false),
                        event(request.context(), 2, "same", "RUN_SUCCEEDED", "", true)),
                List.of(event(request.context(), 1, "terminal", "RUN_SUCCEEDED", "", true),
                        event(request.context(), 2, "late", "USAGE", "", false)),
                List.of(event(request.context(), 1, "unknown", "FUTURE_EVENT", "", true)),
                List.of(event(request.context(), 1, "false-terminal", "RUN_SUCCEEDED", "", false)));

        for (List<ExternalEventEnvelope> caseEvents : invalid) {
            events.set(caseEvents);
            assertThatThrownBy(() -> client.execute(request, noInteractions()))
                    .isInstanceOf(ExternalProtocolException.class);
        }
    }

    @Test
    void cancellationAcknowledgementDoesNotManufactureATerminalEvent() {
        client.cancel(request().context(), "timeout");
        assertThat(interactions).singleElement().asString().contains("timeout");
    }

    @Test
    void timeoutRequestsCancellationButRefusesToClaimItCompleted() {
        hang.set(true);
        RemoteExecutorHttpClient impatient = RemoteExecutorHttpClient.loopback(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "test-key",
                () -> "short-lived-test-secret".toCharArray(), 64 * 1024, 16,
                Duration.ofMillis(100), Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> impatient.execute(request(), noInteractions()))
                .isInstanceOfSatisfying(ExternalProtocolException.class,
                        failure -> assertThat(failure.error()).isEqualTo(ExternalProtocolError.CANCELLATION_UNCONFIRMED));
        assertThat(interactions).singleElement().asString().contains("timeout");
    }

    @Test
    void durableDescriptorRequiresRecoveryEvidenceAndMediatedAccess() {
        assertThatThrownBy(() -> descriptor(ExternalAssurance.DURABLE, ExternalAccessMode.DVARAOS_MEDIATED,
                Set.of(ExternalCapability.CHECKPOINT_EXPORT, ExternalCapability.CHECKPOINT_RESTORE)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("proven checkpoint");
        assertThatThrownBy(() -> new ExternalExecutorDescriptor("fake", "1", "test", "java", DIGEST, DIGEST,
                RemoteExecutorHttpClient.PROTOCOL_VERSION, RemoteExecutorHttpClient.SCHEMA_VERSION,
                ExternalAssurance.DURABLE, ExternalAccessMode.FRAMEWORK_DIRECT,
                Set.of(ExternalCapability.CHECKPOINT_EXPORT, ExternalCapability.CHECKPOINT_RESTORE), true))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Framework-direct");
    }

    @Test
    void productionClientRejectsLoopbackEvenWhenItUsesHttps() {
        assertThatThrownBy(() -> new RemoteExecutorHttpClient(URI.create("https://127.0.0.1"), "test-key",
                () -> "secret".toCharArray(), 4096, 10, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("private address");
    }

    private static ExternalExecutorDescriptor descriptor(ExternalAssurance assurance, ExternalAccessMode access,
            Set<ExternalCapability> capabilities) {
        return new ExternalExecutorDescriptor("fake-runner", "1.2.3", "deterministic-fake", "java-25", DIGEST,
                DIGEST, RemoteExecutorHttpClient.PROTOCOL_VERSION, RemoteExecutorHttpClient.SCHEMA_VERSION,
                assurance, access, capabilities, false);
    }

    private static ExternalInvocationRequest request() {
        ExternalInvocationContext context = new ExternalInvocationContext(UUID.fromString("10000000-0000-0000-0000-000000000001"),
                UUID.fromString("20000000-0000-0000-0000-000000000002"),
                UUID.fromString("30000000-0000-0000-0000-000000000003"),
                UUID.fromString("40000000-0000-0000-0000-000000000004"),
                UUID.fromString("50000000-0000-0000-0000-000000000005"), "stable",
                UUID.fromString("60000000-0000-0000-0000-000000000006"), "trace-1", "invoke-1");
        ExternalProtocolRequirements requirements = new ExternalProtocolRequirements(
                RemoteExecutorHttpClient.PROTOCOL_VERSION, RemoteExecutorHttpClient.SCHEMA_VERSION,
                ExternalAssurance.INSTRUMENTED, ExternalAccessMode.DVARAOS_MEDIATED,
                Set.of(ExternalCapability.EVENT_STREAMING, ExternalCapability.CANCELLATION,
                        ExternalCapability.TOOL_MEDIATION, ExternalCapability.ARTIFACT_TRANSFER));
        return new ExternalInvocationRequest(context, requirements,
                descriptor(ExternalAssurance.INSTRUMENTED, ExternalAccessMode.DVARAOS_MEDIATED,
                        Set.of(ExternalCapability.EVENT_STREAMING, ExternalCapability.CANCELLATION,
                                ExternalCapability.TOOL_MEDIATION, ExternalCapability.ARTIFACT_TRANSFER)), "hello");
    }

    private static ExternalEventEnvelope event(ExternalInvocationContext context, long sequence, String id,
            String type, String payload, boolean terminal) {
        return new ExternalEventEnvelope(context.organizationId(), context.workspaceId(), context.runId(),
                context.releaseId(), context.deploymentRevisionId(), context.cohortId(), context.routingGenerationId(),
                context.traceId(), context.invocationId(), RemoteExecutorHttpClient.PROTOCOL_VERSION,
                RemoteExecutorHttpClient.SCHEMA_VERSION, id, sequence, type, payload, terminal);
    }

    private static ExternalInteractionHandler noInteractions() {
        return new ExternalInteractionHandler() {
            @Override public String invokeTool(String request) { throw new AssertionError("unexpected tool request"); }
            @Override public String transferArtifact(String reference) { throw new AssertionError("unexpected artifact request"); }
        };
    }

    private static void assertAuth(HttpExchange exchange) {
        assertThat(exchange.getRequestHeaders().getFirst("X-Dvara-Key-Id")).isEqualTo("test-key");
        assertThat(exchange.getRequestHeaders().getFirst("X-Dvara-Signature")).isNotBlank();
    }

    private static void sendJson(HttpExchange exchange, int status, Object value) throws IOException {
        byte[] body = JSON.writeValueAsBytes(value);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length); exchange.getResponseBody().write(body); exchange.close();
    }
}
