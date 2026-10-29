package com.dvarahq.agentworks.runplane.execution;

import com.dvarahq.agentworks.domain.lifecycle.state.LifecycleRows.ReleaseRow;
import com.dvarahq.agentworks.domain.run.RunLimits;

import java.util.Objects;

/**
 * Chooses the limits a run is created with. They are pinned in the creation transaction and never change for that
 * run. Admission control, bulkheads, and limits derived from the AgentSpec are #41.
 */
@FunctionalInterface
public interface RunLimitsPolicy {
    RunLimits limitsFor(ReleaseRow release);

    static RunLimitsPolicy fixed(RunLimits limits) {
        Objects.requireNonNull(limits, "limits");
        return release -> limits;
    }
}
