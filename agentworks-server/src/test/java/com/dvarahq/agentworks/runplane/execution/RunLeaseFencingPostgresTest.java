package com.dvarahq.agentworks.runplane.execution;

import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.CohortCommand;
import com.dvarahq.agentworks.domain.run.ClaimScope;
import com.dvarahq.agentworks.domain.run.RunBlockReason;
import com.dvarahq.agentworks.domain.run.RunEffect;
import com.dvarahq.agentworks.domain.run.RunEventType;
import com.dvarahq.agentworks.domain.run.RunLease;
import com.dvarahq.agentworks.domain.run.RunLifecycle;
import com.dvarahq.agentworks.domain.run.RunRecord;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import com.dvarahq.agentworks.runplane.execution.DurableRunEngine.ClaimedRun;
import com.dvarahq.agentworks.runplane.execution.DurableRunEngine.EventDraft;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.dvarahq.agentworks.runplane.execution.DurableRunLifecyclePostgresTest.assertCode;
import static com.dvarahq.agentworks.runplane.execution.DurableRunLifecyclePostgresTest.sleep;
import static com.dvarahq.agentworks.runplane.execution.RunScenario.race;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Leases, fencing, database time, and cohort transitions for workers on PostgreSQL. */
class RunLeaseFencingPostgresTest {
    private final RunScenario scenario = new RunScenario();
    private final DurableRunEngine engine = scenario.engine;

    @Test
    void competingWorkersProduceExactlyOneValidOwner() throws Exception {
        RunRecord run = scenario.submit(null);

        List<Object> outcomes = race(12, index -> engine.claimNext("worker-" + index, scenario.scope(), RunScenario.LEASE));

        List<ClaimedRun> winners = new ArrayList<>();
        for (Object outcome : outcomes) {
            assertThat(outcome).isInstanceOf(Optional.class);
            ((Optional<?>) outcome).ifPresent(claimed -> winners.add((ClaimedRun) claimed));
        }
        assertThat(winners).singleElement().satisfies(claimed -> {
            assertThat(claimed.run().id()).isEqualTo(run.id());
            assertThat(claimed.lease().fence()).isOne();
        });
        assertThat(scenario.count("select count(*) from agent_run_event where run_id = ? and event_type = 'RUN_ACTIVATED'",
                run.id())).isOne();
        assertThat(scenario.count("select count(*) from cohort_work_item where run_id = ? and category = 'CLAIM' "
                + "and state = 'ACTIVE'", run.id())).isOne();
    }

    @Test
    void aStaleLeaseHolderCannotWriteAfterAnotherWorkerRecoveredTheRun() {
        ClaimedRun first = scenario.started("worker-a", RunScenario.LEASE);
        var progress = engine.append(first.lease(), List.of(EventDraft.of(RunEventType.MODEL_REQUESTED, Map.of())));
        scenario.expireLease(first.run().id());
        ClaimedRun second = scenario.claim("worker-b");

        assertThat(second.recovered()).isTrue();
        assertThat(second.lease().fence()).isEqualTo(2);
        for (Runnable stale : List.<Runnable>of(
                () -> engine.append(progress.lease(), List.of(EventDraft.of(RunEventType.MODEL_REQUESTED, Map.of()))),
                () -> engine.checkpoint(progress.lease(), Map.of()),
                () -> engine.heartbeat(progress.lease(), RunScenario.LEASE),
                () -> engine.block(progress.lease(), RunBlockReason.CAPACITY, null),
                () -> engine.complete(progress.lease(), null))) {
            assertCode(stale, "STALE_LEASE");
        }
        // Directly, the stale owner cannot extend or release its expired lease either.
        assertThatThrownBy(() -> scenario.fixture.asRole("agentworks_run_plane", "update agent_run set lease_owner = ?, "
                + "lease_fence = ?, lease_until = clock_timestamp() + interval '1 hour' where id = ?", "worker-a", 1L,
                first.run().id())).isInstanceOf(RuntimeException.class);
        RunRecord done = engine.complete(second.lease(), null);
        assertThat(done.summary().leaseFence()).isEqualTo(2);
        var recovered = engine.history(scenario.workspace, first.run().id()).stream()
                .filter(event -> event.type() == RunEventType.RUN_RECOVERED).findFirst().orElseThrow();
        assertThat(recovered.payload().path("previousOwner").stringValue()).isEqualTo("worker-a");
        assertThat(recovered.payload().path("runtimeMillis").longValue()).isBetween(0L, RunScenario.LEASE.toMillis());
        assertThat(engine.verify(scenario.workspace, first.run().id()).intact()).isTrue();
    }

