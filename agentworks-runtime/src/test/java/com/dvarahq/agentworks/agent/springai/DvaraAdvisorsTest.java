package com.dvarahq.agentworks.agent.springai;

import com.dvarahq.agentworks.gateway.CancellationSignal;
import com.dvarahq.agentworks.gateway.GatewayErrorCode;
import com.dvarahq.agentworks.gateway.GatewayException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.client.advisor.api.MemoryAdvisor;
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
import org.springframework.core.OrderComparator;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Issue #34 AC3: the advisor order is explicit, and regression-tested as declared and as executed. */
class DvaraAdvisorsTest {
    private final RecordingRunContext context = new RecordingRunContext();

    @Test
    void theChainIsCorrelationThenToolCallingThenTheGuardAndHasNoMemoryAdvisor() {
        List<Advisor> chain = DvaraAdvisors.chain(context, CancellationSignal.create());

        assertThat(chain).extracting(Advisor::getOrder).containsExactly(DvaraAdvisors.CORRELATION,
                DvaraAdvisors.TOOL_CALLING, DvaraAdvisors.GUARD).isSorted();
        assertThat(chain.get(0)).isInstanceOf(DvaraAdvisors.RunCorrelationAdvisor.class);
        assertThat(chain.get(1)).isInstanceOf(ToolCallingAdvisor.class);
        assertThat(chain.get(2)).isInstanceOf(DvaraAdvisors.RunGuardAdvisor.class);
        assertThat(chain).noneMatch(advisor -> advisor instanceof MemoryAdvisor);
        List<Advisor> sorted = new ArrayList<>(chain);
        java.util.Collections.reverse(sorted);
        OrderComparator.sort(sorted);
        assertThat(sorted).isEqualTo(chain);
    }

    /** A model with one tool round: the guard must run before each round, after correlation, inside the tool loop. */
    private static final class TwoRoundModel implements ChatModel {
        private final List<String> trace;
        private final ToolCallback tool;

        TwoRoundModel(List<String> trace, ToolCallback tool) {
            this.trace = trace;
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
            boolean answered = prompt.getInstructions().stream().anyMatch(m -> m.getMessageType() == MessageType.TOOL);
            trace.add(answered ? "model:final" : "model:tool-call");
            AssistantMessage message = answered ? new AssistantMessage("done") : AssistantMessage.builder().content("")
                    .toolCalls(List.of(new AssistantMessage.ToolCall("c1", "function", "add", "{\"left\":1,\"right\":2}")))
                    .build();
            return Flux.just(new ChatResponse(List.of(new Generation(message))));
        }
    }

    private static BaseAdvisor probe(List<String> trace, String name, int order) {
        return new BaseAdvisor() {
            @Override
            public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
                trace.add(name + (request.context().containsKey(DvaraAdvisors.RUN_ID) ? "+correlated" : ""));
                return request;
            }

            @Override
            public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
                return response;
            }

            @Override
            public int getOrder() {
                return order;
            }

            @Override
            public String getName() {
                return name;
            }
        };
    }

    @Test
    void asExecutedCorrelationRunsOnceThenTheGuardBeforeEveryModelRound() {
        List<String> trace = new CopyOnWriteArrayList<>();
        ToolCallback add = MethodToolCallbackProvider.builder().toolObjects(new CalculatorTools()).build()
                .getToolCallbacks()[0];
        List<Advisor> advisors = new ArrayList<>(DvaraAdvisors.chain(context, CancellationSignal.create()));
        // Probes just outside correlation and just inside the guard observe the effective order.
        advisors.add(probe(trace, "outer", DvaraAdvisors.CORRELATION - 1));
        advisors.add(probe(trace, "inner", DvaraAdvisors.GUARD + 1));

        List<String> content = ChatClient.builder(new TwoRoundModel(trace, add)).defaultAdvisors(advisors).build()
                .prompt().user("add").stream().content().collectList().block();

        assertThat(String.join("", content)).isEqualTo("done");
        assertThat(trace).containsExactly("outer", "inner+correlated", "model:tool-call", "inner+correlated",
                "model:final");
    }

    @Test
    void theGuardStopsAContinuationThatIsCancelledOrSuspended() {
        CancellationSignal signal = CancellationSignal.create();
        var guard = new DvaraAdvisors.RunGuardAdvisor(context, signal);
        var correlated = ChatClientRequest.builder().prompt(new Prompt("x"))
                .context(DvaraAdvisors.RUN_ID, context.pins.id().toString()).build();

        assertThat(guard.before(correlated, null)).isSameAs(correlated);
        context.suspended = true;
        assertThatThrownBy(() -> guard.before(correlated, null))
                .isInstanceOf(DvaraAdvisors.RunGuardAdvisor.SuspendedException.class);
        context.suspended = false;
        signal.cancel();
        assertThatThrownBy(() -> guard.before(correlated, null)).isInstanceOfSatisfying(GatewayException.class,
                failure -> assertThat(failure.code()).isEqualTo(GatewayErrorCode.CANCELLED));
        // Out of order, the guard refuses to run at all.
        var uncorrelated = ChatClientRequest.builder().prompt(new Prompt("x")).build();
        assertThatThrownBy(() -> new DvaraAdvisors.RunGuardAdvisor(context, CancellationSignal.create())
                .before(uncorrelated, null)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theToolLoopResolvesOnlyTheToolsOnTheRequest() {
        List<String> trace = new CopyOnWriteArrayList<>();
        ToolCallback other = Arrays.stream(MethodToolCallbackProvider.builder().toolObjects(new CalculatorTools())
                .build().getToolCallbacks()).findFirst().orElseThrow();
        // The model asks for "add", but the request advertises no tools: nothing may resolve it.
        ChatModel model = new TwoRoundModel(trace, other);
        ChatModel withoutTools = new ChatModel() {
            @Override
            public ChatOptions getOptions() {
                return ToolCallingChatOptions.builder().build();
            }

            @Override
            public ChatResponse call(Prompt prompt) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                return model.stream(prompt);
            }
        };

        assertThatThrownBy(() -> ChatClient.builder(withoutTools)
                .defaultAdvisors(DvaraAdvisors.chain(context, CancellationSignal.create())).build().prompt().user("add")
                .stream().content().collectList().block()).rootCause()
                .hasMessageContaining("No ToolCallback found for tool name: add");
    }
}
