package com.dvarahq.agentworks.runplane.execution;

import com.dvarahq.agentworks.domain.run.RunBlockReason;
import com.dvarahq.agentworks.domain.run.RunEffect;
import com.dvarahq.agentworks.domain.run.RunEventType;
import com.dvarahq.agentworks.domain.run.RunLifecycle;
import com.dvarahq.agentworks.domain.run.RunRecord;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Issue #183 over the persistence port: an operator's pause of a queued run takes effect at once, and an active run's
 * pause is a durable request that its worker observes and honors at its next safe boundary, never inside an effect.
 * Every race ends in exactly one legal outcome.
 */
class RunPauseTest {
    private static final String OPERATOR = "user:ops";
    /** Runs of an executor that checkpoints at every boundary, so an operator may pause them while active. */
    private final EngineHarness harness = new EngineHarness(EngineHarness.CHECKPOINTING, EngineHarness.EXECUTORS);

    private RunRecord queued() {
        return harness.engine.enqueue(harness.workspace, harness.run(), "system:test");
    }

    private RunRecord run(UUID id) {
        return harness.engine.run(harness.workspace, id);
    }

    private RunEventCodec.DecodedEvent last(UUID id) {
        return harness.engine.history(harness.workspace, id).getLast();
    }

    private RunWorker worker(RunHandler handler) {
        return new RunWorker(harness.engine, handler, "worker-1", harness.scope(), Duration.ofSeconds(30));
    }

    @Test
    void aQueuedRunPausesAtOnceNamingTheOperatorAndRepeatsAreIdempotent() {
        RunRecord queued = queued();

        var paused = harness.engine.requestPause(harness.workspace, queued.id(), OPERATOR, "maintenance window");

        assertThat(paused.pending()).isFalse();
        assertThat(paused.run().lifecycle()).isEqualTo(RunLifecycle.PAUSED);
        var event = last(queued.id());
        assertThat(event.type()).isEqualTo(RunEventType.RUN_PAUSED);
        assertThat(event.raw().actor()).isEqualTo(OPERATOR);
        assertThat(event.payload().path("reason").stringValue()).isEqualTo(DurableRunEngine.OPERATOR_PAUSE);
        assertThat(event.payload().path("requestedBy").stringValue()).isEqualTo(OPERATOR);
        assertThat(event.payload().path("detail").stringValue()).isEqualTo("maintenance window");
        assertThat(event.payload().path("from").stringValue()).isEqualTo("QUEUED");
        // A repeat returns the paused run unchanged: one RUN_PAUSED, one transition.
        var again = harness.engine.requestPause(harness.workspace, queued.id(), "user:other", null);
        assertThat(again.pending()).isFalse();
        assertThat(again.run().summary().eventSequence()).isEqualTo(paused.run().summary().eventSequence());
        // No worker claims a paused run; the existing resume queues it again, with its pins unchanged.
        assertThat(harness.engine.claimNext("worker-1", harness.scope(), EngineHarness.LEASE)).isEmpty();
        RunRecord resumed = harness.engine.resumePaused(harness.workspace, queued.id(), OPERATOR);
        assertThat(resumed.lifecycle()).isEqualTo(RunLifecycle.QUEUED);
        assertThat(resumed.pins()).isEqualTo(queued.pins());
        assertThat(harness.engine.verify(harness.workspace, queued.id()).intact()).isTrue();
    }

    @Test
    void anActiveRunRecordsADurableRequestThatItsNextWriteObservesAndItsPauseConsumes() {
        var claimed = harness.started("worker-1");
        UUID id = claimed.run().id();

        var requested = harness.engine.requestPause(harness.workspace, id, OPERATOR, "drain");
        assertThat(requested.pending()).isTrue();
        assertThat(requested.run().lifecycle()).isEqualTo(RunLifecycle.ACTIVE);
        // The worker still holds its lease: no event was written for the request, and nothing it does is refused.
        assertThat(requested.run().summary().eventSequence()).isEqualTo(claimed.run().summary().eventSequence());
        // A repeat by another operator keeps the first request.
        assertThat(harness.engine.requestPause(harness.workspace, id, "user:other", "again").pending()).isTrue();

        var write = harness.engine.append(claimed.lease(), List.of(DurableRunEngineTest.model(1)));
        assertThat(write.pauseRequested()).isTrue();
        var checkpoint = harness.engine.checkpoint(write.lease(), Map.of("step", 1));
        assertThat(checkpoint.pauseRequested()).isTrue();

        RunRecord paused = harness.engine.pause(checkpoint.lease(), DurableRunEngine.OPERATOR_PAUSE);

        assertThat(paused.lifecycle()).isEqualTo(RunLifecycle.PAUSED);
        assertThat(paused.control().leaseOwner()).isNull();
        var event = last(id);
        assertThat(event.type()).isEqualTo(RunEventType.RUN_PAUSED);
        assertThat(event.payload().path("reason").stringValue()).isEqualTo(DurableRunEngine.OPERATOR_PAUSE);
        assertThat(event.payload().path("requestedBy").stringValue()).isEqualTo(OPERATOR);
        assertThat(event.payload().path("detail").stringValue()).isEqualTo("drain");
        // The request was consumed: after a resume, the next claim runs normally.
        harness.engine.resumePaused(harness.workspace, id, OPERATOR);
        assertThat(harness.claim("worker-2").pauseRequested()).isFalse();
        assertThat(harness.engine.verify(harness.workspace, id).intact()).isTrue();
    }

