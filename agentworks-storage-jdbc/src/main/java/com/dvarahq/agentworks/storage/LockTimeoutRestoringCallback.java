package com.dvarahq.agentworks.storage;

import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Gives every migration's remaining work the {@code lock_timeout} the migration started with, on PostgreSQL (#206).
 *
 * <p>Flyway runs, in each migration's transaction and after its script, the {@code afterEachMigrate} callbacks and then
 * the schema-history insert; with {@code spring.flyway.group} it also runs every later migration in that transaction.
 * V19_1, which is applied and is never changed, ends its retry block with {@code set_config('lock_timeout', '0',
 * true)}, so without this callback all of that would run without the operator's lock timeout. Before each migration
 * this callback reads the session's {@code lock_timeout}, and after it restores that value, local to the transaction
 * when there is one, before the schema-history insert. Callbacks run in name order; this callback's numeric prefix
 * puts it before the project's conventionally named callbacks, including Flyway's built-ins and the verification
 * probe. It does not claim to sort before every string Java can represent (#210). Other databases are left alone.
 */
public final class LockTimeoutRestoringCallback implements Callback {
    /** This callback's name; its numeric prefix sorts before the project's conventional callback names. */
    public static final String NAME = "0-agentworks-restore-lock-timeout";

    /** The value read before the current migration; Flyway migrates on one thread. */
    private String before;
    /** This Flyway instance migrates one database; its product check is stable and read once (#210). */
    private volatile Boolean postgresql;

    @Override
    public boolean supports(Event event, Context context) {
        return (event == Event.BEFORE_EACH_MIGRATE || event == Event.AFTER_EACH_MIGRATE) && postgres(context);
    }

    @Override
    public boolean canHandleInTransaction(Event event, Context context) {
        return true;
    }

    @Override
    public void handle(Event event, Context context) {
        Connection connection = context.getConnection();
        try {
            if (event == Event.BEFORE_EACH_MIGRATE) {
                before = query(connection, "select current_setting('lock_timeout')", null);
            } else if (before != null) {
                query(connection, "select set_config('lock_timeout', ?, " + !connection.getAutoCommit() + ")", before);
                before = null;
            }
        } catch (SQLException failure) {
            throw new FlywayException("cannot restore lock_timeout after a migration", failure);
        }
    }

    @Override
    public String getCallbackName() {
        return NAME;
    }

    private static String query(Connection connection, String sql, String argument) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            if (argument != null) statement.setString(1, argument);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getString(1);
            }
        }
    }

    private boolean postgres(Context context) {
        Boolean known = postgresql;
        if (known != null) return known;
        synchronized (this) {
            if (postgresql == null) {
                try {
                    postgresql = context.getConnection().getMetaData().getDatabaseProductName()
                            .toLowerCase(java.util.Locale.ROOT).contains("postgresql");
                } catch (SQLException unknown) {
                    postgresql = false;
                }
            }
            return postgresql;
        }
    }
}
