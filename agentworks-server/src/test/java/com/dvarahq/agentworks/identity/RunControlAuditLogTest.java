package com.dvarahq.agentworks.identity;

import com.dvarahq.agentworks.domain.identity.AuthenticatedPrincipal;
import com.dvarahq.agentworks.domain.identity.AuthenticationMethod;
import com.dvarahq.agentworks.domain.identity.WorkspaceRole;
import com.dvarahq.agentworks.domain.tenant.OrganizationId;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import com.dvarahq.agentworks.storage.run.JdbcRunControlAudit;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #198 low 4: a run-control audit target must name the workspace the action was resolved in; the recorder never falls
 * back to the principal's own workspace, which may differ from the run's.
 */
class RunControlAuditLogTest {
    @Test
    void aTargetWithoutItsResolvedWorkspaceIsRefusedBeforeAnythingIsWritten() {
        // A template without a data source: any database access would fail differently.
        var log = new RunControlAuditLog(new JdbcRunControlAudit(new JdbcTemplate(), false));
        var principal = new AuthenticatedPrincipal(UUID.randomUUID(), new OrganizationId(UUID.randomUUID()), "ops",
                new WorkspaceId(UUID.randomUUID()), WorkspaceRole.OPERATOR, WorkspaceRole.OPERATOR.permissions(),
                false, AuthenticationMethod.API_TOKEN, UUID.randomUUID(), false);

        assertThatThrownBy(() -> log.record("RUN_PAUSE_REQUESTED", principal,
                new ControlActionAudit.Target("RUN", UUID.randomUUID(), "REQUESTED")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("resolved");
    }
}
