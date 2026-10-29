package com.dvarahq.agentworks.runplane.execution;

/**
 * Executes one claimed continuation of a run on a stateless worker. A handler keeps no state between calls: it
 * restores everything from {@link RunContext#checkpointState()} and the run's input and pins, records progress
 * through the context, and returns how the continuation ended. The integration with the stateless Spring AI
 * executor is #34.
 */
@FunctionalInterface
public interface RunHandler {
    RunOutcome execute(RunContext context) throws Exception;
}
