package com.dvarahq.agentworks.storage;

import com.dvarahq.agentworks.domain.tenant.OrganizationId;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;
import java.util.UUID;

/**
 * Idempotent single-tenant bootstrap for simple installations: the default organization and workspace. It grants
 * no membership; the first administrator is created through the one-time installation bootstrap (issue #74).
 */
public final class DefaultTenantBootstrap {
    public static final OrganizationId DEFAULT_ORGANIZATION_ID =
            new OrganizationId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
    public static final WorkspaceId DEFAULT_WORKSPACE_ID =
            new WorkspaceId(UUID.fromString("00000000-0000-0000-0000-000000000002"));

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public DefaultTenantBootstrap(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    public BootstrapResult initialize() {
        return transactions.execute(status -> {
            if (!exists("organization", "id", DEFAULT_ORGANIZATION_ID.value())) {
                jdbc.update("insert into organization(id, name) values (?, ?)",
                        DEFAULT_ORGANIZATION_ID.value(), "Default Organization");
            }
            if (!exists("workspace", "id", DEFAULT_WORKSPACE_ID.value())) {
                jdbc.update("insert into workspace(id, organization_id, name) values (?, ?, ?)",
                        DEFAULT_WORKSPACE_ID.value(), DEFAULT_ORGANIZATION_ID.value(), "Default Workspace");
            }
            return new BootstrapResult(DEFAULT_ORGANIZATION_ID, DEFAULT_WORKSPACE_ID);
        });
    }

    private boolean exists(String table, String column, UUID id) {
        return jdbc.queryForObject("select count(*) from " + table + " where " + column + " = ?",
                Integer.class, id) == 1;
    }

    public record BootstrapResult(OrganizationId organizationId, WorkspaceId workspaceId) {}
}
