package com.dvarahq.agentworks.autoconfigure;

import com.dvarahq.agentworks.controlplane.deployment.ControlPlaneOutboxDispatcher;
import com.dvarahq.agentworks.domain.lifecycle.state.LifecycleReads;
import com.dvarahq.agentworks.domain.lifecycle.state.LifecycleRows;
import com.dvarahq.agentworks.runplane.reconciliation.DeploymentReconciler;
import com.dvarahq.agentworks.storage.TenantTransactionTemplate;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/** #175 medium 4 and its reviews: startup reconciliation runs in the background and retries only what failed. */
class RunPlaneStartupReconciliationTest {
    private final DriverManagerDataSource source = new DriverManagerDataSource(
            "jdbc:h2:mem:startup-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
    private final JdbcTemplate jdbc = new JdbcTemplate(source);
    private final TransactionTemplate transactions = new TransactionTemplate(new JdbcTransactionManager(source));
    private final ControlPlaneOutboxDispatcher dispatcher = new ControlPlaneOutboxDispatcher(jdbc, transactions);
    private final Map<UUID, AtomicInteger> reads = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicInteger> failuresLeft = new ConcurrentHashMap<>();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger maxInFlight = new AtomicInteger();
    private final CountDownLatch firstReadEntered = new CountDownLatch(1);
    /** When set, the first deployment read waits for it, ignoring interrupts. */
    private volatile CountDownLatch holdFirstRead;

    private DeploymentReconciler reconciler() {
        LifecycleReads lifecycle = new LifecycleReads() {
            @Override
            public Optional<LifecycleRows.DeploymentRow> findDeployment(UUID id) {
                int concurrent = inFlight.incrementAndGet();
                maxInFlight.accumulateAndGet(concurrent, Math::max);
                try {
                    int read = reads.computeIfAbsent(id, key -> new AtomicInteger()).incrementAndGet();
                    CountDownLatch hold = holdFirstRead;
                    if (read == 1 && hold != null) {
                        firstReadEntered.countDown();
                        // Like a database read, it does not stop when the stopping executor interrupts it.
                        boolean released = false;
                        while (!released) {
                            try {
                                hold.await(30, TimeUnit.SECONDS);
                                released = true;
                            } catch (InterruptedException ignored) {
                                // keep reading
                            }
                        }
                    }
                    if (failuresLeft.getOrDefault(id, new AtomicInteger()).getAndDecrement() > 0) {
                        throw new IllegalStateException("deployment read failed");
                    }
                    return Optional.empty();
                } finally {
                    inFlight.decrementAndGet();
                }
            }

            @Override
            public Optional<LifecycleRows.RoutingGenerationRow> findRoutingGeneration(UUID id) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<LifecycleRows.RoutingTargetRow> routingTargets(UUID generationId) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<LifecycleRows.CohortRow> findCohort(UUID id) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<LifecycleRows.RevisionRow> findRevision(UUID id) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<LifecycleRows.ReleaseRow> findRelease(UUID id) {
                throw new UnsupportedOperationException();
            }
        };
        return new DeploymentReconciler(new TenantTransactionTemplate(transactions), lifecycle);
    }

    private RunPlaneStartupReconciliation startup() {
        return new RunPlaneStartupReconciliation(dispatcher, reconciler(), Duration.ofMillis(5), Duration.ofMillis(20));
    }

    private void deployments(UUID workspace, UUID... ids) {
        jdbc.execute("create table if not exists agent_deployment(workspace_id uuid not null, id uuid not null, "
                + "revision bigint not null)");
        for (UUID id : ids) jdbc.update("insert into agent_deployment values (?, ?, 1)", workspace, id);
    }

    private static boolean eventually(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return true;
            Thread.sleep(5);
        }
        return condition.getAsBoolean();
    }

