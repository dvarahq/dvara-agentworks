package com.dvarahq.agentworks.gateway.openaicompat;

import com.dvarahq.agentworks.domain.lifecycle.AgentReleaseId;
import com.dvarahq.agentworks.domain.security.InvocationIdentity;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import com.dvarahq.agentworks.gateway.CapabilityPolicy;
import com.dvarahq.agentworks.gateway.ConfiguredGateway;
import com.dvarahq.agentworks.gateway.GatewayCapability;
import com.dvarahq.agentworks.gateway.GatewayErrorCode;
import com.dvarahq.agentworks.gateway.GatewayException;
import com.dvarahq.agentworks.gateway.GatewayRegistry;
import com.dvarahq.agentworks.gateway.InvocationCorrelation;
import com.dvarahq.agentworks.gateway.ModelGateway;
import com.dvarahq.agentworks.gateway.ModelInvocation;
import com.dvarahq.agentworks.gateway.ModelMessage;
import com.dvarahq.agentworks.gateway.ModelRequest;
import com.dvarahq.agentworks.gateway.ModelResponse;
import com.dvarahq.agentworks.gateway.ModelRole;
import com.dvarahq.agentworks.gateway.ModelRoleBinding;
import com.dvarahq.agentworks.gateway.ModelUsage;
import com.dvarahq.agentworks.gateway.PinnedModel;
import com.dvarahq.agentworks.gateway.PinnedModelRoles;
import com.dvarahq.agentworks.gateway.ResponseFormat;
import com.dvarahq.agentworks.gateway.ToolCall;
import com.dvarahq.agentworks.gateway.ToolDefinition;
import com.dvarahq.agentworks.gateway.conformance.ProviderScript;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.net.ServerSocket;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/** Wire-format, configuration, and resource-bound behavior specific to the OpenAI-compatible adapter. */
class OpenAiCompatibleGatewayAdapterTest {

