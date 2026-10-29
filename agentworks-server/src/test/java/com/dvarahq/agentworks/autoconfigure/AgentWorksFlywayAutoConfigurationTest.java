package com.dvarahq.agentworks.autoconfigure;

import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleFixture;
import com.dvarahq.agentworks.storage.CompatibilityCheckingCallback;
import com.dvarahq.agentworks.storage.LockTimeoutRestoringCallback;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #206 review, medium 1: V19_1 (applied, never changed) ends with {@code set_config('lock_timeout', '0', true)}, and
 * Flyway runs the {@code afterEachMigrate} callbacks and the schema-history insert in V19_1's transaction after it, and
 * with {@code spring.flyway.group} every later migration too. Through Spring Boot's own Flyway auto-configuration, on
 * PostgreSQL, the server's callback gives all of that the session's {@code lock_timeout} back.
 */
class AgentWorksFlywayAutoConfigurationTest {

    /** Issue #210: Flyway asks supports for every event; database metadata is stable and is read only once. */
    @Test
    void theLockTimeoutCallbackCachesItsDatabaseProductCheck() {
        var metadataReads = new java.util.concurrent.atomic.AtomicInteger();
        var metadata = (java.sql.DatabaseMetaData) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{java.sql.DatabaseMetaData.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getDatabaseProductName")) {
                        metadataReads.incrementAndGet();
                        return "PostgreSQL";
                    }
                    return null;
                });
        var connection = (java.sql.Connection) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{java.sql.Connection.class}, (proxy, method, args) ->
                        method.getName().equals("getMetaData") ? metadata : null);
        var context = (Context) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{Context.class}, (proxy, method, args) ->
                        method.getName().equals("getConnection") ? connection : null);
        var callback = new LockTimeoutRestoringCallback();

        assertThat(callback.supports(Event.BEFORE_EACH_MIGRATE, context)).isTrue();
        assertThat(callback.supports(Event.AFTER_EACH_MIGRATE, context)).isTrue();
        assertThat(callback.supports(Event.AFTER_MIGRATE, context)).isFalse();
        assertThat(metadataReads).hasValue(1);
    }

    /** Records the {@code lock_timeout} it sees at each event; its name sorts after every server callback. */
    static final class Probe implements Callback {
        final List<String> seen = new CopyOnWriteArrayList<>();

        @Override
        public boolean supports(Event event, Context context) {
            return event == Event.BEFORE_EACH_MIGRATE || event == Event.AFTER_EACH_MIGRATE;
        }

        @Override
        public boolean canHandleInTransaction(Event event, Context context) {
            return true;
        }

        @Override
        public void handle(Event event, Context context) {
            try (var statement = context.getConnection().createStatement();
                    var result = statement.executeQuery("select current_setting('lock_timeout')")) {
                result.next();
                seen.add(event.getId() + " " + context.getMigrationInfo().getVersion() + " " + result.getString(1));
            } catch (SQLException failure) {
                throw new IllegalStateException(failure);
            }
        }

        @Override
        public String getCallbackName() {
            return "zz-probe";
        }
    }

    @Test
    void v19_1sLockTimeoutResetNeverReachesTheRestOfItsMigration() {
        migrate(false, true);
    }

    @Test
    void v19_1sLockTimeoutResetNeverReachesLaterMigrationsOfAGroupSetFromTheEnvironment() {
        migrate(true, true);
    }

    /** Without the server's callback the reset is seen: the probe shows the finding is real. */
    @Test
    void withoutTheCallbackTheResetReachesTheRestOfTheMigration() {
        migrate(true, false);
    }

    private void migrate(boolean group, boolean withServerCallback) {
        LifecycleFixture.postgres();
        var container = LifecycleFixture.POSTGRES;
        String database = "flyway_lock_" + UUID.randomUUID().toString().replace("-", "");
        var admin = new JdbcTemplate(new DriverManagerDataSource(container.getJdbcUrl(), container.getUsername(),
                container.getPassword()));
        admin.execute("create database " + database);
        try {
            String url = container.getJdbcUrl().replace("/" + container.getDatabaseName(), "/" + database)
                    + (container.getJdbcUrl().contains("?") ? "&" : "?") + "options=-c%20lock_timeout%3D7s";
            var probe = new Probe();
            var configurations = withServerCallback
                    ? AutoConfigurations.of(
                            org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration.class,
                            org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration.class,
                            AgentWorksFlywayAutoConfiguration.class)
                    : AutoConfigurations.of(
                            org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration.class,
                            org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration.class);
            new ApplicationContextRunner().withConfiguration(configurations)
                    .withBean("probe", Probe.class, () -> probe)
                    // As an operator would set it: the environment variable, bound by Spring Boot's relaxed binding.
                    .withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                            new SystemEnvironmentPropertySource("test-environment",
                                    Map.of("SPRING_FLYWAY_GROUP", Boolean.toString(group)))))
                    .withPropertyValues("spring.datasource.url=" + url,
                            "spring.datasource.username=" + container.getUsername(),
                            "spring.datasource.password=" + container.getPassword(),
                            "spring.flyway.locations=classpath:db/migration/common,classpath:db/vendor/postgresql")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context.getBean(Flyway.class).getConfiguration().isGroup()).isEqualTo(group);
                        assertThat(context.getBeansOfType(LockTimeoutRestoringCallback.class))
                                .hasSize(withServerCallback ? 1 : 0);
                        assertThat(context.getBeansOfType(CompatibilityCheckingCallback.class))
                                .hasSize(withServerCallback ? 1 : 0);
                    });

            // The session's value before V19_1; after its script (right before the schema-history insert); and, in a
            // group, at the start of V20 in the same transaction.
            assertThat(probe.seen).contains("beforeEachMigrate 19.1 7s");
            String after = withServerCallback ? "7s" : "0";
            assertThat(probe.seen).contains("afterEachMigrate 19.1 " + after);
            if (group) assertThat(probe.seen).contains("beforeEachMigrate 20 " + after);
        } finally {
            admin.execute("drop database if exists " + database + " with (force)");
        }
    }
}
