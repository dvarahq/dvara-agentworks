package com.dvarahq.agentworks.session;

/** Monotonic durable state of one inbox entry. */
public enum InboxMessageStatus {
    PENDING,
    CLAIMED,
    CONSUMED,
    SUPERSEDED,
    CANCELLED;

    public boolean terminal() {
        return this == CONSUMED || this == SUPERSEDED || this == CANCELLED;
    }
}
