package com.dvarahq.agentworks.domain.run;

/** Durable reason a run is {@link RunLifecycle#BLOCKED} (master §11). */
public enum RunBlockReason {
    HUMAN_INTERACTION, AUTHORIZATION, DURABLE_OPERATION, CHILD_RUN, SCHEDULED_WAKEUP, CAPACITY
}
