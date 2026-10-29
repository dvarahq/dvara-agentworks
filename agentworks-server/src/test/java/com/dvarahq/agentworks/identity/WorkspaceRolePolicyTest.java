package com.dvarahq.agentworks.identity;

import com.dvarahq.agentworks.domain.identity.AuthenticatedPrincipal;
import com.dvarahq.agentworks.domain.identity.AuthenticationMethod;
import com.dvarahq.agentworks.domain.identity.WorkspacePermission;
import com.dvarahq.agentworks.domain.identity.WorkspaceRole;
import com.dvarahq.agentworks.domain.tenant.OrganizationId;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import com.dvarahq.agentworks.identity.web.AgentWorksAuthentication;
import com.dvarahq.agentworks.storage.identity.IdentityRows.CredentialGrant;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static com.dvarahq.agentworks.domain.identity.WorkspacePermission.AGENT_WRITE;
import static com.dvarahq.agentworks.domain.identity.WorkspacePermission.ARTIFACT_WRITE;
import static com.dvarahq.agentworks.domain.identity.WorkspacePermission.DEPLOYMENT_OPERATE;
import static com.dvarahq.agentworks.domain.identity.WorkspacePermission.INTERACTION_RESPOND;
import static com.dvarahq.agentworks.domain.identity.WorkspacePermission.RELEASE_WRITE;
import static com.dvarahq.agentworks.domain.identity.WorkspacePermission.RUN_ADMIT;
import static com.dvarahq.agentworks.domain.identity.WorkspacePermission.TOKEN_SELF;
import static com.dvarahq.agentworks.domain.identity.WorkspacePermission.WORKSPACE_READ;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The role-to-permission policy, token narrowing, and principal invariants. */
class WorkspaceRolePolicyTest {

    @Test
    void rolesGrantAdministrationAuthoringOperationAndReadOnlyAccess() {
        assertThat(WorkspaceRole.VIEWER.permissions())
                .containsExactlyInAnyOrder(WORKSPACE_READ, INTERACTION_RESPOND, TOKEN_SELF);
        assertThat(WorkspaceRole.AUTHOR.permissions())
                .containsExactlyInAnyOrder(WORKSPACE_READ, INTERACTION_RESPOND, TOKEN_SELF, AGENT_WRITE, RELEASE_WRITE,
                        ARTIFACT_WRITE);
        assertThat(WorkspaceRole.OPERATOR.permissions())
                .containsExactlyInAnyOrder(WORKSPACE_READ, INTERACTION_RESPOND, TOKEN_SELF, DEPLOYMENT_OPERATE,
                        RUN_ADMIT);
        assertThat(WorkspaceRole.ADMIN.permissions()).containsExactlyInAnyOrder(WorkspacePermission.values());
        // Authors and operators are separated: neither covers the other; the administrator covers both.
        assertThat(WorkspaceRole.AUTHOR.covers(WorkspaceRole.OPERATOR)).isFalse();
        assertThat(WorkspaceRole.OPERATOR.covers(WorkspaceRole.AUTHOR)).isFalse();
        assertThat(WorkspaceRole.ADMIN.covers(WorkspaceRole.AUTHOR)).isTrue();
        assertThat(WorkspaceRole.AUTHOR.covers(WorkspaceRole.VIEWER)).isTrue();
        assertThat(WorkspaceRole.VIEWER.covers(WorkspaceRole.AUTHOR)).isFalse();
        assertThat(WorkspaceRole.parse(" author ")).contains(WorkspaceRole.AUTHOR);
        assertThat(WorkspaceRole.parse("OWNER")).isEmpty();
        assertThat(WorkspaceRole.parse(null)).isEmpty();
    }

