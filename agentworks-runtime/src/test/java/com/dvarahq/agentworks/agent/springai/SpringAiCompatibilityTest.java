package com.dvarahq.agentworks.agent.springai;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.core.Ordered;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spring AI compatibility suite (issue #34 AC7). It pins, as executable assertions, every Spring AI behavior the
 * executor relies on, so a dependency update that changes one fails the build. It runs in every {@code clean verify},
 * so it runs on every dependency update. When it fails after an upgrade, update the adapter (not the assertion) and
 * record the change in {@code docs/implementation/ISSUE-34-SPRING-AI-EXECUTOR.md}.
 */
class SpringAiCompatibilityTest {

    /** Streams one tool-call round and then a final answer, the way {@link GatewayChatModel} shapes its responses. */
    private static final class BridgeShapedModel implements ChatModel {
        final List<Prompt> prompts = new CopyOnWriteArrayList<>();
        private final ToolCallback tool;

        BridgeShapedModel(ToolCallback tool) {
            this.tool = tool;
        }

        @Override
        public ChatOptions getOptions() {
            return ToolCallingChatOptions.builder().toolCallbacks(List.of(tool)).build();
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            prompts.add(prompt);
            boolean answered = prompt.getInstructions().stream().anyMatch(m -> m.getMessageType() == MessageType.TOOL);
            if (answered) {
                return Flux.just(text("the "), text("sum is "), text("5"));
            }
            // Text chunks first, then one final response that carries the tool calls: the bridge's contract.
            AssistantMessage calls = AssistantMessage.builder().content("").toolCalls(List.of(
                    new AssistantMessage.ToolCall("c1", "function", "add", "{\"left\":2,\"right\":3}"))).build();
            return Flux.just(text(""), new ChatResponse(List.of(new Generation(calls))));
        }

        private static ChatResponse text(String delta) {
            return new ChatResponse(List.of(new Generation(new AssistantMessage(delta))));
        }
    }

    private static ToolCallback add() {
        return MethodToolCallbackProvider.builder().toolObjects(new CalculatorTools()).build().getToolCallbacks()[0];
    }

    @Test
    void theToolCallingAdvisorOrderTheChainAssumesIsUnchanged() {
        assertThat(ToolCallingAdvisor.DEFAULT_ORDER).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 300);
        assertThat(DvaraAdvisors.CORRELATION).isLessThan(DvaraAdvisors.TOOL_CALLING);
        assertThat(DvaraAdvisors.TOOL_CALLING).isLessThan(DvaraAdvisors.GUARD);
    }

    @Test
    void streamedToolCallsOnAFinalResponseAreExecutedAndTheModelIsCalledAgain() {
        BridgeShapedModel model = new BridgeShapedModel(add());

        List<String> content = ChatClient.builder(model)
                .defaultAdvisors(DvaraAdvisors.chain(new RecordingRunContext(),
                        com.dvarahq.agentworks.gateway.CancellationSignal.create())).build()
                .prompt().user("add").stream().content().collectList().block();

        // The tool-call response is not passed downstream; the answer's chunks are, in order.
        assertThat(String.join("", content)).isEqualTo("the sum is 5");
        assertThat(model.prompts).hasSize(2);
        assertThat(model.prompts.getLast().getInstructions()).anySatisfy(message -> {
            assertThat(message.getMessageType()).isEqualTo(MessageType.TOOL);
            assertThat(message).isInstanceOfSatisfying(org.springframework.ai.chat.messages.ToolResponseMessage.class,
                    result -> assertThat(result.getResponses().getFirst().responseData()).isEqualTo("5"));
        });
    }

    @Test
    void theModelsOptionsAreTheBaseOfEveryPromptSoItsToolsReachTheToolLoop() {
        BridgeShapedModel model = new BridgeShapedModel(add());

        ChatClient.builder(model).defaultAdvisors(DvaraAdvisors.chain(new RecordingRunContext(),
                        com.dvarahq.agentworks.gateway.CancellationSignal.create())).build()
                .prompt().user("add").stream().content().collectList().block();

        assertThat(model.prompts.getFirst().getOptions()).isInstanceOfSatisfying(ToolCallingChatOptions.class,
                options -> assertThat(options.getToolCallbacks()).extracting(tool -> tool.getToolDefinition().name())
                        .containsExactly("add"));
    }

    @Test
    void anExplicitToolAdvisorSuppressesChatClientsOwnSoOnlyOneToolLoopRuns() {
        AtomicReference<Integer> rounds = new AtomicReference<>(0);
        BridgeShapedModel model = new BridgeShapedModel(add());
        ChatModel counting = new ChatModel() {
            @Override
            public ChatOptions getOptions() {
                return model.getOptions();
            }

            @Override
            public ChatResponse call(Prompt prompt) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                rounds.updateAndGet(value -> value + 1);
                return model.stream(prompt);
            }
        };

        ChatClient.builder(counting).defaultAdvisors(DvaraAdvisors.chain(new RecordingRunContext(),
                        com.dvarahq.agentworks.gateway.CancellationSignal.create())).build()
                .prompt().user("add").stream().content().collectList().block();

        // One tool round plus the answer: a second, auto-registered loop would multiply the rounds.
        assertThat(rounds.get()).isEqualTo(2);
    }
}
