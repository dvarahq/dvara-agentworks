package com.dvarahq.agentworks.controlplane.deployment;

import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.DependencyType;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;

import java.util.Map;

/** Captures the presently resolvable identity/schema/status for every dependency class. */
@FunctionalInterface
public interface DependencyContinuityProvider {
    Map<DependencyType, Object> capture(WorkspaceId workspace, String approvedManifest);

    /** Library-safe fallback. The server replaces this with registry-backed capture. */
    DependencyContinuityProvider MANIFEST = (workspace, manifest) ->
            PromotionPolicyEvaluator.dependencies(manifest);
}
