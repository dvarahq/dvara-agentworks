package com.dvarahq.agentworks.storage;

import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

/** Transaction-bound tenant authority. Repositories fail closed when it is absent. */
public final class TenantContext {
    private static final Object RESOURCE_KEY = TenantContext.class;

    private TenantContext() {}

    public static Scope bind(WorkspaceId workspaceId) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new MissingTenantBindingException("tenant binding requires an active transaction");
        }
        Object existing = TransactionSynchronizationManager.getResource(RESOURCE_KEY);
        if (existing != null && !existing.equals(workspaceId)) {
            throw new MissingTenantBindingException("transaction is already bound to another workspace");
        }
        if (existing != null) return new Scope(false);
        TransactionSynchronizationManager.bindResource(RESOURCE_KEY, workspaceId);
        try {
            bindPostgresSession(workspaceId);
            return new Scope(true);
        } catch (RuntimeException failure) {
            TransactionSynchronizationManager.unbindResource(RESOURCE_KEY);
            throw failure;
        }
    }

    public static WorkspaceId requireWorkspace() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new MissingTenantBindingException("repository access requires an active transaction");
        }
        Object value = TransactionSynchronizationManager.getResource(RESOURCE_KEY);
        if (!(value instanceof WorkspaceId workspaceId)) {
            throw new MissingTenantBindingException("repository access requires a workspace binding");
        }
        return workspaceId;
    }

    /**
     * Mirrors the Java tenant authority into PostgreSQL as a transaction-local setting. JDBC transaction managers
     * eagerly bind their connection before repository work starts, so this also covers authorities that use
     * {@link TenantContext} directly instead of {@link TenantTransactionTemplate}. SET LOCAL semantics ensure a
     * pooled connection cannot retain one request's workspace after commit or rollback.
     */
    private static void bindPostgresSession(WorkspaceId workspaceId) {
        for (Object resource : TransactionSynchronizationManager.getResourceMap().values()) {
            if (!(resource instanceof ConnectionHolder holder)) continue;
            Connection connection = holder.getConnection();
            try {
                if (!"PostgreSQL".equals(connection.getMetaData().getDatabaseProductName())) continue;
                try (var statement = connection.prepareStatement("select set_config('agentworks.workspace_id', ?, true)")) {
                    statement.setString(1, workspaceId.value().toString());
                    statement.execute();
                }
            } catch (SQLException failure) {
                throw new MissingTenantBindingException("could not bind the PostgreSQL transaction workspace", failure);
            }
        }
    }

    public static final class Scope implements AutoCloseable {
        private final boolean owner;
        private boolean closed;
        private Scope(boolean owner) { this.owner = owner; }
        @Override public void close() {
            if (!closed && owner) TransactionSynchronizationManager.unbindResource(RESOURCE_KEY);
            closed = true;
        }
    }
}
