package com.dvarahq.agentworks.storage.lifecycle;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Objects;

/**
 * Serializes transactions by a key (an idempotency key, or an outbox consumer), and says whether the database is
 * PostgreSQL, the only database that has the lock. PostgreSQL takes a transaction-scoped advisory lock, released when
 * the transaction that took it ends. H2 (local development) has none: its callers serialize otherwise. Shared by the
 * lifecycle stores and the outbox dispatcher (#201), so the probe and the lock exist once.
 */
public final class KeyLocks {
    private final JdbcTemplate jdbc;
    private volatile Boolean postgres;

    public KeyLocks(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /** Whether the database is PostgreSQL; read once. */
    public boolean postgres() {
        Boolean value = postgres;
        if (value == null) {
            value = Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>) connection ->
                    "PostgreSQL".equals(connection.getMetaData().getDatabaseProductName())));
            postgres = value;
        }
        return value;
    }

    /**
     * On PostgreSQL, takes the transaction-scoped advisory lock of {@code scope} in the current transaction, waiting
     * for any other transaction that holds it; elsewhere does nothing.
     */
    public void lock(String scope) {
        if (postgres()) {
            jdbc.queryForObject("select 1 from (select pg_advisory_xact_lock(hashtextextended(?, 0))) locked",
                    Integer.class, scope);
        }
    }
}