    // Review round 1: a request exists only while the run is active. A run that blocks before its next boundary ends
    // the request, its RUN_BLOCKED names the requester, and it never pauses later when its completion resumes it.
    @Test
    void aRunThatBlocksBeforeItsBoundaryEndsTheRequestAndNamesTheRequester() {
        var claimed = harness.started("worker-1");
        UUID id = claimed.run().id();
        harness.engine.requestPause(harness.workspace, id, OPERATOR, null);

        harness.engine.block(claimed.lease(), RunBlockReason.HUMAN_INTERACTION, null);

        assertThat(last(id).payload().path("pauseRequestedBy").stringValue()).isEqualTo(OPERATOR);
        assertThat(harness.store.pauseRequests()).isEmpty();
        harness.engine.resume(harness.workspace, id, "system:interaction");
        assertThat(harness.claim("worker-2").pauseRequested()).isFalse();
    }

    // PR #191 review, medium 2: a resume that withdraws a request the worker already observed must not pause the run.
    // The check and the pause are one atomic write, and the operator reason is reserved for a pending request.
    @Test
    void aWithdrawalRacingTheBoundaryLeavesTheRunActiveAndContinuing() {
        var claimed = harness.started("worker-1");
        UUID id = claimed.run().id();
        harness.engine.requestPause(harness.workspace, id, OPERATOR, null);
        var observed = harness.engine.append(claimed.lease(), List.of(DurableRunEngineTest.model(1)));
        assertThat(observed.pauseRequested()).isTrue();
        harness.engine.resumePaused(harness.workspace, id, OPERATOR);

        assertThat(harness.engine.pauseIfRequested(observed.lease())).isEmpty();
        assertThatThrownBy(() -> harness.engine.pause(observed.lease(), DurableRunEngine.OPERATOR_PAUSE))
                .isInstanceOf(RunEngineException.class).extracting("code").isEqualTo("PAUSE_NOT_REQUESTED");

        assertThat(run(id).lifecycle()).isEqualTo(RunLifecycle.ACTIVE);
        assertThat(harness.engine.complete(observed.lease(), Map.of("done", true)).lifecycle())
                .isEqualTo(RunLifecycle.SUCCEEDED);
        assertThat(harness.engine.history(harness.workspace, id).stream()
                .filter(e -> e.type() == RunEventType.RUN_PAUSED)).isEmpty();
    }

    // PR #191 review, medium 2, through the worker: a handler that observed the request pauses through the context,
    // which finds it withdrawn, so the run stays active and the handler completes it.
    @Test
    void aWorkerWhoseRequestWasWithdrawnBeforeItsBoundaryContinues() {
        RunRecord queued = queued();
        try (RunWorker worker = worker(context -> {
            harness.engine.requestPause(harness.workspace, context.pins().id(), OPERATOR, null);
            context.emit(List.of(DurableRunEngineTest.model(1)));
            assertThat(context.pauseRequested()).isTrue();
            harness.engine.resumePaused(harness.workspace, context.pins().id(), OPERATOR);
            if (context.pauseIfRequested()) return RunOutcome.paused(DurableRunEngine.OPERATOR_PAUSE);
            return RunOutcome.completed(Map.of("continued", true));
        })) {
            assertThat(worker.pollOnce()).isEqualTo(RunWorker.Poll.EXECUTED);
        }
        assertThat(run(queued.id()).lifecycle()).isEqualTo(RunLifecycle.SUCCEEDED);
    }

    // A handler that stops for a request it did not pause through the context, after a resume withdrew it, leaves the
    // run active for recovery rather than pausing it: the operator reason is reserved for a pending request.
    @Test
    void aHandlerThatStopsForAWithdrawnRequestLeavesTheRunActiveForRecovery() {
        RunRecord queued = queued();
        try (RunWorker worker = worker(context -> {
            harness.engine.requestPause(harness.workspace, context.pins().id(), OPERATOR, null);
            context.emit(List.of(DurableRunEngineTest.model(1)));
            harness.engine.resumePaused(harness.workspace, context.pins().id(), OPERATOR);
            return RunOutcome.paused(DurableRunEngine.OPERATOR_PAUSE);
        })) {
            assertThat(worker.pollOnce()).isEqualTo(RunWorker.Poll.LOST);
        }
        assertThat(run(queued.id()).lifecycle()).isEqualTo(RunLifecycle.ACTIVE);
    }

    // PR #191 review, medium 5: pause is enforced for handlers that never ask. Right after a checkpoint, and before an
    // effect starts (so nothing is dispatched), the worker pauses the run and stops the handler.
    @Test
    void theWorkerEnforcesThePauseAtACheckpointAndBeforeAnEffectForAnUncooperativeHandler() {
        RunRecord atCheckpoint = queued();
        var reachedAfterCheckpoint = new java.util.concurrent.atomic.AtomicBoolean();
        try (RunWorker worker = worker(context -> {
            harness.engine.requestPause(harness.workspace, context.pins().id(), OPERATOR, null);
            context.checkpoint(Map.of("step", 1));
            reachedAfterCheckpoint.set(true);
            return RunOutcome.completed(null);
        })) {
            assertThat(worker.pollOnce()).isEqualTo(RunWorker.Poll.EXECUTED);
        }
        assertThat(reachedAfterCheckpoint).isFalse();
        assertThat(run(atCheckpoint.id()).lifecycle()).isEqualTo(RunLifecycle.PAUSED);
        assertThat(run(atCheckpoint.id()).summary().checkpointSequence()).isOne();

        RunRecord beforeEffect = queued();
        var invoked = new java.util.concurrent.atomic.AtomicBoolean();
        try (RunWorker worker = worker(context -> {
            harness.engine.requestPause(harness.workspace, context.pins().id(), OPERATOR, null);
            context.emit(List.of(DurableRunEngineTest.model(1)));
            context.effect("charge", Map.of(), (key, request) -> {
                invoked.set(true);
                return null;
            });
            return RunOutcome.completed(null);
        })) {
            assertThat(worker.pollOnce()).isEqualTo(RunWorker.Poll.EXECUTED);
        }
        assertThat(invoked).isFalse();
        assertThat(run(beforeEffect.id()).lifecycle()).isEqualTo(RunLifecycle.PAUSED);
        assertThat(harness.engine.effects(harness.workspace, beforeEffect.id())).isEmpty();
    }