    private final StubChatCompletionsServer server = new StubChatCompletionsServer();
    private final WorkspaceId workspace = new WorkspaceId(UUID.randomUUID());
    private final InvocationIdentity identity = new InvocationIdentity(UUID.randomUUID(), workspace.value(),
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

    @AfterEach
    void stop() {
        server.close();
    }

    @Test
    void theRequestBodyCarriesThePinnedModelMessagesToolsSchemaAndOptions() {
        server.script(ProviderScript.text(List.of("{\"ok\":true}"), ModelUsage.reported(1, 1, null, null, null)));
        ModelRequest request = new ModelRequest(List.of(ModelMessage.system("sys"), ModelMessage.user("question"),
                ModelMessage.assistant(null, List.of(new ToolCall("call_1", "lookup", "{\"q\":1}"))),
                ModelMessage.tool("call_1", "result")),
                List.of(new ToolDefinition("lookup", "Look up.", "{\"type\":\"object\"}")),
                ResponseFormat.jsonSchema("answer", "{\"type\":\"object\"}"), 256, 0.2);

        gateway(config()).stream(invocation(config(), request), chunk -> { });
        JsonNode body = server.lastBody();

        assertThat(body.path("model").asString()).isEqualTo("gpt-test-2026-09-01");
        assertThat(body.path("stream").asBoolean()).isTrue();
        assertThat(body.at("/stream_options/include_usage").asBoolean()).isTrue();
        assertThat(body.path("messages")).hasSize(4);
        assertThat(body.at("/messages/2/tool_calls/0/function/name").asString()).isEqualTo("lookup");
        assertThat(body.at("/messages/2/content").isNull()).isTrue();
        assertThat(body.at("/messages/3/tool_call_id").asString()).isEqualTo("call_1");
        assertThat(body.at("/tools/0/function/parameters/type").asString()).isEqualTo("object");
        assertThat(body.at("/response_format/json_schema/strict").asBoolean()).isTrue();
        assertThat(body.path("max_tokens").intValue()).isEqualTo(256);
        assertThat(body.path("temperature").doubleValue()).isEqualTo(0.2);
    }

    @Test
    void invalidSchemasFailBeforeTheCredentialOrNetworkIsUsed() {
        ModelRequest request = ModelRequest.of(ModelMessage.user("x"))
                .withTools(List.of(new ToolDefinition("t", "d", "{not json")));

        var failure = catchThrowableOfType(GatewayException.class,
                () -> gateway(config()).call(invocation(config(), request)));

        assertThat(failure.code()).isEqualTo(GatewayErrorCode.INVALID_REQUEST);
        assertThat(server.requests()).isZero();
    }

    @Test
    void rateLimitsCarryBoundedRetryAfterAndAreExplicitlyRetrySafe() {
        server.respondWithStatus(429, "2");

        GatewayException failure = catchThrowableOfType(GatewayException.class,
                () -> gateway(config()).call(invocation(config(), ModelRequest.of(ModelMessage.user("x")))));

        assertThat(failure.code()).isEqualTo(GatewayErrorCode.PROVIDER_RATE_LIMITED);
        assertThat(failure.retrySafe()).isTrue();
        assertThat(failure.retryAfter()).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void endpointsMustBeHttpsOrLoopbackAndCarryNoSecrets() {
        Map<String, String> models = Map.of("m", "model");

        for (String rejected : List.of("http://api.example.com/v1", "https://user:pass@api.example.com/v1",
                "https://api.example.com/v1?api_key=abc", "https://api.example.com/v1#frag", "ftp://api.example.com",
                "/relative/v1", "http://10.0.0.8/v1")) {
            assertThatThrownBy(() -> OpenAiCompatibleGatewayConfig.of("direct", URI.create(rejected), models))
                    .as(rejected).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageNotContaining("pass").hasMessageNotContaining("abc");
        }
        for (String accepted : List.of("https://api.example.com/v1", "http://127.0.0.1:8000/v1",
                "http://localhost:11434/v1", "http://[::1]:8000/v1")) {
            assertThat(OpenAiCompatibleGatewayConfig.of("direct", URI.create(accepted), models).endpoint().toString())
                    .endsWith("/v1/chat/completions");
        }
        assertThatThrownBy(() -> OpenAiCompatibleGatewayConfig.of("direct", URI.create("https://a.example/v1"), models)
                .withCapabilities(java.util.Set.of(GatewayCapability.MCP_PROXY)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void oversizedResponsesFailWithinTheByteBound() {
        server.script(ProviderScript.text(List.of("x".repeat(20_000)), null));
        OpenAiCompatibleGatewayConfig bounded = config().withMaxResponseBytes(4_096);

        var failure = catchThrowableOfType(GatewayException.class,
                () -> gateway(bounded).call(invocation(bounded, ModelRequest.of(ModelMessage.user("x")))));

        assertThat(failure.code()).isEqualTo(GatewayErrorCode.INVALID_PROVIDER_RESPONSE);
    }

    @Test
    void redirectsAreNeverFollowedSoTheCredentialStaysWithTheConfiguredEndpoint() {
        try (StubChatCompletionsServer elsewhere = new StubChatCompletionsServer()) {
            server.redirectTo(elsewhere.baseUri() + "/chat/completions");

            var failure = catchThrowableOfType(GatewayException.class,
                    () -> gateway(config()).call(invocation(config(), ModelRequest.of(ModelMessage.user("x")))));

            assertThat(failure.code()).isEqualTo(GatewayErrorCode.PROVIDER_REJECTED_REQUEST);
            assertThat(elsewhere.requests()).isZero();
        }
    }

    @Test
    void anUnreachableEndpointIsAnUnavailableProvider() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        OpenAiCompatibleGatewayConfig closed = OpenAiCompatibleGatewayConfig.of("direct",
                URI.create("http://127.0.0.1:" + port + "/v1"), Map.of("standard", "gpt-test-2026-09-01"));

        List<com.dvarahq.agentworks.gateway.GatewayEvent> events = new ArrayList<>();

        var failure = catchThrowableOfType(GatewayException.class,
                () -> gateway(closed, events).call(invocation(closed, ModelRequest.of(ModelMessage.user("x")))));

        assertThat(failure.code()).isEqualTo(GatewayErrorCode.PROVIDER_UNAVAILABLE);
        assertThat(failure.getMessage()).doesNotContain(String.valueOf(port));
        // Review 2, finding 5: a connection that never opened is not provider contact.
        assertThat(events).last().isInstanceOfSatisfying(com.dvarahq.agentworks.gateway.GatewayEvent.ModelInvocationFailed.class,
                failed -> assertThat(failed.providerContacted()).isFalse());
    }

    // Review 2, finding 5: a connect timeout is an unreachable provider whose request never left, not a TIMEOUT.
    @Test
    void connectFailuresAreUnavailableAndNotSentWhileResponseTimeoutsAreTimeouts() {
        GatewayException connectTimeout = OpenAiCompatibleGatewayAdapter.transportFailure(
                new java.net.http.HttpConnectTimeoutException("connect timed out to secret-host"));
        GatewayException refused = OpenAiCompatibleGatewayAdapter.transportFailure(
                new java.net.ConnectException("refused by secret-host"));
        GatewayException responseTimeout = OpenAiCompatibleGatewayAdapter.transportFailure(
                new java.net.http.HttpTimeoutException("request timed out"));

        assertThat(connectTimeout.code()).isEqualTo(GatewayErrorCode.PROVIDER_UNAVAILABLE);
        assertThat(connectTimeout.requestNotSent()).isTrue();
        assertThat(connectTimeout.getMessage()).doesNotContain("secret-host");
        assertThat(refused.code()).isEqualTo(GatewayErrorCode.PROVIDER_UNAVAILABLE);
        assertThat(refused.requestNotSent()).isTrue();
        assertThat(responseTimeout.code()).isEqualTo(GatewayErrorCode.TIMEOUT);
        assertThat(responseTimeout.requestNotSent()).isFalse();
    }

    @Test
    void usageIsNormalizedAndMissingUsageIsSafelyEstimated() {
        server.respondWithRawBody("{\"model\":\"m\",\"choices\":[{\"message\":{\"content\":\"hi\"},"
                + "\"finish_reason\":\"length\"}],\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":2}}");
        ModelResponse derived = gateway(config()).call(invocation(config(), ModelRequest.of(ModelMessage.user("x"))));
        server.respondWithRawBody("{\"choices\":[{\"message\":{\"content\":\"hi\"},\"finish_reason\":\"stop\"}]}");
        ModelResponse missing = gateway(config()).call(invocation(config(), ModelRequest.of(ModelMessage.user("x"))));

        assertThat(derived.usage()).isEqualTo(ModelUsage.reported(5, 2, 7L, null, null));
        assertThat(derived.finishReason()).isEqualTo(com.dvarahq.agentworks.gateway.FinishReason.LENGTH);
        assertThat(missing.usage().status()).isEqualTo(ModelUsage.UsageStatus.ESTIMATED);
        assertThat(missing.usage().inputTokens()).isPositive();
        assertThat(missing.usage().outputTokens()).isPositive();
    }

    @Test
    void providerErrorObjectsAndEmptyChoicesFailTypedWhileNegativeUsageDegrades() {
        server.respondWithRawBody("{\"error\":{\"message\":\"quota exceeded for sk-leaked\"}}");
        var declared = catchThrowableOfType(GatewayException.class,
                () -> gateway(config()).call(invocation(config(), ModelRequest.of(ModelMessage.user("x")))));
        server.respondWithRawBody("{\"choices\":[{\"message\":{\"content\":\"hi\"}}],"
                + "\"usage\":{\"prompt_tokens\":-1,\"completion_tokens\":2}}");
        // Negative counts are malformed usage: under the default policy they degrade usage, not the completion.
        ModelResponse negative = gateway(config()).call(invocation(config(), ModelRequest.of(ModelMessage.user("x"))));
        server.respondWithRawBody("{\"choices\":[]}");
        var empty = catchThrowableOfType(GatewayException.class,
                () -> gateway(config()).call(invocation(config(), ModelRequest.of(ModelMessage.user("x")))));

        assertThat(declared.code()).isEqualTo(GatewayErrorCode.PROVIDER_REJECTED_REQUEST);
        assertThat(declared.getMessage()).doesNotContain("sk-leaked");
        assertThat(negative.usage().status()).isEqualTo(ModelUsage.UsageStatus.ESTIMATED);
        assertThat(empty.code()).isEqualTo(GatewayErrorCode.INVALID_PROVIDER_RESPONSE);
    }

    @Test
    void cancellationAndDeadlinesAbortTheHttpExchange() throws Exception {
        server.script(ProviderScript.text(java.util.Collections.nCopies(60, "tick "), null)
                .withChunkDelay(Duration.ofMillis(50)));
        var signal = com.dvarahq.agentworks.gateway.CancellationSignal.create();
        ModelRequest request = ModelRequest.of(ModelMessage.user("x"));
        ModelInvocation cancellable = new ModelInvocation(invocation(config(), request).model(), identity,
                new InvocationCorrelation(null, "trace"), request, Duration.ofSeconds(10), signal);
        ModelInvocation shortDeadline = new ModelInvocation(invocation(config(), request).model(), identity,
                new InvocationCorrelation(null, "trace"), request, Duration.ofMillis(300), null);

        var cancelled = catchThrowableOfType(GatewayException.class,
                () -> gateway(config()).stream(cancellable, chunk -> signal.cancel()));
        awaitDisconnects(1);
        var timedOut = catchThrowableOfType(GatewayException.class,
                () -> gateway(config()).stream(shortDeadline, chunk -> { }));
        awaitDisconnects(2);

        assertThat(cancelled.code()).isEqualTo(GatewayErrorCode.CANCELLED);
        assertThat(timedOut.code()).isEqualTo(GatewayErrorCode.PARTIAL_STREAM);
        assertThat(server.disconnects()).isEqualTo(2);
    }

    // Review 2, finding 4: a long stream is bounded by its content, not by SSE wire bytes.
    @Test
    void longStreamsAreBoundedByContentNotByWireBytes() {
        server.script(ProviderScript.text(java.util.Collections.nCopies(200, "tick "), null));
        OpenAiCompatibleGatewayConfig bounded = config().withMaxResponseBytes(4_096);

        ModelResponse response = gateway(bounded).stream(invocation(bounded, ModelRequest.of(ModelMessage.user("x"))),
                chunk -> { });

        assertThat(response.content()).hasSize(1_000);
    }

    @Test
    void anOversizedStreamEventFailsWithinTheEventBound() {
        server.respondWithRawBody("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\""
                + "z".repeat(OpenAiCompatibleGatewayAdapter.MAX_EVENT_CHARS + 10) + "\"}}]}\n\ndata: [DONE]\n\n");
        OpenAiCompatibleGatewayConfig roomy = config().withMaxResponseBytes(64L * 1024 * 1024);

        var failure = catchThrowableOfType(GatewayException.class, () -> gateway(roomy)
                .stream(invocation(roomy, ModelRequest.of(ModelMessage.user("x"))), chunk -> { }));

        assertThat(failure.code()).isEqualTo(GatewayErrorCode.INVALID_PROVIDER_RESPONSE);
    }

    // Review 3, finding 2: CR line endings dispatch an event without waiting for the provider's next byte.
    @Test
    void crLineEndingsDispatchEventsWithoutWaitingForTheNextByte() {
        String chunk = "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"%s\"}}]}\r\r";
        server.respondWithRawEvents(List.of(chunk.formatted("first"), chunk.formatted("second"), "data: [DONE]\r\r"),
                Duration.ofMillis(800));
        long started = System.nanoTime();
        List<Long> arrivals = new ArrayList<>();

        ModelResponse response = gateway(config()).stream(invocation(config(), ModelRequest.of(ModelMessage.user("x"))),
                chunk2 -> arrivals.add(System.nanoTime() - started));

        assertThat(response.content()).isEqualTo("firstsecond");
        assertThat(Duration.ofNanos(arrivals.getFirst())).isLessThan(Duration.ofMillis(600));
    }

    // Review 3, finding 2: retained tool-call state is bounded, including the number of tool-call indices.
    @Test
    void theNumberOfStreamedToolCallsIsBounded() {
        StringBuilder stream = new StringBuilder();
        for (int index = 0; index <= OpenAiCompatibleGatewayAdapter.MAX_TOOL_CALLS; index++) {
            stream.append("data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":").append(index)
                    .append(",\"id\":\"c").append(index).append("\",\"function\":{\"name\":\"t\",\"arguments\":\"{}\"}}]}}]}\n\n");
        }
        server.respondWithRawBody(stream + "data: [DONE]\n\n");
        ModelRequest request = ModelRequest.of(ModelMessage.user("x"))
                .withTools(List.of(new ToolDefinition("t", "d", "{\"type\":\"object\"}")));

        var failure = catchThrowableOfType(GatewayException.class,
                () -> gateway(config()).stream(invocation(config(), request), chunk -> { }));

        assertThat(failure.code()).isEqualTo(GatewayErrorCode.INVALID_PROVIDER_RESPONSE);
    }

    // Review 3, finding 4: malformed UTF-8 from the provider is an invalid response, never silently replaced.
    @Test
    void malformedUtf8IsAnInvalidProviderResponse() {
        byte[] prefix = "{\"choices\":[{\"message\":{\"content\":\"bad ".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] suffix = "\"}}]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] body = new byte[prefix.length + 2 + suffix.length];
        System.arraycopy(prefix, 0, body, 0, prefix.length);
        body[prefix.length] = (byte) 0xC3;
        body[prefix.length + 1] = (byte) 0x28;
        System.arraycopy(suffix, 0, body, prefix.length + 2, suffix.length);
        byte[] streamPrefix = "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"bad ".getBytes(
                java.nio.charset.StandardCharsets.UTF_8);
        byte[] streamSuffix = "\"}}]}\n\ndata: [DONE]\n\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] stream = new byte[streamPrefix.length + 2 + streamSuffix.length];
        System.arraycopy(streamPrefix, 0, stream, 0, streamPrefix.length);
        stream[streamPrefix.length] = (byte) 0xC3;
        stream[streamPrefix.length + 1] = (byte) 0x28;
        System.arraycopy(streamSuffix, 0, stream, streamPrefix.length + 2, streamSuffix.length);

        server.respondWithRawBytes(body);
        var blocking = catchThrowableOfType(GatewayException.class,
                () -> gateway(config()).call(invocation(config(), ModelRequest.of(ModelMessage.user("x")))));
        server.respondWithRawBytes(stream);
        var streamed = catchThrowableOfType(GatewayException.class, () -> gateway(config())
                .stream(invocation(config(), ModelRequest.of(ModelMessage.user("x"))), chunk -> { }));

        assertThat(blocking).isNotNull();
        assertThat(blocking.code()).isEqualTo(GatewayErrorCode.INVALID_PROVIDER_RESPONSE);
        assertThat(streamed).isNotNull();
        assertThat(streamed.code()).isEqualTo(GatewayErrorCode.INVALID_PROVIDER_RESPONSE);
    }

    // Review 4, item 4: a blocking body is strict UTF-8; UTF-16 and UTF-32 bodies are never auto-detected.
    @Test
    void nonUtf8EncodingsOfABlockingBodyAreInvalidResponses() {
        String json = "{\"choices\":[{\"message\":{\"content\":\"hi\"}}]}";
        byte[] utf16 = json.getBytes(java.nio.charset.StandardCharsets.UTF_16LE);
        byte[] badUtf32 = new byte[]{0, 0, 0, '{', 0, 0, 0, '"', (byte) 0x7F, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF};

        server.respondWithRawBytes(utf16);
        var sixteen = catchThrowableOfType(GatewayException.class,
                () -> gateway(config()).call(invocation(config(), ModelRequest.of(ModelMessage.user("x")))));
        server.respondWithRawBytes(badUtf32);
        var thirtyTwo = catchThrowableOfType(GatewayException.class,
                () -> gateway(config()).call(invocation(config(), ModelRequest.of(ModelMessage.user("x")))));

        assertThat(sixteen).isNotNull();
        assertThat(sixteen.code()).isEqualTo(GatewayErrorCode.INVALID_PROVIDER_RESPONSE);
        assertThat(thirtyTwo.code()).isEqualTo(GatewayErrorCode.INVALID_PROVIDER_RESPONSE);
    }

    // Review 5, item 1: a UTF-8 byte-order mark is valid UTF-8 and is skipped, in blocking and streamed bodies.
    @Test
    void aUtf8ByteOrderMarkIsAcceptedInBlockingAndStreamedBodies() {
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] json = "{\"choices\":[{\"message\":{\"content\":\"hi\"}}]}".getBytes(
                java.nio.charset.StandardCharsets.UTF_8);
        byte[] events = ("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hi\"}}]}\n\n"
                + "data: [DONE]\n\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);

        server.respondWithRawBytes(concat(bom, json));
        ModelResponse blocking = gateway(config()).call(invocation(config(), ModelRequest.of(ModelMessage.user("x"))));
        server.respondWithRawBytes(concat(bom, events));
        ModelResponse streamed = gateway(config()).stream(invocation(config(), ModelRequest.of(ModelMessage.user("x"))),
                chunk -> { });

        assertThat(blocking.content()).isEqualTo("hi");
        assertThat(streamed.content()).isEqualTo("hi");
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] joined = java.util.Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, joined, first.length, second.length);
        return joined;
    }

