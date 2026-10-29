package com.dvarahq.agentworks.gateway.openaicompat;

import com.dvarahq.agentworks.gateway.AdapterInvocation;
import com.dvarahq.agentworks.gateway.AdapterResult;
import com.dvarahq.agentworks.gateway.AiGatewayAdapter;
import com.dvarahq.agentworks.gateway.CancellationSignal;
import com.dvarahq.agentworks.gateway.ChunkSink;
import com.dvarahq.agentworks.gateway.FinishReason;
import com.dvarahq.agentworks.gateway.GatewayCapability;
import com.dvarahq.agentworks.gateway.GatewayDescriptor;
import com.dvarahq.agentworks.gateway.GatewayErrorCode;
import com.dvarahq.agentworks.gateway.GatewayException;
import com.dvarahq.agentworks.gateway.ModelResolutionRequest;
import com.dvarahq.agentworks.gateway.ModelUsage;
import com.dvarahq.agentworks.gateway.ResolvedModel;
import com.dvarahq.agentworks.gateway.ToolCall;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.nio.charset.CharacterCodingException;
import javax.net.ssl.SSLHandshakeException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * The direct adapter for OpenAI-compatible Chat Completions endpoints: OpenAI itself and compatible servers such as
 * vLLM, llama.cpp, Ollama, LiteLLM, or a customer gateway. It uses only the JDK HTTP client and Jackson, so no provider
 * SDK type exists on either side of the SPI.
 *
 * <p>Behavior: aliases resolve offline through the configured catalog; the credential is presented as a bearer token
 * obtained just in time from Core; redirects are never followed, so the credential only reaches the configured
 * endpoint; responses are read within a byte bound; streams are Server-Sent Events whose deltas are emitted in order,
 * and a stream that ends without {@code [DONE]} is invalid; cancellation aborts the HTTP exchange. No provider body,
 * prompt, or credential enters an exception message.
 */
public final class OpenAiCompatibleGatewayAdapter implements AiGatewayAdapter {

    public static final String TYPE = "openai-compatible";
    public static final String PROTOCOL = "openai-chat-completions/v1";
    public static final String ADAPTER_VERSION = "1";

    /**
     * The most characters one SSE line, or one event's accumulated data, may hold. One event carries one small delta,
     * so a larger event is a malformed or hostile stream.
     */
    public static final int MAX_EVENT_CHARS = 1 << 20;
    /** The most tool calls one response may carry, blocking or streamed. */
    public static final int MAX_TOOL_CALLS = 128;

    private final OpenAiCompatibleGatewayConfig config;
    private final URI endpoint;
    private final GatewayDescriptor descriptor;
    private final HttpClient client;

    public OpenAiCompatibleGatewayAdapter(OpenAiCompatibleGatewayConfig config) {
        config.validate();
        this.config = config;
        this.endpoint = config.endpoint();
        this.descriptor = new GatewayDescriptor(config.gatewayId(), TYPE, ADAPTER_VERSION, PROTOCOL);
        this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(config.connectTimeout())
                .version("https".equalsIgnoreCase(config.baseUri().getScheme())
                        ? HttpClient.Version.HTTP_2 : HttpClient.Version.HTTP_1_1)
                .build();
    }

    @Override
    public GatewayDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public Set<GatewayCapability> capabilities() {
        return config.capabilities();
    }

    @Override
    public ResolvedModel resolveModel(ModelResolutionRequest request) {
        String model = config.models().get(request.alias());
        if (model == null) {
            throw new GatewayException(GatewayErrorCode.MODEL_ALIAS_UNKNOWN,
                    "alias is not in the catalog of gateway '" + config.gatewayId() + "'");
        }
        return new ResolvedModel(model, ResolvedModel.ResolutionSource.GATEWAY_ALIAS_CATALOG, null);
    }

