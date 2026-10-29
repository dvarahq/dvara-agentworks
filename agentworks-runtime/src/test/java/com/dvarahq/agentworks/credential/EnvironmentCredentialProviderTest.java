package com.dvarahq.agentworks.credential;

import com.dvarahq.agentworks.domain.security.CredentialRef;
import com.dvarahq.agentworks.domain.security.InvocationIdentity;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EnvironmentCredentialProviderTest {
    private static final Instant NOW = Instant.parse("2026-10-08T12:00:00Z");
    private static final CredentialRef REFERENCE = new CredentialRef("env://calendar-token");
    private static final String VARIABLE = "CALENDAR_TOKEN";
    private static final String SECRET_A = "environment-secret-a-49de";
    private static final String SECRET_B = "environment-secret-b-82a1";
    private static final UUID AGENT = UUID.randomUUID();
    private static final InvocationIdentity IDENTITY = new InvocationIdentity(UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

    @Test
    void readsOnlyAtResolutionAndObservesRotationWithoutCaching() {
        Map<String, String> environment = new HashMap<>();
        EnvironmentCredentialProvider provider = provider(CredentialScope.workspace(IDENTITY.workspaceId()), environment);
        CredentialUseRequest request = CredentialUseRequest.forAgent(REFERENCE, IDENTITY, AGENT);

        CredentialAuthorization first = provider.authorize(request, NOW);
        environment.put(VARIABLE, SECRET_A);
        assertThat(resolve(provider, first, NOW)).isEqualTo(SECRET_A);

        environment.put(VARIABLE, SECRET_B);
        CredentialAuthorization second = provider.authorize(request, NOW);
        assertThat(resolve(provider, second, NOW)).isEqualTo(SECRET_B);
        assertThat(provider.toString() + provider.registration()).doesNotContain(SECRET_A, SECRET_B, VARIABLE);
    }

    @Test
    void everySupportedScopeIsExactAndAgentNeedsAgentContext() {
        Map<String, String> environment = new HashMap<>(Map.of(VARIABLE, SECRET_A));
        CredentialUseRequest request = CredentialUseRequest.forAgent(REFERENCE, IDENTITY, AGENT);
        for (CredentialScope scope : new CredentialScope[] { CredentialScope.platform(),
                CredentialScope.organization(IDENTITY.organizationId()),
                CredentialScope.workspace(IDENTITY.workspaceId()), CredentialScope.agent(AGENT),
                CredentialScope.user(IDENTITY.userId()), CredentialScope.session(IDENTITY.sessionId()) }) {
            EnvironmentCredentialProvider provider = provider(scope, environment);
            assertThat(resolve(provider, provider.authorize(request, NOW), NOW)).isEqualTo(SECRET_A);
        }

        assertThatThrownBy(() -> provider(CredentialScope.workspace(UUID.randomUUID()), environment)
                .authorize(request, NOW)).isInstanceOf(CredentialResolutionException.class);
        assertThatThrownBy(() -> provider(CredentialScope.agent(AGENT), environment)
                .authorize(REFERENCE, IDENTITY, NOW)).isInstanceOf(CredentialResolutionException.class);
    }

    @Test
    void removalOutageExpiryReplayAndTamperFailClosed() {
        Map<String, String> environment = new HashMap<>(Map.of(VARIABLE, SECRET_A));
        EnvironmentCredentialProvider provider = provider(CredentialScope.platform(), environment);
        CredentialAuthorization removed = provider.authorize(REFERENCE, IDENTITY, NOW);
        environment.remove(VARIABLE);
        assertThatThrownBy(() -> provider.resolve(removed, NOW)).isInstanceOf(CredentialResolutionException.class);

        environment.put(VARIABLE, SECRET_A);
        CredentialAuthorization expired = provider.authorize(REFERENCE, IDENTITY, NOW);
        assertThatThrownBy(() -> provider.resolve(expired, NOW.plusSeconds(5)))
                .isInstanceOf(CredentialResolutionException.class);

        CredentialAuthorization singleUse = provider.authorize(REFERENCE, IDENTITY, NOW);
        assertThat(resolve(provider, singleUse, NOW)).isEqualTo(SECRET_A);
        assertThatThrownBy(() -> provider.resolve(singleUse, NOW)).isInstanceOf(CredentialResolutionException.class);

        CredentialAuthorization tampered = provider.authorize(REFERENCE, IDENTITY, NOW);
        InvocationIdentity other = new InvocationIdentity(IDENTITY.organizationId(), IDENTITY.workspaceId(),
                UUID.randomUUID(), IDENTITY.sessionId(), IDENTITY.delegatedSubjectId());
        CredentialAuthorization changed = new CredentialAuthorization(tampered.grantId(), REFERENCE, other);
        assertThatThrownBy(() -> provider.resolve(changed, NOW)).isInstanceOf(CredentialResolutionException.class);

        EnvironmentCredentialProvider unavailable = new EnvironmentCredentialProvider(
                Map.of(REFERENCE, new EnvironmentCredentialProvider.Binding(VARIABLE, CredentialScope.platform())),
                Duration.ofSeconds(5), name -> { throw new IllegalStateException("source down"); });
        CredentialAuthorization outage = unavailable.authorize(REFERENCE, IDENTITY, NOW);
        assertThatThrownBy(() -> unavailable.resolve(outage, NOW))
                .isInstanceOf(CredentialResolutionException.class).hasMessageNotContaining("source down");
    }

    @Test
    void configurationRejectsInvalidVariablesReferencesAndTtl() {
        assertThatThrownBy(() -> new EnvironmentCredentialProvider.Binding("bad-name", CredentialScope.platform()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EnvironmentCredentialProvider(
                Map.of(new CredentialRef("file://secret"),
                        new EnvironmentCredentialProvider.Binding(VARIABLE, CredentialScope.platform()))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EnvironmentCredentialProvider(
                Map.of(REFERENCE, new EnvironmentCredentialProvider.Binding(VARIABLE, CredentialScope.platform())),
                Duration.ZERO, name -> SECRET_A)).isInstanceOf(IllegalArgumentException.class);
    }

    private static EnvironmentCredentialProvider provider(CredentialScope scope, Map<String, String> environment) {
        return new EnvironmentCredentialProvider(
                Map.of(REFERENCE, new EnvironmentCredentialProvider.Binding(VARIABLE, scope)), Duration.ofSeconds(5),
                environment::get);
    }

    private static String resolve(EnvironmentCredentialProvider provider, CredentialAuthorization authorization,
            Instant now) {
        try (ResolvedCredential credential = provider.resolve(authorization, now)) {
            return credential.use(now, String::new);
        }
    }
}
