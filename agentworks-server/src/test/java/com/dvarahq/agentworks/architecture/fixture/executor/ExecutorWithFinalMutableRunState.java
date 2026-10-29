package com.dvarahq.agentworks.architecture.fixture.executor;

import com.dvarahq.agentworks.agent.AgentExecutor;

import java.util.concurrent.atomic.AtomicReference;

public final class ExecutorWithFinalMutableRunState implements AgentExecutor {

    private final AtomicReference<String> currentRunId = new AtomicReference<>();

    @Override
    public String type() {
        return "violating-final-run-container";
    }
}
