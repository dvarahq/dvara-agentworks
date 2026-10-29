package com.dvarahq.agentworks.storage;

import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;
import java.util.function.Supplier;

public final class TenantTransactionTemplate {
    private final TransactionTemplate transactions;
    public TenantTransactionTemplate(TransactionTemplate transactions) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }
    public <T> T execute(WorkspaceId workspaceId, Supplier<T> work) {
        return transactions.execute(status -> {
            try (var ignored = TenantContext.bind(workspaceId)) { return work.get(); }
        });
    }
    public void executeWithoutResult(WorkspaceId workspaceId, Runnable work) {
        execute(workspaceId, () -> { work.run(); return null; });
    }
}