    // PR #191 review, medium 4: an active run whose worker is gone (lease expired) and that no worker recovers pauses at
    // once, the fence advancing so the lost worker can never write again; with an effect in doubt, the request is kept
    // and the effect reported.
    @Test
    void anActiveRunWhoseLeaseExpiredPausesAtOnceUnlessAnEffectIsInDoubt() {
        var lost = harness.started("worker-lost");
        UUID id = lost.run().id();
        harness.store.advance(EngineHarness.LEASE.plusSeconds(1));

        var paused = harness.engine.requestPause(harness.workspace, id, OPERATOR, "stuck");

        assertThat(paused.outcome()).isEqualTo(DurableRunEngine.PauseResult.Outcome.PAUSED);
        assertThat(paused.run().lifecycle()).isEqualTo(RunLifecycle.PAUSED);
        assertThat(paused.run().summary().leaseFence()).isEqualTo(lost.lease().fence() + 1);
        var event = last(id);
        assertThat(event.raw().actor()).isEqualTo(OPERATOR);
        assertThat(event.payload().path("leaseExpired").booleanValue()).isTrue();
        assertThatThrownBy(() -> harness.engine.complete(lost.lease(), null))
                .isInstanceOf(RunEngineException.class).extracting("code").isEqualTo("STALE_LEASE");
        assertThat(harness.engine.verify(harness.workspace, id).intact()).isTrue();

        var inDoubt = harness.started("worker-lost-2");
        UUID doubtful = inDoubt.run().id();
        assertThatThrownBy(() -> harness.engine.executeEffect(inDoubt.lease(), "charge", Map.of(), (key, request) -> {
            throw new java.io.IOException("timeout");
        })).isInstanceOf(DurableRunEngine.EffectInDoubtException.class);
        harness.store.advance(EngineHarness.LEASE.plusSeconds(1));

        var requested = harness.engine.requestPause(harness.workspace, doubtful, OPERATOR, null);

        assertThat(requested.outcome()).isEqualTo(DurableRunEngine.PauseResult.Outcome.REQUESTED);
        assertThat(requested.inDoubtEffects()).containsExactly("charge");
        assertThat(run(doubtful).lifecycle()).isEqualTo(RunLifecycle.ACTIVE);
    }

    // PR #191 review, medium 6: a recovery claim of a run with an effect in doubt does not pause at the claim; the run
    // recovers as usual (the effect is retried with its stable key) and pauses at the first boundary after that.
    @Test
    void aRecoveredRunWithAnEffectInDoubtRecoversItBeforePausing() {
        var lost = harness.started("worker-lost");
        UUID id = lost.run().id();
        assertThatThrownBy(() -> harness.engine.executeEffect(lost.lease(), "charge", Map.of(), (key, request) -> {
            throw new java.io.IOException("timeout");
        })).isInstanceOf(DurableRunEngine.EffectInDoubtException.class);
        harness.store.advance(EngineHarness.LEASE.plusSeconds(1));
        harness.engine.requestPause(harness.workspace, id, OPERATOR, null);
        var retried = new java.util.concurrent.atomic.AtomicInteger();

        try (RunWorker worker = worker(context -> {
            context.effect("charge", Map.of(), (key, request) -> {
                retried.incrementAndGet();
                return tools.jackson.databind.node.JsonNodeFactory.instance.objectNode().put("charged", true);
            });
            context.checkpoint(Map.of("step", "charged"));
            return RunOutcome.completed(null);
        })) {
            assertThat(worker.pollOnce()).isEqualTo(RunWorker.Poll.EXECUTED);
        }

        assertThat(retried).hasValue(1);
        assertThat(run(id).lifecycle()).isEqualTo(RunLifecycle.PAUSED);
        assertThat(harness.engine.effects(harness.workspace, id)).singleElement()
                .satisfies(effect -> assertThat(effect.state()).isEqualTo(RunEffect.State.COMMITTED));
        assertThat(last(id).payload().path("inDoubtEffects")).isEmpty();
    }

    // PR #191 review, low 7: a limit that ends the run names a pending requester, like every other ending.
    @Test
    void aLimitEndingNamesThePendingRequester() {
        UUID limited = harness.run(new com.dvarahq.agentworks.domain.run.RunLimits(null, 1L, null, null, null),
                "{\"task\":\"t\"}");
        harness.engine.enqueue(harness.workspace, limited, "system:test");
        var claimed = harness.claim("worker-1");
        harness.engine.requestPause(harness.workspace, limited, OPERATOR, null);
        harness.engine.append(claimed.lease(), List.of(DurableRunEngineTest.model(2)));
        assertThat(last(limited).type()).isEqualTo(RunEventType.RUN_FAILED);
        assertThat(last(limited).payload().path("pauseRequestedBy").stringValue()).isEqualTo(OPERATOR);
    }

