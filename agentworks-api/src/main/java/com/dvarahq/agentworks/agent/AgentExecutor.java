package com.dvarahq.agentworks.agent;

import com.dvarahq.agentworks.domain.agent.execution.AgentExecutionRequest;
import com.dvarahq.agentworks.domain.agent.execution.AgentExecutionResult;
import com.dvarahq.agentworks.domain.agent.execution.AgentStreamChunk;

import java.util.List;

/**
 * Framework-neutral boundary for agent execution implementations.
 *
 * <p>Implementations are stateless services. Invocation and run state belong in
 * explicit method inputs introduced with the runtime contract, never in fields.</p>
 */
public interface AgentExecutor {

    String type();

    default AgentExecutionResult execute(AgentExecutionRequest request) {
        throw new UnsupportedOperationException("Execution is not implemented by " + type());
    }

    default List<AgentStreamChunk> stream(AgentExecutionRequest request) {
        throw new UnsupportedOperationException("Streaming is not implemented by " + type());
    }
}
