package com.dvarahq.agentworks.controlplane.lifecycle;

import com.dvarahq.agentworks.storage.RuntimeCompatibility;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

/** The CI min/max matrix proves fresh install and forward upgrade on every supported PostgreSQL boundary. */
@EnabledIfEnvironmentVariable(named = "AGENTWORKS_COMPATIBILITY_POSTGRES_IMAGE", matches = "postgres:[0-9.]+-alpine")
class DatabaseCompatibilityPostgresTest {

    @Test
    void supportedBoundaryPerformsFreshInstallAndUpgradeWithoutPendingMigrations() throws Exception {
        String image = System.getenv("AGENTWORKS_COMPATIBILITY_POSTGRES_IMAGE");
        try (var postgres = new PostgreSQLContainer(image)) {
            postgres.start();
            var source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword());
            try (var connection = source.getConnection()) {
                RuntimeCompatibility.requireDatabase(connection.getMetaData(), false);
            }
            var locations = new String[]{"classpath:db/migration/common", "classpath:db/vendor/postgresql"};
            Flyway.configure().dataSource(source).locations(locations).target(MigrationVersion.fromVersion("2.1"))
                    .load().migrate();
            var jdbc = new JdbcTemplate(source);
            assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where success", Integer.class))
                    .isEqualTo(3);

            var current = Flyway.configure().dataSource(source).locations(locations).load();
            var upgraded = current.migrate();

            assertThat(upgraded.migrationsExecuted).isPositive();
            assertThat(current.info().pending()).isEmpty();
            assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where not success", Integer.class))
                    .isZero();
            assertThat(jdbc.queryForObject("select count(*) from agent_run", Integer.class)).isZero();
        }
    }
}