    @Test
    void terminalEventsNameAPendingRequester() {
        var cancelled = harness.started("worker-1");
        harness.engine.requestPause(harness.workspace, cancelled.run().id(), OPERATOR, null);
        harness.engine.cancel(harness.workspace, cancelled.run().id(), "user:other", "stop");
        assertThat(last(cancelled.run().id()).payload().path("pauseRequestedBy").stringValue()).isEqualTo(OPERATOR);

        var completed = harness.started("worker-1");
        harness.engine.requestPause(harness.workspace, completed.run().id(), OPERATOR, null);
        harness.engine.complete(completed.lease(), null);
        assertThat(last(completed.run().id()).payload().path("pauseRequestedBy").stringValue()).isEqualTo(OPERATOR);
        assertThat(harness.store.pauseRequests()).isEmpty();
    }

    // PR #191 review, medium 3 (the user's decision): an active run whose executor cannot resume mid-exchange without
    // repeating completed model rounds or tools (the Spring AI executor today) is refused PAUSE_UNSAFE, with nothing
    // written; its queued runs still pause at once.
    @Test
    void anActiveRunOfAnExecutorThatCannotResumeMidExchangeIsRefusedAndNothingIsWritten() {
        var springAi = new EngineHarness("spring-ai", EngineHarness.EXECUTORS);
        UUID queued = springAi.engine.enqueue(springAi.workspace, springAi.run(), "system:test").id();
        var claimed = springAi.claim("worker-1");
        long head = claimed.run().summary().eventSequence();

        assertThatThrownBy(() -> springAi.engine.requestPause(springAi.workspace, queued, OPERATOR, null))
                .isInstanceOf(RunEngineException.class).extracting("code").isEqualTo("PAUSE_UNSAFE");

        assertThat(springAi.engine.run(springAi.workspace, queued).summary().eventSequence()).isEqualTo(head);
        assertThat(springAi.store.pauseRequests()).isEmpty();
        UUID another = springAi.engine.enqueue(springAi.workspace, springAi.run(), "system:test").id();
        assertThat(springAi.engine.requestPause(springAi.workspace, another, OPERATOR, null).run().lifecycle())
                .isEqualTo(RunLifecycle.PAUSED);
        // Once its worker is gone (the lease expired), pausing repeats no more than recovery would: it pauses at once.
        springAi.store.advance(EngineHarness.LEASE.plusSeconds(1));
        assertThat(springAi.engine.requestPause(springAi.workspace, queued, OPERATOR, null).outcome())
                .isEqualTo(DurableRunEngine.PauseResult.Outcome.PAUSED);
        assertThat(springAi.engine.verify(springAi.workspace, queued).intact()).isTrue();
    }

    // PR #191 review round 4: an expired run that already has a request pauses at once too, answering the request.
    @Test
    void anExpiredRunWithAPendingRequestPausesAtOnce() {
        var lost = harness.started("worker-lost");
        UUID id = lost.run().id();
        harness.engine.requestPause(harness.workspace, id, OPERATOR, null);
        harness.store.advance(EngineHarness.LEASE.plusSeconds(1));

        assertThat(harness.engine.requestPause(harness.workspace, id, "user:other", null).outcome())
                .isEqualTo(DurableRunEngine.PauseResult.Outcome.PAUSED);
        assertThat(harness.store.pauseRequests()).isEmpty();
        assertThat(run(id).lifecycle()).isEqualTo(RunLifecycle.PAUSED);
        assertThat(last(id).payload().path("requestedBy").stringValue()).isEqualTo("user:other");
        assertThat(last(id).payload().path("pauseRequestedBy").stringValue()).isEqualTo(OPERATOR);
    }

    // A request recorded by a node that allowed it, for an executor this node does not promise can resume
    // mid-exchange, is cleared at the worker's boundary instead of honored; the run continues.
    @Test
    void aRequestForAnExecutorThatCannotResumeMidExchangeIsClearedNotHonored() {
        var claimed = harness.started("worker-1");
        UUID id = claimed.run().id();
        harness.engine.requestPause(harness.workspace, id, OPERATOR, null);
        var strict = new DurableRunEngine(harness.store, harness.engine.codec(), new ReleaseManifestReader(),
                new com.dvarahq.agentworks.storage.lifecycle.LifecycleRetry(2), harness.engine.compatibility(),
                ExecutorCapabilities.Registry.NONE_DECLARED);

        assertThat(strict.pauseIfRequested(claimed.lease())).isEmpty();

        assertThat(harness.store.pauseRequests()).isEmpty();
        assertThat(run(id).lifecycle()).isEqualTo(RunLifecycle.ACTIVE);
    }

    @Test
    void blockedCreatedAndTerminalRunsAreTypedRefusals() {
        UUID created = harness.run();
        assertThatThrownBy(() -> harness.engine.requestPause(harness.workspace, created, OPERATOR, null))
                .isInstanceOf(RunEngineException.class).extracting("code").isEqualTo("RUN_NOT_QUEUED");

        var claimed = harness.started("worker-1");
        harness.engine.block(claimed.lease(), RunBlockReason.HUMAN_INTERACTION, null);
        assertThatThrownBy(() -> harness.engine.requestPause(harness.workspace, claimed.run().id(), OPERATOR, null))
                .isInstanceOf(RunEngineException.class).extracting("code").isEqualTo("RUN_BLOCKED");

        harness.engine.cancel(harness.workspace, claimed.run().id(), OPERATOR, "done");
        assertThatThrownBy(() -> harness.engine.requestPause(harness.workspace, claimed.run().id(), OPERATOR, null))
                .isInstanceOf(RunEngineException.class).extracting("code").isEqualTo("RUN_TERMINAL");
        assertThatThrownBy(() -> harness.engine.requestPause(harness.workspace, UUID.randomUUID(), OPERATOR, null))
                .isInstanceOf(RunEngineException.class).extracting("code").isEqualTo("NOT_FOUND");
    }

