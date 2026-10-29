package com.dvarahq.agentworks.architecture.fixture.executor;

import com.dvarahq.agentworks.agent.AgentExecutor;

public final class ExecutorWithMutableSessionState implements AgentExecutor {

    private String currentSessionId;

    @Override
    public String type() {
        return "violating-mutable-session";
    }
}
