package com.dvarahq.agentworks.spec;

import com.dvarahq.agentworks.domain.lifecycle.AgentDefinitionId;
import com.dvarahq.agentworks.domain.lifecycle.AgentVersionId;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable published version. {@code sequence} orders the definition lineage; the version label is the authored
 * {@code metadata.version}.
 */
public record AgentVersion(AgentVersionId id, AgentDefinitionId definitionId, WorkspaceId workspaceId,
        long sequence, CanonicalAgentSpec spec, Instant createdAt) {
    public AgentVersion {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(definitionId, "definitionId");
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(createdAt, "createdAt");
        if (sequence < 1) throw new IllegalArgumentException("sequence must be positive");
    }

    public String version() {
        return spec.spec().metadata().version();
    }

    public String digest() {
        return spec.digest();
    }
}
