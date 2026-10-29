package com.dvarahq.agentworks.storage;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.util.UUID;

import com.dvarahq.agentworks.domain.tenant.WorkspaceId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StructuralTenancyPostgresTest {
    private static final SharedPostgresTestDatabase.Database database =
            SharedPostgresTestDatabase.create("structural_tenancy");

    @Test
    void canonicalMigrationAndIdempotentBootstrapWorkOnPostgres() throws Exception {
        var source = database.source();
        var migration = SharedPostgresTestDatabase.migrate(database);
        var flyway = migration.flyway();
        // Every canonical and vendor migration applies once (eleven through V5_1 and V7_1).
        assertThat(migration.result().migrationsExecuted).isGreaterThanOrEqualTo(11)
                .isEqualTo(flyway.info().applied().length);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        var jdbc = new JdbcTemplate(source);
        var bootstrap = new DefaultTenantBootstrap(jdbc,
                new TransactionTemplate(new JdbcTransactionManager(source)));
        bootstrap.initialize();
        bootstrap.initialize();
        assertThat(jdbc.queryForObject("select count(*) from workspace_membership", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from agent_run where workspace_id is null", Integer.class)).isZero();

        UUID organization = UUID.randomUUID();
        UUID workspaceA = UUID.randomUUID();
        UUID workspaceB = UUID.randomUUID();
        UUID dataset = UUID.randomUUID();
        jdbc.update("insert into organization(id,name) values (?,?)", organization, "evaluation-org");
        jdbc.update("insert into workspace(id,organization_id,name) values (?,?,?)", workspaceA, organization, "evaluation-a");
        jdbc.update("insert into workspace(id,organization_id,name) values (?,?,?)", workspaceB, organization, "evaluation-b");
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute("set role agentworks_control_plane");
            statement.execute("select set_config('agentworks.workspace_id', '" + workspaceA + "', true)");
            statement.executeUpdate("insert into evaluation_dataset(workspace_id,id,name,version,content_digest,"
                    + "sensitive,expires_at,created_at) values ('" + workspaceA + "','" + dataset
                    + "','rls',1,'sha256:" + "0".repeat(64) + "',false,null,now())");
            connection.commit();

            statement.execute("select set_config('agentworks.workspace_id', '" + workspaceB + "', true)");
            try (var rows = statement.executeQuery("select count(*) from evaluation_dataset")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isZero();
            }
            connection.rollback();
            statement.execute("reset role");
            assertThatThrownBy(() -> statement.executeUpdate("update evaluation_dataset set name='changed' where id='"
                    + dataset + "'"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("IMMUTABLE_HISTORY");
            connection.rollback();

            statement.execute("set role agentworks_run_plane");
            assertThatThrownBy(() -> statement.executeQuery("select id from evaluation_dataset where false"))
                    .isInstanceOf(SQLException.class);
            connection.rollback();
        }
    }

    @Test
    void transactionBindingDrivesRlsAndCannotLeakAcrossAReusedConnection() throws Exception {
        var owner = database.source();
        var flyway = Flyway.configure().dataSource(owner)
                .locations("classpath:db/migration/common", "classpath:db/vendor/postgresql").load();
        flyway.migrate();
        var ownerJdbc = new JdbcTemplate(owner);
        UUID organization = UUID.randomUUID();
        WorkspaceId workspaceA = new WorkspaceId(UUID.randomUUID());
        WorkspaceId workspaceB = new WorkspaceId(UUID.randomUUID());
        UUID dataset = UUID.randomUUID();
        ownerJdbc.update("insert into organization(id,name) values (?,?)", organization, "rls-org");
        ownerJdbc.update("insert into workspace(id,organization_id,name) values (?,?,?)",
                workspaceA.value(), organization, "rls-a");
        ownerJdbc.update("insert into workspace(id,organization_id,name) values (?,?,?)",
                workspaceB.value(), organization, "rls-b");
        ownerJdbc.execute("drop role if exists agentworks_test_control");
        ownerJdbc.execute("create role agentworks_test_control login password 'test-control-password' "
                + "in role agentworks_control_plane");

        var pooled = new SingleConnectionDataSource(database.jdbcUrl(), "agentworks_test_control",
                "test-control-password", true);
        try {
            var jdbc = new JdbcTemplate(pooled);
            var transactions = new TransactionTemplate(new JdbcTransactionManager(pooled));
            var tenants = new TenantTransactionTemplate(transactions);
            tenants.executeWithoutResult(workspaceA, () -> jdbc.update("insert into evaluation_dataset("
                    + "workspace_id,id,name,version,content_digest,sensitive,expires_at,created_at) "
                    + "values (?,?,?,1,?,false,null,current_timestamp)", workspaceA.value(), dataset, "bound",
                    "sha256:" + "0".repeat(64)));

            assertThat(tenants.execute(workspaceB,
                    () -> jdbc.queryForObject("select count(*) from evaluation_dataset", Integer.class))).isZero();
            assertThat(tenants.execute(workspaceA,
                    () -> jdbc.queryForObject("select count(*) from evaluation_dataset", Integer.class))).isOne();
            Integer unboundRows = transactions.execute(status ->
                    jdbc.queryForObject("select count(*) from evaluation_dataset", Integer.class));
            assertThat(unboundRows).isZero();

            assertThatThrownBy(() -> tenants.executeWithoutResult(workspaceB,
                    () -> jdbc.update("insert into evaluation_dataset(workspace_id,id,name,version,content_digest,"
                                    + "sensitive,expires_at,created_at) values (?,?,?,1,?,false,null,current_timestamp)",
                            workspaceA.value(), UUID.randomUUID(), "cross-tenant", "sha256:" + "1".repeat(64))))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class)
                    .hasRootCauseMessage("ERROR: new row violates row-level security policy for table \"evaluation_dataset\"");
        } finally {
            pooled.destroy();
            ownerJdbc.execute("drop role agentworks_test_control");
        }
    }

    @Test
    void everyWorkspaceControlOrRunTableHasRlsAndPlaneRolesStayNonPrivileged() {
        var source = database.source();
        Flyway.configure().dataSource(source)
                .locations("classpath:db/migration/common", "classpath:db/vendor/postgresql").load().migrate();
        var jdbc = new JdbcTemplate(source);
        assertThat(jdbc.queryForList("select c.table_name from information_schema.columns c "
                + "join information_schema.tables t using(table_schema,table_name) "
                + "join pg_class p on p.relname=c.table_name "
                + "join pg_namespace n on n.oid=p.relnamespace and n.nspname=c.table_schema "
                + "where c.table_schema=current_schema() and c.column_name='workspace_id' "
                + "and t.table_type='BASE TABLE' and c.table_name not in "
                + "('workspace_membership','auth_session','api_token','auth_audit_event') and (not p.relrowsecurity "
                + "or not exists (select 1 from pg_policies q where q.schemaname=c.table_schema "
                + "and q.tablename=c.table_name and q.policyname=c.table_name || '_workspace_tenant'))",
                String.class)).isEmpty();
        assertThat(jdbc.queryForList("select indexname from pg_indexes where schemaname=current_schema() "
                + "and indexname like 'idx_%_workspace_%' order by indexname", String.class))
                .contains("idx_agent_run_event_workspace_run_sequence",
                        "idx_agent_run_checkpoint_workspace_run_sequence",
                        "idx_control_plane_outbox_workspace_sequence",
                        "idx_control_plane_delivery_workspace_outbox",
                        "idx_agent_run_workspace_lifecycle_created");
        assertThat(jdbc.queryForMap("select rolcanlogin, rolsuper, rolcreatedb, rolcreaterole, rolreplication, "
                + "rolbypassrls from pg_roles where rolname='agentworks_run_plane'").values())
                .containsOnly(false);
        assertThat(jdbc.queryForMap("select rolcanlogin, rolsuper, rolcreatedb, rolcreaterole, rolreplication, "
                + "rolbypassrls from pg_roles where rolname='agentworks_control_plane'").values())
                .containsOnly(false);
    }
}