    @Test
    void aTokenIsNarrowedToTheIntersectionOfItsRoleAndTheCurrentMembership() {
        var viewerToken = Principals.of(grant("ADMIN", "VIEWER", "ACTIVE", false), AuthenticationMethod.API_TOKEN, "VIEWER")
                .orElseThrow();
        var demotedOwner = Principals.of(grant("VIEWER", "ADMIN", "ACTIVE", false), AuthenticationMethod.API_TOKEN, "ADMIN")
                .orElseThrow();

        assertThat(viewerToken.permissions()).containsExactlyInAnyOrder(WORKSPACE_READ, INTERACTION_RESPOND, TOKEN_SELF);
        assertThat(viewerToken.role()).as("the effective role of a VIEWER token owned by an ADMIN")
                .isEqualTo(WorkspaceRole.VIEWER);
        assertThat(demotedOwner.role()).isEqualTo(WorkspaceRole.VIEWER);
        assertThat(Principals.of(grant("OPERATOR", "AUTHOR", "ACTIVE", false), AuthenticationMethod.API_TOKEN, "AUTHOR")
                .orElseThrow().role()).isEqualTo(WorkspaceRole.VIEWER);
        assertThat(demotedOwner.permissions()).containsExactlyInAnyOrder(WORKSPACE_READ, INTERACTION_RESPOND, TOKEN_SELF);
        assertThat(viewerToken.organizationAdmin()).as("organization administration needs a session").isFalse();
        assertThat(Principals.of(grant("ADMIN", null, "ACTIVE", false), AuthenticationMethod.API_TOKEN, "OWNER"))
                .isEmpty();
        assertThat(Principals.of(grant(null, null, "ACTIVE", false), AuthenticationMethod.API_TOKEN, "ADMIN")).isEmpty();
        assertThat(Principals.of(grant("ADMIN", null, "DISABLED", false), AuthenticationMethod.SESSION, null)).isEmpty();
    }

    /**
     * #177: the effective role is derived from coverage, so a permission set that is no role's exact set (as a future
     * role or permission change could produce) still labels a principal instead of refusing the credential. #203: a
     * set that covers no role, not even VIEWER, has no effective role rather than being labelled VIEWER.
     */
    @Test
    void theEffectiveRoleIsTheLargestRoleThePermissionsCoverAndNoneWhenTheyCoverNone() {
        for (WorkspaceRole role : WorkspaceRole.values()) {
            assertThat(WorkspaceRole.effective(role.permissions())).contains(role);
        }
        var viewerAndAgentWrite = java.util.EnumSet.copyOf(WorkspaceRole.VIEWER.permissions());
        viewerAndAgentWrite.add(WorkspacePermission.AGENT_WRITE);
        assertThat(WorkspaceRole.effective(viewerAndAgentWrite)).contains(WorkspaceRole.VIEWER);
        var authorAndRunAdmit = java.util.EnumSet.copyOf(WorkspaceRole.AUTHOR.permissions());
        authorAndRunAdmit.add(WorkspacePermission.RUN_ADMIT);
        assertThat(WorkspaceRole.effective(authorAndRunAdmit)).contains(WorkspaceRole.AUTHOR);
        assertThat(WorkspaceRole.effective(java.util.EnumSet.of(WorkspacePermission.AGENT_WRITE))).isEmpty();
        assertThat(WorkspaceRole.effective(java.util.EnumSet.of(WORKSPACE_READ))).isEmpty();
        assertThat(WorkspaceRole.effective(java.util.EnumSet.noneOf(WorkspacePermission.class))).isEmpty();
    }

    @Test
    void aPendingPasswordChangeGrantsNoWorkspaceAuthority() {
        var principal = Principals.of(grant("ADMIN", null, "ACTIVE", true), AuthenticationMethod.SESSION, null)
                .orElseThrow();
        Set<String> authorities = authorities(new AgentWorksAuthentication(principal));

        assertThat(principal.can(WORKSPACE_READ)).isFalse();
        assertThat(authorities).containsExactlyInAnyOrder(AgentWorksAuthentication.AUTHENTICATED,
                AgentWorksAuthentication.SESSION);
    }

