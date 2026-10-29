package com.dvarahq.agentworks.agent.springai;

import com.dvarahq.agentworks.domain.security.InvocationIdentity;
import com.dvarahq.agentworks.gateway.CancellationSignal;
import com.dvarahq.agentworks.gateway.FinishReason;
import com.dvarahq.agentworks.gateway.GatewayException;
import com.dvarahq.agentworks.gateway.InvocationCorrelation;
import com.dvarahq.agentworks.gateway.ModelGateway;
import com.dvarahq.agentworks.gateway.ModelInvocation;
import com.dvarahq.agentworks.gateway.ModelMessage;
import com.dvarahq.agentworks.gateway.ModelRequest;
import com.dvarahq.agentworks.gateway.ModelResponse;
import com.dvarahq.agentworks.gateway.PinnedModel;
import com.dvarahq.agentworks.gateway.ResponseFormat;
import com.dvarahq.agentworks.gateway.ToolCall;
import com.dvarahq.agentworks.gateway.ToolDefinition;
import com.dvarahq.agentworks.runplane.execution.RunEventCodec;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The Spring AI {@link ChatModel} of one continuation, bridged onto Core's {@link ModelGateway} (ADR-009). It is the only
 * way the executor reaches a model, so every invocation uses the release-pinned model, runs under the invoking
 * principal's identity (the gateway refuses it unless its workspace owns the release), carries the run's trace
 * correlation, and records normalized {@code MODEL_*} events as durable run events. Spring AI and Reactor types end
 * here: nothing below the gateway sees them.
 *
 * <p>Streaming is incremental: each gateway chunk is recorded and emitted as its own {@link ChatResponse} as it
 * arrives. Tool calls, the finish reason, and usage arrive in one final response. Disposing the stream (the consumer
 * stopped, or the run was cancelled) cancels the invocation, and cancelling the continuation's signal cancels every
 * invocation it made. The stream buffers what the consumer has not yet requested; the gateway bounds a stream by its
 * configured response size, so the buffer is bounded too.
 *
 * <p>An instance belongs to exactly one continuation and is created per continuation; the executor keeps none.
 */
final class GatewayChatModel implements ChatModel {
    /** Thrown when a continuation would exceed its model-round bound. */
    static final class ModelRoundsExceededException extends RuntimeException {
        ModelRoundsExceededException(int limit) {
            super("the continuation exceeded " + limit + " model invocations");
        }
    }

    /** The assistant-message property naming the model step (one model invocation) a streamed response belongs to. */
    static final String STEP = "agentworks.stepId";

    private final ModelGateway gateway;
    private final PinnedModel model;
    private final InvocationIdentity identity;
    private final InvocationCorrelation correlation;
    private final Duration timeout;
    private final CancellationSignal continuation;
    private final ResponseFormat responseFormat;
    private final DurableRunRecorder recorder;
    private final List<ToolCallback> tools;
    private final ToolCallIdentityRegistry toolCallIdentities;
    private final int maxRounds;
    private final AtomicInteger rounds = new AtomicInteger();

    GatewayChatModel(ModelGateway gateway, PinnedModel model, InvocationIdentity identity,
            InvocationCorrelation correlation, Duration timeout, CancellationSignal continuation,
            ResponseFormat responseFormat, DurableRunRecorder recorder, List<ToolCallback> tools, int maxRounds) {
        this(gateway, model, identity, correlation, timeout, continuation, responseFormat, recorder, tools, maxRounds,
                new ToolCallIdentityRegistry(correlation.runId() == null
                        ? new java.util.UUID(0, 0) : correlation.runId().value()));
    }

