package com.dvarahq.agentworks.storage;

import org.junit.jupiter.api.Test;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuntimeCompatibilityTest {

    @Test
    void supportedJavaAndPostgresqlRangeAreAccepted() throws Exception {
        assertThatCode(() -> RuntimeCompatibility.requireJava(25, "OpenJDK Runtime Environment"))
                .doesNotThrowAnyException();
        for (int major : new int[]{15, 16, 17, 18}) {
            assertThatCode(() -> RuntimeCompatibility.requireDatabase(metadata("PostgreSQL", major), false))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void unsupportedJavaAndDatabasesFailWithAnActionableTypedDiagnosticBeforeMigration() {
        assertThatThrownBy(() -> RuntimeCompatibility.requireJava(26, "future-jdk"))
                .hasMessageContaining("agentworks:UNSUPPORTED_RUNTIME")
                .hasMessageContaining("Java 25")
                .hasMessageContaining("docs/operations/compatibility.md");
        for (DatabaseMetaData metadata : new DatabaseMetaData[]{metadata("PostgreSQL", 14),
                metadata("PostgreSQL", 19), metadata("MariaDB", 11), metadata("H2", 2)}) {
            assertThatThrownBy(() -> RuntimeCompatibility.requireDatabase(metadata, false))
                    .hasMessageContaining("agentworks:UNSUPPORTED_RUNTIME")
                    .hasMessageContaining("PostgreSQL 15 through 18");
        }
    }

    @Test
    void embeddedDatabaseRequiresAnExplicitLocalOrTestGate() throws Exception {
        assertThatCode(() -> RuntimeCompatibility.requireDatabase(metadata("H2", 2), true))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> RuntimeCompatibility.requireDatabase(metadata("H2", 2), false))
                .hasMessageContaining("only by the loopback local profile or tests");
    }

    @Test
    void flywayChecksBeforeMigrationEvenWhenValidationIsDisabledAndCachesSupportedMetadata() throws Exception {
        AtomicInteger metadataReads = new AtomicInteger();
        Context unsupported = context(metadata("PostgreSQL", 19), metadataReads);
        var callback = new CompatibilityCheckingCallback(false);

        assertThat(callback.supports(Event.BEFORE_VALIDATE, unsupported)).isTrue();
        assertThat(callback.supports(Event.BEFORE_MIGRATE, unsupported)).isTrue();
        assertThatThrownBy(() -> callback.handle(Event.BEFORE_MIGRATE, unsupported))
                .hasMessageContaining("agentworks:UNSUPPORTED_RUNTIME");

        AtomicInteger supportedReads = new AtomicInteger();
        Context supported = context(metadata("PostgreSQL", 15), supportedReads);
        var cached = new CompatibilityCheckingCallback(false);
        cached.handle(Event.BEFORE_VALIDATE, supported);
        cached.handle(Event.BEFORE_MIGRATE, supported);
        assertThat(supportedReads).hasValue(1);
    }

    private static Context context(DatabaseMetaData metadata, AtomicInteger metadataReads) {
        Connection connection = (Connection) Proxy.newProxyInstance(RuntimeCompatibilityTest.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("getMetaData")) {
                        metadataReads.incrementAndGet();
                        return metadata;
                    }
                    return method.getReturnType().isPrimitive() ? 0 : null;
                });
        return (Context) Proxy.newProxyInstance(RuntimeCompatibilityTest.class.getClassLoader(),
                new Class<?>[]{Context.class}, (proxy, method, arguments) ->
                        method.getName().equals("getConnection") ? connection
                                : method.getReturnType().isPrimitive() ? 0 : null);
    }

    private static DatabaseMetaData metadata(String product, int major) {
        return (DatabaseMetaData) Proxy.newProxyInstance(RuntimeCompatibilityTest.class.getClassLoader(),
                new Class<?>[]{DatabaseMetaData.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "getDatabaseProductName" -> product;
                    case "getDatabaseMajorVersion" -> major;
                    default -> method.getReturnType().isPrimitive() ? 0 : null;
                });
    }
}
