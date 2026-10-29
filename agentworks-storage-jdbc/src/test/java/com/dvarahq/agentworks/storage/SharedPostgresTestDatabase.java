package com.dvarahq.agentworks.storage;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;

import javax.sql.DataSource;
import java.util.UUID;

/** One PostgreSQL process per test JVM, with an isolated database for each test class. */
public final class SharedPostgresTestDatabase {
    private static final PostgreSQLContainer POSTGRES = start();
    private static Database template;

    private SharedPostgresTestDatabase() {}

    public static synchronized Database create(String label) {
        return createDatabase(label, null);
    }

    public static synchronized Database createMigrated(String label) {
        if (template == null) {
            template = createDatabase("template", null);
            migrate(template);
        }
        return createDatabase(label, template.databaseName());
    }

    public static synchronized Migration migrate(Database database) {
        Flyway flyway = Flyway.configure().dataSource(database.source())
                .locations("classpath:db/migration/common", "classpath:db/vendor/postgresql").load();
        return new Migration(flyway, flyway.migrate());
    }

    private static Database createDatabase(String label, String templateName) {
        String database = label.replaceAll("[^a-z0-9_]", "_") + "_" + UUID.randomUUID().toString().replace("-", "");
        var admin = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        String templateClause = templateName == null ? "" : " template \"" + templateName + "\"";
        new JdbcTemplate(admin).execute("create database \"" + database + "\"" + templateClause);
        String original = POSTGRES.getJdbcUrl();
        int query = original.indexOf('?');
        String suffix = query < 0 ? "" : original.substring(query);
        String withoutQuery = query < 0 ? original : original.substring(0, query);
        String jdbcUrl = withoutQuery.substring(0, withoutQuery.lastIndexOf('/') + 1) + database + suffix;
        return new Database(database, new DriverManagerDataSource(jdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword()),
                jdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static PostgreSQLContainer start() {
        var postgres = new PostgreSQLContainer("postgres:17-alpine");
        postgres.start();
        return postgres;
    }

    public record Database(String databaseName, DataSource source, String jdbcUrl, String username, String password) {}
    public record Migration(Flyway flyway, MigrateResult result) {}
}
