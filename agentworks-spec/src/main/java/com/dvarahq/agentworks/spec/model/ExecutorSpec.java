package com.dvarahq.agentworks.spec.model;

import com.dvarahq.agentworks.domain.external.ExternalExecutorDescriptor;

import java.util.List;

/** Executor identity by neutral type name plus capabilities the executor must advertise during negotiation. */
public record ExecutorSpec(String type, List<ExecutorCapability> requiredCapabilities,
        ExternalExecutorDescriptor external) {
    public ExecutorSpec {
        requiredCapabilities = requiredCapabilities == null ? null : List.copyOf(requiredCapabilities);
    }

    public ExecutorSpec(String type, List<ExecutorCapability> requiredCapabilities) {
        this(type, requiredCapabilities, null);
    }

    public static ExecutorSpec of(String type) {
        return new ExecutorSpec(type, null, null);
    }
}
