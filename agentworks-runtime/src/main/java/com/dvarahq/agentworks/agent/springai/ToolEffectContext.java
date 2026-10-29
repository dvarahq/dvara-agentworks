package com.dvarahq.agentworks.agent.springai;

import org.springframework.ai.chat.model.ToolContext;

import java.util.Optional;

/** Stable metadata supplied to an effectful tool invocation by the durable runtime. */
public final class ToolEffectContext {
    /** The downstream idempotency key is stable for this run and logical tool effect across recovery. */
    public static final String IDEMPOTENCY_KEY = "agentworks.effect.idempotency-key";

    private ToolEffectContext() {}

    public static Optional<String> idempotencyKey(ToolContext context) {
        if (context == null) return Optional.empty();
        Object value = context.getContext().get(IDEMPOTENCY_KEY);
        return value instanceof String key && !key.isBlank() ? Optional.of(key) : Optional.empty();
    }
}
