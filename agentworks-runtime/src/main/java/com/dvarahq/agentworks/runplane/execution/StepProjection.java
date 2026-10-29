package com.dvarahq.agentworks.runplane.execution;

import com.dvarahq.agentworks.domain.run.AgentStep;
import com.dvarahq.agentworks.domain.run.RunEventType;
import com.dvarahq.agentworks.domain.run.StepCategory;
import com.dvarahq.agentworks.runplane.execution.RunEventCodec.DecodedEvent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Derives normalized {@link AgentStep}s from canonical events (master §10.9). Events that share a payload
 * {@code stepId} form one step; any other event is a single recorded step. The projection is a pure function of
 * the history, is computed on read, and is never stored as an alternate source of truth.
 */
public final class StepProjection {
    private static final Set<RunEventType> COMPLETING = Set.of(RunEventType.MODEL_RESPONSE_RECEIVED,
            RunEventType.TOOL_COMPLETED, RunEventType.OPERATION_COMPLETED, RunEventType.OPERATION_RESOLVED,
            RunEventType.HUMAN_INTERACTION_RESPONDED, RunEventType.SUB_AGENT_COMPLETED,
            RunEventType.ARTIFACT_COMPUTE_COMPLETED, RunEventType.AUTHORIZATION_GRANTED, RunEventType.CONTEXT_COMPILED);
    private static final Set<RunEventType> FAILING = Set.of(RunEventType.MODEL_FAILED, RunEventType.TOOL_FAILED,
            RunEventType.OPERATION_FAILED, RunEventType.OPERATION_CANCELLED, RunEventType.SUB_AGENT_FAILED,
            RunEventType.AUTHORIZATION_FAILED, RunEventType.HUMAN_INTERACTION_EXPIRED);

    private StepProjection() {}

    public static List<AgentStep> project(List<DecodedEvent> history) {
        Map<String, List<DecodedEvent>> groups = new LinkedHashMap<>();
        for (DecodedEvent event : history) {
            var stepId = event.payload().get("stepId");
            String key = stepId != null && stepId.isString() ? event.type().category() + ":" + stepId.stringValue()
                    : "event:" + event.raw().sequence();
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(event);
        }
        List<AgentStep> steps = new ArrayList<>(groups.size());
        groups.forEach((key, events) -> steps.add(step(key, events)));
        return List.copyOf(steps);
    }

    private static AgentStep step(String key, List<DecodedEvent> events) {
        DecodedEvent first = events.getFirst();
        DecodedEvent last = events.getLast();
        StepCategory category = first.type().category();
        var name = first.payload().get("name");
        AgentStep.Status status;
        if (!key.startsWith("event:") || events.size() > 1) {
            status = FAILING.contains(last.type()) ? AgentStep.Status.FAILED
                    : COMPLETING.contains(last.type()) ? AgentStep.Status.COMPLETED : AgentStep.Status.STARTED;
        } else {
            status = FAILING.contains(first.type()) ? AgentStep.Status.FAILED : AgentStep.Status.RECORDED;
        }
        Instant ended = status == AgentStep.Status.STARTED ? null : last.raw().createdAt();
        return new AgentStep(key.startsWith("event:") ? key : key.substring(key.indexOf(':') + 1), category,
                name != null && name.isString() ? name.stringValue() : first.type().name(), status,
                first.raw().sequence(), last.raw().sequence(), events.stream().map(event -> event.type().name()).toList(),
                first.raw().createdAt(), ended);
    }
}