    @Test
    void aLiveLeaseCannotBeStolenOrReassignedByDirectWrites() {
        ClaimedRun claimed = scenario.started("owner", RunScenario.LEASE);
        UUID runId = claimed.run().id();
        String writer = "agentworks_run_plane";

        // A second claim of a live lease is refused before anything else is considered.
        assertThatThrownBy(() -> scenario.fixture.asRole(writer, "update agent_run set lease_owner = 'thief', "
                + "lease_fence = lease_fence + 1, row_version = row_version + 1 where id = ?", runId))
                .hasStackTraceContaining("LEASE_HELD");
        // Renaming the owner under the same fence is refused too.
        assertThatThrownBy(() -> scenario.fixture.asRole(writer, "update agent_run set lease_owner = 'thief' where id = ?",
                runId)).hasStackTraceContaining("STALE_LEASE");
        assertThat(engine.claimNext("thief", scenario.scope(), RunScenario.LEASE)).isEmpty();
        assertThat(engine.run(scenario.workspace, runId).control().leaseOwner()).isEqualTo("owner");
        assertThat(engine.append(claimed.lease(), List.of(EventDraft.of(RunEventType.MODEL_REQUESTED, Map.of())))
                .ended()).isFalse();
    }

    @Test
    void aDelayedWorkerCannotCommitOnceItsLeaseExpiredInDatabaseTime() {
        ClaimedRun claimed = scenario.started("slow-worker", Duration.ofMillis(300));
        sleep(500);

        // Nobody else claimed the run, yet the expired lease grants nothing and cannot be revived.
        assertCode(() -> engine.append(claimed.lease(), List.of(EventDraft.of(RunEventType.MODEL_REQUESTED, Map.of()))),
                "STALE_LEASE");
        assertCode(() -> engine.heartbeat(claimed.lease(), RunScenario.LEASE), "STALE_LEASE");
        assertThat(engine.run(scenario.workspace, claimed.run().id()).summary().eventSequence())
                .isEqualTo(claimed.run().summary().eventSequence());
    }

    @Test
    void postgresTimeGovernsLeasesRegardlessOfWorkerClockSkewOrJumps() {
        ClaimedRun claimed = scenario.started("worker", RunScenario.LEASE);
        UUID runId = claimed.run().id();
        // Worker clock jumped backwards: the worker still believes its lease is valid for another hour...
        RunLease believer = new RunLease(claimed.lease().workspace(), runId, claimed.lease().cohortId(),
                claimed.lease().owner(), claimed.lease().fence(), claimed.lease().rowVersion(),
                claimed.lease().leaseUntil().plus(Duration.ofHours(1)));
        // ...while authoritative database time has passed the expiry.
        scenario.fixture.bypassFence("update agent_run set lease_until = clock_timestamp() - interval '1 second' "
                + "where id = ?", runId);
        assertCode(() -> engine.append(believer, List.of(EventDraft.of(RunEventType.MODEL_REQUESTED, Map.of()))),
                "STALE_LEASE");
        assertThatThrownBy(() -> scenario.fixture.asRole("agentworks_run_plane", "update agent_run set lease_until = "
                + "clock_timestamp() + interval '1 hour' where id = ?", runId)).hasStackTraceContaining("STALE_LEASE");

        // Worker clock jumped forwards: the worker believes its lease expired, but database time says it holds it.
        ClaimedRun fresh = scenario.started("worker-2", RunScenario.LEASE);
        RunLease doubter = new RunLease(fresh.lease().workspace(), fresh.run().id(), fresh.lease().cohortId(),
                fresh.lease().owner(), fresh.lease().fence(), fresh.lease().rowVersion(),
                fresh.lease().leaseUntil().minus(Duration.ofHours(1)));
        assertThat(engine.append(doubter, List.of(EventDraft.of(RunEventType.MODEL_REQUESTED, Map.of()))).ended())
                .isFalse();
        // Lease expiry is recorded from PostgreSQL time, not the worker's.
        assertThat(scenario.count("select count(*) from agent_run where id = ? and lease_until between "
                + "clock_timestamp() + interval '100 seconds' and clock_timestamp() + interval '121 seconds'",
                fresh.run().id())).isOne();
    }

