package com.dvarahq.agentworks.capability;

import com.dvarahq.agentworks.domain.tenant.WorkspaceId;

import java.time.Instant;

/** Control-plane mutations for immutable capability metadata and its separate lifecycle state. */
public interface CapabilityCatalogAdministration {
    void insertTool(WorkspaceId workspace, AgentTool tool, Instant createdAt);
    void transitionTool(WorkspaceId workspace, ToolId id, AgentTool.Availability state, Instant changedAt);
    void insertPack(WorkspaceId workspace, CapabilityPack pack, Instant createdAt);
    void transitionPack(WorkspaceId workspace, String id, String version, CapabilityPack.State state,
            Instant changedAt);
}
