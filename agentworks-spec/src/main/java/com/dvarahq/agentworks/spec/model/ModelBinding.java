package com.dvarahq.agentworks.spec.model;

/** Gateway adapter type and a logical model alias; never a provider SDK type or credential. */
public record ModelBinding(String gateway, String model, ModelResilience resilience) {
    public ModelBinding(String gateway, String model) {
        this(gateway, model, null);
    }
}
