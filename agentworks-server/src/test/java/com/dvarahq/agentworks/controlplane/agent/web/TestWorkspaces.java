package com.dvarahq.agentworks.controlplane.agent.web;

import com.dvarahq.agentworks.identity.ApiTokenService;
import com.dvarahq.agentworks.identity.SessionService;
import com.dvarahq.agentworks.identity.TestIdentities;
import com.dvarahq.agentworks.storage.identity.JdbcIdentityRepository;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

/**
 * Test-only provisioning for REST contract tests: {@link AgentApiClient} authenticates each request with a real
 * API token of an administrator of the requested workspace (issue #74), so tests can act as principals of two
 * different organizations through the production security chain.
 */
@TestConfiguration(proxyBeanMethods = false)
class TestWorkspaces {
    static final UUID OTHER_ORGANIZATION = UUID.fromString("00000000-0000-0000-0000-00000000b001");
    static final UUID OTHER_WORKSPACE = UUID.fromString("00000000-0000-0000-0000-00000000b002");

    @Bean
    TestIdentities testIdentities(JdbcIdentityRepository repository, SessionService sessions, ApiTokenService tokens,
            org.springframework.security.crypto.password.PasswordEncoder encoder, java.time.Clock clock) {
        return new TestIdentities(repository, sessions, tokens, encoder, clock);
    }

    @Bean
    com.dvarahq.agentworks.storage.lifecycle.IdempotencyDigests testIdempotencyDigests() {
        return new com.dvarahq.agentworks.storage.lifecycle.IdempotencyDigests(
                java.util.Map.of(1, "test-only-idempotency-hmac-key-32-bytes!".getBytes(java.nio.charset.StandardCharsets.UTF_8)), 1);
    }

    static UUID createWorkspace(JdbcTemplate jdbc) {
        UUID organization = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        jdbc.update("insert into organization(id, name) values (?, ?)", organization, "org-" + organization);
        jdbc.update("insert into workspace(id, organization_id, name) values (?, ?, ?)", workspace, organization, "ws");
        return workspace;
    }

    static void ensureOtherWorkspace(JdbcTemplate jdbc) {
        if (jdbc.queryForObject("select count(*) from workspace where id = ?", Integer.class, OTHER_WORKSPACE) == 0) {
            jdbc.update("insert into organization(id, name) values (?, ?)", OTHER_ORGANIZATION, "Other Organization");
            jdbc.update("insert into workspace(id, organization_id, name) values (?, ?, ?)", OTHER_WORKSPACE,
                    OTHER_ORGANIZATION, "Other Workspace");
        }
    }
}
