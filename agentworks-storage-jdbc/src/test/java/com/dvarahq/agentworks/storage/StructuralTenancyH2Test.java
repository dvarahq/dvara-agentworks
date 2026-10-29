package com.dvarahq.agentworks.storage;

import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StructuralTenancyH2Test {
    private JdbcTemplate jdbc;
    private TenantTransactionTemplate tenants;
    private DefaultTenantBootstrap bootstrap;

    @BeforeEach
    void setUp() {
        var dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration/common").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        var transactions = new TransactionTemplate(new JdbcTransactionManager(dataSource));
        tenants = new TenantTransactionTemplate(transactions);
        bootstrap = new DefaultTenantBootstrap(jdbc, transactions);
    }

    @Test
    void defaultTenantBootstrapIsIdempotent() {
        assertThat(bootstrap.initialize()).isEqualTo(bootstrap.initialize());
        assertThat(jdbc.queryForObject("select count(*) from organization", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("select count(*) from workspace", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("select count(*) from workspace_membership", Integer.class)).isZero();
    }

    @Test
    void repositoryFailsClosedWithoutTransactionScopedBinding() {
        bootstrap.initialize();
        var repository = new JdbcWorkspaceRepository(jdbc);
        assertThatThrownBy(repository::findCurrent).isInstanceOf(MissingTenantBindingException.class)
                .hasMessageContaining("active transaction");
        assertThatThrownBy(() -> TenantContext.bind(DefaultTenantBootstrap.DEFAULT_WORKSPACE_ID))
                .isInstanceOf(MissingTenantBindingException.class);
    }

    @Test
    void repositoryCannotReadAnotherWorkspace() {
        bootstrap.initialize();
        var otherWorkspace = new WorkspaceId(UUID.randomUUID());
        jdbc.update("insert into workspace(id, organization_id, name) values (?, ?, ?)", otherWorkspace.value(),
                DefaultTenantBootstrap.DEFAULT_ORGANIZATION_ID.value(), "Other Workspace");
        var repository = new JdbcWorkspaceRepository(jdbc);
        assertThat(tenants.execute(DefaultTenantBootstrap.DEFAULT_WORKSPACE_ID, repository::findCurrent))
                .get().extracting(JdbcWorkspaceRepository.WorkspaceView::name).isEqualTo("Default Workspace");
        assertThat(tenants.execute(otherWorkspace, repository::findCurrent))
                .get().extracting(JdbcWorkspaceRepository.WorkspaceView::name).isEqualTo("Other Workspace");
    }

    @Test
    void transactionCannotSwitchItsWorkspaceBinding() {
        bootstrap.initialize();
        var otherWorkspace = new WorkspaceId(UUID.randomUUID());
        var transactions = new TransactionTemplate(new JdbcTransactionManager(jdbc.getDataSource()));

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            try (var ignored = TenantContext.bind(DefaultTenantBootstrap.DEFAULT_WORKSPACE_ID)) {
                TenantContext.bind(otherWorkspace);
            }
        })).isInstanceOf(MissingTenantBindingException.class)
                .hasMessageContaining("another workspace");
    }

    @Test
    void databaseRejectsCrossWorkspaceLifecycleOwnership() {
        bootstrap.initialize();
        UUID otherWorkspace = UUID.randomUUID();
        jdbc.update("insert into workspace(id, organization_id, name) values (?, ?, ?)", otherWorkspace,
                DefaultTenantBootstrap.DEFAULT_ORGANIZATION_ID.value(), "Other Workspace");
        UUID definition = UUID.randomUUID();
        jdbc.update("insert into agent_definition(workspace_id, id, name, created_at) values (?, ?, ?, current_timestamp)",
                DefaultTenantBootstrap.DEFAULT_WORKSPACE_ID.value(), definition, "agent");
        String insertVersion = "insert into agent_version(workspace_id, id, definition_id, sequence_number, version_label, "
                + "api_version, spec_json, spec_digest, created_at) values (?, ?, ?, ?, ?, 'dvarahq.com/v1alpha1', '{}', ?, "
                + "current_timestamp)";
        String digest = "sha256:" + "0".repeat(64);
        // The same complete row is accepted for the owning workspace, so the rejection below is the ownership key.
        jdbc.update(insertVersion, DefaultTenantBootstrap.DEFAULT_WORKSPACE_ID.value(), UUID.randomUUID(), definition,
                1, "1.0.0", digest);
        assertThatThrownBy(() -> jdbc.update(insertVersion, otherWorkspace, UUID.randomUUID(), definition, 2, "2.0.0",
                digest)).isInstanceOf(DataIntegrityViolationException.class);
    }
}
