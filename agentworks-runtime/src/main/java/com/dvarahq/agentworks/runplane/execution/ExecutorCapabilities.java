package com.dvarahq.agentworks.runplane.execution;

import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * What an executor type promises about its runs, read by the run engine from a run's pinned executor type.
 *
 * @param resumableMidExchange the executor records a checkpoint at every safe boundary, so a run it paused
 *     mid-exchange resumes without repeating completed model rounds or tool calls. An operator pauses an active run
 *     only when its executor promises this (#183); a queued run always pauses, since nothing has run. The Spring AI
 *     executor checkpoints only its final response and declares {@code false} until per-round checkpoints (#44).
 */
public record ExecutorCapabilities(boolean resumableMidExchange) {
    /** What an unknown executor type is assumed to promise: nothing. */
    public static final ExecutorCapabilities NONE = new ExecutorCapabilities(false);

    /** An executor that declares its capabilities for its executor type. */
    public interface Declaring {
        /** The executor type, as releases pin it. */
        String type();

        ExecutorCapabilities capabilities();
    }

    /** The capabilities of each executor type; unknown types get {@link #NONE}. */
    @FunctionalInterface
    public interface Registry extends Function<String, ExecutorCapabilities> {
        Registry NONE_DECLARED = type -> ExecutorCapabilities.NONE;

        static Registry of(Map<String, ExecutorCapabilities> declared) {
            Map<String, ExecutorCapabilities> copy = Map.copyOf(declared);
            return type -> copy.getOrDefault(type, ExecutorCapabilities.NONE);
        }

        static Registry of(Iterable<? extends Declaring> executors) {
            Map<String, ExecutorCapabilities> declared = new java.util.HashMap<>();
            for (Declaring executor : executors) {
                ExecutorCapabilities previous = declared.put(Objects.requireNonNull(executor.type(), "type"),
                        Objects.requireNonNull(executor.capabilities(), "capabilities"));
                if (previous != null && !previous.equals(executor.capabilities())) {
                    throw new IllegalStateException("executor type " + executor.type()
                            + " declares conflicting capabilities");
                }
            }
            return of(declared);
        }
    }
}
