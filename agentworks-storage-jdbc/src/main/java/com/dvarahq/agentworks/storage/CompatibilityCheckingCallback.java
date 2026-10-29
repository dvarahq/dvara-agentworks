package com.dvarahq.agentworks.storage;

import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;

import java.sql.SQLException;

/** Refuses an unsupported database before Flyway validates or mutates its schema. */
public final class CompatibilityCheckingCallback implements Callback {
    public static final String NAME = "00-agentworks-compatibility";

    private final boolean allowEmbedded;
    private boolean checked;

    public CompatibilityCheckingCallback(boolean allowEmbedded) {
        this.allowEmbedded = allowEmbedded;
    }

    @Override
    public boolean supports(Event event, Context context) {
        // BEFORE_MIGRATE is required when an operator disables Flyway's validate-on-migrate step.
        return event == Event.BEFORE_VALIDATE || event == Event.BEFORE_MIGRATE;
    }

    @Override
    public boolean canHandleInTransaction(Event event, Context context) {
        return false;
    }

    @Override
    public synchronized void handle(Event event, Context context) {
        if (checked) return;
        try {
            RuntimeCompatibility.requireDatabase(context.getConnection().getMetaData(), allowEmbedded);
            checked = true;
        } catch (SQLException failure) {
            throw new FlywayException("agentworks:COMPATIBILITY_CHECK_FAILED: cannot read database metadata", failure);
        }
    }

    @Override
    public String getCallbackName() {
        return NAME;
    }
}
