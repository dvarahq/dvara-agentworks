package com.dvarahq.agentworks.identity;

import com.dvarahq.agentworks.domain.identity.AuthenticatedPrincipal;
import com.dvarahq.agentworks.storage.identity.IdentityRows.AuditEvent;
import com.dvarahq.agentworks.storage.identity.JdbcIdentityRepository;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

/**
 * Writes the append-only authentication and administration audit trail. It is called inside the transaction that
 * makes the audited change, so a fact and its record commit together. Details are fixed reason codes; nothing
 * from a request body, and no credential material, is ever recorded.
 */
public final class AuthAuditLog {
    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String FAILED = "FAILED";
    public static final String DENIED = "DENIED";

    private final JdbcIdentityRepository repository;
    private final Clock clock;

    public AuthAuditLog(JdbcIdentityRepository repository, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** A change made by an authenticated principal. */
    public void record(String type, AuthenticatedPrincipal actor, String targetType, UUID targetId, String detail) {
        record(type, SUCCEEDED, actor.organizationId().value(),
                actor.workspace().map(workspace -> workspace.value()).orElse(null), actor.subjectId(),
                actor.method().value(), actor.credentialId(), targetType, targetId, detail);
    }

    public void record(String type, String outcome, UUID organizationId, UUID workspaceId, UUID actorId,
            String method, UUID credentialId, String targetType, UUID targetId, String detail) {
        repository.insertAudit(new AuditEvent(UUID.randomUUID(), clock.instant(), type, outcome, organizationId,
                workspaceId, actorId, method, credentialId, targetType, targetId, detail, remoteAddress()));
    }

    static String remoteAddress() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) return null;
        String address = attributes.getRequest().getRemoteAddr();
        return address == null || address.length() > 64 ? null : address;
    }
}
