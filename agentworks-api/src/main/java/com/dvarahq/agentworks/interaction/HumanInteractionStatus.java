package com.dvarahq.agentworks.interaction;

/** OPEN may advance exactly once to a terminal result; terminal interactions never reopen. */
public enum HumanInteractionStatus {
    OPEN, RESPONDED, EXPIRED, CANCELLED, REVOKED;

    public boolean terminal() { return this != OPEN; }

    public boolean mayTransitionTo(HumanInteractionStatus target) {
        return this == OPEN && target != null && target.terminal();
    }
}
