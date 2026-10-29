package com.dvarahq.agentworks.identity.web;

import com.dvarahq.agentworks.domain.identity.AuthenticatedPrincipal;
import com.dvarahq.agentworks.domain.identity.AuthenticationMethod;
import com.dvarahq.agentworks.domain.identity.WorkspacePermission;
import com.dvarahq.agentworks.domain.tenant.OrganizationId;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** How {@code GET /auth/me} describes the principal. */
class SessionViewTest {

    /**
     * #203: a workspace principal whose permissions amount to no role is described with a null role (the schema allows
     * it) and its actual permissions, never with a role it does not cover.
     */
    @Test
    void aPrincipalWhosePermissionsAmountToNoRoleHasANullRole() {
        var principal = new AuthenticatedPrincipal(UUID.randomUUID(), new OrganizationId(UUID.randomUUID()), "alice",
                new WorkspaceId(UUID.randomUUID()), null, Set.of(WorkspacePermission.AGENT_WRITE), false,
                AuthenticationMethod.API_TOKEN, UUID.randomUUID(), false);

        var view = AuthController.SessionView.of(principal, null, null);

        assertThat(view.workspaceId()).isEqualTo(principal.workspaceId().value());
        assertThat(view.role()).isNull();
        assertThat(view.permissions()).containsExactly("AGENT_WRITE");
    }
}
