package com.dvarahq.agentworks.agent.springai;

import com.dvarahq.agentworks.capability.ToolEffect;
import com.dvarahq.agentworks.runplane.execution.DurableRunEngine.EffectOutcome;
import com.dvarahq.agentworks.runplane.execution.RunContext;
import com.dvarahq.agentworks.runplane.execution.RunEngineException;
import org.springframework.ai.chat.model.ToolContext;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/** Binds one selected effectful tool to the durable run-effect ledger. */
final class ToolEffectBinding {
    @FunctionalInterface
    interface Invocation {
        String invoke(ToolContext context);
    }

    record Result(String value, String effectKey, boolean replayed, String failure) {
        boolean failed() {
            return failure != null;
        }
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final RunContext context;
    private final String capability;
    private final ToolEffect effect;
    private final String keyPrefix;
    private final AtomicInteger sequence = new AtomicInteger();

    ToolEffectBinding(RunContext context, String toolName, String capability, ToolEffect effect) {
        this.context = Objects.requireNonNull(context, "context");
        Objects.requireNonNull(toolName, "toolName");
        this.capability = Objects.requireNonNull(capability, "capability");
        this.effect = Objects.requireNonNull(effect, "effect");
        this.keyPrefix = "tool/" + digest(toolName + "\n" + capability).substring(0, 24);
    }

    Result invoke(String input, ToolContext toolContext, Invocation invocation) {
        Objects.requireNonNull(invocation, "invocation");
        String effectKey = keyPrefix + "/" + sequence.incrementAndGet();
        String argumentsDigest = com.dvarahq.agentworks.runplane.execution.RunEventCodec
                .sha256(input == null ? "" : input);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("capability", capability);
        request.put("resource", effect.resource());
        request.put("action", effect.action());
        request.put("risk", effect.risk().name());
        request.put("argumentsDigest", argumentsDigest);
        EffectOutcome outcome = context.effect(effectKey, request, (externalKey, ignored) ->
                JSON.valueToTree(invocation.invoke(withIdempotencyKey(toolContext, externalKey))));
        if (outcome.failed()) return new Result(null, effectKey, outcome.replayed(), outcome.failure());
        if (outcome.result() == null || !outcome.result().isString()) {
            throw new RunEngineException("INVALID_EVENT", "a durable tool effect result must be a string");
        }
        return new Result(outcome.result().stringValue(), effectKey, outcome.replayed(), null);
    }

    private static ToolContext withIdempotencyKey(ToolContext existing, String key) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (existing != null) values.putAll(existing.getContext());
        values.put(ToolEffectContext.IDEMPOTENCY_KEY, key);
        return new ToolContext(Map.copyOf(values));
    }

    private static String digest(String value) {
        return com.dvarahq.agentworks.runplane.execution.RunEventCodec.sha256(value)
                .substring("sha256:".length());
    }
}
