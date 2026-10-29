package com.dvarahq.agentworks.credential;

import com.dvarahq.agentworks.domain.security.CredentialRef;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Persistable evidence intentionally contains identity/reference metadata only. */
public record CredentialAuditEvent(
        String action,
        String outcome,
        CredentialRef credentialRef,
        UUID organizationId,
        UUID workspaceId,
        UUID userId,
        UUID sessionId,
        UUID delegatedSubjectId,
        Instant occurredAt
) {
    public CredentialAuditEvent {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(credentialRef, "credentialRef");
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(delegatedSubjectId, "delegatedSubjectId");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
