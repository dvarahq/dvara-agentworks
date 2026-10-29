package com.dvarahq.agentworks.runplane.execution;

import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleFixture;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.EnvironmentView;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.PromotionResult;
import com.dvarahq.agentworks.domain.run.ClaimScope;
import com.dvarahq.agentworks.domain.run.RunRecord;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import com.dvarahq.agentworks.runplane.admission.RunAdmissionService;
import com.dvarahq.agentworks.runplane.execution.DurableRunEngine.ClaimedRun;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

/**
 * A per-test PostgreSQL scenario on the shared lifecycle fixture: a fresh workspace, environment, and deployment
 * with one promoted release, plus helpers to submit and claim runs in that workspace only.
 */
final class RunScenario {
    static final Duration LEASE = Duration.ofMinutes(2);

    final LifecycleFixture fixture;
    final DurableRunEngine engine;
    final WorkspaceId workspace;
    final EnvironmentView environment;
    final PromotionResult promotion;

    RunScenario() {
        this(LifecycleFixture.postgres());
    }

    RunScenario(LifecycleFixture fixture) {
        this(fixture, "spring-ai");
    }

    /** A scenario whose release pins {@code executorType}. */
    RunScenario(String executorType) {
        this(LifecycleFixture.postgres(), executorType);
    }

    RunScenario(LifecycleFixture fixture, String executorType) {
        this.fixture = fixture;
        this.engine = fixture.engine;
        this.workspace = fixture.workspace();
        this.environment = fixture.environment(workspace, "prod");
        this.promotion = fixture.promote(workspace, environment, "api",
                fixture.release(workspace, "agent", "1.0.0", executorType));
    }

    /** Submits a root run: created and queued in one transaction. */
    RunRecord submit(Object input) {
        var admitted = fixture.admissions.admitRoot(workspace, promotion.deploymentId(), null, input,
                RunAdmissionService.Options.SUBMIT);
        return engine.run(workspace, admitted.run().id());
    }

    ClaimScope scope() {
        return ClaimScope.any(ReleaseManifestReader.SUPPORTED_VERSIONS).in(workspace);
    }

    ClaimedRun claim(String worker) {
        return claim(worker, LEASE);
    }

    ClaimedRun claim(String worker, Duration lease) {
        return engine.claimNext(worker, scope(), lease).orElseThrow();
    }

    /** Submits and claims a run, excluding any other claimable run of the workspace. */
    ClaimedRun started(String worker, Duration lease) {
        RunRecord run = submit(java.util.Map.of("task", "test"));
        Set<UUID> others = Set.copyOf(fixture.jdbc.queryForList("select id from agent_run where workspace_id = ? "
                + "and id <> ?", UUID.class, workspace.value(), run.id()));
        return engine.claimNext(worker, scope().excluding(others), lease).orElseThrow();
    }

    /**
     * Expires a run's lease in database time, as if its holder stalled past the expiry, without sleeping; the lease
     * row is changed outside the fence exactly as time passing would.
     */
    void expireLease(UUID runId) {
        fixture.bypassFence("update agent_run set lease_until = clock_timestamp() - interval '1 millisecond' "
                + "where id = ?", runId);
    }

    /** Every claim quarantine of this workspace, as the Control Plane lists them, by run and identity (#181). */
    List<com.dvarahq.agentworks.domain.run.ClaimQuarantine> quarantines() {
        return new com.dvarahq.agentworks.storage.run.JdbcClaimQuarantineRepository(fixture.jdbc, fixture.transactions)
                .list(workspace, true, 500, null).stream()
                .map(com.dvarahq.agentworks.storage.run.JdbcClaimQuarantineRepository.Listed::quarantine).toList();
    }

    long count(String sql, Object... arguments) {
        return fixture.count(sql, arguments);
    }

    /** Starts {@code parties} tasks together and returns each outcome or the exception it raised. */
    static List<Object> race(int parties, IntFunction<Object> task) throws Exception {
        CountDownLatch ready = new CountDownLatch(parties);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(parties)) {
            for (int index = 0; index < parties; index++) {
                int party = index;
                futures.add(executor.submit((Callable<Object>) () -> {
                    ready.countDown();
                    go.await(10, TimeUnit.SECONDS);
                    try {
                        return task.apply(party);
                    } catch (RuntimeException failure) {
                        return failure;
                    }
                }));
            }
            ready.await(10, TimeUnit.SECONDS);
            go.countDown();
            List<Object> outcomes = new ArrayList<>();
            for (Future<Object> future : futures) outcomes.add(future.get(60, TimeUnit.SECONDS));
            return outcomes;
        }
    }
}
