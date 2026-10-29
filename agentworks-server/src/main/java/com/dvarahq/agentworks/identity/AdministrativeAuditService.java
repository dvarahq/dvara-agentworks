package com.dvarahq.agentworks.identity;

import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import com.dvarahq.agentworks.storage.TenantTransactionTemplate;
import com.dvarahq.agentworks.storage.identity.IdentityRows.AuditEvent;
import com.dvarahq.agentworks.storage.identity.JdbcIdentityRepository;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Workspace-scoped read authority for the append-only authentication and administration audit trail. */
public final class AdministrativeAuditService {
    private final TenantTransactionTemplate tenants;
    private final JdbcIdentityRepository repository;

    public AdministrativeAuditService(TenantTransactionTemplate tenants, JdbcIdentityRepository repository) {
        this.tenants = Objects.requireNonNull(tenants, "tenants");
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    public List<AuditEvent> list(WorkspaceId workspace, String eventType, String outcome, String targetType,
            UUID targetId, Instant beforeOccurredAt, UUID beforeId, int limit) {
        return tenants.execute(workspace, () -> repository.listAudit(eventType, outcome, targetType, targetId,
                beforeOccurredAt, beforeId, limit));
    }
}
