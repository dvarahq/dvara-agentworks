package com.dvarahq.agentworks.domain.run;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.dvarahq.agentworks.domain.run.RunLifecycle.ACTIVE;
import static com.dvarahq.agentworks.domain.run.RunLifecycle.BLOCKED;
import static com.dvarahq.agentworks.domain.run.RunLifecycle.CANCELLED;
import static com.dvarahq.agentworks.domain.run.RunLifecycle.CREATED;
import static com.dvarahq.agentworks.domain.run.RunLifecycle.FAILED;
import static com.dvarahq.agentworks.domain.run.RunLifecycle.PAUSED;
import static com.dvarahq.agentworks.domain.run.RunLifecycle.QUEUED;
import static com.dvarahq.agentworks.domain.run.RunLifecycle.SUCCEEDED;

/**
 * The legal run transitions (master §11) and the event that must record each one. The PostgreSQL cohort fence
 * enforces the same graph for direct SQL.
 *
 * <pre>
 * CREATED → QUEUED → ACTIVE → SUCCEEDED
 *            │        ├→ BLOCKED ─resume→ QUEUED
 *            │        ├→ PAUSED  ─resume→ QUEUED
 *            └→ PAUSED (an operator's pause of a queued run, #183)
 * any non-terminal    → FAILED | CANCELLED
 * </pre>
 */
public final class RunStateMachine {
    private static final Map<RunLifecycle, Set<RunLifecycle>> LEGAL = new EnumMap<>(RunLifecycle.class);

    static {
        LEGAL.put(CREATED, EnumSet.of(QUEUED, FAILED, CANCELLED));
        LEGAL.put(QUEUED, EnumSet.of(ACTIVE, PAUSED, FAILED, CANCELLED));
        LEGAL.put(ACTIVE, EnumSet.of(BLOCKED, PAUSED, SUCCEEDED, FAILED, CANCELLED));
        LEGAL.put(BLOCKED, EnumSet.of(QUEUED, FAILED, CANCELLED));
        LEGAL.put(PAUSED, EnumSet.of(QUEUED, FAILED, CANCELLED));
        LEGAL.put(SUCCEEDED, EnumSet.noneOf(RunLifecycle.class));
        LEGAL.put(FAILED, EnumSet.noneOf(RunLifecycle.class));
        LEGAL.put(CANCELLED, EnumSet.noneOf(RunLifecycle.class));
    }

    private RunStateMachine() {}

    public static boolean legal(RunLifecycle from, RunLifecycle to) {
        return LEGAL.get(from).contains(to);
    }

    /** The event that records {@code from → to}; empty when the transition is illegal. */
    public static Optional<RunEventType> eventFor(RunLifecycle from, RunLifecycle to) {
        if (!legal(from, to)) return Optional.empty();
        return Optional.of(switch (to) {
            case QUEUED -> from == CREATED ? RunEventType.RUN_QUEUED : RunEventType.RUN_RESUMED;
            case ACTIVE -> RunEventType.RUN_ACTIVATED;
            case BLOCKED -> RunEventType.RUN_BLOCKED;
            case PAUSED -> RunEventType.RUN_PAUSED;
            case SUCCEEDED -> RunEventType.RUN_SUCCEEDED;
            case FAILED -> RunEventType.RUN_FAILED;
            case CANCELLED -> RunEventType.RUN_CANCELLED;
            case CREATED -> throw new IllegalStateException("no transition enters CREATED");
        });
    }

    /** The lifecycle a transition event moves a run into; empty for events that do not change lifecycle. */
    public static Optional<RunLifecycle> target(RunEventType type) {
        return Optional.ofNullable(switch (type) {
            case RUN_QUEUED, RUN_RESUMED -> QUEUED;
            case RUN_ACTIVATED -> ACTIVE;
            case RUN_BLOCKED -> BLOCKED;
            case RUN_PAUSED -> PAUSED;
            case RUN_SUCCEEDED -> SUCCEEDED;
            case RUN_FAILED -> FAILED;
            case RUN_CANCELLED -> CANCELLED;
            default -> null;
        });
    }
}
