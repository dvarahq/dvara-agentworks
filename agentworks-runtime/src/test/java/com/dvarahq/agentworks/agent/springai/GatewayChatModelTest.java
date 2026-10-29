package com.dvarahq.agentworks.agent.springai;

import com.dvarahq.agentworks.domain.lifecycle.AgentReleaseId;
import com.dvarahq.agentworks.domain.lifecycle.AgentRunId;
import com.dvarahq.agentworks.domain.security.InvocationIdentity;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import com.dvarahq.agentworks.gateway.CancellationSignal;
import com.dvarahq.agentworks.gateway.CapabilityPolicy;
import com.dvarahq.agentworks.gateway.ConfiguredGateway;
import com.dvarahq.agentworks.gateway.FinishReason;
import com.dvarahq.agentworks.gateway.GatewayErrorCode;
import com.dvarahq.agentworks.gateway.GatewayException;
import com.dvarahq.agentworks.gateway.GatewayRegistry;
import com.dvarahq.agentworks.gateway.InvocationCorrelation;
import com.dvarahq.agentworks.gateway.ModelGateway;
import com.dvarahq.agentworks.gateway.ModelMessage;
import com.dvarahq.agentworks.gateway.ModelRequest;
import com.dvarahq.agentworks.gateway.ModelRole;
import com.dvarahq.agentworks.gateway.ModelRoleBinding;
import com.dvarahq.agentworks.gateway.ModelUsage;
import com.dvarahq.agentworks.gateway.PinnedModel;
import com.dvarahq.agentworks.gateway.ResponseFormat;
import com.dvarahq.agentworks.gateway.ToolCall;
import com.dvarahq.agentworks.gateway.fake.DeterministicFakeGatewayAdapter;
import com.dvarahq.agentworks.gateway.fake.FakeReply;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import reactor.core.Disposable;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The Spring AI {@code ChatModel} bridge over Core's {@link ModelGateway} (issue #34). */
class GatewayChatModelTest {
    private final RecordingRunContext context = new RecordingRunContext();
    private final WorkspaceId workspace = new WorkspaceId(UUID.randomUUID());
    private final List<com.dvarahq.agentworks.gateway.GatewayEvent> gatewayEvents = new CopyOnWriteArrayList<>();

    private PinnedModel pinned(DeterministicFakeGatewayAdapter adapter) {
        var identity = GatewayRegistry.of(List.of(new ConfiguredGateway(adapter, CapabilityPolicy.DEFAULT, null)))
                .resolve(List.of(new ModelRoleBinding(ModelRole.REASONING, "direct", "standard")))
                .get(ModelRole.REASONING);
        return new PinnedModel(workspace, new AgentReleaseId(UUID.randomUUID()), identity);
    }

    private GatewayChatModel bridge(Function<com.dvarahq.agentworks.gateway.AdapterInvocation, FakeReply> script,
            UUID invokerWorkspace, CancellationSignal continuation, List<ToolCallback> tools) {
        var adapter = DeterministicFakeGatewayAdapter.builder("direct").model("standard", "model-a").script(script)
                .build();
        var gateway = new ModelGateway(GatewayRegistry.of(List.of(new ConfiguredGateway(adapter, CapabilityPolicy.DEFAULT,
                null))), null, null, gatewayEvents::add, Clock.systemUTC());
        var identity = new InvocationIdentity(UUID.randomUUID(), invokerWorkspace, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID());
        return new GatewayChatModel(gateway, pinned(adapter), identity,
                new InvocationCorrelation(new AgentRunId(context.pins.id()), context.pins.traceId()),
                Duration.ofSeconds(10), continuation, ResponseFormat.TEXT, new DurableRunRecorder(context, 4), tools, 4);
    }

    @Test
    void promptsToolsAndToolHistoryMapOntoTheProviderNeutralRequest() {
        ToolCallback add = MethodToolCallbackProvider.builder().toolObjects(new CalculatorTools()).build()
                .getToolCallbacks()[0];
        GatewayChatModel bridge = bridge(DeterministicFakeGatewayAdapter::echo, workspace.value(),
                CancellationSignal.create(), List.of(add));
        Prompt prompt = new Prompt(List.of(new SystemMessage("rules"), new UserMessage("add 1 and 2"),
                AssistantMessage.builder().content("").toolCalls(List.of(new AssistantMessage.ToolCall("c1", "function",
                        "add", "{\"left\":1,\"right\":2}"))).build(),
                ToolResponseMessage.builder().responses(List.of(new ToolResponseMessage.ToolResponse("c1", "add", "3")))
                        .build()), ToolCallingChatOptions.builder().toolCallbacks(List.of(add)).build());

        ModelRequest request = bridge.request(prompt);

        assertThat(request.messages()).containsExactly(ModelMessage.system("rules"), ModelMessage.user("add 1 and 2"),
                ModelMessage.assistant("", List.of(new ToolCall("c1", "add", "{\"left\":1,\"right\":2}"))),
                ModelMessage.tool("c1", "3"));
        assertThat(request.tools()).singleElement().satisfies(tool -> {
            assertThat(tool.name()).isEqualTo("add");
            assertThat(tool.parametersJsonSchema()).contains("left");
        });
        assertThat(bridge.getOptions()).isInstanceOfSatisfying(ToolCallingChatOptions.class,
                options -> assertThat(options.getToolCallbacks()).containsExactly(add));
    }