    @Test
    void anotherWorkspaceCannotPauseTheRun() {
        RunRecord queued = queued();
        var other = new EngineHarness(harness);
        assertThatThrownBy(() -> other.engine.requestPause(other.workspace, queued.id(), OPERATOR, null))
                .isInstanceOf(RunEngineException.class).extracting("code").isEqualTo("NOT_FOUND");
        assertThat(run(queued.id()).lifecycle()).isEqualTo(RunLifecycle.QUEUED);
    }

    @Test
    void aResumeWithdrawsARequestTheRunHasNotReachedYet() {
        var claimed = harness.started("worker-1");
        UUID id = claimed.run().id();
        harness.engine.requestPause(harness.workspace, id, OPERATOR, null);

        assertThat(harness.engine.resumePaused(harness.workspace, id, OPERATOR).lifecycle()).isEqualTo(RunLifecycle.ACTIVE);

        var write = harness.engine.checkpoint(claimed.lease(), Map.of("step", 1));
        assertThat(write.pauseRequested()).isFalse();
        // Without a request, an active run's resume is still the typed RUN_ACTIVE refusal.
        assertThatThrownBy(() -> harness.engine.resumePaused(harness.workspace, id, OPERATOR))
                .isInstanceOf(RunEngineException.class).extracting("code").isEqualTo("RUN_ACTIVE");
    }

    // Races: whatever ends the run first wins, exactly once, and the request ends with it.
    @Test
    void completionCancellationAndLimitFailureWinOverAPendingRequest() {
        var completing = harness.started("worker-1");
        harness.engine.requestPause(harness.workspace, completing.run().id(), OPERATOR, null);
        assertThat(harness.engine.complete(completing.lease(), Map.of("ok", true)).lifecycle())
                .isEqualTo(RunLifecycle.SUCCEEDED);
        assertThat(harness.store.pauseRequests()).isEmpty();
        assertThatThrownBy(() -> harness.engine.requestPause(harness.workspace, completing.run().id(), OPERATOR, null))
                .isInstanceOf(RunEngineException.class).extracting("code").isEqualTo("RUN_TERMINAL");

        var cancelled = harness.started("worker-1");
        harness.engine.requestPause(harness.workspace, cancelled.run().id(), OPERATOR, null);
        harness.engine.cancel(harness.workspace, cancelled.run().id(), OPERATOR, "stop");
        assertThat(harness.store.pauseRequests()).isEmpty();
        // The worker's pause after the cancellation is refused as stale: one outcome, CANCELLED.
        assertThatThrownBy(() -> harness.engine.pause(cancelled.lease(), DurableRunEngine.OPERATOR_PAUSE))
                .isInstanceOf(RunEngineException.class).extracting("code").isEqualTo("STALE_LEASE");
        assertThat(run(cancelled.run().id()).lifecycle()).isEqualTo(RunLifecycle.CANCELLED);

        UUID limited = harness.run(new com.dvarahq.agentworks.domain.run.RunLimits(null, 1L, null, null, null),
                "{\"task\":\"t\"}");
        harness.engine.enqueue(harness.workspace, limited, "system:test");
        var claimed = harness.claim("worker-1");
        harness.engine.requestPause(harness.workspace, limited, OPERATOR, null);
        var write = harness.engine.append(claimed.lease(), List.of(DurableRunEngineTest.model(2)));
        assertThat(write.ended()).isTrue();
        assertThat(run(limited).lifecycle()).isEqualTo(RunLifecycle.FAILED);
        assertThat(harness.store.pauseRequests()).isEmpty();
    }

    @Test
    void aSuspensionAndAPendingRequestPauseTheRunOnceAndTheEventNamesTheOperator() {
        var claimed = harness.started("worker-1");
        UUID id = claimed.run().id();
        harness.engine.requestPause(harness.workspace, id, OPERATOR, null);
        harness.store.cohort(harness.cohort, "SUSPENDED");
        try {
            var write = harness.engine.checkpoint(claimed.lease(), Map.of("step", 1));
            assertThat(write.cohortState()).isEqualTo("SUSPENDED");
            assertThat(write.pauseRequested()).isTrue();
            harness.engine.pause(write.lease(), "COHORT_SUSPENDED");
        } finally {
            harness.store.cohort(harness.cohort, "ROUTABLE");
        }
        var event = last(id);
        assertThat(event.payload().path("reason").stringValue()).isEqualTo("COHORT_SUSPENDED");
        assertThat(event.payload().path("requestedBy").stringValue()).isEqualTo(OPERATOR);
        assertThat(harness.store.pauseRequests()).isEmpty();
        assertThat(harness.engine.history(harness.workspace, id).stream()
                .filter(e -> e.type() == RunEventType.RUN_PAUSED)).hasSize(1);
    }

    @Test
    void aRecoveryClaimObservesARequestAndTheWorkerPausesAtTheClaimBeforeAnyWork() {
        var claimed = harness.started("worker-lost");
        UUID id = claimed.run().id();
        harness.engine.requestPause(harness.workspace, id, OPERATOR, null);
        harness.store.advance(EngineHarness.LEASE.plusSeconds(1));
        var invoked = new java.util.concurrent.atomic.AtomicBoolean();

        try (RunWorker worker = worker(context -> {
            invoked.set(true);
            return RunOutcome.completed(null);
        })) {
            assertThat(worker.pollOnce()).isEqualTo(RunWorker.Poll.EXECUTED);
        }

        assertThat(invoked).isFalse();
        assertThat(run(id).lifecycle()).isEqualTo(RunLifecycle.PAUSED);
        assertThat(last(id).payload().path("requestedBy").stringValue()).isEqualTo(OPERATOR);
        assertThat(harness.engine.verify(harness.workspace, id).intact()).isTrue();
    }

