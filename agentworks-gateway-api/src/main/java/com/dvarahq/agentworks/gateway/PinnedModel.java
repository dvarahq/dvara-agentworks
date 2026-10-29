package com.dvarahq.agentworks.gateway;

import com.dvarahq.agentworks.domain.lifecycle.AgentReleaseId;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;

import java.util.Objects;

/** One role's pinned identity, scoped to the workspace and release that pinned it. */
public record PinnedModel(WorkspaceId workspace, AgentReleaseId release, ResolvedModelIdentity identity,
        ModelResiliencePolicy resilience) {
    public PinnedModel(WorkspaceId workspace, AgentReleaseId release, ResolvedModelIdentity identity) {
        this(workspace, release, identity, ModelResiliencePolicy.DEFAULT);
    }

    public PinnedModel {
        Objects.requireNonNull(workspace, "workspace");
        Objects.requireNonNull(release, "release");
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(resilience, "resilience");
    }
}