    @Test
    void concurrentWritesWithOneTokenAreLinearizedAndHeartbeatsDoNotSupersedeThem() throws Exception {
        ClaimedRun claimed = scenario.started("worker", RunScenario.LEASE);
        RunLease beat = engine.heartbeat(claimed.lease(), RunScenario.LEASE);
        assertThat(beat.rowVersion()).isEqualTo(claimed.lease().rowVersion());

        List<Object> outcomes = race(2, index -> engine.checkpoint(claimed.lease(), Map.of("writer", index)));

        assertThat(outcomes).filteredOn(DurableRunEngine.WriteResult.class::isInstance).hasSize(1);
        assertThat(outcomes).filteredOn(RunEngineException.class::isInstance).singleElement()
                .satisfies(failure -> assertThat(((RunEngineException) failure).code()).isEqualTo("STALE_LEASE"));
        assertThat(engine.run(scenario.workspace, claimed.run().id()).summary().checkpointSequence()).isOne();
    }

    @Test
    void drainingCohortRunsStillClaimAndResumeWhileNewRootsGoToTheNewCohort() {
        RunRecord old = scenario.submit(null);
        engine.block(scenario.claim("worker").lease(), RunBlockReason.HUMAN_INTERACTION, null);
        var r2 = scenario.fixture.promote(scenario.workspace, scenario.environment, "api",
                scenario.fixture.release(scenario.workspace, "agent", "2.0.0"));
        RunRecord fresh = scenario.submit(null);

        engine.resume(scenario.workspace, old.id(), "system:interaction");
        ClaimedRun resumed = engine.claimNext("worker", scenario.scope().excluding(java.util.Set.of(fresh.id())),
                RunScenario.LEASE).orElseThrow();

        assertThat(scenario.fixture.deployments.cohort(scenario.workspace, old.pins().cohortId()).state())
                .isEqualTo("DRAINING");
        assertThat(resumed.run().id()).isEqualTo(old.id());
        assertThat(resumed.cohortState()).isEqualTo("DRAINING");
        assertThat(resumed.run().pins().revisionId()).isEqualTo(scenario.promotion.revisionId());
        assertThat(fresh.pins().cohortId()).isEqualTo(r2.cohortId());
        engine.complete(resumed.lease(), null);
    }

    @Test
    void suspensionStopsReinvokingAnInDoubtEffectButStillRecordsAnOutcome() {
        ClaimedRun active = scenario.started("worker", RunScenario.LEASE);
        RunLease lease;
        try {
            engine.executeEffect(active.lease(), "charge", Map.of(), (key, request) -> {
                throw new java.io.IOException("timeout");
            });
            throw new AssertionError("expected in doubt");
        } catch (DurableRunEngine.EffectInDoubtException inDoubt) {
            lease = inDoubt.lease();
        }
        scenario.fixture.deployments.suspend(scenario.workspace, new CohortCommand(scenario.promotion.cohortId(), 2,
                null, "operator", "incident"));
        java.util.concurrent.atomic.AtomicInteger invoked = new java.util.concurrent.atomic.AtomicInteger();
        RunLease held = lease;

        assertThatThrownBy(() -> engine.executeEffect(held, "charge", Map.of(), (key, request) -> {
            invoked.incrementAndGet();
            return null;
        })).isInstanceOfSatisfying(RunEngineException.class,
                failure -> assertThat(failure.getMessage()).contains("COHORT_NOT_ADMITTING SUSPENDED"));
        assertThat(invoked).hasValue(0);
        assertThat(engine.effects(scenario.workspace, active.run().id()).getFirst().attempts()).isOne();
    }

