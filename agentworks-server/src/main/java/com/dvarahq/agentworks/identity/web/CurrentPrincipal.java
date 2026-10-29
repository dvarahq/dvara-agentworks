package com.dvarahq.agentworks.identity.web;

import com.dvarahq.agentworks.controlplane.agent.web.WorkspaceScopeUnavailableException;
import com.dvarahq.agentworks.domain.identity.AuthenticatedPrincipal;
import com.dvarahq.agentworks.domain.security.InvocationIdentity;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

/** The authenticated principal of the current request, as established by the security filter chain. */
public final class CurrentPrincipal {
    private CurrentPrincipal() {}

    public static Optional<AuthenticatedPrincipal> get() {
        return SecurityContextHolder.getContext().getAuthentication() instanceof AgentWorksAuthentication authentication
                ? Optional.of(authentication.getPrincipal()) : Optional.empty();
    }

    /** The principal, failing closed when the request is not authenticated by AgentWorks. */
    public static AuthenticatedPrincipal require() {
        return get().orElseThrow(WorkspaceScopeUnavailableException::new);
    }

    /**
     * The principal as the invoker of a run it submits in {@code workspace} (issue #34): identifiers only, recorded on
     * the run so that a worker invokes models on its behalf. Fails closed without a principal or a workspace.
     */
    public static com.dvarahq.agentworks.domain.run.RunInvoker invoker(
            WorkspaceId workspace) {
        AuthenticatedPrincipal principal = require();
        if (!principal.workspace().map(workspace::equals).orElse(false)) {
            throw new WorkspaceScopeUnavailableException();
        }
        return new com.dvarahq.agentworks.domain.run.RunInvoker(principal.organizationId().value(), workspace.value(),
                principal.subjectId(), principal.credentialId(), principal.method().value());
    }

    /** Identifier-only responder identity used by the durable human-interaction assignment gate. */
    public static InvocationIdentity interactionIdentity(WorkspaceId workspace) {
        AuthenticatedPrincipal principal = require();
        if (!principal.workspace().map(workspace::equals).orElse(false)) {
            throw new WorkspaceScopeUnavailableException();
        }
        return new InvocationIdentity(principal.organizationId().value(), workspace.value(), principal.subjectId(),
                principal.credentialId(), principal.subjectId());
    }

    /** The audit actor for control actions; see {@link AuthenticatedPrincipal#actor()}. */
    public static String actor() {
        return require().actor();
    }
}
