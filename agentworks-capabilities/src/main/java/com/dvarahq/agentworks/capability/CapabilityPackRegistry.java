package com.dvarahq.agentworks.capability;

import com.dvarahq.agentworks.domain.tenant.WorkspaceId;

import java.util.List;
import java.util.Optional;

/** Tenant-scoped lookup of exact pack versions. Only active packs are discoverable to resolution. */
public interface CapabilityPackRegistry {
    Optional<CapabilityPack> get(WorkspaceId workspace, String id, String version);

    /** All active versions for an unversioned AgentSpec reference, ordered by version. */
    List<CapabilityPack> activeVersions(WorkspaceId workspace, String id);
}
