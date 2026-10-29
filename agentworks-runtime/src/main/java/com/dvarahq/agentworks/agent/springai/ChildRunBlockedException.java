package com.dvarahq.agentworks.agent.springai;

import com.dvarahq.agentworks.child.ChildRunRecord;

import java.util.UUID;

/** Internal control signal: child authority already blocked the parent and released its worker lease. */
final class ChildRunBlockedException extends RuntimeException {
    private final UUID relationshipId;
    private final UUID childRunId;
    ChildRunBlockedException(ChildRunRecord child) { this(child.id(), child.childRunId()); }
    ChildRunBlockedException(UUID relationshipId, UUID childRunId) {
        super("child run delegated", null, false, false);
        this.relationshipId = relationshipId;
        this.childRunId = childRunId;
    }
    UUID relationshipId() { return relationshipId; }
    UUID childRunId() { return childRunId; }
}
