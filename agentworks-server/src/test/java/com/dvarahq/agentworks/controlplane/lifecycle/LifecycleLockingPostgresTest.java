package com.dvarahq.agentworks.controlplane.lifecycle;

import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.CohortCommand;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.EnvironmentView;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.PromotionCommand;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.PromotionPolicy;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.PromotionResult;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import com.dvarahq.agentworks.storage.lifecycle.LifecycleRetry;
import com.dvarahq.agentworks.storage.lifecycle.LifecycleRetryExhaustedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Deterministic lock order, bounded deadlock/lock-timeout retry, and PostgreSQL-authoritative time. */
class LifecycleLockingPostgresTest {
    private LifecycleFixture fixture;
    private WorkspaceId workspace;
    private EnvironmentView environment;

    @BeforeEach
    void setUp() {
        fixture = LifecycleFixture.postgres();
        workspace = fixture.workspace();
        environment = fixture.environment(workspace, "prod");
    }

    @Test
    void anOpposingLockOrderDeadlockIsRetriedToACompleteCommit() throws Exception {
        PromotionResult r1 = fixture.promote(workspace, environment, "api", fixture.release(workspace, "agent", "1.0.0"));
        var r2 = fixture.release(workspace, "agent", "2.0.0");
        List<String> retriedStates = new CopyOnWriteArrayList<>();
        LifecycleFixture observed = new LifecycleFixture(fixture.dataSource, new LifecycleRetry(4, failure ->
                retriedStates.add(sqlState(failure))), Duration.ofHours(24));
        CountDownLatch cohortLocked = new CountDownLatch(1);

        // The adversary takes the cohort and then the deployment, the opposite of the documented order. Its long
        // deadlock_timeout makes the waiting promotion the one PostgreSQL chooses as the deadlock victim.
        CompletableFuture<Void> adversary = CompletableFuture.runAsync(() -> {
            try (Connection connection = fixture.dataSource.getConnection(); Statement statement = connection.createStatement()) {
                connection.setAutoCommit(false);
                statement.execute("set local deadlock_timeout = '10s'");
                statement.execute("select 1 from runtime_cohort where id = '" + r1.cohortId() + "' for update");
                cohortLocked.countDown();
                awaitBlocked(connection);
                statement.execute("select 1 from agent_deployment where id = '" + r1.deploymentId() + "' for update");
                connection.commit();
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        });
        assertThat(cohortLocked.await(10, TimeUnit.SECONDS)).isTrue();

        PromotionResult result = observed.deployments.promote(workspace, new PromotionCommand(environment.id(), "api",
                r2.id(), null, 1L, null, "tester", null)).value();
        adversary.get(60, TimeUnit.SECONDS);

        assertThat(retriedStates).contains("40P01");
        assertThat(result.generation()).isEqualTo(2);
        assertThat(fixture.deployments.cohorts(workspace, r1.deploymentId())).extracting(row -> row.state())
                .containsExactly("DRAINING", "ROUTABLE");
        assertThat(fixture.count("select count(*) from deployment_revision where deployment_id = ?", r1.deploymentId())).isEqualTo(2);
    }

    /**
     * Waits until another backend is blocked on this transaction, so the deadlock is certain. Row-lock waits
     * appear as an ungranted transaction-id lock held by the waiting backend.
     */
    private static void awaitBlocked(Connection connection) throws Exception {
        for (int attempt = 0; attempt < 400; attempt++) {
            try (Statement statement = connection.createStatement(); var rows = statement.executeQuery(
                    "select count(*) from pg_locks where not granted and pid <> pg_backend_pid()")) {
                rows.next();
                if (rows.getLong(1) > 0) return;
            }
            Thread.sleep(25);
        }
        throw new IllegalStateException("no transaction blocked on the adversary");
    }

    private static String sqlState(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.sql.SQLException sql) return sql.getSQLState();
        }
        return "none";
    }

    /**
     * Reproduces admission against promotion: the admission holds the deployment FOR SHARE (the fence) while the
     * promotion holds the environment and waits for the deployment; the admission then needs FOR KEY SHARE on the
     * environment for its foreign-key check. Because control-plane locks are FOR NO KEY UPDATE, that check does not
     * wait, so no deadlock forms and the promotion needs no deadlock retry.
     */
    @Test
    void foreignKeyChecksNeverDeadlockWithControlPlaneLocks() throws Exception {
        PromotionResult r1 = fixture.promote(workspace, environment, "api", fixture.release(workspace, "agent", "1.0.0"));
        var r2 = fixture.release(workspace, "agent", "2.0.0");
        List<String> retriedStates = new CopyOnWriteArrayList<>();
        LifecycleFixture observed = new LifecycleFixture(fixture.dataSource, new LifecycleRetry(4, failure ->
                retriedStates.add(sqlState(failure))), Duration.ofHours(24));

        try (Connection admission = fixture.dataSource.getConnection(); Statement statement = admission.createStatement()) {
            admission.setAutoCommit(false);
            statement.execute("set local deadlock_timeout = '10s'");
            statement.execute("select 1 from agent_deployment where id = '" + r1.deploymentId() + "' for share");
            CompletableFuture<PromotionResult> promotion = CompletableFuture.supplyAsync(() -> observed.deployments.promote(
                    workspace, new PromotionCommand(environment.id(), "api", r2.id(), null, 1L, null, "tester", null)).value());
            awaitBlocked(admission);
            statement.execute("set local lock_timeout = '5s'");
            statement.execute("select 1 from agent_environment where id = '" + environment.id() + "' for key share");
            admission.commit();

            assertThat(promotion.get(60, TimeUnit.SECONDS).generation()).isEqualTo(2);
        }
        assertThat(retriedStates).doesNotContain("40P01");
    }

    @Test
    void retryExhaustionReturnsATypedFailureWithNoPartialCommitment() throws Exception {
        PromotionResult r1 = fixture.promote(workspace, environment, "api", fixture.release(workspace, "agent", "1.0.0"));
        var r2 = fixture.release(workspace, "agent", "2.0.0");
        var database = LifecycleFixture.postgresDatabase();
        LifecycleFixture impatient = new LifecycleFixture(new org.springframework.jdbc.datasource.DriverManagerDataSource(
                database.jdbcUrl() + "&options=-c%20lock_timeout%3D150ms",
                database.username(), database.password()),
                new LifecycleRetry(3), Duration.ofHours(24));

        try (Connection holder = fixture.dataSource.getConnection(); Statement statement = holder.createStatement()) {
            holder.setAutoCommit(false);
            statement.execute("select 1 from agent_deployment where id = '" + r1.deploymentId() + "' for update");

            assertThatThrownBy(() -> impatient.deployments.promote(workspace, new PromotionCommand(environment.id(), "api",
                    r2.id(), null, 1L, null, "tester", null)))
                    .isInstanceOfSatisfying(LifecycleRetryExhaustedException.class,
                            exhausted -> assertThat(exhausted.operation()).isEqualTo("deployment.promote"));
            holder.rollback();
        }
        assertThat(fixture.count("select count(*) from deployment_revision where deployment_id = ?", r1.deploymentId())).isOne();
        assertThat(fixture.count("select count(*) from lifecycle_decision where workspace_id = ?", workspace.value())).isOne();
        assertThat(fixture.count("select count(*) from routing_generation where deployment_id = ?", r1.deploymentId())).isOne();
        assertThat(fixture.deployments.cohort(workspace, r1.cohortId()).state()).isEqualTo("ROUTABLE");
    }

    @Test
    void lifecycleTimestampsAndDrainDeadlinesUsePostgresqlTime() {
        var immediate = fixture.environments.create(workspace, "immediate", null, new PromotionPolicy(List.of(), "PT0S"));
        var patient = fixture.environments.create(workspace, "patient", null, new PromotionPolicy(List.of(), "PT24H"));
        PromotionResult a1 = fixture.promote(workspace, immediate, "api", fixture.release(workspace, "agent", "1.0.0"));
        fixture.promote(workspace, immediate, "api", fixture.release(workspace, "agent", "2.0.0"));
        PromotionResult b1 = fixture.promote(workspace, patient, "api", fixture.release(workspace, "agent", "1.0.0"));
        fixture.promote(workspace, patient, "api", fixture.release(workspace, "agent", "2.0.0"));
        fixture.admissions.admitRoot(workspace, a1.deploymentId(), null, "{}");

        var overdue = fixture.deployments.observeQuiescence(workspace, a1.cohortId(), "tester");
        var withinDeadline = fixture.deployments.observeQuiescence(workspace, b1.cohortId(), "tester");

        assertThat(overdue.drainDeadlineExceeded()).isTrue();
        assertThat(overdue.state()).as("an exceeded deadline never retires or repins work").isEqualTo("QUIESCENT");
        assertThat(withinDeadline.drainDeadlineExceeded()).isFalse();
        Instant databaseNow = fixture.jdbc.queryForObject("select current_timestamp", OffsetDateTime.class).toInstant();
        assertThat(Duration.between(overdue.sourceCutoff(), databaseNow).abs()).isLessThan(Duration.ofMinutes(1));
        // Every row of one promotion carries the same database transaction timestamp.
        assertThat(fixture.jdbc.queryForObject("select count(distinct created_at) from (select created_at from "
                + "deployment_revision where decision_id = ? union all select created_at from lifecycle_decision where id = ? "
                + "union all select created_at from routing_generation where decision_id = ?) facts", Long.class,
                a1.decisionId(), a1.decisionId(), a1.decisionId())).isOne();
        var cohort = fixture.deployments.cohort(workspace, b1.cohortId());
        assertThat(Duration.between(cohort.drainStartedAt(), cohort.drainDeadline())).isEqualTo(Duration.ofHours(24));
        assertThatThrownBy(() -> fixture.deployments.retire(workspace, new CohortCommand(a1.cohortId(), 1, null, "t", null)))
                .isInstanceOfSatisfying(LifecycleConflictException.class,
                        conflict -> assertThat(conflict.code()).isEqualTo("STALE_VERSION"));
    }
}
