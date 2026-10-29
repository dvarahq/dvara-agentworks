package com.dvarahq.agentworks.agent.springai;

/**
 * Marks a failure raised by Dvara AgentWorks' own durable recording (the {@link DurableRunRecorder}, and through it the
 * run engine and its store) while an exchange was running. Only failures carrying this marker are handed back to the
 * worker as infrastructure (#181): a tool or model library that throws a database or transaction exception of its own
 * ends the run typed instead, so a deterministic tool failure is never re-executed by recovery.
 *
 * <p>The original failure is the cause and is what the executor rethrows, so the worker sees exactly the engine,
 * database, or transaction failure it always did.
 */
final class RecordingFailedException extends RuntimeException {
    RecordingFailedException(RuntimeException cause) {
        super(cause.getClass().getName(), cause, false, false);
    }

    /** The engine, database, or transaction failure that stopped the recording. */
    RuntimeException failure() {
        return (RuntimeException) getCause();
    }
}
