package com.dvarahq.agentworks.journey;

import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The journey's PostgreSQL, installed the way ISSUE-155 documents for a migration user without {@code CREATEROLE}: a
 * database administrator creates the owner login {@code installer} and its database, pre-creates the NOLOGIN plane,
 * fence, and auditor roles with {@code ADMIN OPTION} for it, and creates one login role per plane. The server then
 * migrates as {@code installer} and connects each plane under its own login, so the journey runs with the shipped
 * least-privilege grants rather than as a superuser.
 */
final class JourneyDatabase implements AutoCloseable {
    static final String IMAGE = "postgres:17-alpine";
    static final List<String> NOLOGIN_ROLES = List.of("agentworks_control_plane", "agentworks_run_plane",
            "agentworks_fence_owner", "agentworks_run_control_auditor");

    final PostgreSQLContainer container = new PostgreSQLContainer(IMAGE);
    final String installerPassword = secret();
    final String controlPassword = secret();
    final String runPassword = secret();

    JourneyDatabase start() {
        container.start();
        admin("create role installer login password '" + installerPassword + "'",
                "create database agentworks owner installer");
        for (String role : NOLOGIN_ROLES) admin("create role " + role + " nologin");
        admin("grant " + String.join(", ", NOLOGIN_ROLES) + " to installer with admin option",
                "create role journey_control login password '" + controlPassword + "' in role agentworks_control_plane",
                "create role journey_run login password '" + runPassword + "' in role agentworks_run_plane");
        return this;
    }

    String url() {
        return container.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/agentworks$1");
    }

    /**
     * The server's database properties: the owner for Flyway, and one login role per plane. Passwords are not
     * properties: they go to the process environment ({@link #serverEnvironment()}), so they never appear on its
     * command line.
     */
    Map<String, String> serverProperties() {
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("spring.datasource.url", url());
        properties.put("spring.datasource.username", "installer");
        properties.put("agentworks.planes.control-plane.url", url());
        properties.put("agentworks.planes.control-plane.username", "journey_control");
        properties.put("agentworks.planes.run-plane.url", url());
        properties.put("agentworks.planes.run-plane.username", "journey_run");
        return properties;
    }

    /** The database passwords, as environment variables Spring Boot binds to the same properties. */
    Map<String, String> serverEnvironment() {
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("SPRING_DATASOURCE_PASSWORD", installerPassword);
        environment.put("AGENTWORKS_PLANES_CONTROL_PLANE_PASSWORD", controlPassword);
        environment.put("AGENTWORKS_PLANES_RUN_PLANE_PASSWORD", runPassword);
        return environment;
    }

    void admin(String... statements) {
        try (Connection connection = DriverManager.getConnection(container.getJdbcUrl(), container.getUsername(),
                container.getPassword()); Statement statement = connection.createStatement()) {
            for (String sql : statements) statement.execute(sql);
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    /** A connection to the AgentWorks database as {@code user}. */
    Connection connect(String user) throws SQLException {
        String password = switch (user) {
            case "installer" -> installerPassword;
            case "journey_control" -> controlPassword;
            case "journey_run" -> runPassword;
            default -> throw new IllegalArgumentException(user);
        };
        return DriverManager.getConnection(url(), user, password);
    }

    /** A connection to the AgentWorks database as the database administrator (operator provisioning only). */
    Connection administrator() throws SQLException {
        return DriverManager.getConnection(url(), container.getUsername(), container.getPassword());
    }

    String serverVersion() {
        try (Connection connection = administrator()) {
            return connection.getMetaData().getDatabaseProductName() + " "
                    + connection.getMetaData().getDatabaseProductVersion();
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static String secret() {
        return "j" + UUID.randomUUID().toString().replace("-", "");
    }

    @Override
    public void close() {
        container.stop();
    }
}
