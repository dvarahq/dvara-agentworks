package com.dvarahq.agentworks.agent.springai;

import com.dvarahq.agentworks.domain.security.RememberedPermissionEvaluator;
import com.dvarahq.agentworks.domain.security.RememberedPermissionRequest;

/** Re-reads remembered permission state immediately before one pinned tool effect. */
@FunctionalInterface
public interface ToolPermissionAuthority {
    RememberedPermissionEvaluator.Decision evaluate(RememberedPermissionRequest request);
}
