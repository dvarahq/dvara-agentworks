package com.dvarahq.agentworks.exposure;

import com.dvarahq.agentworks.interaction.InteractionEvent;

import java.util.List;

public record OutboundInteraction(String externalThreadId, List<InteractionEvent> events) {
    public OutboundInteraction { events = List.copyOf(events); }
}
