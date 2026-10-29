package com.dvarahq.agentworks.agent.springai;

import com.dvarahq.agentworks.gateway.ToolCall;
import org.springframework.ai.chat.model.ToolContext;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Carries durable call slots and provider provenance across Spring AI's callback boundary. */
final class ToolCallIdentityRegistry {
    static final String SLOT_CONTEXT_KEY = "agentworks.durableToolCallSlot";
    static final String PROVIDER_CONTEXT_KEY = "agentworks.providerToolCallId";
    private final UUID parentRunId;
    private final Map<Key, ArrayDeque<Identity>> pending = new HashMap<>();

    ToolCallIdentityRegistry(UUID parentRunId) {
        this.parentRunId = Objects.requireNonNull(parentRunId, "parentRunId");
    }

    synchronized void register(int modelRound, List<ToolCall> calls) {
        for (int ordinal = 0; ordinal < calls.size(); ordinal++) {
            ToolCall call = calls.get(ordinal);
            Identity identity = new Identity("child:" + parentRunId + ":" + modelRound + ":" + ordinal, call.id());
            pending.computeIfAbsent(new Key(call.name(), call.argumentsJson()), ignored -> new ArrayDeque<>())
                    .addLast(identity);
        }
    }

    synchronized Identity claim(String name, String arguments) {
        Key key = new Key(name, arguments);
        ArrayDeque<Identity> identities = pending.get(key);
        if (identities == null || identities.isEmpty()) return null;
        Identity identity = identities.removeFirst();
        if (identities.isEmpty()) pending.remove(key);
        return identity;
    }

    static ToolContext withIdentity(ToolContext existing, Identity identity) {
        Map<String, Object> values = new HashMap<>();
        if (existing != null) values.putAll(existing.getContext());
        values.put(SLOT_CONTEXT_KEY, identity.durableSlot());
        values.put(PROVIDER_CONTEXT_KEY, identity.providerCallId());
        return new ToolContext(Map.copyOf(values));
    }

    static String durableSlot(ToolContext context) {
        Object id = context == null ? null : context.getContext().get(SLOT_CONTEXT_KEY);
        return id instanceof String value && !value.isBlank() ? value : null;
    }

    record Identity(String durableSlot, String providerCallId) {
        Identity {
            if (durableSlot == null || durableSlot.isBlank()) throw new IllegalArgumentException("durableSlot");
            if (providerCallId == null || providerCallId.isBlank()) throw new IllegalArgumentException("providerCallId");
        }
    }

    private record Key(String name, String arguments) {}
}
