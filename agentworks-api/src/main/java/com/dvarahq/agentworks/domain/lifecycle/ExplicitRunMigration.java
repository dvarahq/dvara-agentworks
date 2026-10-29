package com.dvarahq.agentworks.domain.lifecycle;

/** Separate authority boundary for a future audited run migration; ordinary stores cannot repin. */
public interface ExplicitRunMigration {
    AgentReleaseId fromRelease();
    AgentReleaseId toRelease();
}
