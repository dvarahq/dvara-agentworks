package com.dvarahq.agentworks.session;

/** Closed Core 1.0 session-inbox message vocabulary (master section 10.6). */
public enum InboxMessageType {
    USER_MESSAGE,
    CHANNEL_MESSAGE,
    SYSTEM_EVENT,
    OPERATION_COMPLETED,
    TEAM_MESSAGE,
    A2A_MESSAGE,
    SCHEDULED_WAKEUP
}