    // #194 medium 1: the worker's pause hint was refreshed only by the handler's event and checkpoint writes. A handler
    // that writes nothing between its own boundaries never saw a request; every heartbeat now refreshes the hint.
    @Test
    void aHandlerThatWritesNothingObservesARequestWithinOneHeartbeat() {
        RunRecord queued = queued();
        var observed = new java.util.concurrent.atomic.AtomicBoolean();
        // A 600 ms lease beats every 200 ms; the in-memory store's clock stands still, so the lease never lapses.
        try (RunWorker worker = new RunWorker(harness.engine, context -> {
            harness.engine.requestPause(harness.workspace, context.pins().id(), OPERATOR, null);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!context.pauseRequested() && System.nanoTime() < deadline) Thread.sleep(20);
            observed.set(context.pauseRequested());
            if (context.pauseIfRequested()) return RunOutcome.paused(DurableRunEngine.OPERATOR_PAUSE);
            return RunOutcome.completed(null);
        }, "worker-1", harness.scope(), Duration.ofMillis(600))) {
            assertThat(worker.pollOnce()).isEqualTo(RunWorker.Poll.EXECUTED);
        }
        assertThat(observed).isTrue();
        assertThat(run(queued.id()).lifecycle()).isEqualTo(RunLifecycle.PAUSED);
    }

    // PR #196 review, low 8: a write whose read preceded a newer observation (here a heartbeat that saw a request made
    // after the write committed) must not hide the request when the worker applies the write's older result.
    @Test
    void anOlderWriteNeverHidesARequestAHeartbeatObservedSince() {
        RunRecord queued = queued();
        var seenAfterWrite = new java.util.concurrent.atomic.AtomicBoolean();
        try (RunWorker worker = new RunWorker(harness.engine, context -> {
            Thread handler = Thread.currentThread();
            harness.store.afterCommit = () -> {
                if (Thread.currentThread() != handler) return;
                harness.store.afterCommit = null;
                // The write committed without a request; one is made now, and a heartbeat observes it before the
                // worker applies the write's result.
                harness.engine.requestPause(harness.workspace, context.pins().id(), OPERATOR, null);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (!context.pauseRequested() && System.nanoTime() < deadline) {
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            };
            context.emit(List.of(DurableRunEngineTest.model(1)));
            seenAfterWrite.set(context.pauseRequested());
            if (context.pauseIfRequested()) return RunOutcome.paused(DurableRunEngine.OPERATOR_PAUSE);
            return RunOutcome.completed(null);
        }, "worker-1", harness.scope(), Duration.ofMillis(600))) {
            assertThat(worker.pollOnce()).isEqualTo(RunWorker.Poll.EXECUTED);
        } finally {
            harness.store.afterCommit = null;
        }
        assertThat(seenAfterWrite).isTrue();
        assertThat(run(queued.id()).lifecycle()).isEqualTo(RunLifecycle.PAUSED);
    }

    // #194 low 3: a heartbeat that runs while the handler's own boundary pauses the run finds the lease released; it
    // must treat that as the late beat it is, not as a lost lease that interrupts the handler.
    @Test
    void aBeatRacingThePauseHandshakeNeverInterruptsTheHandler() {
        RunRecord queued = queued();
        var interrupted = new java.util.concurrent.atomic.AtomicBoolean(true);
        var beatsAfterPause = new java.util.concurrent.atomic.AtomicInteger();
        try (RunWorker worker = new RunWorker(harness.engine, context -> {
            harness.engine.requestPause(harness.workspace, context.pins().id(), OPERATOR, null);
            context.emit(List.of(DurableRunEngineTest.model(1)));
            Thread handler = Thread.currentThread();
            // The next commit on this thread is the pause itself: before the boundary returns, two beats run and
            // find the lease already released.
            harness.store.afterCommit = () -> {
                if (Thread.currentThread() != handler) return;
                harness.store.afterCommit = null;
                int before = harness.store.transactionAttempts.get();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (harness.store.transactionAttempts.get() < before + 2 && System.nanoTime() < deadline) {
                    java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
                }
                beatsAfterPause.set(harness.store.transactionAttempts.get() - before);
            };
            boolean paused = context.pauseIfRequested();
            interrupted.set(Thread.interrupted());
            return paused ? RunOutcome.paused(DurableRunEngine.OPERATOR_PAUSE) : RunOutcome.completed(null);
        }, "worker-1", harness.scope(), Duration.ofMillis(600))) {
            assertThat(worker.pollOnce()).isEqualTo(RunWorker.Poll.EXECUTED);
        } finally {
            harness.store.afterCommit = null;
        }
        assertThat(beatsAfterPause.get()).isGreaterThanOrEqualTo(2);
        assertThat(interrupted).isFalse();
        assertThat(run(queued.id()).lifecycle()).isEqualTo(RunLifecycle.PAUSED);
    }

    // PR #199 review, low 7: boundaries can be reached from more than one thread (the Spring AI guard may run on a
    // Reactor thread), so they are serialized: a second boundary waits while the first is still checking.
    @Test
    void concurrentBoundariesAreSerialized() throws Exception {
        RunRecord queued = queued();
        var secondFinishedDuringFirst = new java.util.concurrent.atomic.AtomicBoolean(true);
        var second = new java.util.concurrent.atomic.AtomicReference<Thread>();
        try (RunWorker worker = worker(context -> {
            harness.engine.requestPause(harness.workspace, context.pins().id(), OPERATOR, null);
            context.emit(List.of(DurableRunEngineTest.model(1)));
            // Withdrawn again, so each boundary checks the database and continues.
            harness.engine.resumePaused(harness.workspace, context.pins().id(), OPERATOR);
            Thread handler = Thread.currentThread();
            // The next commit on this thread is the first boundary's own check: while it is still in its boundary,
            // another thread reaches one too.
            harness.store.afterCommit = () -> {
                if (Thread.currentThread() != handler) return;
                harness.store.afterCommit = null;
                CountDownLatch done = new CountDownLatch(1);
                second.set(Thread.ofPlatform().start(() -> {
                    try {
                        context.pauseIfRequested();
                    } finally {
                        done.countDown();
                    }
                }));
                try {
                    secondFinishedDuringFirst.set(done.await(500, TimeUnit.MILLISECONDS));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            };
            boolean paused = context.pauseIfRequested();
            second.get().join(TimeUnit.SECONDS.toMillis(10));
            return paused ? RunOutcome.paused(DurableRunEngine.OPERATOR_PAUSE) : RunOutcome.completed(null);
        })) {
            assertThat(worker.pollOnce()).isEqualTo(RunWorker.Poll.EXECUTED);
        } finally {
            harness.store.afterCommit = null;
        }
        assertThat(secondFinishedDuringFirst).isFalse();
        assertThat(run(queued.id()).lifecycle()).isEqualTo(RunLifecycle.SUCCEEDED);
    }

    // #194 low 6: a worker's boundary reads no release manifest under the run lock; the claim already told it whether
    // the run's executor may be paused mid-exchange.
    @Test
    void aWorkersBoundaryReadsNoReleaseManifest() {
        RunRecord queued = queued();
        var reads = new java.util.concurrent.atomic.AtomicInteger(-1);
        try (RunWorker worker = worker(context -> {
            harness.engine.requestPause(harness.workspace, context.pins().id(), OPERATOR, null);
            context.emit(List.of(DurableRunEngineTest.model(1)));
            int before = harness.store.manifestReads.get();
            boolean paused = context.pauseIfRequested();
            reads.set(harness.store.manifestReads.get() - before);
            return paused ? RunOutcome.paused(DurableRunEngine.OPERATOR_PAUSE) : RunOutcome.completed(null);
        })) {
            assertThat(worker.pollOnce()).isEqualTo(RunWorker.Poll.EXECUTED);
        }
        assertThat(reads).hasValue(0);
        assertThat(run(queued.id()).lifecycle()).isEqualTo(RunLifecycle.PAUSED);
    }

    // PR #196 review, low 7: a heartbeat reads the pause request only for an executor that may be paused mid-exchange;
    // for any other a request is never honored, so the read would be pure cost on every beat.
    @Test
    void heartbeatsReadThePauseRequestOnlyForAnExecutorThatMayBePausedMidExchange() {
        assertThat(pauseReadsDuringThreeBeats(new EngineHarness("spring-ai", EngineHarness.EXECUTORS))).isZero();
        assertThat(pauseReadsDuringThreeBeats(harness)).isGreaterThanOrEqualTo(3);
    }

    /** The pause-request reads while a handler that writes nothing waits for three heartbeats. */
    private static int pauseReadsDuringThreeBeats(EngineHarness on) {
        on.engine.enqueue(on.workspace, on.run(), "system:test");
        var reads = new java.util.concurrent.atomic.AtomicInteger(-1);
        var beats = new java.util.concurrent.atomic.AtomicInteger();
        try (RunWorker worker = new RunWorker(on.engine, context -> {
            int before = on.store.pauseReads.get();
            beats.set(0);
            on.store.afterCommit = beats::incrementAndGet;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (beats.get() < 3 && System.nanoTime() < deadline) Thread.sleep(20);
            on.store.afterCommit = null;
            reads.set(on.store.pauseReads.get() - before);
            return RunOutcome.completed(null);
        }, "worker-1", on.scope(), Duration.ofMillis(600))) {
            assertThat(worker.pollOnce()).isEqualTo(RunWorker.Poll.EXECUTED);
        } finally {
            on.store.afterCommit = null;
        }
        assertThat(beats.get()).isGreaterThanOrEqualTo(3);
        return reads.get();
    }

    // #194 medium 1: a request made while an effect runs is observed from the effect's own outcome write, so the worker
    // pauses before the next effect starts instead of dispatching it.
    @Test
    void aRequestMadeDuringAnEffectPausesTheRunBeforeTheNextEffectStarts() {
        RunRecord queued = queued();
        var secondInvoked = new java.util.concurrent.atomic.AtomicBoolean();
        try (RunWorker worker = worker(context -> {
            context.effect("first", Map.of(), (key, request) -> {
                harness.engine.requestPause(harness.workspace, context.pins().id(), OPERATOR, null);
                return null;
            });
            context.effect("second", Map.of(), (key, request) -> {
                secondInvoked.set(true);
                return null;
            });
            return RunOutcome.completed(null);
        })) {
            assertThat(worker.pollOnce()).isEqualTo(RunWorker.Poll.EXECUTED);
        }
        assertThat(secondInvoked).isFalse();
        assertThat(run(queued.id()).lifecycle()).isEqualTo(RunLifecycle.PAUSED);
        assertThat(harness.engine.effects(harness.workspace, queued.id())).singleElement()
                .satisfies(effect -> assertThat(effect.state()).isEqualTo(RunEffect.State.COMMITTED));
    }

    // #194 medium 2: a boundary that cannot pause because an effect is in doubt must not make the worker forget the
    // request, which still stands; once the effect's outcome is recorded, a later boundary pauses the run.
    @Test
    void aRequestWaitingForAnInDoubtEffectStillStandsAtTheWorker() {
        RunRecord queued = queued();
        var standingWhileInDoubt = new java.util.concurrent.atomic.AtomicBoolean();
        var boundaryWhileInDoubt = new java.util.concurrent.atomic.AtomicBoolean(true);
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        try (RunWorker worker = worker(context -> {
            DurableRunEngine.EffectInvoker flaky = (key, request) -> {
                if (attempts.incrementAndGet() == 1) throw new java.io.IOException("timeout");
                return null;
            };
            try {
                context.effect("charge", Map.of(), flaky);
            } catch (DurableRunEngine.EffectInDoubtException inDoubt) {
                harness.engine.requestPause(harness.workspace, context.pins().id(), OPERATOR, null);
                context.emit(List.of(DurableRunEngineTest.model(1)));
                // The boundary cannot pause while the effect is in doubt, and the request stands.
                boundaryWhileInDoubt.set(context.pauseIfRequested());
                standingWhileInDoubt.set(context.pauseRequested());
                context.effect("charge", Map.of(), flaky);
            }
            if (context.pauseIfRequested()) return RunOutcome.paused(DurableRunEngine.OPERATOR_PAUSE);
            return RunOutcome.completed(null);
        })) {
            assertThat(worker.pollOnce()).isEqualTo(RunWorker.Poll.EXECUTED);
        }
        assertThat(boundaryWhileInDoubt).isFalse();
        assertThat(standingWhileInDoubt).isTrue();
        assertThat(attempts).hasValue(2);
        assertThat(run(queued.id()).lifecycle()).isEqualTo(RunLifecycle.PAUSED);
    }

    // #194 medium 2, at the engine: a boundary that changed nothing says whether the request still stands.
    @Test
    void theBoundaryReportsWhetherTheRequestStillStands() {
        var claimed = harness.started("worker-1");
        UUID id = claimed.run().id();
        DurableRunEngine.EffectInDoubtException inDoubt = null;
        try {
            harness.engine.executeEffect(claimed.lease(), "charge", Map.of(), (key, request) -> {
                throw new java.io.IOException("timeout");
            });
        } catch (DurableRunEngine.EffectInDoubtException expected) {
            inDoubt = expected;
        }
        assertThat(inDoubt).isNotNull();
        harness.engine.requestPause(harness.workspace, id, OPERATOR, null);

        var waiting = harness.engine.pauseAtBoundary(inDoubt.lease());
        assertThat(waiting.pausedNow()).isFalse();
        assertThat(waiting.requestStands()).isTrue();

        harness.engine.resumePaused(harness.workspace, id, OPERATOR);
        var withdrawn = harness.engine.pauseAtBoundary(inDoubt.lease());
        assertThat(withdrawn.pausedNow()).isFalse();
        assertThat(withdrawn.requestStands()).isFalse();
        assertThat(run(id).lifecycle()).isEqualTo(RunLifecycle.ACTIVE);
    }

    // A pause requested while an effect is in flight waits for the effect's outcome: the effect is recorded as
    // committed, and the run pauses at the handler's next boundary, after it.
    @Test
    void aPauseDuringAnInFlightEffectWaitsForItsOutcome() throws Exception {
        RunRecord queued = queued();
        CountDownLatch inFlight = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var reachedAfterCheckpoint = new java.util.concurrent.atomic.AtomicBoolean();
        var released = new java.util.concurrent.atomic.AtomicBoolean();
        try (RunWorker worker = worker(context -> {
            context.effect("charge", Map.of("amount", 5), (key, request) -> {
                inFlight.countDown();
                released.set(release.await(10, TimeUnit.SECONDS));
                return tools.jackson.databind.node.JsonNodeFactory.instance.objectNode().put("charged", true);
            });
            // The checkpoint after the effect is the next safe boundary: the worker pauses the run there.
            context.checkpoint(Map.of("step", "charged"));
            reachedAfterCheckpoint.set(true);
            return RunOutcome.completed(null);
        })) {
            var poll = java.util.concurrent.CompletableFuture.supplyAsync(worker::pollOnce);
            assertThat(inFlight.await(10, TimeUnit.SECONDS)).isTrue();
            var requested = harness.engine.requestPause(harness.workspace, queued.id(), OPERATOR, null);
            assertThat(requested.pending()).isTrue();
            release.countDown();
            assertThat(poll.get(10, TimeUnit.SECONDS)).isEqualTo(RunWorker.Poll.EXECUTED);
        }

        assertThat(released).isTrue();
        assertThat(reachedAfterCheckpoint).isFalse();
        assertThat(run(queued.id()).lifecycle()).isEqualTo(RunLifecycle.PAUSED);
        assertThat(harness.engine.effects(harness.workspace, queued.id())).singleElement()
                .satisfies(effect -> assertThat(effect.state()).isEqualTo(RunEffect.State.COMMITTED));
        List<RunEventType> types = harness.engine.history(harness.workspace, queued.id()).stream()
                .map(RunEventCodec.DecodedEvent::type).toList();
        assertThat(types.indexOf(RunEventType.OPERATION_COMPLETED)).isLessThan(types.indexOf(RunEventType.RUN_PAUSED));
        assertThat(types).doesNotContain(RunEventType.OPERATION_FAILED);
    }
}
