package com.dvarahq.agentworks.agent.springai;

import com.dvarahq.agentworks.domain.lifecycle.AgentRunId;
import com.dvarahq.agentworks.gateway.CancellationSignal;
import com.dvarahq.agentworks.gateway.InvocationCorrelation;
import com.dvarahq.agentworks.gateway.ModelGateway;
import com.dvarahq.agentworks.gateway.ModelInvocation;
import com.dvarahq.agentworks.gateway.ModelMessage;
import com.dvarahq.agentworks.gateway.ModelRequest;
import com.dvarahq.agentworks.gateway.ModelResponse;
import com.dvarahq.agentworks.gateway.ModelRole;
import com.dvarahq.agentworks.gateway.ResponseFormat;
import com.dvarahq.agentworks.gateway.ToolCall;
import com.dvarahq.agentworks.replay.ReplayModelExecutor;
import com.dvarahq.agentworks.replay.ReplayService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Model-only replay adapter. It deliberately exposes no tool callbacks or other live effect channel. */
public final class GatewayReplayModelExecutor implements ReplayModelExecutor {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final ModelGateway gateway;
    private final Duration timeout;

    public GatewayReplayModelExecutor(ModelGateway gateway, Duration timeout) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
    }

    @Override
    public List<ReplayService.Diagnostic> diagnostics(
            com.dvarahq.agentworks.runplane.execution.DurableRunEngine.ReplaySource source,
            List<ReplayService.Substitution> substitutions) {
        return List.of();
    }

    @Override
    public JsonNode execute(ReplayService.ModelInvocation replay) {
        var source = replay.source();
        ExecutorInput input;
        try {
            input = ExecutorInput.from(source.input());
        } catch (ExecutorInput.InvalidInputException invalid) {
            throw new com.dvarahq.agentworks.replay.ReplayException(invalid.code(), invalid.getMessage());
        }
        List<ModelMessage> messages = new ArrayList<>();
        String instructions = SpringAiAgentExecutor.instructions(source.agentVersion().orElseThrow());
        String prompt = input.prompt();
        if (source.manifest().contextPolicy().isPresent()) {
            if (instructions != null) instructions = "[SYSTEM_INSTRUCTION:agent-instructions]\n" + instructions;
            prompt = "[USER_INPUT:run-input]\n" + prompt;
        }
        if (instructions != null) messages.add(ModelMessage.system(instructions));
        messages.add(ModelMessage.user(prompt));
        int index = 0;
        for (ReplayService.Substitution substitution : replay.plan().substitutions()) {
            String callId = "replay-" + ++index;
            String name = "recorded_" + substitution.kind().toLowerCase(java.util.Locale.ROOT);
            String arguments = JSON.writeValueAsString(Map.of("sourceKey", substitution.key()));
            messages.add(ModelMessage.assistant(null, List.of(new ToolCall(callId, name, arguments))));
            messages.add(ModelMessage.tool(callId, replay.recordedResults().get(
                    ReplayService.materialKey(substitution.kind(), substitution.key()))));
        }
        ResponseFormat format = input.structured().map(ExecutorInput.StructuredOutput::responseFormat)
                .orElse(ResponseFormat.TEXT);
        var model = source.manifest().models().role(ModelRole.REASONING);
        ModelRequest request = new ModelRequest(messages, List.of(), format, null, null);
        ModelResponse response = gateway.call(new ModelInvocation(model, source.invoker().orElseThrow().identity(),
                new InvocationCorrelation(new AgentRunId(replay.plan().replayId()), source.run().pins().traceId()),
                request, timeout, CancellationSignal.create()));
        refuseLiveEffect(response);
        if (input.structured().isPresent()) {
            return JSON.valueToTree(Map.of("type", "json", "value",
                    input.structured().orElseThrow().validate(response.content())));
        }
        return JSON.valueToTree(Map.of("type", "text", "content", response.content()));
    }

    static void refuseLiveEffect(ModelResponse response) {
        if (!response.toolCalls().isEmpty()) {
            throw new com.dvarahq.agentworks.replay.ReplayException("LIVE_EFFECT_FORBIDDEN",
                    "the model requested a new tool call; replay executed no live effect");
        }
    }
}
