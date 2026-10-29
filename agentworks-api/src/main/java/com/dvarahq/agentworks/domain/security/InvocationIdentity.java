package com.dvarahq.agentworks.domain.security;

import java.util.Objects;
import java.util.UUID;

/** Tenant and delegated principal scope required at credential resolution time. */
public record InvocationIdentity(
        UUID organizationId,
        UUID workspaceId,
        UUID userId,
        UUID sessionId,
        UUID delegatedSubjectId
) {
    public InvocationIdentity {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(delegatedSubjectId, "delegatedSubjectId");
    }
}
