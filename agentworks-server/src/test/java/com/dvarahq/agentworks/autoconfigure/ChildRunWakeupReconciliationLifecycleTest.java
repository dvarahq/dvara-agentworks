package com.dvarahq.agentworks.autoconfigure;

import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import com.dvarahq.agentworks.storage.child.JdbcChildRunAuthority;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ChildRunWakeupReconciliationLifecycleTest {
    @Test
    void keysetCursorMakesProgressAcrossMoreWorkspacesThanOneBatch() {
        WorkspaceId first = workspace(1), second = workspace(2), third = workspace(3);
        var pages = java.util.Map.of(nullKey(), List.of(candidate(first)), first.value(), List.of(candidate(second)),
                second.value(), List.of(candidate(third)), third.value(), List.<JdbcChildRunAuthority.ReconciliationCandidate>of());
        List<WorkspaceId> visited = new ArrayList<>();
        var scan = new ChildRunWakeupReconciliationLifecycle.WorkspaceScan();
        for (int pass = 0; pass < 3; pass++) {
            var page = scan.next((after, ignored) -> pages.get(after == null ? nullKey() : after), 1);
            assertThat(page).hasSize(1);
            visited.add(page.getFirst().workspace());
            scan.advanced(page.getFirst().workspace().value());
        }
        assertThat(visited).containsExactly(first, second, third);
    }

    private static JdbcChildRunAuthority.ReconciliationCandidate candidate(WorkspaceId workspace) {
        return new JdbcChildRunAuthority.ReconciliationCandidate(workspace, true, false);
    }

    private static WorkspaceId workspace(long value) {
        return new WorkspaceId(new UUID(0, value));
    }

    private static UUID nullKey() { return new UUID(0, 0); }
}
