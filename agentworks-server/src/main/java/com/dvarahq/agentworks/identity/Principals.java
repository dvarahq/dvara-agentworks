package com.dvarahq.agentworks.identity;

import com.dvarahq.agentworks.domain.identity.AuthenticatedPrincipal;
import com.dvarahq.agentworks.domain.identity.AuthenticationMethod;
import com.dvarahq.agentworks.domain.identity.WorkspacePermission;
import com.dvarahq.agentworks.domain.identity.WorkspaceRole;
import com.dvarahq.agentworks.domain.tenant.OrganizationId;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import com.dvarahq.agentworks.storage.identity.IdentityRows.CredentialGrant;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/** Builds principals from authenticated credential grants; any inconsistent grant is refused. */
final class Principals {
    private Principals() {}

    /**
     * @param tokenRole the API token's role, which narrows the membership role, or {@code null} for a session
     */
    static Optional<AuthenticatedPrincipal> of(CredentialGrant grant, AuthenticationMethod method, String tokenRole) {
        if (!IdentityService.ACTIVE.equals(grant.userStatus())) return Optional.empty();
        WorkspaceId workspace = null;
        WorkspaceRole role = null;
        Set<WorkspacePermission> permissions = EnumSet.noneOf(WorkspacePermission.class);
        if (grant.workspaceId() != null) {
            Optional<WorkspaceRole> membership = WorkspaceRole.parse(grant.membershipRole());
            // A bound workspace without a membership only survives a missing cascade; refuse it.
            if (membership.isEmpty()) return Optional.empty();
            workspace = new WorkspaceId(grant.workspaceId());
            role = membership.get();
            permissions.addAll(role.permissions());
            if (tokenRole != null) {
                Optional<WorkspaceRole> narrowed = WorkspaceRole.parse(tokenRole);
                if (narrowed.isEmpty()) return Optional.empty();
                permissions.retainAll(narrowed.get().permissions());
                // The effective role labels the intersection, or is absent when it amounts to no role (#203); the
                // permissions themselves authorize.
                role = WorkspaceRole.effective(permissions).orElse(null);
            }
        } else if (tokenRole != null) {
            return Optional.empty();
        }
        boolean session = AuthenticationMethod.SESSION.equals(method);
        return Optional.of(new AuthenticatedPrincipal(grant.userId(), new OrganizationId(grant.organizationId()),
                grant.username(), workspace, role, permissions,
                session && IdentityService.ORGANIZATION_ADMIN.equals(grant.organizationRole()), method,
                grant.credentialId(), grant.mustChangePassword()));
    }
}
