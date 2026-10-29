package com.dvarahq.agentworks.controlplane.security.web;

import com.dvarahq.agentworks.domain.identity.WorkspaceRole;
import com.dvarahq.agentworks.DvaraAgentWorksApplication;
import com.dvarahq.agentworks.identity.TestIdentities;
import com.dvarahq.agentworks.identity.web.IdentityHttp;
import com.dvarahq.agentworks.identity.web.IdentityTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = DvaraAgentWorksApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(IdentityTestConfiguration.class)
class RememberedPermissionApiTest {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired TestIdentities identities;

    @Test
    void anAdministratorAuthorsListsAndIdempotentlyRevokesTenantBoundPermissionHistory() {
        UUID workspace = workspace("permission-a");
        UUID otherWorkspace = workspace("permission-b");
        TestIdentities.Member admin = identities.create(workspace, WorkspaceRole.ADMIN);
        TestIdentities.Member actor = identities.create(workspace, WorkspaceRole.AUTHOR);
        IdentityHttp administrator = new IdentityHttp(port).bearer(admin.token());
        IdentityHttp operator = new IdentityHttp(port).bearer(identities.create(workspace, WorkspaceRole.OPERATOR).token());
        IdentityHttp otherAdministrator = new IdentityHttp(port)
                .bearer(identities.create(otherWorkspace, WorkspaceRole.ADMIN).token());
        UUID environment = environment(workspace, "prod");

        Map<String, Object> request = permission(actor.userId(), environment);
        assertThat(operator.send("POST", "/api/v1/remembered-permissions", request).status()).isEqualTo(403);
        IdentityHttp.Response created = administrator.send("POST", "/api/v1/remembered-permissions", request);
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.json().path("version").asLong()).isEqualTo(1);
        assertThat(created.json().path("scope").asString()).isEqualTo("WORKSPACE");
        assertThat(created.json().path("scopeId").asString()).isEqualTo(workspace.toString());
        assertThat(created.json().path("revokedAt").isNull()).isTrue();
        UUID permission = UUID.fromString(created.json().path("id").asString());
        assertThat(administrator.send("POST", "/api/v1/remembered-permissions", request).status()).isEqualTo(200);

        assertThat(administrator.get("/api/v1/remembered-permissions").raw()).contains(permission.toString());
        assertThat(otherAdministrator.get("/api/v1/remembered-permissions/" + permission).status()).isEqualTo(404);

        IdentityHttp.Response revoked = administrator.send("POST",
                "/api/v1/remembered-permissions/" + permission + "/revoke", null);
        assertThat(revoked.status()).isEqualTo(200);
        assertThat(revoked.json().path("version").asLong()).isEqualTo(2);
        assertThat(revoked.json().path("revokedAt").isNull()).isFalse();
        IdentityHttp.Response repeated = administrator.send("POST",
                "/api/v1/remembered-permissions/" + permission + "/revoke", null);
        assertThat(repeated.status()).isEqualTo(200);
        assertThat(repeated.json().path("version").asLong()).isEqualTo(2);

        assertThat(jdbc.queryForObject("select count(*) from remembered_permission_rule where permission_id = ?",
                Long.class, permission)).isEqualTo(2L);
        assertThat(jdbc.queryForObject("select count(*) from auth_audit_event where target_id = ? and "
                        + "event_type = 'REMEMBERED_PERMISSION_CREATED'", Long.class, permission)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from auth_audit_event where target_id = ? and "
                        + "event_type = 'REMEMBERED_PERMISSION_REVOKED'", Long.class, permission)).isEqualTo(1L);
    }

    @Test
    void resourceSelectorsAreValidatedBeforeAnythingIsStoredOrAudited() {
        UUID workspace = workspace("permission-negative");
        TestIdentities.Member admin = identities.create(workspace, WorkspaceRole.ADMIN);
        TestIdentities.Member actor = identities.create(workspace, WorkspaceRole.AUTHOR);
        IdentityHttp administrator = new IdentityHttp(port).bearer(admin.token());
        Map<String, Object> request = permission(actor.userId(), environment(workspace, "prod"));
        UUID invalidEnvironment = UUID.randomUUID();
        request.put("environmentId", invalidEnvironment);

        IdentityHttp.Response invalid = administrator.send("POST", "/api/v1/remembered-permissions", request);
        assertThat(invalid.status()).isEqualTo(400);
        assertThat(invalid.code()).isEqualTo("INVALID_REMEMBERED_PERMISSION");
        assertThat(jdbc.queryForObject("select count(*) from remembered_permission_rule where environment_id = ?",
                Long.class, invalidEnvironment)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from auth_audit_event where "
                + "event_type = 'REMEMBERED_PERMISSION_CREATED' and actor_id = ?", Long.class, admin.userId())).isZero();
    }

    @Test
    void missingRequiredFieldsAreA400Problem() {
        UUID workspace = workspace("permission-malformed");
        IdentityHttp administrator = new IdentityHttp(port)
                .bearer(identities.create(workspace, WorkspaceRole.ADMIN).token());

        IdentityHttp.Response invalid = administrator.send("POST", "/api/v1/remembered-permissions", Map.of());

        assertThat(invalid.status()).isEqualTo(400);
        assertThat(invalid.code()).isEqualTo("INVALID_REMEMBERED_PERMISSION");
    }

    private UUID workspace(String name) {
        UUID organization = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        jdbc.update("insert into organization(id, name) values (?, ?)", organization, "org-" + name);
        jdbc.update("insert into workspace(id, organization_id, name) values (?, ?, ?)", workspace, organization, name);
        return workspace;
    }

    private UUID environment(UUID workspace, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into agent_environment(workspace_id, id, name, description, policy_version, "
                        + "promotion_policy_json, promotion_policy_digest, created_at) values (?, ?, ?, ?, ?, ?, ?, ?)",
                workspace, id, name, "test", 1L, "{}", "sha256:" + "0".repeat(64), Instant.now());
        return id;
    }

    private static Map<String, Object> permission(UUID actor, UUID environment) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("id", UUID.randomUUID());
        request.put("effect", "ALLOW");
        request.put("scope", "WORKSPACE");
        request.put("actorId", actor);
        request.put("capability", "core:ledger:1.0.0");
        request.put("resource", "ledger");
        request.put("action", "write");
        request.put("environmentId", environment);
        request.put("maximumRisk", "HIGH");
        // Exercise database timestamp normalization so the same request remains idempotent after persistence.
        request.put("expiresAt", Instant.now().plus(1, ChronoUnit.DAYS)
                .truncatedTo(ChronoUnit.MICROS).plusNanos(219));
        return request;
    }
}
