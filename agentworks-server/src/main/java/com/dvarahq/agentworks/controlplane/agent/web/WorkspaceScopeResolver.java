package com.dvarahq.agentworks.controlplane.agent.web;

import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Resolves the workspace a request is authorized to act in. Implementations derive scope from authenticated
 * identity; request content, paths, and untrusted headers never select a workspace. Throw
 * {@link WorkspaceScopeUnavailableException} to fail closed.
 */
@FunctionalInterface
public interface WorkspaceScopeResolver {
    WorkspaceId resolve(HttpServletRequest request);
}
