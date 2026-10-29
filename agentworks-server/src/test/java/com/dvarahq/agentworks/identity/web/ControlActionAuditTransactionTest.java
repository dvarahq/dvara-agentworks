package com.dvarahq.agentworks.identity.web;

import com.dvarahq.agentworks.controlplane.agent.web.WorkspaceScopeUnavailableException;
import com.dvarahq.agentworks.domain.identity.WorkspaceRole;
import com.dvarahq.agentworks.DvaraAgentWorksApplication;
import com.dvarahq.agentworks.identity.AuthAuditLog;
import com.dvarahq.agentworks.identity.ControlActionAudit;
import com.dvarahq.agentworks.identity.TestIdentities;
import com.dvarahq.agentworks.identity.TestIdentities.Member;
import com.dvarahq.agentworks.storage.DefaultTenantBootstrap;
import com.dvarahq.agentworks.storage.identity.JdbcIdentityRepository;
import com.dvarahq.agentworks.storage.lifecycle.LifecycleRetry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A control action and its audit row commit together: an audit failure rolls the action back, a success writes
 * exactly one row, and a request without a principal fails before anything is written.
 */
@SpringBootTest(classes = DvaraAgentWorksApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // A dedicated context: the audit table is constrained temporarily.
        properties = "agentworks.security.login.window=PT2M")
@Import(IdentityTestConfiguration.class)
class ControlActionAuditTransactionTest {
    private static final UUID WORKSPACE = DefaultTenantBootstrap.DEFAULT_WORKSPACE_ID.value();

    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TransactionTemplate transactions;
    @Autowired
    TestIdentities identities;
    @Autowired
    Clock clock;

    @Test
    void anAuditFailureRollsTheActionBack() {
        Member admin = identities.create(WORKSPACE, WorkspaceRole.ADMIN);
        var cli = new IdentityHttp(port).bearer(admin.token());
        String definition = cli.send("POST", "/api/v1/agents", Map.of("name", "audited")).json().path("id").asString();
        String version = cli.send("POST", "/api/v1/agents/" + definition + "/versions",
                "apiVersion: dvarahq.com/v1alpha1\nkind: Agent\nmetadata: {name: audited, version: 1.0.0}\n"
                        + "spec:\n  executor: {type: spring-ai}\n", Map.of("Content-Type", "application/yaml"))
                .json().path("id").asString();
        jdbc.execute("alter table auth_audit_event add constraint audit_refused check (event_type not in "
                + "('AGENT_DEFINITION_CREATED', 'RELEASE_CREATED', 'ENVIRONMENT_CREATED')) nocheck");
        try {
            var agent = cli.send("POST", "/api/v1/agents", Map.of("name", "unaudited-agent"));
            var release = cli.send("POST", "/api/v1/agent-versions/" + version + "/releases",
                    Map.of("name", "unaudited-release"));
            var environment = cli.send("POST", "/api/v1/environments", Map.of("name", "unaudited-environment"));

            assertThat(agent.status()).isEqualTo(500);
            assertThat(release.status()).isEqualTo(500);
            assertThat(environment.status()).isEqualTo(500);
        } finally {
            jdbc.execute("alter table auth_audit_event drop constraint audit_refused");
        }
        assertThat(jdbc.queryForObject("select count(*) from agent_definition where name = 'unaudited-agent'",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from agent_release where release_name = 'unaudited-release'",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from agent_environment where name = 'unaudited-environment'",
                Integer.class)).isZero();
    }

    @Test
    void aSuccessfulActionWritesExactlyOneAuditRow() {
        Member admin = identities.create(WORKSPACE, WorkspaceRole.ADMIN);
        String environment = new IdentityHttp(port).bearer(admin.token())
                .send("POST", "/api/v1/environments", Map.of("name", "audited-" + UUID.randomUUID().toString()
                        .substring(0, 8))).json().path("id").asString();

        assertThat(jdbc.queryForObject("select count(*) from auth_audit_event where event_type = 'ENVIRONMENT_CREATED' "
                + "and target_id = ? and actor_id = ?", Integer.class, UUID.fromString(environment), admin.userId()))
                .isOne();
    }

    @Test
    void aMissingPrincipalFailsBeforeAnythingIsWritten() {
        var audit = ControlActionAudit.transactional(transactions,
                new AuthAuditLog(new JdbcIdentityRepository(jdbc), clock), CurrentPrincipal::require,
                LifecycleRetry.standard());
        var ran = new AtomicBoolean();
        int before = jdbc.queryForObject("select count(*) from auth_audit_event", Integer.class);

        assertThatThrownBy(() -> audit.record("ENVIRONMENT_CREATED", () -> {
            ran.set(true);
            return UUID.randomUUID();
        }, id -> new ControlActionAudit.Target("ENVIRONMENT", id, null)))
                .isInstanceOf(WorkspaceScopeUnavailableException.class);
        assertThat(ran).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from auth_audit_event", Integer.class)).isEqualTo(before);
    }
}