    @Test
    void suspensionFencesClaimsResumesAndNewEffectsButAllowsSafeBoundaryWrites() {
        ClaimedRun active = scenario.started("worker", RunScenario.LEASE);
        RunRecord queued = scenario.submit(null);
        scenario.fixture.deployments.suspend(scenario.workspace, new CohortCommand(scenario.promotion.cohortId(), 2,
                null, "operator", "incident"));

        assertThat(engine.claimNext("other", scenario.scope(), RunScenario.LEASE)).isEmpty();
        var boundary = engine.checkpoint(active.lease(), Map.of("step", 1));
        assertThat(boundary.cohortState()).isEqualTo("SUSPENDED");
        assertThatThrownBy(() -> engine.executeEffect(boundary.lease(), "charge", Map.of(), (key, request) -> null))
                .isInstanceOfSatisfying(RunEngineException.class, failure -> {
                    assertThat(failure.code()).isEqualTo("COHORT_NOT_ADMITTING");
                    assertThat(failure.getMessage()).contains("SUSPENDED");
                });
        RunRecord paused = engine.pause(boundary.lease(), "COHORT_SUSPENDED");
        assertThat(paused.lifecycle()).isEqualTo(RunLifecycle.PAUSED);
        assertCode(() -> engine.resume(scenario.workspace, paused.id(), "operator"), "COHORT_NOT_ADMITTING");
        assertThat(engine.run(scenario.workspace, queued.id()).lifecycle()).isEqualTo(RunLifecycle.QUEUED);
        // The same immutable cohort resumes; its work continues.
        scenario.fixture.deployments.resume(scenario.workspace, new CohortCommand(scenario.promotion.cohortId(), 3,
                null, "operator", null));
        engine.resume(scenario.workspace, paused.id(), "operator");
        assertThat(engine.claimNext("other", scenario.scope(), RunScenario.LEASE)).isPresent();
    }

    @Test
    void revocationStopsTheWorkerAtItsNextWriteAndLeavesInFlightEffectsExplicitlyInDoubt() {
        ClaimedRun active = scenario.started("worker", RunScenario.LEASE);
        try {
            engine.executeEffect(active.lease(), "deploy", Map.of("target", "prod"), (key, request) -> {
                scenario.fixture.deployments.revoke(scenario.workspace, new CohortCommand(scenario.promotion.cohortId(),
                        2, null, "security", "compromised release"));
                return JsonNodeFactory.instance.objectNode().put("deployed", true);
            });
            throw new AssertionError("the commit must be fenced");
        } catch (RunEngineException fenced) {
            assertThat(fenced.code()).isEqualTo("COHORT_NOT_ADMITTING");
        }
        UUID runId = active.run().id();
        RunLease current = new RunLease(active.lease().workspace(), runId, active.lease().cohortId(),
                active.lease().owner(), active.lease().fence(), engine.run(scenario.workspace, runId).control().rowVersion(),
                active.lease().leaseUntil());
        assertCode(() -> engine.append(current, List.of(EventDraft.of(RunEventType.MODEL_REQUESTED, Map.of()))),
                "COHORT_NOT_ADMITTING");
        // Terminalization evidence is always permitted; the effect is not reported as rolled back or completed.
        RunRecord failed = engine.fail(current, "COHORT_REVOKED", "the cohort was revoked mid-effect");
        assertThat(failed.lifecycle()).isEqualTo(RunLifecycle.FAILED);
        assertThat(engine.effects(scenario.workspace, runId)).singleElement()
                .satisfies(effect -> assertThat(effect.state()).isEqualTo(RunEffect.State.PENDING));
        assertThat(engine.history(scenario.workspace, runId).getLast().payload().path("inDoubtEffects").get(0).stringValue())
                .isEqualTo("deploy");
        assertThat(scenario.count("select count(*) from cohort_work_item where run_id = ? and category = 'OPERATION' "
                + "and state = 'PENDING'", runId)).isOne();
        engine.resolveEffect(scenario.workspace, runId, "deploy", "security:alice", "verified in the target system");
        assertThat(engine.effects(scenario.workspace, runId).getFirst().state()).isEqualTo(RunEffect.State.ABANDONED);
    }

