package com.dvarahq.agentworks.credential;

import com.dvarahq.agentworks.domain.security.CredentialRef;
import com.dvarahq.agentworks.domain.security.InvocationIdentity;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CredentialScopeTest {
    private static final CredentialRef REFERENCE = new CredentialRef("env://calendar-token");

    @Test
    void allSixLevelsMatchOnlyTheirExactAvailableIdentity() {
        InvocationIdentity identity = identity();
        UUID agent = UUID.randomUUID();
        CredentialUseRequest request = CredentialUseRequest.forAgent(REFERENCE, identity, agent);

        assertThat(CredentialScope.platform().permits(request)).isTrue();
        assertThat(CredentialScope.organization(identity.organizationId()).permits(request)).isTrue();
        assertThat(CredentialScope.workspace(identity.workspaceId()).permits(request)).isTrue();
        assertThat(CredentialScope.agent(agent).permits(request)).isTrue();
        assertThat(CredentialScope.user(identity.userId()).permits(request)).isTrue();
        assertThat(CredentialScope.session(identity.sessionId()).permits(request)).isTrue();

        assertThat(CredentialScope.organization(UUID.randomUUID()).permits(request)).isFalse();
        assertThat(CredentialScope.workspace(UUID.randomUUID()).permits(request)).isFalse();
        assertThat(CredentialScope.agent(UUID.randomUUID()).permits(request)).isFalse();
        assertThat(CredentialScope.user(UUID.randomUUID()).permits(request)).isFalse();
        assertThat(CredentialScope.session(UUID.randomUUID()).permits(request)).isFalse();
    }

    @Test
    void agentScopeFailsClosedWhenTheCallerHasNoAgentFact() {
        InvocationIdentity identity = identity();
        assertThat(CredentialScope.agent(UUID.randomUUID())
                .permits(CredentialUseRequest.withoutAgent(REFERENCE, identity))).isFalse();
    }

    @Test
    void onlyPlatformMayOmitAnIdentity() {
        assertThatThrownBy(() -> new CredentialScope(CredentialScope.Level.WORKSPACE, java.util.Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CredentialScope(CredentialScope.Level.PLATFORM,
                java.util.Optional.of(UUID.randomUUID()))).isInstanceOf(IllegalArgumentException.class);
    }

    private static InvocationIdentity identity() {
        return new InvocationIdentity(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID());
    }
}
