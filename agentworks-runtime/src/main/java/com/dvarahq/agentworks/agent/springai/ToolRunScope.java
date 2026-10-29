package com.dvarahq.agentworks.agent.springai;

import com.dvarahq.agentworks.runplane.execution.RunContext;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.function.Supplier;

/** Continuation-local resource ownership; shared callbacks never retain mutable run state. */
public final class ToolRunScope implements AutoCloseable {
    private final RunContext context;
    private final LinkedHashMap<Object, AutoCloseable> resources = new LinkedHashMap<>();
    private boolean closed;

    public ToolRunScope(RunContext context) { this.context = Objects.requireNonNull(context); }
    public RunContext context() { return context; }

    /** Called only inside an authorized invocation. A key shares exactly one resource within this continuation. */
    @SuppressWarnings("unchecked")
    public synchronized <T extends AutoCloseable> T resource(Object key, Supplier<T> acquire) {
        if (closed) throw new IllegalStateException("tool scope closed");
        return (T) resources.computeIfAbsent(Objects.requireNonNull(key), ignored -> acquire.get());
    }

    @Override public synchronized void close() throws Exception {
        if (closed) return;
        closed = true;
        boolean failed = false;
        RecordingFailedException recording = null;
        for (AutoCloseable resource : resources.sequencedValues().reversed()) {
            try { resource.close(); }
            catch (Exception cleanup) {
                failed = true;
                if (cleanup instanceof InterruptedException) Thread.currentThread().interrupt();
                if (recording == null) recording = SpringAiAgentExecutor.recordingFailure(cleanup);
            }
        }
        resources.clear();
        // Resource/library exception graphs may contain secrets. Only our own recording marker is trusted.
        if (recording != null) throw recording;
        if (failed) throw new ToolAdapterException("TOOL_RESOURCE_CLEANUP_FAILED");
    }
}