    @Test
    void principalsCarryIdentifiersOnlyAndAStableActor() {
        UUID subject = UUID.randomUUID();
        UUID credential = UUID.randomUUID();
        var workspace = new WorkspaceId(UUID.randomUUID());
        var session = new AuthenticatedPrincipal(subject, new OrganizationId(UUID.randomUUID()), "alice", workspace,
                WorkspaceRole.AUTHOR, WorkspaceRole.AUTHOR.permissions(), false, AuthenticationMethod.SESSION,
                credential, false);
        var token = new AuthenticatedPrincipal(subject, session.organizationId(), "alice", workspace,
                WorkspaceRole.VIEWER, WorkspaceRole.VIEWER.permissions(), false, AuthenticationMethod.API_TOKEN,
                credential, false);

        assertThat(session.actor()).isEqualTo("user:" + subject + "/session");
        assertThat(token.actor()).isEqualTo("user:" + subject + "/api-token:" + credential);
        assertThat(authorities(new AgentWorksAuthentication(token)))
                .contains(AgentWorksAuthentication.permission(WORKSPACE_READ))
                .doesNotContain(AgentWorksAuthentication.SESSION, AgentWorksAuthentication.permission(AGENT_WRITE));
        assertThatThrownBy(() -> new AuthenticatedPrincipal(subject, session.organizationId(), "alice", null, null,
                Set.of(WORKSPACE_READ), false, AuthenticationMethod.SESSION, credential, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuthenticatedPrincipal(subject, session.organizationId(), "alice", null,
                WorkspaceRole.VIEWER, Set.of(), false, AuthenticationMethod.SESSION, credential, false))
                .isInstanceOf(IllegalArgumentException.class);
        // #203: only an API token's narrowed permissions can amount to no role; a session always has its role.
        assertThatThrownBy(() -> new AuthenticatedPrincipal(subject, session.organizationId(), "alice", workspace, null,
                Set.of(), false, AuthenticationMethod.SESSION, credential, false))
                .isInstanceOf(IllegalArgumentException.class);
        // #203: a workspace principal whose permissions amount to no role carries no role label.
        assertThat(new AuthenticatedPrincipal(subject, session.organizationId(), "alice", workspace, null,
                Set.of(AGENT_WRITE), false, AuthenticationMethod.API_TOKEN, credential, false).role()).isNull();
        // #203 review: an API token's role is always the one its permissions amount to, so a dropped or wrong label
        // fails closed.
        for (WorkspaceRole label : new WorkspaceRole[]{null, WorkspaceRole.ADMIN}) {
            assertThatThrownBy(() -> new AuthenticatedPrincipal(subject, session.organizationId(), "alice", workspace,
                    label, WorkspaceRole.VIEWER.permissions(), false, AuthenticationMethod.API_TOKEN, credential, false))
                    .as(String.valueOf(label)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new AuthenticationMethod("Bearer token")).isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * #207 low: null permissions are no permission for every principal. The API-token invariant read them before they
     * were normalized, so an API token with null permissions failed with a NullPointerException instead.
     */
    @Test
    void nullPermissionsAreNoPermissionForAnApiTokenToo() {
        UUID subject = UUID.randomUUID();
        var organization = new OrganizationId(UUID.randomUUID());
        var workspace = new WorkspaceId(UUID.randomUUID());

        var token = new AuthenticatedPrincipal(subject, organization, "alice", workspace, null, null, false,
                AuthenticationMethod.API_TOKEN, UUID.randomUUID(), false);

        assertThat(token.permissions()).isEmpty();
        assertThat(token.role()).isNull();
        assertThatThrownBy(() -> new AuthenticatedPrincipal(subject, organization, "alice", workspace,
                WorkspaceRole.VIEWER, null, false, AuthenticationMethod.API_TOKEN, UUID.randomUUID(), false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Set<String> authorities(AgentWorksAuthentication authentication) {
        return authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                .collect(java.util.stream.Collectors.toSet());
    }

    private static CredentialGrant grant(String membershipRole, String tokenRole, String status, boolean mustChange) {
        Instant now = Instant.now();
        return new CredentialGrant(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                membershipRole == null && tokenRole == null ? null : UUID.randomUUID(), "alice", "ADMIN", status,
                mustChange, membershipRole, tokenRole, now, now, now.plusSeconds(60), null, "digest");
    }
}