    @Test
    void responsesCarryToolCallsFinishReasonAndUsage() {
        ChatResponse response = GatewayChatModel.response(new com.dvarahq.agentworks.gateway.ModelResponse("",
                List.of(new ToolCall("c1", "add", "{}")), FinishReason.TOOL_CALLS, ModelUsage.reported(3, 4, null, null,
                        null), pinned(DeterministicFakeGatewayAdapter.builder("direct").model("standard", "m").build())
                        .identity(), new com.dvarahq.agentworks.gateway.ServedModel("m", null,
                        com.dvarahq.agentworks.gateway.ServedModel.Source.PROVIDER_REPORTED), List.of(), 0), true, "model-1");

        assertThat(response.hasToolCalls()).isTrue();
        assertThat(response.getResult().getMetadata().getFinishReason()).isEqualTo("tool_calls");
        assertThat(response.getMetadata().getUsage().getPromptTokens()).isEqualTo(3);
        assertThat(response.getMetadata().getUsage().getCompletionTokens()).isEqualTo(4);
        assertThat(GatewayChatModel.finishReason(FinishReason.STOP)).isEqualTo("stop");
        assertThat(GatewayChatModel.finishReason(FinishReason.CONTENT_FILTER)).isEqualTo("content_filter");
    }

    @Test
    void theGatewayRefusesAnInvokerWhoseWorkspaceDoesNotOwnThePinnedRelease() {
        GatewayChatModel bridge = bridge(DeterministicFakeGatewayAdapter::echo, UUID.randomUUID(),
                CancellationSignal.create(), List.of());

        assertThatThrownBy(() -> bridge.stream(new Prompt("hello")).collectList().block())
                .isInstanceOfSatisfying(GatewayException.class,
                        failure -> assertThat(failure.code()).isEqualTo(GatewayErrorCode.TENANT_MISMATCH));
        assertThat(context.types()).containsExactly("MODEL_REQUESTED", "MODEL_FAILED");
        assertThat(context.events.getLast().payload()).isInstanceOfSatisfying(java.util.Map.class,
                payload -> assertThat(payload.get("code")).isEqualTo("TENANT_MISMATCH"));
    }

    @Test
    void theStreamIsIncrementalAndDisposingItCancelsTheInvocation() throws Exception {
        CountDownLatch firstChunk = new CountDownLatch(1);
        List<String> chunks = new java.util.ArrayList<>();
        for (int index = 0; index < 200; index++) chunks.add("c" + index);
        GatewayChatModel bridge = bridge(invocation -> FakeReply.text(chunks, ModelUsage.UNREPORTED)
                .withChunkDelay(Duration.ofMillis(10)), workspace.value(), CancellationSignal.create(), List.of());
        List<String> received = new CopyOnWriteArrayList<>();

        Disposable subscription = bridge.stream(new Prompt("go")).subscribe(response -> {
            received.add(response.getResult().getOutput().getText());
            firstChunk.countDown();
        });
        // The first chunk arrives while the rest are still streaming: nothing is collected before delivery.
        assertThat(firstChunk.await(5, TimeUnit.SECONDS)).isTrue();
        subscription.dispose();

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (gatewayEvents.stream().noneMatch(e -> e instanceof com.dvarahq.agentworks.gateway.GatewayEvent.ModelInvocationFailed)
                && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(gatewayEvents).anySatisfy(event -> assertThat(event).isInstanceOfSatisfying(
                com.dvarahq.agentworks.gateway.GatewayEvent.ModelInvocationFailed.class,
                failed -> assertThat(failed.code()).isEqualTo(GatewayErrorCode.CANCELLED)));
        assertThat(received.size()).isLessThan(200);
        // A disposed invocation is not recorded as a model failure: the run's own ending is its outcome.
        assertThat(context.types()).doesNotContain("MODEL_FAILED");
    }

    @Test
    void cancellingTheContinuationCancelsItsInvocationsAndRefusesNewOnes() {
        CancellationSignal continuation = CancellationSignal.create();
        GatewayChatModel bridge = bridge(invocation -> FakeReply.hanging(), workspace.value(), continuation, List.of());
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            continuation.cancel();
        });

        assertThatThrownBy(() -> bridge.stream(new Prompt("hang")).collectList().block())
                .isInstanceOfSatisfying(GatewayException.class,
                        failure -> assertThat(failure.code()).isEqualTo(GatewayErrorCode.CANCELLED));
        assertThatThrownBy(() -> bridge.stream(new Prompt("again")).collectList().block())
                .isInstanceOfSatisfying(GatewayException.class,
                        failure -> assertThat(failure.code()).isEqualTo(GatewayErrorCode.CANCELLED));
    }

    @Test
    void modelRoundsAreBounded() {
        GatewayChatModel bridge = bridge(DeterministicFakeGatewayAdapter::echo, workspace.value(),
                CancellationSignal.create(), List.of());
        for (int round = 0; round < 4; round++) bridge.stream(new Prompt("x")).collectList().block();

        assertThatThrownBy(() -> bridge.stream(new Prompt("x")).collectList().block())
                .isInstanceOf(GatewayChatModel.ModelRoundsExceededException.class);
    }
}
