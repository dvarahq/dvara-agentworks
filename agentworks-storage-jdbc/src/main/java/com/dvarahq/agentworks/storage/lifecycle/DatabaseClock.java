package com.dvarahq.agentworks.storage.lifecycle;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * PostgreSQL-authoritative time for lifecycle facts (ADR-027). Worker and controller clocks are never used for
 * lifecycle timestamps, deadlines, or eligibility.
 */
public final class DatabaseClock {
    private final JdbcTemplate jdbc;

    public DatabaseClock(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    public Instant now() {
        OffsetDateTime now = jdbc.queryForObject("select current_timestamp", OffsetDateTime.class);
        return Objects.requireNonNull(now).toInstant().truncatedTo(ChronoUnit.MICROS);
    }
}
