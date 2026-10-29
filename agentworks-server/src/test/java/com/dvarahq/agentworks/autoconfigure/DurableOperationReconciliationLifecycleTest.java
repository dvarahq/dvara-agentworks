package com.dvarahq.agentworks.autoconfigure;

import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import com.dvarahq.agentworks.runplane.execution.DurableOperationCoordinator;
import com.dvarahq.agentworks.storage.operation.JdbcDurableOperationAuthority;
import org.junit.jupiter.api.Test;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DurableOperationReconciliationLifecycleTest {
    @Test
    void aPassReconcilesEveryOpenOperationThroughItsWorkspaceScope() {
        DurableOperationCoordinator coordinator = mock(DurableOperationCoordinator.class);
        JdbcDurableOperationAuthority operations = mock(JdbcDurableOperationAuthority.class);
        WorkspaceId workspace = new WorkspaceId(UUID.randomUUID());
        UUID operationId = UUID.randomUUID();
        when(operations.reconciliationCandidates(2)).thenReturn(List.of(
                new JdbcDurableOperationAuthority.ReconciliationCandidate(workspace, operationId)));

        new DurableOperationReconciliationLifecycle(coordinator, operations, Duration.ofSeconds(5), 2).pass();

        verify(coordinator).reconcile(workspace, operationId);
    }

    @Test
    void aPassPublishesOnlyABoundedOutcomeDimension() {
        DurableOperationCoordinator coordinator = mock(DurableOperationCoordinator.class);
        JdbcDurableOperationAuthority operations = mock(JdbcDurableOperationAuthority.class);
        when(operations.reconciliationCandidates(2)).thenReturn(List.of());
        var metrics = new SimpleMeterRegistry();

        new DurableOperationReconciliationLifecycle(coordinator, operations, Duration.ofSeconds(5), 2, metrics).pass();

        assertThat(metrics.get("agentworks.operation.reconciliation.passes").tag("outcome", "success")
                .counter().count()).isEqualTo(1);
        assertThat(metrics.get("agentworks.operation.reconciliation.duration").tag("outcome", "success")
                .timer().count()).isEqualTo(1);
    }
}