    // Review 5, item 4: an unset stream cap follows the content bound; withers are order independent.
    @Test
    void anUnsetStreamCapFollowsTheContentBoundAndWithersAreOrderIndependent() {
        OpenAiCompatibleGatewayConfig large = config().withMaxResponseBytes(100L * 1024 * 1024);
        OpenAiCompatibleGatewayConfig lowered = config().withMaxStreamBytes(4_096).withMaxResponseBytes(2_048);

        assertThat(new OpenAiCompatibleGatewayAdapter(large)).isNotNull();
        assertThat(large.effectiveMaxStreamBytes()).isEqualTo(800L * 1024 * 1024);
        assertThat(config().effectiveMaxStreamBytes()).isEqualTo(OpenAiCompatibleGatewayConfig.DEFAULT_MAX_STREAM_BYTES);
        assertThat(new OpenAiCompatibleGatewayAdapter(lowered)).isNotNull();
        assertThat(lowered.effectiveMaxStreamBytes()).isEqualTo(4_096);
    }

    // #174 medium 1: an explicit zero stream cap is rejected rather than read as "unset".
    @Test
    void anExplicitZeroStreamCapIsRejected() {
        assertThatThrownBy(() -> config().withMaxStreamBytes(0)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxStreamBytes must be positive");
        assertThatThrownBy(() -> config().withMaxStreamBytes(-2)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxStreamBytes must be positive");
        assertThat(config().withMaxStreamBytes(OpenAiCompatibleGatewayConfig.UNSET).effectiveMaxStreamBytes())
                .isEqualTo(OpenAiCompatibleGatewayConfig.DEFAULT_MAX_STREAM_BYTES);
    }

    // Review 4, item 5: the tool-call cap applies to blocking responses as well.
    @Test
    void theNumberOfBlockingToolCallsIsBounded() {
        StringBuilder calls = new StringBuilder();
        for (int index = 0; index <= OpenAiCompatibleGatewayAdapter.MAX_TOOL_CALLS; index++) {
            calls.append(index == 0 ? "" : ",").append("{\"id\":\"c").append(index)
                    .append("\",\"type\":\"function\",\"function\":{\"name\":\"t\",\"arguments\":\"{}\"}}");
        }
        server.respondWithRawBody("{\"choices\":[{\"message\":{\"content\":null,\"tool_calls\":[" + calls
                + "]},\"finish_reason\":\"tool_calls\"}]}");
        ModelRequest request = ModelRequest.of(ModelMessage.user("x"))
                .withTools(List.of(new ToolDefinition("t", "d", "{\"type\":\"object\"}")));

        var failure = catchThrowableOfType(GatewayException.class,
                () -> gateway(config()).call(invocation(config(), request)));

        assertThat(failure).isNotNull();
        assertThat(failure.code()).isEqualTo(GatewayErrorCode.INVALID_PROVIDER_RESPONSE);
        assertThat(failure.getMessage()).contains("more than " + OpenAiCompatibleGatewayAdapter.MAX_TOOL_CALLS);
    }

    // Review 4, item 7: the stream wire cap may not be below the retained-content bound.
    @Test
    void aStreamCapBelowTheContentBoundIsRejected() {
        for (OpenAiCompatibleGatewayConfig inconsistent : List.of(
                config().withMaxResponseBytes(8_192).withMaxStreamBytes(4_096),
                config().withMaxStreamBytes(4_096).withMaxResponseBytes(8_192))) {
            assertThatThrownBy(() -> new OpenAiCompatibleGatewayAdapter(inconsistent))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("maxStreamBytes").hasMessageContaining("maxResponseBytes");
        }
    }

    // Review 3, finding 6: a TLS handshake that fails never carried the request.
    @Test
    void aFailedTlsHandshakeIsUnavailableAndNotContacted() throws Exception {
        // A peer that accepts TCP and closes at once: the JDK reports the failed handshake as SSLHandshakeException.
        try (java.net.ServerSocket peer = new java.net.ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress())) {
            Thread.ofVirtual().start(() -> {
                while (!peer.isClosed()) {
                    try (java.net.Socket accepted = peer.accept()) {
                        accepted.setSoLinger(true, 0);
                    } catch (java.io.IOException closed) {
                        return;
                    }
                }
            });
            OpenAiCompatibleGatewayConfig tls = OpenAiCompatibleGatewayConfig.of("direct",
                    URI.create("https://127.0.0.1:" + peer.getLocalPort() + "/v1"), Map.of("standard", "m"));
            List<com.dvarahq.agentworks.gateway.GatewayEvent> events = new ArrayList<>();

            var failure = catchThrowableOfType(GatewayException.class, () -> gateway(tls, events)
                    .call(invocation(tls, ModelRequest.of(ModelMessage.user("x")))));

            assertThat(failure.code()).isEqualTo(GatewayErrorCode.PROVIDER_UNAVAILABLE);
            assertThat(events).last()
                    .isInstanceOfSatisfying(com.dvarahq.agentworks.gateway.GatewayEvent.ModelInvocationFailed.class,
                            failed -> assertThat(failed.providerContacted()).isFalse());
        }
        GatewayException handshake = OpenAiCompatibleGatewayAdapter.transportFailure(
                new javax.net.ssl.SSLHandshakeException("handshake failed"));
        GatewayException unresolved = OpenAiCompatibleGatewayAdapter.transportFailure(new java.io.IOException(
                new java.nio.channels.UnresolvedAddressException()));
        GatewayException afterHandshake = OpenAiCompatibleGatewayAdapter.transportFailure(
                new javax.net.ssl.SSLException("bad record MAC"));

        assertThat(handshake.requestNotSent()).isTrue();
        assertThat(unresolved.requestNotSent()).isTrue();
        assertThat(afterHandshake.requestNotSent()).isFalse();
    }

    // Review 3, finding 2: one configurable wire cap bounds a whole stream.
    @Test
    void streamsBeyondTheConfiguredWireCapFail() {
        // Pace the loopback response so the client deterministically delivers a chunk before reaching the wire cap.
        // Without pacing, a fast server can fill one socket read beyond the cap before the SSE parser sees event one.
        server.script(ProviderScript.text(java.util.Collections.nCopies(200, "tick "), null)
                .withChunkDelay(Duration.ofMillis(1)));
        OpenAiCompatibleGatewayConfig capped = config().withMaxResponseBytes(2_048).withMaxStreamBytes(4_096);

        var failure = catchThrowableOfType(GatewayException.class, () -> gateway(capped)
                .stream(invocation(capped, ModelRequest.of(ModelMessage.user("x"))), chunk -> { }));

        assertThat(failure.code()).isEqualTo(GatewayErrorCode.PARTIAL_STREAM);
        assertThat(OpenAiCompatibleGatewayConfig.of("direct", URI.create("https://a.example/v1"), Map.of())
                .effectiveMaxStreamBytes()).isEqualTo(64L * 1024 * 1024);
    }

    @Test
    void oversizedStreamsFailWithinTheByteBound() {
        server.script(ProviderScript.text(java.util.Collections.nCopies(200, "y".repeat(100)), null));
        OpenAiCompatibleGatewayConfig bounded = config().withMaxResponseBytes(4_096);

        var failure = catchThrowableOfType(GatewayException.class, () -> gateway(bounded)
                .stream(invocation(bounded, ModelRequest.of(ModelMessage.user("x"))), chunk -> { }));

        assertThat(failure.code()).isEqualTo(GatewayErrorCode.PARTIAL_STREAM);
    }

    @Test
    void blankReportedModelsAreUnreportedAndOversizedOnesAreInvalid() {
        server.respondWithRawBody("{\"model\":\"  \",\"choices\":[{\"message\":{\"content\":\"hi\"}}]}");
        ModelResponse blank = gateway(config()).call(invocation(config(), ModelRequest.of(ModelMessage.user("x"))));
        server.respondWithRawBody("{\"model\":\"" + "m".repeat(300) + "\",\"choices\":[{\"message\":{\"content\":\"hi\"}}]}");
        var oversized = catchThrowableOfType(GatewayException.class,
                () -> gateway(config()).call(invocation(config(), ModelRequest.of(ModelMessage.user("x")))));

        assertThat(blank.served().source()).isEqualTo(com.dvarahq.agentworks.gateway.ServedModel.Source.PINNED);
        assertThat(oversized.code()).isEqualTo(GatewayErrorCode.INVALID_PROVIDER_RESPONSE);
    }

    // Review finding 1: the abort must never interrupt the thread while it runs listener code.
    @Test
    void cancellingFromInsideTheListenerNeverInterruptsListenerCode() {
        server.script(ProviderScript.text(java.util.Collections.nCopies(20, "tick "), null)
                .withChunkDelay(Duration.ofMillis(20)));
        var signal = com.dvarahq.agentworks.gateway.CancellationSignal.create();
        var interruptedInListener = new java.util.concurrent.atomic.AtomicBoolean();
        var sleepInterrupted = new java.util.concurrent.atomic.AtomicBoolean();
        ModelRequest request = ModelRequest.of(ModelMessage.user("x"));
        ModelInvocation cancellable = new ModelInvocation(invocation(config(), request).model(), identity,
                new InvocationCorrelation(null, "trace"), request, Duration.ofSeconds(10), signal);

        var failure = catchThrowableOfType(GatewayException.class, () -> gateway(config()).stream(cancellable, chunk -> {
            signal.cancel();
            interruptedInListener.set(Thread.currentThread().isInterrupted());
            try {
                Thread.sleep(20);
            } catch (InterruptedException interrupted) {
                sleepInterrupted.set(true);
            }
        }));

        assertThat(failure.code()).isEqualTo(GatewayErrorCode.CANCELLED);
        assertThat(interruptedInListener).isFalse();
        assertThat(sleepInterrupted).isFalse();
    }

    @Test
    void cancellingFromAnotherThreadNeverInterruptsARunningListener() throws Exception {
        server.script(ProviderScript.text(java.util.Collections.nCopies(20, "tock "), null)
                .withChunkDelay(Duration.ofMillis(20)));
        var signal = com.dvarahq.agentworks.gateway.CancellationSignal.create();
        var entered = new java.util.concurrent.CountDownLatch(1);
        var sleepInterrupted = new java.util.concurrent.atomic.AtomicBoolean();
        ModelRequest request = ModelRequest.of(ModelMessage.user("x"));
        ModelInvocation cancellable = new ModelInvocation(invocation(config(), request).model(), identity,
                new InvocationCorrelation(null, "trace"), request, Duration.ofSeconds(10), signal);
        Thread.ofVirtual().start(() -> {
            try {
                entered.await();
                Thread.sleep(50);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            signal.cancel();
        });

        var failure = catchThrowableOfType(GatewayException.class, () -> gateway(config()).stream(cancellable, chunk -> {
            entered.countDown();
            try {
                Thread.sleep(300);
            } catch (InterruptedException interrupted) {
                sleepInterrupted.set(true);
            }
        }));

        assertThat(failure.code()).isEqualTo(GatewayErrorCode.CANCELLED);
        assertThat(sleepInterrupted).isFalse();
    }

    // Review finding 6: [DONE] at end of stream without a trailing blank line completes the turn.
    @Test
    void doneAtEndOfStreamWithoutATrailingBlankLineCompletesTheTurn() {
        server.respondWithRawBody("data: {\"model\":\"m\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hi\"},"
                + "\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]");
        List<String> deltas = new ArrayList<>();

        ModelResponse response = gateway(config()).stream(invocation(config(), ModelRequest.of(ModelMessage.user("x"))),
                chunk -> deltas.add(chunk.delta()));

        assertThat(response.content()).isEqualTo("hi");
        assertThat(deltas).containsExactly("hi");
    }

    @Test
    void anUnterminatedNonDoneEventAtEndOfStreamIsStillIncomplete() {
        server.respondWithRawBody("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hi\"}}]}");

        var failure = catchThrowableOfType(GatewayException.class, () -> gateway(config())
                .stream(invocation(config(), ModelRequest.of(ModelMessage.user("x"))), chunk -> { }));

        assertThat(failure.code()).isEqualTo(GatewayErrorCode.INVALID_PROVIDER_RESPONSE);
    }

    // Review finding 9: malformed usage degrades usage by default and fails typed only when usage is required.
    @Test
    void malformedUsageDegradesByDefaultAndFailsTypedWhenRequired() {
        String nonInteger = "{\"choices\":[{\"message\":{\"content\":\"hi\"}}],"
                + "\"usage\":{\"prompt_tokens\":\"abc\",\"completion_tokens\":2}}";
        String overflow = "{\"choices\":[{\"message\":{\"content\":\"hi\"}}],"
                + "\"usage\":{\"prompt_tokens\":9223372036854775807,\"completion_tokens\":1}}";
        List<com.dvarahq.agentworks.gateway.GatewayEvent> events = new ArrayList<>();
        CapabilityPolicy usageRequired = CapabilityPolicy.withOverrides(java.util.Map.of(GatewayCapability.USAGE,
                com.dvarahq.agentworks.gateway.CapabilityMode.REQUIRED));

        server.respondWithRawBody(nonInteger);
        ModelResponse degraded = gateway(config(), events).call(invocation(config(), ModelRequest.of(ModelMessage.user("x"))));
        server.respondWithRawBody(overflow);
        ModelResponse overflowed = gateway(config(), events).call(invocation(config(),
                ModelRequest.of(ModelMessage.user("x"))));
        var required = catchThrowableOfType(GatewayException.class, () -> gateway(config(), events)
                .call(invocation(config(), ModelRequest.of(ModelMessage.user("x")), usageRequired)));

        assertThat(degraded.content()).isEqualTo("hi");
        assertThat(degraded.usage().status()).isEqualTo(ModelUsage.UsageStatus.ESTIMATED);
        assertThat(degraded.degradations()).containsExactly(com.dvarahq.agentworks.gateway.DegradedBehavior.USAGE_UNREPORTED);
        assertThat(overflowed.usage().status()).isEqualTo(ModelUsage.UsageStatus.ESTIMATED);
        assertThat(required.code()).isEqualTo(GatewayErrorCode.INVALID_PROVIDER_RESPONSE);
        assertThat(events).filteredOn(com.dvarahq.agentworks.gateway.GatewayEvent.CapabilityDegraded.class::isInstance)
                .hasSize(2);
    }

    // Review finding 4: the output-token field is chosen per gateway.
    @Test
    void theOutputTokenFieldFollowsTheGatewayConfiguration() {
        server.script(ProviderScript.text(List.of("ok"), null));
        ModelRequest bounded = new ModelRequest(List.of(ModelMessage.user("x")), List.of(), null, 64, null);
        OpenAiCompatibleGatewayConfig completion = config()
                .withOutputTokenField(OpenAiCompatibleGatewayConfig.OutputTokenField.MAX_COMPLETION_TOKENS);

        gateway(config()).call(invocation(config(), bounded));
        JsonNode defaultBody = server.lastBody();
        gateway(completion).call(invocation(completion, bounded));
        JsonNode completionBody = server.lastBody();

        assertThat(defaultBody.path("max_tokens").intValue()).isEqualTo(64);
        assertThat(defaultBody.has("max_completion_tokens")).isFalse();
        assertThat(completionBody.path("max_completion_tokens").intValue()).isEqualTo(64);
        assertThat(completionBody.has("max_tokens")).isFalse();
        assertThat(OpenAiCompatibleGatewayConfig.of("direct", URI.create("https://api.openai.com/v1"), Map.of())
                .outputTokenField()).isEqualTo(OpenAiCompatibleGatewayConfig.OutputTokenField.MAX_COMPLETION_TOKENS);
        assertThat(OpenAiCompatibleGatewayConfig.of("direct", URI.create("https://llm.example.com/v1"), Map.of())
                .outputTokenField()).isEqualTo(OpenAiCompatibleGatewayConfig.OutputTokenField.MAX_TOKENS);
    }

    // Review finding 5: strict schema enforcement is the caller's choice per request.
    @Test
    void strictSchemaEnforcementFollowsTheRequest() {
        server.script(ProviderScript.text(List.of("{}"), null));
        ModelRequest strict = ModelRequest.of(ModelMessage.user("x"))
                .withResponseFormat(ResponseFormat.jsonSchema("answer", "{\"type\":\"object\"}"));
        ModelRequest lenient = ModelRequest.of(ModelMessage.user("x"))
                .withResponseFormat(ResponseFormat.jsonSchema("answer", "{\"type\":\"object\"}", false));

        gateway(config()).call(invocation(config(), strict));
        boolean strictSent = server.lastBody().at("/response_format/json_schema/strict").asBoolean();
        gateway(config()).call(invocation(config(), lenient));

        assertThat(strictSent).isTrue();
        assertThat(server.lastBody().at("/response_format/json_schema/strict").asBoolean(true)).isFalse();
    }

    private void awaitDisconnects(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (server.disconnects() < expected && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(server.disconnects()).as("the stub observed the client abort").isGreaterThanOrEqualTo(expected);
    }

    @Test
    void aGatewayWithoutCredentialSendsNoAuthorizationHeader() {
        server.script(ProviderScript.text(List.of("local"), null));
        List<Object> events = new ArrayList<>();
        GatewayRegistry registry = GatewayRegistry.of(List.of(new ConfiguredGateway(
                new OpenAiCompatibleGatewayAdapter(config()), CapabilityPolicy.DEFAULT, null)));

        ModelResponse response = new ModelGateway(registry, null, null, events::add, Clock.systemUTC())
                .call(invocation(config(), ModelRequest.of(ModelMessage.user("x"))));

        assertThat(response.content()).isEqualTo("local");
        assertThat(server.lastCredentialDigest()).isNull();
    }

    private OpenAiCompatibleGatewayConfig config() {
        return OpenAiCompatibleGatewayConfig.of("direct", server.baseUri(), Map.of("standard", "gpt-test-2026-09-01"));
    }

    private ModelGateway gateway(OpenAiCompatibleGatewayConfig config) {
        return gateway(config, new ArrayList<>());
    }

    private ModelGateway gateway(OpenAiCompatibleGatewayConfig config,
            List<com.dvarahq.agentworks.gateway.GatewayEvent> events) {
        GatewayRegistry registry = GatewayRegistry.of(List.of(new ConfiguredGateway(
                new OpenAiCompatibleGatewayAdapter(config), CapabilityPolicy.DEFAULT, null)));
        return new ModelGateway(registry, null, null, events::add, Clock.systemUTC());
    }

    private ModelInvocation invocation(OpenAiCompatibleGatewayConfig config, ModelRequest request) {
        return invocation(config, request, CapabilityPolicy.DEFAULT);
    }

    /** The pin captures {@code policy}, as a release would. */
    private ModelInvocation invocation(OpenAiCompatibleGatewayConfig config, ModelRequest request,
            CapabilityPolicy policy) {
        GatewayRegistry registry = GatewayRegistry.of(List.of(new ConfiguredGateway(
                new OpenAiCompatibleGatewayAdapter(config), policy, null)));
        PinnedModel pinned = PinnedModelRoles.of(workspace, new AgentReleaseId(UUID.randomUUID()), registry.resolve(
                List.of(new ModelRoleBinding(ModelRole.REASONING, "direct", "standard")))).role(ModelRole.REASONING);
        return new ModelInvocation(pinned, identity, new InvocationCorrelation(null, "trace"), request,
                Duration.ofSeconds(10), null);
    }
}
