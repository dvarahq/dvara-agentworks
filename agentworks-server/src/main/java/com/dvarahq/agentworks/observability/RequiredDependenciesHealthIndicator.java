package com.dvarahq.agentworks.observability;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Objects;

/** Readiness includes only explicitly required correctness dependencies, never optional telemetry exporters. */
public final class RequiredDependenciesHealthIndicator implements HealthIndicator {
    private final JdbcTemplate jdbc;
    private final List<String> required;

    public RequiredDependenciesHealthIndicator(JdbcTemplate jdbc, List<String> required) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.required = List.copyOf(Objects.requireNonNull(required, "required"));
    }

    @Override
    public Health health() {
        if (required.isEmpty()) return Health.up().withDetail("required", List.of()).build();
        if (required.contains("database")) {
            try {
                Integer one = jdbc.queryForObject("select 1", Integer.class);
                if (one == null || one != 1) return unavailable("database");
            } catch (RuntimeException unavailable) {
                return unavailable("database");
            }
        }
        return Health.up().withDetail("required", required).build();
    }

    private Health unavailable(String dependency) {
        return Health.down().withDetail("dependency", dependency).withDetail("reason", "unavailable").build();
    }
}
