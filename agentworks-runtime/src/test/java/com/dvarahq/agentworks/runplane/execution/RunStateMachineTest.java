package com.dvarahq.agentworks.runplane.execution;

import com.dvarahq.agentworks.domain.run.RunEventType;
import com.dvarahq.agentworks.domain.run.RunLifecycle;
import com.dvarahq.agentworks.domain.run.RunStateMachine;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static com.dvarahq.agentworks.domain.run.RunLifecycle.ACTIVE;
import static com.dvarahq.agentworks.domain.run.RunLifecycle.BLOCKED;
import static com.dvarahq.agentworks.domain.run.RunLifecycle.CANCELLED;
import static com.dvarahq.agentworks.domain.run.RunLifecycle.CREATED;
import static com.dvarahq.agentworks.domain.run.RunLifecycle.FAILED;
import static com.dvarahq.agentworks.domain.run.RunLifecycle.PAUSED;
import static com.dvarahq.agentworks.domain.run.RunLifecycle.QUEUED;
import static com.dvarahq.agentworks.domain.run.RunLifecycle.SUCCEEDED;
import static org.assertj.core.api.Assertions.assertThat;

class RunStateMachineTest {
    private static final Map<RunLifecycle, Set<RunLifecycle>> EXPECTED = Map.of(
            CREATED, EnumSet.of(QUEUED, FAILED, CANCELLED),
            QUEUED, EnumSet.of(ACTIVE, PAUSED, FAILED, CANCELLED),
            ACTIVE, EnumSet.of(BLOCKED, PAUSED, SUCCEEDED, FAILED, CANCELLED),
            BLOCKED, EnumSet.of(QUEUED, FAILED, CANCELLED),
            PAUSED, EnumSet.of(QUEUED, FAILED, CANCELLED),
            SUCCEEDED, EnumSet.noneOf(RunLifecycle.class),
            FAILED, EnumSet.noneOf(RunLifecycle.class),
            CANCELLED, EnumSet.noneOf(RunLifecycle.class));

    @Test
    void onlyTheDocumentedTransitionsAreLegalAndEachHasExactlyOneEvent() {
        for (RunLifecycle from : RunLifecycle.values()) {
            for (RunLifecycle to : RunLifecycle.values()) {
                boolean legal = EXPECTED.get(from).contains(to);
                assertThat(RunStateMachine.legal(from, to)).as(from + " -> " + to).isEqualTo(legal);
                assertThat(RunStateMachine.eventFor(from, to).isPresent()).as(from + " -> " + to).isEqualTo(legal);
            }
        }
        assertThat(RunStateMachine.eventFor(CREATED, QUEUED)).contains(RunEventType.RUN_QUEUED);
        assertThat(RunStateMachine.eventFor(BLOCKED, QUEUED)).contains(RunEventType.RUN_RESUMED);
        assertThat(RunStateMachine.eventFor(PAUSED, QUEUED)).contains(RunEventType.RUN_RESUMED);
        assertThat(RunLifecycle.values()).filteredOn(RunLifecycle::terminal).containsExactly(SUCCEEDED, FAILED, CANCELLED);
    }

    @Test
    void everyTransitionEventMapsBackToItsTargetAndOnlyTheEngineOrControlWritesIt() {
        for (RunLifecycle from : RunLifecycle.values()) {
            for (RunLifecycle to : EXPECTED.get(from)) {
                RunEventType event = RunStateMachine.eventFor(from, to).orElseThrow();
                assertThat(RunStateMachine.target(event)).contains(to);
                assertThat(event.authorship()).isNotEqualTo(RunEventType.Authorship.WORKER);
            }
        }
        assertThat(RunStateMachine.target(RunEventType.MODEL_REQUESTED)).isEmpty();
        assertThat(RunStateMachine.target(RunEventType.RUN_RECOVERED)).isEmpty();
    }
}
