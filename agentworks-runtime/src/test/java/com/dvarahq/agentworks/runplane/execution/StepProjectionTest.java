package com.dvarahq.agentworks.runplane.execution;

import com.dvarahq.agentworks.domain.run.AgentStep;
import com.dvarahq.agentworks.domain.run.RunEventType;
import com.dvarahq.agentworks.domain.run.StepCategory;
import com.dvarahq.agentworks.runplane.execution.DurableRunEngine.EventDraft;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StepProjectionTest {
    private final EngineHarness harness = new EngineHarness();

    @Test
    void stepsGroupByStepIdAcrossCategoriesAndRebuildDeterministically() {
        var claimed = harness.started("worker");
        var lease = harness.engine.append(claimed.lease(), List.of(
                EventDraft.of(RunEventType.CONTEXT_COMPILED, Map.of("stepId", "ctx-1")),
                EventDraft.of(RunEventType.MODEL_REQUESTED, Map.of("stepId", "m-1", "name", "reasoning")),
                EventDraft.of(RunEventType.MODEL_RESPONSE_RECEIVED, Map.of("stepId", "m-1",
                        "usage", Map.of("inputTokens", 5, "outputTokens", 7, "modelCalls", 1))),
                EventDraft.of(RunEventType.TOOL_REQUESTED, Map.of("stepId", "t-1", "name", "search")),
                EventDraft.of(RunEventType.TOOL_FAILED, Map.of("stepId", "t-1")),
                EventDraft.of(RunEventType.MEMORY_READ, Map.of()),
                EventDraft.of(RunEventType.ARTIFACT_CREATED, Map.of("stepId", "a-1")))).lease();
        harness.engine.executeEffect(lease, "deploy", Map.of(), (key, request) -> null);

        List<AgentStep> steps = harness.engine.steps(harness.workspace, claimed.run().id());

        assertThat(steps).isEqualTo(harness.engine.steps(harness.workspace, claimed.run().id()));
        assertThat(steps).extracting(AgentStep::category).containsSubsequence(StepCategory.SYSTEM, StepCategory.CONTEXT,
                StepCategory.MODEL, StepCategory.TOOL, StepCategory.MEMORY, StepCategory.ARTIFACT, StepCategory.OPERATION);
        assertThat(steps).filteredOn(step -> step.stepId().equals("m-1")).singleElement().satisfies(step -> {
            assertThat(step.name()).isEqualTo("reasoning");
            assertThat(step.status()).isEqualTo(AgentStep.Status.COMPLETED);
            assertThat(step.eventTypes()).containsExactly("MODEL_REQUESTED", "MODEL_RESPONSE_RECEIVED");
        });
        assertThat(steps).filteredOn(step -> step.stepId().equals("t-1")).singleElement()
                .satisfies(step -> assertThat(step.status()).isEqualTo(AgentStep.Status.FAILED));
        assertThat(steps).filteredOn(step -> step.stepId().equals("deploy")).singleElement()
                .satisfies(step -> assertThat(step.eventTypes()).containsExactly("OPERATION_STARTED", "OPERATION_COMPLETED"));
        // Steps are derived only: every step's events exist in canonical history, in order.
        var history = harness.engine.history(harness.workspace, claimed.run().id());
        assertThat(steps).allSatisfy(step -> assertThat(step.lastSequence()).isLessThanOrEqualTo(history.size()));
    }
}