    GatewayChatModel(ModelGateway gateway, PinnedModel model, InvocationIdentity identity,
            InvocationCorrelation correlation, Duration timeout, CancellationSignal continuation,
            ResponseFormat responseFormat, DurableRunRecorder recorder, List<ToolCallback> tools, int maxRounds,
            ToolCallIdentityRegistry toolCallIdentities) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.model = Objects.requireNonNull(model, "model");
        this.identity = Objects.requireNonNull(identity, "identity");
        this.correlation = Objects.requireNonNull(correlation, "correlation");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.continuation = Objects.requireNonNull(continuation, "continuation");
        this.responseFormat = Objects.requireNonNull(responseFormat, "responseFormat");
        this.recorder = Objects.requireNonNull(recorder, "recorder");
        this.tools = List.copyOf(tools);
        this.maxRounds = maxRounds;
        this.toolCallIdentities = Objects.requireNonNull(toolCallIdentities, "toolCallIdentities");
    }

    /** The continuation's tools, as tool-calling options, so the tool-calling advisor runs exactly these. */
    @Override
    public ChatOptions getOptions() {
        return ToolCallingChatOptions.builder().toolCallbacks(tools).build();
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        Call call = begin(prompt);
        try {
            ModelResponse response = gateway.call(call.invocation(continuation));
            recorder.modelResponded(call.stepId, call.spanId, model, response);
            toolCallIdentities.register(call.round, response.toolCalls());
            return response(response, true, call.stepId);
        } catch (GatewayException failure) {
            if (!continuation.isCancelled()) recorder.modelFailed(call.stepId, call.spanId, model, failure);
            throw failure;
        }
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.create(sink -> {
            Call call;
            try {
                call = begin(prompt);
            } catch (RuntimeException refused) {
                sink.error(refused);
                return;
            }
            CancellationSignal signal = CancellationSignal.create();
            CancellationSignal.Registration link = continuation.onCancel(signal::cancel);
            sink.onDispose(() -> {
                signal.cancel();
                link.close();
            });
            Thread.ofVirtual().name("agentworks-model-stream").start(() -> run(call, signal, sink));
        }, FluxSink.OverflowStrategy.BUFFER);
    }

    private void run(Call call, CancellationSignal signal, FluxSink<ChatResponse> sink) {
        try {
            ModelResponse response = gateway.stream(call.invocation(signal), chunk -> {
                recorder.chunk(call.stepId, call.spanId, chunk.sequence(), chunk.delta());
                sink.next(delta(chunk.delta(), call.stepId));
            });
            recorder.modelResponded(call.stepId, call.spanId, model, response);
            toolCallIdentities.register(call.round, response.toolCalls());
            sink.next(response(response, false, call.stepId));
            sink.complete();
        } catch (GatewayException failure) {
            try {
                if (!signal.isCancelled()) recorder.modelFailed(call.stepId, call.spanId, model, failure);
            } catch (RuntimeException unrecorded) {
                failure.addSuppressed(unrecorded);
            }
            sink.error(failure);
        } catch (Throwable failure) {
            sink.error(failure);
        }
    }

    private Call begin(Prompt prompt) {
        continuation.throwIfCancelled();
        int round = rounds.incrementAndGet();
        if (round > maxRounds) throw new ModelRoundsExceededException(maxRounds);
        ModelRequest request = request(prompt);
        Call call = new Call(request, "model-" + hex(4), hex(8), round);
        recorder.modelRequested(call.stepId, call.spanId, model, round, request.messages().size(),
                request.tools().size(), request.responseFormat().kind().name(), digest(request));
        return call;
    }

    private final class Call {
        private final ModelRequest request;
        private final String stepId;
        private final String spanId;
        private final int round;

        private Call(ModelRequest request, String stepId, String spanId, int round) {
            this.request = request;
            this.stepId = stepId;
            this.spanId = spanId;
            this.round = round;
        }

        private ModelInvocation invocation(CancellationSignal signal) {
            return new ModelInvocation(model, identity, correlation, request, timeout, signal);
        }
    }

    /** Spring AI prompt → provider-neutral request; the model always comes from the pin, never from the prompt. */
    ModelRequest request(Prompt prompt) {
        List<ModelMessage> messages = new ArrayList<>();
        for (Message message : prompt.getInstructions()) {
            switch (message) {
                case SystemMessage system -> messages.add(ModelMessage.system(text(system.getText())));
                case UserMessage user -> messages.add(ModelMessage.user(text(user.getText())));
                case ToolResponseMessage results -> results.getResponses().forEach(result ->
                        messages.add(ModelMessage.tool(result.id(), text(result.responseData()))));
                case AssistantMessage assistant -> {
                    List<ToolCall> calls = assistant.getToolCalls().stream()
                            .map(call -> new ToolCall(call.id(), call.name(), call.arguments())).toList();
                    String content = assistant.getText();
                    messages.add(ModelMessage.assistant(calls.isEmpty() ? text(content) : content, calls));
                }
                default -> throw new GatewayException(com.dvarahq.agentworks.gateway.GatewayErrorCode.INVALID_REQUEST,
                        "unsupported message type " + message.getMessageType());
            }
        }
        List<ToolDefinition> definitions = new ArrayList<>();
        ChatOptions options = prompt.getOptions();
        if (options instanceof ToolCallingChatOptions toolOptions && toolOptions.getToolCallbacks() != null) {
            for (ToolCallback tool : toolOptions.getToolCallbacks()) {
                var definition = tool.getToolDefinition();
                definitions.add(new ToolDefinition(definition.name(), definition.description(), definition.inputSchema()));
            }
        }
        Integer maxTokens = options == null ? null : options.getMaxTokens();
        Double temperature = options == null ? null : options.getTemperature();
        return new ModelRequest(messages, definitions, responseFormat, maxTokens, temperature);
    }

    /**
     * Provider-neutral response → Spring AI; tool calls, finish reason, and usage travel only on a final response. Every
     * response names its model step, so a consumer can tell one tool-loop round's text from the next.
     */
    static ChatResponse response(ModelResponse response, boolean withContent, String stepId) {
        List<AssistantMessage.ToolCall> calls = response.toolCalls().stream()
                .map(call -> new AssistantMessage.ToolCall(call.id(), "function", call.name(), call.argumentsJson()))
                .toList();
        AssistantMessage message = AssistantMessage.builder().content(withContent ? response.content() : "")
                .toolCalls(calls).properties(java.util.Map.of(STEP, stepId)).build();
        ChatResponseMetadata.Builder metadata = ChatResponseMetadata.builder().model(response.served().model());
        if (response.usage().inputTokens() != null && response.usage().outputTokens() != null) {
            metadata.usage(new DefaultUsage(clamp(response.usage().inputTokens()), clamp(response.usage().outputTokens())));
        }
        return new ChatResponse(List.of(new Generation(message,
                ChatGenerationMetadata.builder().finishReason(finishReason(response.finishReason())).build())),
                metadata.build());
    }

    private static ChatResponse delta(String delta, String stepId) {
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content(delta)
                .properties(java.util.Map.of(STEP, stepId)).build())));
    }

    static String finishReason(FinishReason reason) {
        return switch (reason) {
            case TOOL_CALLS -> "tool_calls";
            case CONTENT_FILTER -> "content_filter";
            default -> reason.name().toLowerCase(Locale.ROOT);
        };
    }

    /** A digest of what was sent, so the request is evidenced without recording prompt text. */
    private static String digest(ModelRequest request) {
        StringBuilder canonical = new StringBuilder();
        for (ModelMessage message : request.messages()) {
            canonical.append(message.role()).append(':').append(message.content() == null ? -1 : message.content().length())
                    .append(':').append(message.content() == null ? "" : message.content()).append('\n');
            message.toolCalls().forEach(call -> canonical.append("call:").append(call.id()).append(':')
                    .append(call.name()).append(':').append(call.argumentsJson()).append('\n'));
            if (message.toolCallId() != null) canonical.append("result-of:").append(message.toolCallId()).append('\n');
        }
        request.tools().forEach(tool -> canonical.append("tool:").append(tool.name()).append('\n'));
        canonical.append("format:").append(request.responseFormat().kind()).append(':')
                .append(Objects.toString(request.responseFormat().jsonSchema(), ""));
        return RunEventCodec.sha256(canonical.toString());
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private static int clamp(long value) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0, value));
    }

    /** Random lowercase hex; never all zero, which trace contexts reserve as invalid. */
    private static String hex(int bytes) {
        byte[] random = new byte[bytes];
        do {
            ThreadLocalRandom.current().nextBytes(random);
        } while (java.util.Arrays.equals(random, new byte[bytes]));
        return HexFormat.of().formatHex(random);
    }
}
