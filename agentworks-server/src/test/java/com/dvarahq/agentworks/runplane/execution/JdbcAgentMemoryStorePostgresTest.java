package com.dvarahq.agentworks.runplane.execution;

import com.dvarahq.agentworks.memory.MemoryActor;
import com.dvarahq.agentworks.memory.MemoryKind;
import com.dvarahq.agentworks.memory.MemoryQuery;
import com.dvarahq.agentworks.memory.MemoryScope;
import com.dvarahq.agentworks.memory.MemoryWrite;
import com.dvarahq.agentworks.storage.memory.JdbcAgentMemoryStore;
import com.dvarahq.agentworks.storage.session.JdbcSessionInboxAuthority;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** PostgreSQL scope, idempotency, provenance, deletion, and cache-revision evidence for issue #49. */
class JdbcAgentMemoryStorePostgresTest {
    @Test
    void conversationAndWorkingMemoryAreScopedAuthorizedIdempotentAndDeletable() throws Exception {
        RunScenario scenario = new RunScenario();
        var active = scenario.started("memory-worker", RunScenario.LEASE);
        UUID sessionId = UUID.randomUUID();
        JdbcSessionInboxAuthority sessions = new JdbcSessionInboxAuthority(scenario.fixture.jdbc,
                scenario.fixture.transactions);
        sessions.create(scenario.workspace, sessionId, active.run().pins().deploymentId());
        sessions.linkRun(scenario.workspace, sessionId, active.run().id());

        UUID user = member(scenario);
        MemoryActor actor = new MemoryActor(user, UUID.randomUUID());
        var pins = active.run().pins();
        MemoryScope scope = new MemoryScope(pins.workspaceId(), pins.definitionId(), pins.id(), sessionId);
        JdbcAgentMemoryStore store = new JdbcAgentMemoryStore(scenario.fixture.jdbc, scenario.fixture.tenants);
        List<MemoryWrite> writes = List.of(
                new MemoryWrite("conversation-user", MemoryKind.CONVERSATION, scope, pins.releaseId(),
                        pins.revisionId(), "user", "remember this", Duration.ofDays(30)),
                new MemoryWrite("working-assistant", MemoryKind.WORKING, scope, pins.releaseId(),
                        pins.revisionId(), "assistant", "temporary plan", Duration.ofMillis(10)));

        assertThat(store.commit(actor, writes)).hasSize(2);
        long conversationRevision = store.revision(MemoryKind.CONVERSATION, scope);
        assertThat(store.commit(actor, writes)).hasSize(2);
        assertThat(store.revision(MemoryKind.CONVERSATION, scope)).isEqualTo(conversationRevision);
        assertThat(store.read(actor, new MemoryQuery(MemoryKind.CONVERSATION, scope, 10, false)).getFirst().content())
                .isEmpty();
        assertThat(store.read(actor, new MemoryQuery(MemoryKind.CONVERSATION, scope, 10, true)).getFirst().content())
                .contains("remember this");
        assertThat(scenario.fixture.jdbc.queryForObject("select count(*) from agent_memory_provenance where "
                + "workspace_id = ? and operation = 'WRITE'", Long.class, pins.workspaceId())).isEqualTo(2L);

        assertThat(store.delete(actor, MemoryKind.CONVERSATION, scope)).isEqualTo(1);
        assertThat(store.revision(MemoryKind.CONVERSATION, scope)).isGreaterThan(conversationRevision);
        assertThat(store.read(actor, new MemoryQuery(MemoryKind.CONVERSATION, scope, 10, true))).isEmpty();
        Thread.sleep(20);
        assertThat(store.expire(pins.workspaceId(), Instant.now(), 100)).isEqualTo(1);
        assertThat(store.read(actor, new MemoryQuery(MemoryKind.WORKING, scope, 10, true))).isEmpty();

        var other = scenario.fixture.workspace();
        MemoryScope crossTenant = new MemoryScope(other.value(), pins.definitionId(), pins.id(), null);
        assertThatThrownBy(() -> store.read(actor, new MemoryQuery(MemoryKind.WORKING, crossTenant, 10, true)))
                .isInstanceOf(SecurityException.class);
    }

    private static UUID member(RunScenario scenario) {
        UUID user = UUID.randomUUID();
        UUID organization = scenario.fixture.jdbc.queryForObject(
                "select organization_id from workspace where id = ?", UUID.class, scenario.workspace.value());
        scenario.fixture.jdbc.update("insert into user_account(id, organization_id, username, display_name, "
                        + "organization_role, identity_provider, status, failed_login_count, must_change_password, "
                        + "created_at, updated_at) values (?, ?, ?, 'Memory Test', 'MEMBER', 'test', 'ACTIVE', 0, "
                        + "false, current_timestamp, current_timestamp)", user, organization, "memory-" + user);
        scenario.fixture.jdbc.update("insert into workspace_membership(organization_id, workspace_id, subject_id, "
                        + "membership_role, created_at, updated_at) values (?, ?, ?, 'OPERATOR', current_timestamp, "
                        + "current_timestamp)", organization, scenario.workspace.value(), user);
        return user;
    }
}
