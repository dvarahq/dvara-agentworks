package com.dvarahq.agentworks.runplane.execution;

import com.dvarahq.agentworks.credential.CredentialAuditEvent;
import com.dvarahq.agentworks.credential.CredentialAuthorization;
import com.dvarahq.agentworks.credential.CredentialProvider;
import com.dvarahq.agentworks.credential.CredentialResolutionException;
import com.dvarahq.agentworks.credential.CredentialUseRequest;
import com.dvarahq.agentworks.credential.ResolvedCredential;
import com.dvarahq.agentworks.domain.security.CredentialRef;
import com.dvarahq.agentworks.domain.security.InvocationIdentity;
import com.dvarahq.agentworks.interaction.HumanInteractionAuthorityException;
import com.dvarahq.agentworks.interaction.HumanInteractionPrompt;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DelegatedAuthorizationExchangeTest {
    private static final Instant NOW = Instant.parse("2026-10-08T20:00:00Z");
    private static final CredentialRef REFERENCE = new CredentialRef("env://calendar-token");
    private static final InvocationIdentity IDENTITY = new InvocationIdentity(UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    private static final UUID AGENT = UUID.randomUUID();
    private static final HumanInteractionPrompt.Authorization PROMPT = new HumanInteractionPrompt.Authorization(
            "Calendar", "Connect", REFERENCE, Set.of("calendar.read"), "calendar-oauth-v3");

    @Test
    void verifiesTheCompleteCurrentScopeAuditsAndZeroizesMaterial() {
        AtomicReference<CredentialUseRequest> request = new AtomicReference<>();
        AtomicReference<ResolvedCredential> material = new AtomicReference<>();
        List<CredentialAuditEvent> audit = new ArrayList<>();
        CredentialProvider provider = provider(request, material, false);

        new DelegatedAuthorizationExchange(provider, audit::add, clock()).requireCurrent(PROMPT, IDENTITY, AGENT);

        assertThat(request.get()).isEqualTo(CredentialUseRequest.forAgent(REFERENCE, IDENTITY, AGENT));
        assertThat(audit).singleElement().satisfies(event -> {
            assertThat(event.action()).isEqualTo(DelegatedAuthorizationExchange.AUDIT_ACTION);
            assertThat(event.outcome()).isEqualTo("SUCCEEDED");
            assertThat(event.toString()).doesNotContain("secret-canary");
        });
        assertThatThrownBy(() -> material.get().use(NOW, chars -> new String(chars)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("closed");
    }

    @Test
    void unavailableOrWronglyScopedMaterialIsTypedAndAuditedWithoutProviderDetails() {
        List<CredentialAuditEvent> audit = new ArrayList<>();
        CredentialProvider provider = provider(new AtomicReference<>(), new AtomicReference<>(), true);
        DelegatedAuthorizationExchange exchange = new DelegatedAuthorizationExchange(provider, audit::add, clock());

        assertThatThrownBy(() -> exchange.requireCurrent(PROMPT, IDENTITY, AGENT))
                .isInstanceOfSatisfying(HumanInteractionAuthorityException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(HumanInteractionAuthorityException.Code.CREDENTIAL_UNAVAILABLE);
                    assertThat(failure.getMessage()).doesNotContain("provider-secret-detail");
                });
        assertThat(audit).singleElement().extracting(CredentialAuditEvent::outcome)
                .isEqualTo("DENIED_OR_FAILED");
    }

    private static CredentialProvider provider(AtomicReference<CredentialUseRequest> request,
            AtomicReference<ResolvedCredential> material, boolean fail) {
        return new CredentialProvider() {
            @Override
            public CredentialAuthorization authorize(CredentialUseRequest value, Instant now) {
                request.set(value);
                if (fail) throw new CredentialResolutionException("provider-secret-detail");
                return new CredentialAuthorization(UUID.randomUUID(), value.credentialRef(), value.identity());
            }

            @Override
            public CredentialAuthorization authorize(CredentialRef reference, InvocationIdentity identity,
                    Instant now) {
                throw new AssertionError("identity-only authorization must not be used");
            }

            @Override
            public ResolvedCredential resolve(CredentialAuthorization authorization, Instant now) {
                ResolvedCredential value = new ResolvedCredential("secret-canary".toCharArray(), now.plusSeconds(30));
                material.set(value);
                return value;
            }
        };
    }

    private static Clock clock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }
}
