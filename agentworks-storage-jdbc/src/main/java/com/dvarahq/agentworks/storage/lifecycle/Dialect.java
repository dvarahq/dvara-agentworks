package com.dvarahq.agentworks.storage.lifecycle;

import org.springframework.jdbc.core.JdbcTemplate;

/** Lock clauses per database. PostgreSQL is authoritative; H2 supports local development only. */
final class Dialect {
    private final JdbcTemplate jdbc;
    private final KeyLocks locks;

    Dialect(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.locks = new KeyLocks(jdbc);
    }

    boolean postgres() {
        return locks.postgres();
    }

    /** Latest repository code is also used by migration tests to seed schemas that predate this table. */
    boolean releaseLifecycleAvailable() {
        Integer count = jdbc.queryForObject("select count(*) from information_schema.tables "
                + "where table_schema = current_schema() and lower(table_name) = 'release_lifecycle'", Integer.class);
        return count != null && count > 0;
    }

    /**
     * Exclusive row lock for control-plane writers. On PostgreSQL it is FOR NO KEY UPDATE, which still excludes
     * the fence's FOR SHARE but lets foreign-key checks (FOR KEY SHARE) proceed, so they never form a cycle.
     */
    String exclusive() {
        return postgres() ? " for no key update" : " for update";
    }

    String exclusiveOf(String alias) {
        return postgres() ? " for no key update of " + alias : " for update";
    }

    /**
     * Serializes transactions that use the same idempotency key. PostgreSQL takes a transaction-scoped advisory
     * lock; on H2 the unique key insert makes a concurrent loser roll back and replay in a fresh transaction.
     */
    void lockKey(String scope) {
        locks.lock(scope);
    }

    String shared() {
        return postgres() ? " for share" : " for update";
    }
}
