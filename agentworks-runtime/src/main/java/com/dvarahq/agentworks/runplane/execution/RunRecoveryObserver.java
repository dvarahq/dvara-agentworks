package com.dvarahq.agentworks.runplane.execution;

import java.time.Duration;

/** Low-cardinality recovery telemetry; implementations must not retain run or tenant identities. */
@FunctionalInterface
public interface RunRecoveryObserver {
    RunRecoveryObserver NONE = delay -> { };

    /** A formerly active run was reclaimed this long after its prior database-time lease expired. */
    void recovered(Duration delay);
}
