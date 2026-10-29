package com.dvarahq.agentworks.runplane.execution;

import com.dvarahq.agentworks.domain.run.ClaimScope;
import com.dvarahq.agentworks.domain.run.DurableRunStore;
import com.dvarahq.agentworks.domain.run.RunAuthorityException;
import com.dvarahq.agentworks.domain.run.RunLifecycle;
import com.dvarahq.agentworks.domain.run.RunRecord;
import com.dvarahq.agentworks.domain.run.RunTransaction;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import com.dvarahq.agentworks.storage.lifecycle.LifecycleRetry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #175 review 2, item 4: with SKIP LOCKED, concurrent workers keep claiming other runs while one holds a poison run
 * locked. Claim failures are counted per run, so those successes do not reset the poison run's count, and the poison
 * run is quarantined while other work remains. Since #181 the quarantine is durable and shared: once one worker records
 * it, no worker selects the run again.
 */
class RunWorkerQuarantinePostgresTest {
    private static final int GOOD_RUNS = 40;

    private final RunScenario scenario = new RunScenario();

    @Test
    void concurrentWorkersQuarantineAPoisonRunWhileOtherWorkRemains() throws Exception {
        RunRecord poison = scenario.submit(Map.of("task", "poison"));
        List<UUID> good = new ArrayList<>();
        for (int index = 0; index < GOOD_RUNS; index++) good.add(scenario.submit(Map.of("task", index)).id());
        DurableRunStore store = scenario.fixture.runStore;
        DurableRunEngine engine = new DurableRunEngine(new DurableRunStore() {
            @Override
            public <T> T inTransaction(WorkspaceId workspace, Function<RunTransaction, T> work) {
                return store.inTransaction(workspace, work);
            }

            @Override
            public <T> Optional<T> claimNext(ClaimScope scope, BiFunction<RunRecord, RunTransaction, T> work) {
                return store.claimNext(scope, (run, tx) -> {
                    if (!run.id().equals(poison.id())) return work.apply(run, tx);
                    return work.apply(run, refusingClaims(tx));
                });
            }

            @Override
            public Optional<com.dvarahq.agentworks.domain.run.ClaimQuarantine> quarantine(UUID runId,
                    com.dvarahq.agentworks.domain.run.ClaimQuarantine.Request request) {
                return store.quarantine(runId, request);
            }

            @Override
            public boolean keepsClaimQuarantine() {
                return true;
            }
        }, RunEventCodec.standard(), new ReleaseManifestReader(), new LifecycleRetry(1));

        List<Future<Integer>> remainingAtQuarantine = new ArrayList<>();
        try (ExecutorService threads = Executors.newFixedThreadPool(2)) {
            for (int party = 0; party < 2; party++) {
                String name = "worker-" + party;
                remainingAtQuarantine.add(threads.submit(() -> {
                    try (RunWorker worker = new RunWorker(engine, context -> {
                        Thread.sleep(30);
                        return RunOutcome.completed(null);
                    }, name, scenario.scope(), RunScenario.LEASE)) {
                        for (int poll = 0; poll < GOOD_RUNS * 4; poll++) {
                            if (worker.pollOnce() == RunWorker.Poll.REFUSED) {
                                assertThat(scenario.quarantines()).singleElement()
                                        .satisfies(quarantine -> assertThat(quarantine.runId()).isEqualTo(poison.id()));
                                return queued(good);
                            }
                        }
                        return -1;
                    }
                }));
            }
            List<Integer> remaining = new ArrayList<>();
            for (Future<Integer> party : remainingAtQuarantine) remaining.add(party.get(120, TimeUnit.SECONDS));
            // The poison run was quarantined while other runs were still queued. A worker that reached its streak
            // at the same moment finds the quarantine in force and does not extend it.
            assertThat(remaining).anySatisfy(count -> assertThat(count).isPositive()).doesNotContain(0);
            assertThat(scenario.quarantines()).singleElement()
                    .satisfies(quarantine -> assertThat(quarantine.quarantines()).isOne());
        }
        assertThat(engine.run(scenario.workspace, poison.id()).lifecycle()).isEqualTo(RunLifecycle.QUEUED);
    }

    // #179 medium 3: the JDBC store reports the selected run before its row is read and decoded, so a failure there
    // (or in the claim) is attributed to that run. The schema's constraints keep undecodable rows out of PostgreSQL, so
    // the decoding failure itself is proven against the port in RunWorkerTest.aRunThatCannotBeDecodedIsQuarantined.
    @Test
    void theStoreReportsTheSelectedRunBeforeReadingIt() {
        RunRecord run = scenario.submit(Map.of("task", "t"));
        List<String> order = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.concurrent.atomic.AtomicReference<UUID> selected = new java.util.concurrent.atomic.AtomicReference<>();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> scenario.fixture.runStore.claimNext(
                scenario.scope().compatibleWith(scenario.engine.compatibility()), id -> {
                    order.add("selected");
                    selected.set(id);
                }, (record, tx) -> {
                    order.add("decoded");
                    throw new IllegalStateException("claim failed");
                })).isInstanceOf(IllegalStateException.class);

        assertThat(order).containsExactly("selected", "decoded");
        assertThat(selected).hasValue(run.id());
        assertThat(scenario.engine.run(scenario.workspace, run.id()).lifecycle()).isEqualTo(RunLifecycle.QUEUED);
    }

    // #185 low: a scope that names no compatibility identity honored no quarantine, so a direct store caller claimed
    // quarantined runs. The store now refuses such a scope, and the engine always names its own identity.
    @Test
    void theStoreRefusesAClaimThatNamesNoCompatibilityIdentity() {
        RunRecord run = scenario.submit(Map.of("task", "t"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> scenario.fixture.runStore.claimNext(scenario.scope(),
                (record, tx) -> record)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("compatibility identity");

        assertThat(scenario.engine.run(scenario.workspace, run.id()).lifecycle()).isEqualTo(RunLifecycle.QUEUED);
    }

    private int queued(List<UUID> runs) {
        return (int) runs.stream().filter(id -> scenario.engine.run(scenario.workspace, id).lifecycle()
                == RunLifecycle.QUEUED).count();
    }

    /** The poison run's claim holds its row lock for a while and is then refused, as a failing guard would. */
    private static RunTransaction refusingClaims(RunTransaction tx) {
        return (RunTransaction) java.lang.reflect.Proxy.newProxyInstance(RunTransaction.class.getClassLoader(),
                new Class<?>[]{RunTransaction.class}, (proxy, method, args) -> {
                    if (method.getName().equals("insertWorkItem")) {
                        Thread.sleep(Duration.ofMillis(150));
                        throw new RunAuthorityException("WORK_ITEM_REJECTED", null, null);
                    }
                    try {
                        return method.invoke(tx, args);
                    } catch (java.lang.reflect.InvocationTargetException thrown) {
                        throw thrown.getCause();
                    }
                });
    }
}
