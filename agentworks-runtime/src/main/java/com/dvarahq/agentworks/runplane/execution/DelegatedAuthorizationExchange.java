package com.dvarahq.agentworks.runplane.execution;

import com.dvarahq.agentworks.credential.CredentialAuditEvent;
import com.dvarahq.agentworks.credential.CredentialAuditSink;
import com.dvarahq.agentworks.credential.CredentialProvider;
import com.dvarahq.agentworks.credential.CredentialUseRequest;
import com.dvarahq.agentworks.credential.ResolvedCredential;
import com.dvarahq.agentworks.domain.security.InvocationIdentity;
import com.dvarahq.agentworks.interaction.HumanInteractionAuthorityException;
import com.dvarahq.agentworks.interaction.HumanInteractionPrompt;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Provider-neutral, secret-free proof that a delegated grant has current material under its exact principal and
 * pinned-agent scope. Material is only touched inside {@link ResolvedCredential#use} and is immediately zeroized.
 */
public final class DelegatedAuthorizationExchange {
    public static final String AUDIT_ACTION = "DELEGATED_AUTHORIZATION_EXCHANGE";

    private final CredentialProvider provider;
    private final CredentialAuditSink audit;
    private final Clock clock;

    public DelegatedAuthorizationExchange(CredentialProvider provider, CredentialAuditSink audit, Clock clock) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public void requireCurrent(HumanInteractionPrompt.Authorization prompt, InvocationIdentity identity,
            UUID agentId) {
        Objects.requireNonNull(prompt, "prompt");
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(agentId, "agentId");
        try {
            Instant authorizedAt = clock.instant();
            var authorization = provider.authorize(
                    CredentialUseRequest.forAgent(prompt.credentialRef(), identity, agentId), authorizedAt);
            try (ResolvedCredential credential = provider.resolve(authorization, clock.instant())) {
                credential.use(clock.instant(), material -> null);
                audit.record(event("SUCCEEDED", prompt, identity, clock.instant()));
            }
        } catch (RuntimeException failure) {
            recordFailure(prompt, identity);
            throw unavailable(failure);
        }
    }

    private void recordFailure(HumanInteractionPrompt.Authorization prompt, InvocationIdentity identity) {
        try {
            audit.record(event("DENIED_OR_FAILED", prompt, identity, clock.instant()));
        } catch (RuntimeException ignored) {
            // The public result remains a credential refusal and never exposes provider or audit-sink details.
        }
    }

    private static CredentialAuditEvent event(String outcome, HumanInteractionPrompt.Authorization prompt,
            InvocationIdentity identity, Instant occurredAt) {
        return new CredentialAuditEvent(AUDIT_ACTION, outcome, prompt.credentialRef(),
                identity.organizationId(), identity.workspaceId(), identity.userId(), identity.sessionId(),
                identity.delegatedSubjectId(), occurredAt);
    }

    private static HumanInteractionAuthorityException unavailable(RuntimeException cause) {
        return new HumanInteractionAuthorityException(
                HumanInteractionAuthorityException.Code.CREDENTIAL_UNAVAILABLE,
                "current credential is unavailable for delegated authorization", cause);
    }
}
