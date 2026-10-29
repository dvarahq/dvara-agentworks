package com.dvarahq.agentworks.controlplane.agent.web;

import com.dvarahq.agentworks.domain.identity.AuthenticatedPrincipal;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import com.dvarahq.agentworks.identity.web.CurrentPrincipal;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Resolves the single workspace bound to the authenticated principal: the session's selected workspace or the API
 * token's workspace, whose membership was re-validated when the request authenticated. Paths, bodies, and headers
 * never select a workspace; a request without an authenticated principal or workspace fails closed.
 */
public final class AuthenticatedWorkspaceScopeResolver implements WorkspaceScopeResolver {
    @Override
    public WorkspaceId resolve(HttpServletRequest request) {
        return CurrentPrincipal.get().flatMap(AuthenticatedPrincipal::workspace)
                .orElseThrow(WorkspaceScopeUnavailableException::new);
    }
}