    @Override
    public AdapterResult invoke(AdapterInvocation invocation, ChunkSink sink) {
        CancellationSignal signal = invocation.cancellation();
        signal.throwIfCancelled();
        byte[] body = ChatCompletionsProtocol.requestBody(invocation, config.outputTokenField());
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                // Backstop for the response headers; Core's deadline and cancellation bound the whole exchange.
                .timeout(invocation.timeout())
                .header("Content-Type", "application/json")
                .header("Accept", invocation.plan().stream() ? "text/event-stream" : "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (invocation.credential().present()) {
            // The header value is necessarily a String; it lives only in this request object.
            invocation.credential().withMaterial(material -> builder.header("Authorization",
                    "Bearer " + String.valueOf(material)));
        }
        HttpRequest request = builder.build();
        sink.requestSent();
        AtomicReference<InputStream> responseBody = new AtomicReference<>();
        CompletableFuture<HttpResponse<InputStream>> exchange =
                client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
        // Cancelling the exchange and closing the body release the connection. The reader thread is interrupted
        // only while it is inside transport I/O, never while it runs Core's sink and therefore the caller's listener
        // (see ReaderGuard). The abort is registered even when the plan degrades cancellation: releasing a local
        // connection is cleanup, and Core's degraded contract (LOCAL_CANCELLATION_ONLY) never relies on it.
        ReaderGuard guard = new ReaderGuard(Thread.currentThread());
        CancellationSignal.Registration abort = signal.onCancel(() -> {
            exchange.cancel(true);
            closeQuietly(responseBody.get());
            guard.interruptIfInIo();
        });
        ChunkSink guardedSink = new ChunkSink() {
            @Override
            public void requestSent() {
                sink.requestSent();
            }

            @Override
            public void emit(String delta) {
                guard.enterCallback();
                try {
                    sink.emit(delta);
                } finally {
                    guard.leaveCallback();
                }
            }
        };
        try {
            HttpResponse<InputStream> response = awaitHeaders(exchange, signal);
            responseBody.set(response.body());
            if (signal.isCancelled()) {
                closeQuietly(response.body());
                signal.throwIfCancelled();
            }
            long wireLimit = invocation.plan().stream() ? config.effectiveMaxStreamBytes() : config.maxResponseBytes();
            try (InputStream in = new BoundedInputStream(response.body(), wireLimit)) {
                rejectStatus(response);
                return invocation.plan().stream() ? readStream(in, invocation, guardedSink, config.maxResponseBytes())
                        : readCompletion(in, invocation);
            }
        } catch (IOException transport) {
            signal.throwIfCancelled();
            if (transport instanceof CharacterCodingException) {
                throw ChatCompletionsProtocol.invalid("response is not valid UTF-8");
            }
            if (transport instanceof BoundedInputStream.LimitExceeded) {
                throw ChatCompletionsProtocol.invalid("response exceeded its byte bound");
            }
            throw new GatewayException(GatewayErrorCode.PROVIDER_UNAVAILABLE,
                    "transport failed while reading the response (" + transport.getClass().getSimpleName() + ")");
        } finally {
            abort.close();
            guard.finish();
        }
    }

    /**
     * Confines the abort's interrupt to transport I/O. The reader thread also runs Core's sink, which runs the
     * caller's listener; an interrupt there would land in caller code, including a listener that cancels from inside
     * {@code onChunk}. The abort interrupts only in the {@code IO} phase, and a callback starts only after any
     * in-flight interrupt completed and was cleared.
     */
    private static final class ReaderGuard {
        private static final int IO = 0;
        private static final int CALLBACK = 1;
        private static final int INTERRUPTING = 2;
        private static final int FINISHED = 3;

        private final Thread reader;
        private final AtomicInteger phase = new AtomicInteger(IO);

        ReaderGuard(Thread reader) {
            this.reader = reader;
        }

        void interruptIfInIo() {
            if (phase.compareAndSet(IO, INTERRUPTING)) {
                reader.interrupt();
                phase.set(IO);
            }
        }

        void enterCallback() {
            while (!phase.compareAndSet(IO, CALLBACK)) {
                Thread.onSpinWait();
            }
            // An abort that interrupted the preceding I/O has completed; its interrupt must not reach the listener.
            Thread.interrupted();
        }

        void leaveCallback() {
            phase.set(IO);
        }

        void finish() {
            while (!phase.compareAndSet(IO, FINISHED)) {
                Thread.onSpinWait();
            }
            Thread.interrupted();
        }
    }

