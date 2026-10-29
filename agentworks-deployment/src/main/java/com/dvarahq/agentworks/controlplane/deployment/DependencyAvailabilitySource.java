package com.dvarahq.agentworks.controlplane.deployment;

import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.DependencyType;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;

import java.util.Optional;

/** Optional live authority for one dependency class not owned by the core registries. */
public interface DependencyAvailabilitySource {
    DependencyType type();
    Optional<Object> capture(WorkspaceId workspace, Object approvedPins);
}