    @Test
    void oneStatelessWorkerPoolServesManyAgentsTenantsAndReleasesWithoutCrossPinning() {
        var billing = scenario.fixture.promote(scenario.workspace, scenario.environment, "billing",
                scenario.fixture.release(scenario.workspace, "billing-agent", "3.1.0"));
        WorkspaceId other = scenario.fixture.workspace();
        var otherPromotion = scenario.fixture.promote(other, scenario.fixture.environment(other, "prod"), "api",
                scenario.fixture.release(other, "agent", "1.0.0"));
        List<UUID> runs = new ArrayList<>();
        runs.add(scenario.submit(Map.of("tenant", "a")).id());
        runs.add(scenario.fixture.admissions.admitRoot(scenario.workspace, billing.deploymentId(), null, Map.of("tenant", "a2"),
                com.dvarahq.agentworks.runplane.admission.RunAdmissionService.Options.SUBMIT).run().id());
        runs.add(scenario.fixture.admissions.admitRoot(other, otherPromotion.deploymentId(), null, Map.of("tenant", "b"),
                com.dvarahq.agentworks.runplane.admission.RunAdmissionService.Options.SUBMIT).run().id());
        // One pool across every agent, tenant, and release, as a spring-ai node composes it. Claimable runs of other tests
        // sharing the database are excluded only to keep the test hermetic; runs of other executor types are never
        // selected, and the exclusion list stays within ClaimScope.MAX_EXCLUDED_RUNS.
        var unrelated = new java.util.HashSet<>(scenario.fixture.jdbc.queryForList("select id from agent_run where "
                + "lifecycle in ('QUEUED', 'ACTIVE') and executor_type = 'spring-ai'", UUID.class));
        unrelated.removeAll(runs);
        ClaimScope pool = ClaimScope.any(ReleaseManifestReader.SUPPORTED_VERSIONS).executing(java.util.Set.of("spring-ai"))
                .excluding(unrelated);

        List<ClaimedRun> claimed = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            claimed.add(engine.claimNext("pool-worker", pool, RunScenario.LEASE).orElseThrow());
        }
        assertThat(engine.claimNext("pool-worker", pool, RunScenario.LEASE)).isEmpty();

        assertThat(claimed).extracting(c -> c.run().id()).containsExactlyInAnyOrderElementsOf(runs);
        for (ClaimedRun run : claimed) {
            assertThat(run.manifest().versionId()).isEqualTo(run.run().pins().versionId());
            engine.complete(run.lease(), run.input());
        }
        assertThat(claimed).filteredOn(c -> c.run().pins().workspaceId().equals(other.value())).singleElement()
                .satisfies(c -> assertThat(c.input().path("tenant").stringValue()).isEqualTo("b"));
        assertCode(() -> engine.run(other, runs.getFirst()), "NOT_FOUND");
        assertCode(() -> engine.history(other, runs.getFirst()), "NOT_FOUND");
        assertCode(() -> engine.cancel(other, runs.getFirst(), "operator", null), "NOT_FOUND");
    }
}
