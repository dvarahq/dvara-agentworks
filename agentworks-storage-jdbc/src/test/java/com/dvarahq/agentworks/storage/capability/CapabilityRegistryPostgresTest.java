package com.dvarahq.agentworks.storage.capability;

import com.dvarahq.agentworks.capability.AgentTool;
import com.dvarahq.agentworks.capability.CapabilityLifecycleConflictException;
import com.dvarahq.agentworks.capability.ToolId;
import com.dvarahq.agentworks.capability.ToolEffect;
import com.dvarahq.agentworks.capability.ToolRegistry;
import com.dvarahq.agentworks.capability.ToolType;
import com.dvarahq.agentworks.domain.security.PermissionRisk;
import com.dvarahq.agentworks.domain.security.PermissionScope;
import com.dvarahq.agentworks.domain.security.RememberedPermissionEvaluator;
import com.dvarahq.agentworks.domain.security.RememberedPermissionRequest;
import com.dvarahq.agentworks.domain.security.RememberedPermissionRule;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import com.dvarahq.agentworks.storage.TenantTransactionTemplate;
import com.dvarahq.agentworks.storage.SharedPostgresTestDatabase;
import com.dvarahq.agentworks.storage.security.JdbcRememberedPermissionAuthority;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CapabilityRegistryPostgresTest {
    private static final String SCHEMA = "{\"type\":\"object\"}";
    private static final DataSource source = SharedPostgresTestDatabase.createMigrated("capability_registry").source();

    private JdbcTemplate jdbc;
    private JdbcCapabilityRegistry registry;
    private TenantTransactionTemplate tenants;
    private WorkspaceId workspace;
    private UUID organization;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(source);
        registry = new JdbcCapabilityRegistry(jdbc);
        tenants = new TenantTransactionTemplate(new TransactionTemplate(new JdbcTransactionManager(source)));
        organization = UUID.randomUUID();
        workspace = new WorkspaceId(UUID.randomUUID());
        jdbc.update("insert into organization(id, name) values (?, ?)", organization, "org-" + organization);
        jdbc.update("insert into workspace(id, organization_id, name) values (?, ?, ?)", workspace.value(),
                organization, "workspace-" + workspace.value());
    }

    @Test
    void referenceDatabaseVerifiesSchemaDigestAndMakesPublishedMetadataImmutable() {
        AgentTool tool = tool("search", AgentTool.Availability.ACTIVE);
        tenants.executeWithoutResult(workspace, () -> registry.insertTool(workspace, tool, Instant.now()));

        assertThatThrownBy(() -> jdbc.update("update agent_tool_version set summary = 'changed' "
                        + "where workspace_id = ? and namespace = ? and name = ? and version = ?", workspace.value(),
                tool.id().namespace(), tool.id().name(), tool.id().version()))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("IMMUTABLE_HISTORY");
        assertThatThrownBy(() -> jdbc.update("delete from agent_tool_tag where workspace_id = ?", workspace.value()))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("IMMUTABLE_HISTORY");
        assertThatThrownBy(() -> jdbc.update("insert into agent_tool_version(workspace_id, namespace, name, version, "
                        + "tool_type, summary, description, input_schema, schema_hash, summary_tokens, detail_tokens, "
                        + "created_at) values (?, 'core', 'forged', '1.0.0', 'JAVA', 'forged', 'forged', ?, ?, 1, 1, now())",
                workspace.value(), SCHEMA, "sha256:" + "0".repeat(64)))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("SCHEMA_HASH_MISMATCH");
    }

    @Test
    void deniedLifecycleIsTerminalAndRunPlaneCannotWriteRegistry() throws Exception {
        AgentTool tool = tool("terminal", AgentTool.Availability.DENIED);
        tenants.executeWithoutResult(workspace, () -> registry.insertTool(workspace, tool, Instant.now()));
        assertThatThrownBy(() -> tenants.executeWithoutResult(workspace, () -> registry.transitionTool(workspace,
                tool.id(), AgentTool.Availability.ACTIVE, Instant.now())))
                .isInstanceOf(CapabilityLifecycleConflictException.class)
                .hasMessageContaining("lifecycle transition is not allowed");

        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            statement.execute("set role agentworks_run_plane");
            statement.executeQuery("select namespace from agent_tool_version where false");
            assertThatThrownBy(() -> statement.executeUpdate("insert into agent_tool_lifecycle(workspace_id, namespace, "
                    + "name, version, state, updated_at) values ('" + workspace.value()
                    + "', 'core', 'x', '1.0.0', 'ACTIVE', now())"))
                    .isInstanceOf(SQLException.class);
        }
    }

    @Test
    void rememberedPermissionHistoryIsImmutableAndRunPlaneReadOnly() throws Exception {
        UUID permission = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        UUID environment = UUID.randomUUID();
        jdbc.update("insert into remembered_permission_identity(permission_id, organization_id, scope_level, "
                        + "scope_id) values (?, ?, 'WORKSPACE', ?)", permission, organization, workspace.value());
        jdbc.update("insert into remembered_permission_rule(permission_id, version, organization_id, scope_level, "
                        + "scope_id, actor_id, capability, resource, action, environment_id, effect, maximum_risk, "
                        + "valid_from, expires_at) values (?, 1, ?, 'WORKSPACE', ?, ?, 'ledger', 'account/7', "
                        + "'write', ?, 'ALLOW', 'LOW', now() - interval '1 minute', now() + interval '1 minute')",
                permission, organization, workspace.value(), actor, environment);

        assertThatThrownBy(() -> jdbc.update("update remembered_permission_rule set effect = 'DENY' "
                        + "where permission_id = ? and version = 1", permission))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("IMMUTABLE_HISTORY");
        assertThatThrownBy(() -> jdbc.update("delete from remembered_permission_rule where permission_id = ?",
                        permission))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("IMMUTABLE_HISTORY");

        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            statement.execute("set role agentworks_run_plane");
            statement.executeQuery("select permission_id from remembered_permission_rule where false");
            var runSource = new SingleConnectionDataSource(connection, true);
            var runTenants = new TenantTransactionTemplate(
                    new TransactionTemplate(new JdbcTransactionManager(runSource)));
            var authority = new JdbcRememberedPermissionAuthority(new JdbcTemplate(runSource));
            RememberedPermissionEvaluator.Decision decision = runTenants.execute(workspace,
                    () -> authority.evaluate(new RememberedPermissionRequest(organization, workspace.value(), actor,
                            UUID.randomUUID(), UUID.randomUUID(), "ledger", "account/7", "write", environment,
                            PermissionRisk.LOW, Instant.EPOCH)));
            assertThat(decision.verdict()).isEqualTo(RememberedPermissionEvaluator.Verdict.ALLOW);
            assertThat(decision.ruleId()).contains(permission);
            assertThatThrownBy(() -> statement.executeUpdate("insert into remembered_permission_rule(permission_id, "
                            + "version, organization_id, scope_level, scope_id, actor_id, capability, resource, action, "
                            + "environment_id, effect, maximum_risk, valid_from, expires_at) values ('"
                            + UUID.randomUUID() + "', 1, '" + organization + "', 'WORKSPACE', '" + workspace.value()
                            + "', '" + UUID.randomUUID() + "', 'x', 'x', 'x', '" + UUID.randomUUID()
                            + "', 'ALLOW', 'LOW', now(), now() + interval '1 minute')"))
                    .isInstanceOf(SQLException.class);
        }
    }

    @Test
    void controlPlaneCanValidatePageLockAndAppendPermissionVersionsThroughItsOwnRole() throws Exception {
        UUID actor = UUID.randomUUID();
        UUID environment = UUID.randomUUID();
        jdbc.update("insert into user_account(id, organization_id, username, display_name, organization_role, "
                        + "password_hash, password_changed_at, must_change_password, status, failed_login_count, "
                        + "created_at, updated_at) values (?, ?, ?, 'Actor', 'MEMBER', '{argon2}test', now(), false, "
                        + "'ACTIVE', 0, now(), now())", actor, organization, "actor-" + actor);
        jdbc.update("insert into workspace_membership(organization_id, workspace_id, subject_id, membership_role, "
                + "created_at, updated_at) values (?, ?, ?, 'AUTHOR', now(), now())", organization,
                workspace.value(), actor);
        jdbc.update("insert into agent_environment(workspace_id, id, name, description, policy_version, "
                        + "promotion_policy_json, promotion_policy_digest, created_at) values (?, ?, 'prod', 'test', "
                        + "1, '{}', ?, now())", workspace.value(), environment, "sha256:" + "0".repeat(64));

        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            statement.execute("set role agentworks_control_plane");
            var controlSource = new SingleConnectionDataSource(connection, true);
            var controlTenants = new TenantTransactionTemplate(
                    new TransactionTemplate(new JdbcTransactionManager(controlSource)));
            var authority = new JdbcRememberedPermissionAuthority(new JdbcTemplate(controlSource));
            UUID id = UUID.randomUUID();
            Instant validFrom = Instant.now().minusSeconds(1).truncatedTo(ChronoUnit.MICROS);
            Instant expiresAt = Instant.now().plusSeconds(60).truncatedTo(ChronoUnit.MICROS);
            var rule = new RememberedPermissionRule(id, 1, RememberedPermissionRule.Effect.ALLOW,
                    new PermissionScope(PermissionScope.Level.WORKSPACE, workspace.value()), actor, Optional.empty(),
                    Optional.empty(), "core:ledger:1.0.0", "ledger", "write", environment, PermissionRisk.HIGH,
                    validFrom, expiresAt, Optional.empty());

            controlTenants.executeWithoutResult(workspace, () -> {
                authority.validateManagementReferences(rule);
                authority.append(rule);
            });
            assertThat(controlTenants.execute(workspace,
                    () -> authority.latest(Optional.empty(), 2))).containsExactly(rule);
            assertThat(controlTenants.execute(workspace,
                    () -> authority.lockLatest(id))).contains(rule);
        }
    }

    @Test
    void largeCatalogSearchIsTenantScopedKeysetPagedAndBounded() {
        WorkspaceId other = insertWorkspace();
        insertLargeCatalog(workspace, 10_000, "primary");
        insertLargeCatalog(other, 10_000, "other");

        long filteredStarted = System.nanoTime();
        ToolRegistry.Page filtered = tenants.execute(workspace, () -> registry.search(workspace,
                new ToolRegistry.Search("rare-needle", Set.of(ToolType.MCP), Set.of("bulk"), null, 200)));
        Duration filteredElapsed = Duration.ofNanos(System.nanoTime() - filteredStarted);

        assertThat(filtered.items()).hasSize(11).allSatisfy(tool -> {
            assertThat(tool.summary()).startsWith("primary rare-needle");
            assertThat(tool.tags()).containsExactly("bulk");
        });
        assertThat(filtered.nextCursor()).isNull();
        assertThat(filteredElapsed).isLessThan(Duration.ofSeconds(5));

        Set<ToolId> seen = new HashSet<>();
        String cursor = null;
        int pages = 0;
        long traversalStarted = System.nanoTime();
        do {
            String current = cursor;
            ToolRegistry.Page page = tenants.execute(workspace, () -> registry.search(workspace,
                    new ToolRegistry.Search("", Set.of(), Set.of(), current, 200)));
            assertThat(page.items()).hasSizeLessThanOrEqualTo(200);
            assertThat(page.items()).allSatisfy(tool -> assertThat(seen.add(tool.id())).isTrue());
            cursor = page.nextCursor();
            pages++;
        } while (cursor != null);
        Duration traversalElapsed = Duration.ofNanos(System.nanoTime() - traversalStarted);

        assertThat(seen).hasSize(10_000);
        assertThat(pages).isEqualTo(50);
        assertThat(traversalElapsed).isLessThan(Duration.ofSeconds(10));
        System.out.printf("large-catalog tools_per_tenant=10000 filtered_ms=%d traversal_ms=%d pages=%d%n",
                filteredElapsed.toMillis(), traversalElapsed.toMillis(), pages);
    }

    private WorkspaceId insertWorkspace() {
        UUID organization = UUID.randomUUID();
        WorkspaceId created = new WorkspaceId(UUID.randomUUID());
        jdbc.update("insert into organization(id, name) values (?, ?)", organization, "org-" + organization);
        jdbc.update("insert into workspace(id, organization_id, name) values (?, ?, ?)", created.value(),
                organization, "workspace-" + created.value());
        return created;
    }

    private void insertLargeCatalog(WorkspaceId target, int size, String marker) {
        jdbc.update("""
                insert into agent_tool_version(workspace_id, namespace, name, version, tool_type, summary,
                    description, input_schema, schema_hash, effect_resource, effect_action, effect_risk,
                    summary_tokens, detail_tokens, created_at)
                select ?, 'catalog', 'tool-' || lpad(n::text, 5, '0'), '1.0.0', 'MCP',
                    case when mod(n, 997) = 0 then ? || ' rare-needle ' || n else ? || ' tool ' || n end,
                    'Large catalog performance fixture', ?, ?, 'bulk-fixture', 'read', 'LOW', 4, 12, now()
                from generate_series(0, ? - 1) as n
                """, target.value(), marker, marker, SCHEMA, AgentTool.digest(SCHEMA), size);
        jdbc.update("""
                insert into agent_tool_lifecycle(workspace_id, namespace, name, version, state, updated_at)
                select workspace_id, namespace, name, version, 'ACTIVE', now()
                from agent_tool_version where workspace_id = ?
                """, target.value());
        jdbc.update("""
                insert into agent_tool_tag(workspace_id, namespace, name, version, tag)
                select workspace_id, namespace, name, version, 'bulk'
                from agent_tool_version where workspace_id = ?
                """, target.value());
    }

    private static AgentTool tool(String name, AgentTool.Availability availability) {
        return new AgentTool(new ToolId("core", name, "1.0.0"), ToolType.JAVA, name, "Detailed " + name, SCHEMA,
                AgentTool.digest(SCHEMA), new ToolEffect("test-resource", "read",
                com.dvarahq.agentworks.domain.security.PermissionRisk.LOW), Set.of("test"), Set.of(), 3, 8, availability);
    }
}