    private static HttpResponse<InputStream> awaitHeaders(CompletableFuture<HttpResponse<InputStream>> exchange,
            CancellationSignal signal) {
        try {
            return exchange.get();
        } catch (CancellationException aborted) {
            signal.throwIfCancelled();
            throw new GatewayException(GatewayErrorCode.CANCELLED, "the exchange was aborted");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            exchange.cancel(true);
            signal.throwIfCancelled();
            throw new GatewayException(GatewayErrorCode.CANCELLED, "interrupted while awaiting the provider");
        } catch (ExecutionException failed) {
            signal.throwIfCancelled();
            throw transportFailure(failed.getCause());
        }
    }

    /**
     * Maps a failure before response headers. A plain {@link HttpTimeoutException} (the request timeout) counts as
     * contacted by design, even when it fired during connect or TLS: the JDK cannot say whether request bytes were
     * written, and reporting contact is the conservative direction for retries (#85 must never re-send a turn that may
     * have been billed). Only the failure types that by definition precede any write are reported as not sent.
     *
     * <p>Details: A connection that never opened (refused, or the connect timeout, which
     * the JDK reports as a subclass of {@link HttpTimeoutException}) means the request never left: that is
     * {@code PROVIDER_UNAVAILABLE} with the request marked as not sent, never {@code TIMEOUT}. Only the exception type
     * is reported; transport messages can include host or proxy details.
     */
    static GatewayException transportFailure(Throwable cause) {
        String type = cause == null ? "unknown" : cause.getClass().getSimpleName();
        if (failedBeforeSending(cause)) {
            return GatewayException.notSent(GatewayErrorCode.PROVIDER_UNAVAILABLE,
                    "could not connect to the provider (" + type + ")");
        }
        if (cause instanceof HttpTimeoutException) {
            return new GatewayException(GatewayErrorCode.TIMEOUT, "the provider did not answer within the deadline");
        }
        return new GatewayException(GatewayErrorCode.PROVIDER_UNAVAILABLE, "transport failed (" + type + ")");
    }

