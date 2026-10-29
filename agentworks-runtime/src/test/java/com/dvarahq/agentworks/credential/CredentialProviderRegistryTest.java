package com.dvarahq.agentworks.credential;

import com.dvarahq.agentworks.domain.security.CredentialRef;
import com.dvarahq.agentworks.domain.security.InvocationIdentity;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CredentialProviderRegistryTest {
    private static final Instant NOW = Instant.parse("2026-10-08T12:00:00Z");
    private static final InvocationIdentity IDENTITY = new InvocationIdentity(UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

    @Test
    void routesAuthorizationAndResolutionByExactReferenceScheme() {
        RecordingProvider environment = new RecordingProvider();
        RecordingProvider file = new RecordingProvider();
        CredentialProviderRegistry registry = new CredentialProviderRegistry(List.of(
                new CredentialProviderRegistration("env", environment),
                new CredentialProviderRegistration("file", file)));

        CredentialAuthorization authorization = registry.authorize(new CredentialRef("file:///run/secrets/api-key"),
                IDENTITY, NOW);
        try (ResolvedCredential credential = registry.resolve(authorization, NOW)) {
            String material = credential.use(NOW, value -> new String(value));
            assertThat(material).isEqualTo("credential-canary");
        }

        assertThat(environment.authorizations).hasValue(0);
        assertThat(environment.resolutions).hasValue(0);
        assertThat(file.authorizations).hasValue(1);
        assertThat(file.resolutions).hasValue(1);
        assertThat(registry.toString()).isEqualTo("CredentialProviderRegistry[schemes=[env, file]]")
                .doesNotContain("credential-canary");
    }

    @Test
    void preservesAgentIdentityWhenRoutingTheCompleteUseRequest() {
        AtomicReference<CredentialUseRequest> observed = new AtomicReference<>();
        RecordingProvider provider = new RecordingProvider() {
            @Override
            public CredentialAuthorization authorize(CredentialUseRequest request, Instant now) {
                observed.set(request);
                return super.authorize(request.credentialRef(), request.identity(), now);
            }
        };
        CredentialProviderRegistry registry = new CredentialProviderRegistry(List.of(
                new CredentialProviderRegistration("file", provider)));
        UUID agentId = UUID.randomUUID();
        CredentialUseRequest request = CredentialUseRequest.forAgent(
                new CredentialRef("file:///run/secrets/api-key"), IDENTITY, agentId);

        registry.authorize(request, NOW);

        assertThat(observed.get()).isEqualTo(request);
        assertThat(observed.get().agentId()).contains(agentId);
    }

    @Test
    void rejectsUnknownMissingAndMalformedSchemesWithoutEchoingTheReference() {
        CredentialProviderRegistry registry = new CredentialProviderRegistry(List.of(
                new CredentialProviderRegistration("env", new RecordingProvider())));

        for (String reference : List.of("vault://secret-canary", "secret-canary", "env://bad path")) {
            assertThatThrownBy(() -> registry.authorize(new CredentialRef(reference), IDENTITY, NOW))
                    .isInstanceOf(CredentialResolutionException.class)
                    .hasMessage("no credential provider is registered for the reference scheme")
                    .hasMessageNotContaining(reference);
        }
    }

    @Test
    void rejectsInvalidAndDuplicateRegistrations() {
        RecordingProvider provider = new RecordingProvider();
        assertThatThrownBy(() -> new CredentialProviderRegistration("ENV", provider))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("lower-case URI scheme");
        assertThatThrownBy(() -> new CredentialProviderRegistry(List.of(
                new CredentialProviderRegistration("env", provider),
                new CredentialProviderRegistration("env", new RecordingProvider()))))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("duplicate credential provider scheme: env");
        assertThatThrownBy(() -> new CredentialProviderRegistry(List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("at least one credential provider is required");
    }

    @Test
    void refusesAProviderThatChangesScopeOrReturnsNoMaterial() {
        CredentialProvider changingScope = new RecordingProvider() {
            @Override
            public CredentialAuthorization authorize(CredentialRef credentialRef, InvocationIdentity identity,
                    Instant now) {
                return new CredentialAuthorization(UUID.randomUUID(), new CredentialRef("file:///other"), identity);
            }
        };
        CredentialProvider noMaterial = new RecordingProvider() {
            @Override
            public ResolvedCredential resolve(CredentialAuthorization authorization, Instant now) {
                return null;
            }
        };

        CredentialProviderRegistry changing = new CredentialProviderRegistry(List.of(
                new CredentialProviderRegistration("file", changingScope)));
        assertThatThrownBy(() -> changing.authorize(new CredentialRef("file:///expected"), IDENTITY, NOW))
                .isInstanceOf(CredentialResolutionException.class)
                .hasMessage("credential provider returned an invalid authorization");

        CredentialProviderRegistry empty = new CredentialProviderRegistry(List.of(
                new CredentialProviderRegistration("file", noMaterial)));
        CredentialAuthorization authorization = empty.authorize(new CredentialRef("file:///expected"), IDENTITY, NOW);
        assertThatThrownBy(() -> empty.resolve(authorization, NOW))
                .isInstanceOf(CredentialResolutionException.class)
                .hasMessage("credential provider returned no credential material");
    }

    private static class RecordingProvider implements CredentialProvider {
        private final AtomicInteger authorizations = new AtomicInteger();
        private final AtomicInteger resolutions = new AtomicInteger();

        @Override
        public CredentialAuthorization authorize(CredentialRef credentialRef, InvocationIdentity identity, Instant now) {
            authorizations.incrementAndGet();
            return new CredentialAuthorization(UUID.randomUUID(), credentialRef, identity);
        }

        @Override
        public ResolvedCredential resolve(CredentialAuthorization authorization, Instant now) {
            resolutions.incrementAndGet();
            return new ResolvedCredential("credential-canary".toCharArray(), now.plusSeconds(60));
        }

        @Override
        public String toString() {
            return "credential-canary";
        }
    }
}
