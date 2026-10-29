package com.dvarahq.agentworks.controlplane.security;

import com.dvarahq.agentworks.domain.security.PermissionRisk;
import com.dvarahq.agentworks.domain.security.PermissionScope;
import com.dvarahq.agentworks.domain.security.RememberedPermissionRule;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import com.dvarahq.agentworks.storage.TenantTransactionTemplate;
import com.dvarahq.agentworks.storage.security.JdbcRememberedPermissionAuthority;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Tenant-bound authoring and revocation of immutable remembered-permission versions. */
public final class RememberedPermissionService {
    public record Draft(UUID id, RememberedPermissionRule.Effect effect, PermissionScope.Level scope, UUID actorId,
            Optional<UUID> agentDefinitionId, Optional<UUID> releaseId, String capability, String resource,
            String action, UUID environmentId, PermissionRisk maximumRisk, Optional<Instant> validFrom,
            Instant expiresAt) {
        public Draft {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(effect, "effect");
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(actorId, "actorId");
            agentDefinitionId = Objects.requireNonNull(agentDefinitionId, "agentDefinitionId");
            releaseId = Objects.requireNonNull(releaseId, "releaseId");
            Objects.requireNonNull(capability, "capability");
            Objects.requireNonNull(resource, "resource");
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(environmentId, "environmentId");
            Objects.requireNonNull(maximumRisk, "maximumRisk");
            validFrom = Objects.requireNonNull(validFrom, "validFrom");
            Objects.requireNonNull(expiresAt, "expiresAt");
        }
    }

    public record Revocation(RememberedPermissionRule rule, boolean changed) {}
    public record Creation(RememberedPermissionRule rule, boolean changed) {}
    public record PermissionPage(List<RememberedPermissionRule> items, String nextCursor) {}

    private final TenantTransactionTemplate transactions;
    private final JdbcRememberedPermissionAuthority authority;

    public RememberedPermissionService(TenantTransactionTemplate transactions,
            JdbcRememberedPermissionAuthority authority) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.authority = Objects.requireNonNull(authority, "authority");
    }

    public Creation create(WorkspaceId workspace, Draft draft) {
        Objects.requireNonNull(draft, "draft");
        return transactions.execute(workspace, () -> {
            Instant now = authority.databaseNow();
            UUID scopeId = draft.scope() == PermissionScope.Level.ORGANIZATION
                    ? authority.organizationId() : workspace.value();
            Optional<RememberedPermissionRule> existing = authority.latest(draft.id());
            if (existing.isPresent()) {
                if (!matches(existing.orElseThrow(), draft, scopeId)) {
                    throw new RememberedPermissionConflictException(draft.id());
                }
                return new Creation(existing.orElseThrow(), false);
            }
            var rule = new RememberedPermissionRule(draft.id(), 1, draft.effect(),
                    new PermissionScope(draft.scope(), scopeId), draft.actorId(), draft.agentDefinitionId(),
                    draft.releaseId(), draft.capability(), draft.resource(), draft.action(), draft.environmentId(),
                    draft.maximumRisk(), databasePrecision(draft.validFrom().orElse(now)),
                    databasePrecision(draft.expiresAt()), Optional.empty());
            authority.validateManagementReferences(rule);
            authority.append(rule);
            return new Creation(rule, true);
        });
    }

    private static boolean matches(RememberedPermissionRule rule, Draft draft, UUID scopeId) {
        return rule.version() == 1 && rule.revokedAt().isEmpty() && rule.effect() == draft.effect()
                && rule.scope().equals(new PermissionScope(draft.scope(), scopeId))
                && rule.actorId().equals(draft.actorId()) && rule.agentDefinitionId().equals(draft.agentDefinitionId())
                && rule.releaseId().equals(draft.releaseId()) && rule.capability().equals(draft.capability())
                && rule.resource().equals(draft.resource()) && rule.action().equals(draft.action())
                && rule.environmentId().equals(draft.environmentId()) && rule.maximumRisk() == draft.maximumRisk()
                && draft.validFrom().map(RememberedPermissionService::databasePrecision)
                        .map(rule.validFrom()::equals).orElse(true)
                && rule.expiresAt().equals(databasePrecision(draft.expiresAt()));
    }

    private static Instant databasePrecision(Instant value) {
        return value.truncatedTo(ChronoUnit.MICROS);
    }

    public PermissionPage list(WorkspaceId workspace, String cursor, int limit) {
        if (limit < 1 || limit > 200) throw new IllegalArgumentException("permission page limit must be 1 to 200");
        Optional<UUID> after;
        try {
            after = cursor == null ? Optional.empty() : Optional.of(UUID.fromString(cursor));
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("permission cursor is invalid", invalid);
        }
        return transactions.execute(workspace, () -> {
            List<RememberedPermissionRule> rows = authority.latest(after, limit + 1);
            String next = rows.size() > limit ? rows.get(limit - 1).id().toString() : null;
            return new PermissionPage(rows.stream().limit(limit).toList(), next);
        });
    }

    public RememberedPermissionRule get(WorkspaceId workspace, UUID id) {
        return transactions.execute(workspace, () -> authority.latest(id)
                .orElseThrow(() -> new RememberedPermissionNotFoundException(id)));
    }

    public Revocation revoke(WorkspaceId workspace, UUID id) {
        return transactions.execute(workspace, () -> {
            RememberedPermissionRule current = authority.lockLatest(id)
                    .orElseThrow(() -> new RememberedPermissionNotFoundException(id));
            if (current.revokedAt().isPresent()) return new Revocation(current, false);
            Instant revokedAt = authority.databaseNow();
            if (revokedAt.isBefore(current.validFrom())) revokedAt = current.validFrom();
            var revoked = new RememberedPermissionRule(current.id(), current.version() + 1, current.effect(),
                    current.scope(), current.actorId(), current.agentDefinitionId(), current.releaseId(),
                    current.capability(), current.resource(), current.action(), current.environmentId(),
                    current.maximumRisk(), current.validFrom(), current.expiresAt(), Optional.of(revokedAt));
            authority.append(revoked);
            return new Revocation(revoked, true);
        });
    }
}
