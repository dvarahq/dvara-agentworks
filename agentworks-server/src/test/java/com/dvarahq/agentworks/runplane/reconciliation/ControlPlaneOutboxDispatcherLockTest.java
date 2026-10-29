package com.dvarahq.agentworks.runplane.reconciliation;

import com.dvarahq.agentworks.controlplane.deployment.ControlPlaneOutboxDispatcher;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleFixture;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.AbstractQueuedSynchronizer;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PR #199 review, low 9: on H2, where no advisory lock exists, dispatches of one consumer are serialized by an
 * in-process lock that must be scoped to one database, not the whole JVM, and must span exactly the dispatch's own
 * transaction, so a dispatch inside a caller's transaction of the same data source is refused. #201: the lock is keyed
 * by the database, so every data source over it shares it, and a transaction on another resource is no reason to refuse.
 */
class ControlPlaneOutboxDispatcherLockTest {
    private static String h2Url() {
        return "jdbc:h2:mem:outbox-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
    }

    private static DataSource h2(String url) {
        return new DriverManagerDataSource(url, "sa", "");
    }

    private static DataSource migrated(String url) {
        DataSource source = h2(url);
        LifecycleFixture.migrate(source, false);
        return source;
    }

    /** A template whose transactions count down {@code entered} once they begin, then wait for {@code release}. */
    private static TransactionTemplate held(DataSource source, CountDownLatch entered, CountDownLatch release) {
        return new TransactionTemplate(new JdbcTransactionManager(source) {
            @Override
            protected void doBegin(Object transaction, org.springframework.transaction.TransactionDefinition definition) {
                super.doBegin(transaction, definition);
                entered.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        });
    }

    /**
     * Waits until {@code thread} is parked in the dispatcher's own lock, or {@code entered} shows it went past that
     * lock into its transaction; the outcome, not the wait, is what the caller asserts. No sleep decides the outcome.
     */
    private static void awaitParkedOrEntered(Thread thread, CountDownLatch entered) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!parkedInDispatchLock(thread) && entered.getCount() > 0 && thread.isAlive()) {
            if (System.nanoTime() > deadline) throw new AssertionError("the dispatch neither waited nor proceeded");
            Thread.sleep(1);
        }
    }

    /** Whether {@code thread} is parked in a {@code ReentrantLock.lock()} that the dispatcher's dispatch called. */
    private static boolean parkedInDispatchLock(Thread thread) {
        if (!(LockSupport.getBlocker(thread) instanceof AbstractQueuedSynchronizer)) return false;
        StackTraceElement[] frames = thread.getStackTrace();
        for (int frame = 1; frame < frames.length; frame++) {
            if (frames[frame].getClassName().equals(ControlPlaneOutboxDispatcher.class.getName())
                    && frames[frame].getMethodName().equals("dispatch")) {
                return frames[frame - 1].getClassName().equals(java.util.concurrent.locks.ReentrantLock.class.getName())
                        && frames[frame - 1].getMethodName().equals("lock");
            }
        }
        return false;
    }

    @Test
    void dispatchesOfOneConsumerAreSerializedPerDatabaseWhateverTheDataSource() throws Exception {
        String url = h2Url();
        DataSource first = migrated(url);
        DataSource second = migrated(h2Url());
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var holding = new ControlPlaneOutboxDispatcher(new JdbcTemplate(first), held(first, entered, release));
        // Each dispatch gets its own thread: on a small machine the common pool may have a single thread, which the
        // holder would occupy.
        var threads = java.util.concurrent.Executors.newThreadPerTaskExecutor(Thread.ofPlatform().factory());
        var holder = CompletableFuture.supplyAsync(() -> holding.dispatch("run-plane", 10, notification -> { }),
                threads);
        try {
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();

            // Another database's dispatcher of the same consumer is not held up.
            var elsewhere = new ControlPlaneOutboxDispatcher(new JdbcTemplate(second),
                    new TransactionTemplate(new JdbcTransactionManager(second)));
            assertThat(CompletableFuture.supplyAsync(() -> elsewhere.dispatch("run-plane", 10, notification -> { }),
                    threads).get(10, TimeUnit.SECONDS)).isZero();

            // The same database's is, until the holder's transaction ends, even through another data source object
            // (another pool or proxy over the same database, #201). It waits on the dispatch lock before its
            // transaction begins.
            DataSource samePool = h2(url);
            CountDownLatch sameEntered = new CountDownLatch(1);
            CountDownLatch sameRelease = new CountDownLatch(0);
            var same = new ControlPlaneOutboxDispatcher(new JdbcTemplate(samePool),
                    held(samePool, sameEntered, sameRelease));
            var waiting = new CompletableFuture<Integer>();
            Thread waiter = Thread.ofPlatform().start(() -> {
                try {
                    waiting.complete(same.dispatch("run-plane", 10, notification -> { }));
                } catch (RuntimeException failed) {
                    waiting.completeExceptionally(failed);
                }
            });
            awaitParkedOrEntered(waiter, sameEntered);
            assertThat(sameEntered.getCount()).as("the second dispatch's transaction began").isOne();
            assertThat(waiting).isNotDone();

            release.countDown();
            assertThat(waiting.get(10, TimeUnit.SECONDS)).isZero();
            assertThat(sameEntered.getCount()).isZero();
        } finally {
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
            threads.shutdown();
        }
    }

    // #201 review, low 6: a dispatch always runs in a transaction of its own. Inside a caller's transaction of the same
    // data source it neither joins it (its lock would end before the caller's commit, and its batch would be read from
    // the caller's snapshot) nor is refused: it delivers and records on its own, and the caller's rollback changes
    // nothing it did.
    @Test
    void aDispatchInsideACallersTransactionRunsInItsOwn() {
        DataSource source = migrated(h2Url());
        var fixture = new LifecycleFixture(source, com.dvarahq.agentworks.storage.lifecycle.LifecycleRetry.standard(),
                Duration.ofHours(24));
        var workspace = fixture.workspace();
        fixture.promote(workspace, fixture.environment(workspace, "local"), "api",
                fixture.release(workspace, "agent", "1.0.0"));
        var transactions = new TransactionTemplate(new JdbcTransactionManager(source));
        var dispatcher = new ControlPlaneOutboxDispatcher(new JdbcTemplate(source), transactions);
        long pending = dispatcher.pending("run-plane");
        assertThat(pending).isPositive();

        var delivered = new java.util.concurrent.atomic.AtomicInteger();
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            delivered.set(dispatcher.dispatch("run-plane", 1000, n -> { }));
            throw new IllegalStateException("the caller fails");
        })).hasMessageContaining("the caller fails");

        assertThat((long) delivered.get()).isEqualTo(pending);
        assertThat(dispatcher.pending("run-plane")).isZero();
        assertThat(dispatcher.dispatch("run-plane", 1000, n -> { })).isZero();
    }

    // #206 low: the dispatch's own transaction copied the injected template's isolation level. It is READ COMMITTED
    // whatever the template's, so its batch read never uses a snapshot taken before it held its lock.
    @Test
    void aDispatchRunsAtReadCommittedWhateverTheInjectedTemplatesIsolation() {
        DataSource source = migrated(h2Url());
        var fixture = new LifecycleFixture(source, com.dvarahq.agentworks.storage.lifecycle.LifecycleRetry.standard(),
                Duration.ofHours(24));
        var workspace = fixture.workspace();
        fixture.promote(workspace, fixture.environment(workspace, "local"), "api",
                fixture.release(workspace, "agent", "1.0.0"));
        var serializable = new TransactionTemplate(new JdbcTransactionManager(source));
        serializable.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_SERIALIZABLE);
        var dispatcher = new ControlPlaneOutboxDispatcher(new JdbcTemplate(source), serializable);

        var isolation = new java.util.concurrent.atomic.AtomicReference<Integer>();
        assertThat(dispatcher.dispatch("run-plane", 1, n -> isolation.set(org.springframework.transaction.support
                .TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()))).isOne();

        assertThat(isolation).hasValue(java.sql.Connection.TRANSACTION_READ_COMMITTED);
        // A manager that accepts it passes the startup check.
        dispatcher.verifyIsolation();
        // The injected template is left as it was.
        assertThat(serializable.getIsolationLevel())
                .isEqualTo(org.springframework.transaction.TransactionDefinition.ISOLATION_SERIALIZABLE);
    }

    // PR #208 review: a transaction manager that refuses READ COMMITTED fails when the server creates the dispatcher,
    // which checks it once, not at every dispatch; the check writes nothing.
    @Test
    void aTransactionManagerThatRefusesReadCommittedFailsTheStartupCheck() {
        DataSource source = migrated(h2Url());
        var refusing = new TransactionTemplate(new JdbcTransactionManager(source) {
            @Override
            protected void doBegin(Object transaction, org.springframework.transaction.TransactionDefinition definition) {
                if (definition.getIsolationLevel()
                        != org.springframework.transaction.TransactionDefinition.ISOLATION_DEFAULT) {
                    throw new org.springframework.transaction.InvalidIsolationLevelException("no custom isolation");
                }
                super.doBegin(transaction, definition);
            }
        });
        var dispatcher = new ControlPlaneOutboxDispatcher(new JdbcTemplate(source), refusing);

        assertThatThrownBy(dispatcher::verifyIsolation).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("READ COMMITTED");
        assertThat(dispatcher.pending("run-plane")).isZero();
    }

}
