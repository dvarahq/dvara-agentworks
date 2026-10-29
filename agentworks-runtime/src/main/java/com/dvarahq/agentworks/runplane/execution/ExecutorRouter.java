package com.dvarahq.agentworks.runplane.execution;

import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Runs each claimed continuation with the executor its release pinned ({@code executor.type} in the manifest, recorded
 * on the run at admission as {@code agent_run.executor_type}). Workers composed with a router claim only runs of the
 * router's {@link #types()}: the claim query filters on the recorded type, so a run of another type is never selected
 * by this node. A run whose executor type no node in the deployment provides therefore stays {@code QUEUED}, silently:
 * it is neither refused, quarantined, nor failed, and it runs as soon as a node with that executor polls. The
 * operator-visible signal for such runs (queue depth and age per executor type, and an admin view) is deferred to #61
 * (metrics) and #79 (administration).
 *
 * <p>A node never substitutes another executor (ADR-027). If a router is used with a scope that does not filter on its
 * types (a custom composition), a claimed run of a type it lacks is paused with {@code EXECUTOR_UNAVAILABLE} instead,
 * so the run keeps its pins and an operator can resume it where a capable executor runs. Stateless: the routing table
 * is immutable.
 */
public final class ExecutorRouter implements RunHandler {
    public static final String UNAVAILABLE = "EXECUTOR_UNAVAILABLE";

    private final Map<String, RunHandler> executors;

    public ExecutorRouter(Map<String, ? extends RunHandler> executors) {
        Objects.requireNonNull(executors, "executors");
        executors.forEach((type, executor) -> {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(executor, "executor");
        });
        this.executors = Map.copyOf(new TreeMap<>(executors));
    }

    public java.util.Set<String> types() {
        return executors.keySet();
    }

    @Override
    public RunOutcome execute(RunContext context) throws Exception {
        RunHandler executor = executors.get(context.manifest().executorType());
        if (executor == null) return RunOutcome.paused(UNAVAILABLE);
        return executor.execute(context);
    }
}
