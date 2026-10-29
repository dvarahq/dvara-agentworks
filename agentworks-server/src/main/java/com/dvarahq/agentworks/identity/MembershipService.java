package com.dvarahq.agentworks.identity;

import com.dvarahq.agentworks.domain.identity.AuthenticatedPrincipal;
import com.dvarahq.agentworks.domain.identity.WorkspaceRole;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import com.dvarahq.agentworks.storage.TenantTransactionTemplate;
import com.dvarahq.agentworks.storage.identity.IdentityRows.MemberRow;
import com.dvarahq.agentworks.storage.identity.IdentityRows.UserRow;
import com.dvarahq.agentworks.storage.identity.IdentityRows.WorkspaceMembershipRow;
import com.dvarahq.agentworks.storage.identity.JdbcIdentityRepository;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Workspace membership administration. Every change runs in the principal's workspace transaction, re-checks
 * under lock that the actor still administers the workspace, admits only users of the workspace's organization,
 * and never removes or demotes the last administrator. Removing a membership deletes the sessions and tokens
 * bound to it in the same transaction.
 */
public final class MembershipService {
    private final TransactionTemplate transactions;
    private final TenantTransactionTemplate tenants;
    private final JdbcIdentityRepository repository;
    private final AuthAuditLog audit;
    private final Clock clock;

    public MembershipService(TransactionTemplate transactions, JdbcIdentityRepository repository, AuthAuditLog audit,
            Clock clock) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.tenants = new TenantTransactionTemplate(transactions);
        this.repository = Objects.requireNonNull(repository, "repository");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public List<MemberRow> list(AuthenticatedPrincipal principal) {
        return tenants.execute(workspace(principal), repository::listMembers);
    }

    /** Grants or changes a member's role; returns true when the membership was created. */
    public boolean put(AuthenticatedPrincipal principal, UUID subjectId, WorkspaceRole role) {
        Objects.requireNonNull(role, "role");
        return tenants.execute(workspace(principal), () -> {
            List<UUID> administrators = lockedAdministrators(principal);
            UserRow user = repository.lockUser(subjectId)
                    .filter(row -> row.organizationId().equals(principal.organizationId().value()))
                    .orElseThrow(IdentityException::notFound);
            if (role != WorkspaceRole.ADMIN && administrators.equals(List.of(user.id()))) throw lastAdministrator();
            boolean created = repository.putMembership(user.organizationId(), user.id(), role.name(), clock.instant());
            audit.record("MEMBERSHIP_SET", principal, "USER", user.id(), role.name());
            return created;
        });
    }

    /** Removes a membership; its sessions and API tokens are deleted with it. */
    public void remove(AuthenticatedPrincipal principal, UUID subjectId) {
        tenants.executeWithoutResult(workspace(principal), () -> {
            List<UUID> administrators = lockedAdministrators(principal);
            repository.findMembership(principal.workspaceId().value(), subjectId).orElseThrow(IdentityException::notFound);
            if (administrators.equals(List.of(subjectId))) throw lastAdministrator();
            repository.deleteMembership(subjectId);
            audit.record("MEMBERSHIP_REMOVED", principal, "USER", subjectId, null);
        });
    }

    /** The workspaces the principal's user belongs to. */
    public List<WorkspaceMembershipRow> workspaces(AuthenticatedPrincipal principal) {
        return transactions.execute(status -> repository.membershipsOf(principal.subjectId()));
    }

    private List<UUID> lockedAdministrators(AuthenticatedPrincipal principal) {
        List<UUID> administrators = repository.lockAdministrators();
        if (!administrators.contains(principal.subjectId())) {
            throw new IdentityException(IdentityException.ROLE_NOT_GRANTABLE,
                    "The actor no longer administers this workspace.");
        }
        return administrators;
    }

    private static IdentityException lastAdministrator() {
        return new IdentityException(IdentityException.LAST_ADMINISTRATOR,
                "A workspace must keep at least one administrator.");
    }

    private static WorkspaceId workspace(AuthenticatedPrincipal principal) {
        return principal.workspace().orElseThrow(() -> new IdentityException(
                IdentityException.WORKSPACE_SCOPE_UNAVAILABLE, "No workspace is selected."));
    }
}
