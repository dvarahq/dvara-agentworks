package com.dvarahq.agentworks.observability;

import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RequiredDependenciesHealthIndicatorTest {
    @Test
    void optionalDependenciesNeverAffectReadiness() {
        var health = new RequiredDependenciesHealthIndicator(new JdbcTemplate(), List.of()).health();
        assertThat(health.getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void aRequiredDatabaseFailsClosedWithoutLeakingItsException() {
        JdbcTemplate jdbc = new JdbcTemplate(new AbstractDataSource() {
            @Override public java.sql.Connection getConnection() throws java.sql.SQLException {
                throw new java.sql.SQLException("secret-canary");
            }
            @Override public java.sql.Connection getConnection(String username, String password)
                    throws java.sql.SQLException { return getConnection(); }
        });
        var health = new RequiredDependenciesHealthIndicator(jdbc, List.of("database")).health();
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails().toString()).contains("database", "unavailable").doesNotContain("secret-canary");
    }
}
