package com.dvarahq.agentworks.spec;

import com.dvarahq.agentworks.domain.lifecycle.AgentDefinitionId;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;

import java.time.Instant;
import java.util.Objects;

/** Workspace-owned logical agent identity; versions form its immutable specification lineage. */
public record AgentDefinition(AgentDefinitionId id, WorkspaceId workspaceId, String name, String description,
        Instant createdAt) {
    public AgentDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