    // Review 2, item 3: a slow or unreachable database never holds up start() (and so startup and readiness).
    @Test
    void startReturnsAtOnceWhileTheDatabaseIsUnreachable() throws Exception {
        CountDownLatch connecting = new CountDownLatch(1);
        DataSource hanging = new DelegatingDataSource(source) {
            @Override
            public Connection getConnection() throws SQLException {
                connecting.countDown();
                try {
                    Thread.sleep(Duration.ofSeconds(30));
                } catch (InterruptedException stopped) {
                    Thread.currentThread().interrupt();
                }
                throw new SQLException("unreachable");
            }
        };
        JdbcTemplate slow = new JdbcTemplate(hanging);
        var startup = new RunPlaneStartupReconciliation(new ControlPlaneOutboxDispatcher(slow,
                new TransactionTemplate(new JdbcTransactionManager(hanging))), reconciler());

        long started = System.nanoTime();
        startup.start();
        long elapsed = Duration.ofNanos(System.nanoTime() - started).toMillis();
        try {
            assertThat(elapsed).isLessThan(1_000);
            assertThat(startup.isRunning()).isTrue();
            // The first pass is under way on the background thread.
            assertThat(connecting.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(startup.completed()).isFalse();
        } finally {
            startup.stop();
        }
    }

    @Test
    void listingIsRetriedUntilTheDatabaseIsAvailable() throws Exception {
        RunPlaneStartupReconciliation startup = startup();
        startup.start();
        try {
            assertThat(eventually(() -> startup.passes() >= 2)).isTrue();
            assertThat(startup.completed()).isFalse();
            assertThat(startup.announced()).isEqualTo(-1);

            deployments(UUID.randomUUID(), UUID.randomUUID());

            assertThat(eventually(startup::completed)).isTrue();
            assertThat(startup.announced()).isOne();
        } finally {
            startup.stop();
        }
    }

    // Review 2, item 8: only the deployment that failed is retried, and the rest are read exactly once.
    @Test
    void onlyAFailedDeploymentIsRetried() throws Exception {
        UUID workspace = UUID.randomUUID();
        UUID failing = UUID.randomUUID();
        UUID healthy = UUID.randomUUID();
        deployments(workspace, failing, healthy);
        failuresLeft.put(failing, new AtomicInteger(2));
        RunPlaneStartupReconciliation startup = startup();

        startup.start();
        try {
            assertThat(eventually(startup::completed)).isTrue();
            assertThat(reads.get(failing)).hasValue(3);
            assertThat(reads.get(healthy)).hasValue(1);
            assertThat(startup.announced()).isEqualTo(2);
            assertThat(startup.abandoned()).isFalse();
        } finally {
            startup.stop();
        }
    }

    // Review 2, item 8: a deployment that always fails is given up after a bounded number of retries.
    @Test
    void aDeploymentThatAlwaysFailsIsGivenUpAfterTheRetryCap() throws Exception {
        UUID workspace = UUID.randomUUID();
        UUID failing = UUID.randomUUID();
        UUID healthy = UUID.randomUUID();
        deployments(workspace, failing, healthy);
        failuresLeft.put(failing, new AtomicInteger(Integer.MAX_VALUE));
        RunPlaneStartupReconciliation startup = startup();

        startup.start();
        try {
            assertThat(eventually(startup::abandoned)).isTrue();
            int passes = startup.passes();
            Thread.sleep(100);

            assertThat(startup.passes()).isEqualTo(passes);
            assertThat(reads.get(failing)).hasValue(1 + RunPlaneStartupReconciliation.MAX_DEPLOYMENT_RETRIES);
            assertThat(reads.get(healthy)).hasValue(1);
            assertThat(startup.completed()).isFalse();
        } finally {
            startup.stop();
        }
    }

    // #179 low 4: a pass left running by a stopped executor overlapped the restart's first pass on unsynchronized state,
    // and then scheduled its own retry onto the new executor, so two retry chains ran. Passes are now serialized, and
    // a replaced executor's attempt schedules nothing.
    @Test
    void aStopAndRestartDuringAPassLeavesOneRetryChain() throws Exception {
        UUID failing = UUID.randomUUID();
        deployments(UUID.randomUUID(), failing);
        failuresLeft.put(failing, new AtomicInteger(Integer.MAX_VALUE));
        CountDownLatch release = new CountDownLatch(1);
        holdFirstRead = release;
        RunPlaneStartupReconciliation startup = startup();
        startup.start();
        try {
            assertThat(firstReadEntered.await(5, TimeUnit.SECONDS)).isTrue();
            startup.stop();
            startup.start();
            // The restart's first attempt has begun while the stopped executor's pass is still reading.
            assertThat(eventually(() -> startup.passes() >= 2)).isTrue();
            release.countDown();

            assertThat(eventually(startup::abandoned)).isTrue();
            // The given-up state is final: no second chain keeps retrying.
            int passes = startup.passes();
            int read = reads.get(failing).get();
            Thread.sleep(100);
            assertThat(startup.passes()).isEqualTo(passes);
            assertThat(reads.get(failing)).hasValue(read);
            assertThat(maxInFlight).hasValue(1);
            assertThat(read).isEqualTo(1 + RunPlaneStartupReconciliation.MAX_DEPLOYMENT_RETRIES);
        } finally {
            release.countDown();
            startup.stop();
        }
    }

    @Test
    void stoppingEndsTheRetries() throws Exception {
        RunPlaneStartupReconciliation startup = startup();
        startup.start();
        assertThat(eventually(() -> startup.passes() >= 2)).isTrue();

        startup.stop();
        int stopped = startup.passes();
        Thread.sleep(100);

        assertThat(startup.isRunning()).isFalse();
        assertThat(startup.passes()).isLessThanOrEqualTo(stopped + 1);
        assertThat(startup.completed()).isFalse();
    }
}