    /**
     * Whether the failure, or any cause, is one that precedes writing request bytes: name resolution, the TCP connect
     * (including its timeout), or the TLS handshake. {@code requestSent()} cannot move later, because the JDK client
     * hands the request off inside {@code sendAsync} with no hook between connecting and writing, so the adapter
     * classifies these failures instead. Any other TLS or I/O failure may follow the write and stays "contacted".
     */
    private static boolean failedBeforeSending(Throwable cause) {
        for (Throwable current = cause; current != null; current = current.getCause()) {
            if (current instanceof HttpConnectTimeoutException || current instanceof ConnectException
                    || current instanceof SSLHandshakeException || current instanceof UnresolvedAddressException
                    || current instanceof UnknownHostException) {
                return true;
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return false;
    }

    private static void rejectStatus(HttpResponse<?> response) {
        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            return;
        }
        GatewayErrorCode code;
        if (status == 401 || status == 403) {
            code = GatewayErrorCode.PROVIDER_AUTHENTICATION_FAILED;
        } else if (status == 429) {
            code = GatewayErrorCode.PROVIDER_RATE_LIMITED;
        } else if (status == 408 || status >= 500) {
            code = GatewayErrorCode.PROVIDER_UNAVAILABLE;
        } else {
            code = GatewayErrorCode.PROVIDER_REJECTED_REQUEST;
        }
        String detail = "provider answered HTTP " + status
                + (status >= 300 && status < 400 ? " (redirects are not followed)" : "");
        if (status == 429 || status == 502 || status == 503 || status == 504) {
            throw GatewayException.retryable(code, detail, retryAfter(response));
        }
        throw new GatewayException(code, detail);
    }

    /** RFC 9110 Retry-After seconds or HTTP date, bounded by Core when the typed failure is constructed. */
    private static Duration retryAfter(HttpResponse<?> response) {
        String value = response.headers().firstValue("Retry-After").orElse(null);
        if (value == null) return null;
        try {
            long seconds = Long.parseLong(value.trim());
            return Duration.ofSeconds(Math.max(0, seconds));
        } catch (RuntimeException ignored) {
            try {
                Instant at = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                return Duration.between(Instant.now(), at).isNegative() ? Duration.ZERO
                        : Duration.between(Instant.now(), at);
            } catch (RuntimeException malformed) {
                return null;
            }
        }
    }

    private static AdapterResult readCompletion(InputStream in, AdapterInvocation invocation) throws IOException {
        // Parsed straight from the bounded stream through a strict UTF-8 reader: Jackson's byte-level encoding
        // detection (UTF-16, UTF-32) never applies, malformed input fails, and the body is held only once.
        JsonNode completion = ChatCompletionsProtocol.parse(
                () -> ChatCompletionsProtocol.JSON.readTree(ChatCompletionsProtocol.strictUtf8(in)), "completion");
        ChatCompletionsProtocol.rejectErrorObject(completion);
        JsonNode choices = completion.get("choices");
        if (choices == null || !choices.isArray() || choices.isEmpty()) {
            throw ChatCompletionsProtocol.invalid("completion has no choices");
        }
        JsonNode message = choices.get(0).get("message");
        if (message == null || !message.isObject()) {
            throw ChatCompletionsProtocol.invalid("completion choice has no message");
        }
        String content = ChatCompletionsProtocol.text(message.get("content"));
        ChatCompletionsProtocol.ParsedUsage usage = invocation.plan().usage()
                ? ChatCompletionsProtocol.usage(completion.get("usage")) : ChatCompletionsProtocol.ParsedUsage.NONE;
        List<ToolCall> toolCalls = invocation.plan().tools()
                ? ChatCompletionsProtocol.toolCalls(message.get("tool_calls"), MAX_TOOL_CALLS) : List.of();
        FinishReason finish = ChatCompletionsProtocol.finishReason(choices.get(0).get("finish_reason"));
        return new AdapterResult(content == null ? "" : content, toolCalls, finish == null ? FinishReason.OTHER : finish,
                usage.usage(), ChatCompletionsProtocol.identity(completion.get("model")),
                ChatCompletionsProtocol.identity(completion.get("system_fingerprint")), usage.malformed());
    }

    /**
     * Reads the SSE stream in linear time. Bounds: each line and each event's data are at most
     * {@link #MAX_EVENT_CHARS}; everything the stream retains (content, tool-call ids, names, and arguments) is at most
     * {@code retainedLimit} characters, counted as a running total; at most {@link #MAX_TOOL_CALLS} tool calls; and the
     * caller bounds the wire total at {@code max-stream-bytes}.
     */
    private static AdapterResult readStream(InputStream in, AdapterInvocation invocation, ChunkSink sink,
            long retainedLimit) throws IOException {
        SseLineReader reader = new SseLineReader(in);
        StringBuilder content = new StringBuilder();
        StringBuilder data = new StringBuilder();
        StringBuilder line = new StringBuilder();
        ChatCompletionsProtocol.ToolCallAccumulator tools = new ChatCompletionsProtocol.ToolCallAccumulator(
                MAX_TOOL_CALLS);
        StreamState state = new StreamState(retainedLimit);
        while (reader.readLine(line, MAX_EVENT_CHARS)) {
            if (line.isEmpty()) {
                if (dispatch(data, state, content, tools, invocation, sink)) {
                    break;
                }
                continue;
            }
            if (startsWith(line, "data:")) {
                if (!data.isEmpty()) {
                    data.append('\n');
                }
                // Per the SSE specification, one optional space follows the field name.
                data.append(line, startsWith(line, "data: ") ? 6 : 5, line.length());
                if (data.length() > MAX_EVENT_CHARS) {
                    throw ChatCompletionsProtocol.invalid("stream event exceeded its bound");
                }
            }
            // "event:", "id:", "retry:", and ":" comment lines carry nothing this protocol uses.
        }
        // Per the SSE specification an event without its terminating blank line is discarded, so a stream cut off
        // mid-event is incomplete. The one exception is [DONE] itself: it carries no data, and servers that end the
        // stream right after it have completed the turn.
        if (!state.done && data.toString().equals("[DONE]")) {
            state.done = true;
        }
        if (!state.done) {
            throw ChatCompletionsProtocol.invalid("stream ended before [DONE]");
        }
        List<ToolCall> toolCalls = invocation.plan().tools() ? tools.complete() : List.of();
        return new AdapterResult(content.toString(), toolCalls,
                state.finish == null ? FinishReason.OTHER : state.finish, state.usageMalformed ? null : state.usage,
                state.model, state.fingerprint, state.usageMalformed);
    }

    private static boolean startsWith(CharSequence text, String prefix) {
        if (text.length() < prefix.length()) {
            return false;
        }
        for (int index = 0; index < prefix.length(); index++) {
            if (text.charAt(index) != prefix.charAt(index)) {
                return false;
            }
        }
        return true;
    }

    /** Handles one SSE event; returns true on {@code [DONE]}. */
    private static boolean dispatch(StringBuilder data, StreamState state, StringBuilder content,
            ChatCompletionsProtocol.ToolCallAccumulator tools, AdapterInvocation invocation, ChunkSink sink)
            throws IOException {
        if (data.isEmpty()) {
            return false;
        }
        String payload = data.toString();
        data.setLength(0);
        if (payload.equals("[DONE]")) {
            state.done = true;
            return true;
        }
        JsonNode chunk = ChatCompletionsProtocol.parse(() -> ChatCompletionsProtocol.JSON.readTree(payload),
                "stream chunk");
        ChatCompletionsProtocol.rejectErrorObject(chunk);
        if (state.model == null) {
            state.model = ChatCompletionsProtocol.identity(chunk.get("model"));
        }
        if (state.fingerprint == null) {
            state.fingerprint = ChatCompletionsProtocol.identity(chunk.get("system_fingerprint"));
        }
        if (invocation.plan().usage()) {
            ChatCompletionsProtocol.ParsedUsage usage = ChatCompletionsProtocol.usage(chunk.get("usage"));
            if (usage.malformed()) {
                state.usageMalformed = true;
            } else if (usage.usage() != null) {
                state.usage = usage.usage();
            }
        }
        JsonNode choices = chunk.get("choices");
        JsonNode choice = firstChoice(choices);
        if (choice != null) {
            JsonNode delta = choice.get("delta");
            if (delta != null && delta.isObject()) {
                String text = ChatCompletionsProtocol.text(delta.get("content"));
                if (text != null && !text.isEmpty()) {
                    state.retain(text.length());
                    content.append(text);
                    sink.emit(text);
                }
                if (invocation.plan().tools()) {
                    state.retain(tools.add(delta.get("tool_calls")));
                }
            }
            FinishReason finish = ChatCompletionsProtocol.finishReason(choice.get("finish_reason"));
            if (finish != null) {
                state.finish = finish;
            }
        }
        return false;
    }

    /** The choice with index 0 (or no index); Core never requests more than one choice. */
    private static JsonNode firstChoice(JsonNode choices) {
        if (choices == null || !choices.isArray()) {
            return null;
        }
        for (JsonNode choice : choices.values()) {
            JsonNode index = choice.get("index");
            if (index == null || index.isNull() || (index.isInt() && index.intValue() == 0)) {
                return choice;
            }
        }
        return null;
    }

    private static void closeQuietly(InputStream stream) {
        if (stream != null) {
            try {
                stream.close();
            } catch (IOException ignored) {
                // Closing is the abort mechanism; a failure to close leaves nothing further to do.
            }
        }
    }

    @Override
    public String toString() {
        return "OpenAiCompatibleGatewayAdapter[" + config + "]";
    }

    private static final class StreamState {
        private final long retainedLimit;
        private long retained;

        StreamState(long retainedLimit) {
            this.retainedLimit = retainedLimit;
        }

        /** Adds to the running total of retained characters, failing once the bound is exceeded. */
        void retain(long characters) {
            retained += characters;
            if (retained > retainedLimit) {
                throw ChatCompletionsProtocol.invalid("streamed content exceeded its bound");
            }
        }

        boolean done;
        String model;
        String fingerprint;
        ModelUsage usage;
        boolean usageMalformed;
        FinishReason finish;
    }
}
