package com.dvarahq.agentworks.storage;

import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.Locale;

/** Fail-closed checks for runtime combinations that can be identified before migration or execution. */
public final class RuntimeCompatibility {
    public static final int JAVA_MAJOR = 25;
    public static final int POSTGRESQL_MINIMUM_MAJOR = 15;
    public static final int POSTGRESQL_MAXIMUM_MAJOR = 18;

    private RuntimeCompatibility() {
    }

    public static void requireJava(int feature, String runtime) {
        if (feature != JAVA_MAJOR) {
            throw unsupported("Java " + feature + " (" + safe(runtime) + ")",
                    "Java 25 from Eclipse Temurin or Amazon Corretto");
        }
    }

    public static void requireDatabase(DatabaseMetaData metadata, boolean allowEmbedded) throws SQLException {
        String product = metadata.getDatabaseProductName();
        int major = metadata.getDatabaseMajorVersion();
        String normalized = safe(product).toLowerCase(Locale.ROOT);
        if (normalized.contains("postgresql")) {
            if (major < POSTGRESQL_MINIMUM_MAJOR || major > POSTGRESQL_MAXIMUM_MAJOR) {
                throw unsupported(product + " " + major,
                        "PostgreSQL " + POSTGRESQL_MINIMUM_MAJOR + " through " + POSTGRESQL_MAXIMUM_MAJOR);
            }
            return;
        }
        if (normalized.equals("h2") && allowEmbedded) return;
        if (normalized.equals("h2")) {
            throw unsupported("H2 " + major,
                    "PostgreSQL 15 through 18; H2 is permitted only by the loopback local profile or tests");
        }
        throw unsupported(product + " " + major, "PostgreSQL 15 through 18");
    }

    private static IllegalStateException unsupported(String actual, String supported) {
        return new IllegalStateException("agentworks:UNSUPPORTED_RUNTIME: detected " + actual
                + "; supported: " + supported + ". See docs/operations/compatibility.md");
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "unknown runtime" : value.strip();
    }
}
