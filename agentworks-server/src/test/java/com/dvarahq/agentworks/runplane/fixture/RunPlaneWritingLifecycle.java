package com.dvarahq.agentworks.runplane.fixture;

import com.dvarahq.agentworks.storage.lifecycle.JdbcLifecycleRepository;

public final class RunPlaneWritingLifecycle {

    private final JdbcLifecycleRepository lifecycle;

    public RunPlaneWritingLifecycle(JdbcLifecycleRepository lifecycle) {
        this.lifecycle = lifecycle;
    }
}
