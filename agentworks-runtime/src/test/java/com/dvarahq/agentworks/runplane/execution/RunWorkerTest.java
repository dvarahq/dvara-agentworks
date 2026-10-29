package com.dvarahq.agentworks.runplane.execution;

import com.dvarahq.agentworks.domain.run.RunBlockReason;
import com.dvarahq.agentworks.domain.run.RunEvent;
import com.dvarahq.agentworks.domain.run.RunLifecycle;
import com.dvarahq.agentworks.domain.run.RunLimits;
import com.dvarahq.agentworks.domain.run.RunRecord;
import com.dvarahq.agentworks.domain.agent.compiled.CompiledAgentDefinition;
import com.dvarahq.agentworks.runplane.compiled.CompiledRelease;
import com.dvarahq.agentworks.runplane.compiled.CompiledReleaseCache;
import com.dvarahq.agentworks.runplane.compiled.CompiledReleaseCachingRunHandler;
import com.dvarahq.agentworks.runplane.compiled.CompiledReleaseKey;
import com.dvarahq.agentworks.runplane.compiled.DeploymentOverlayKey;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class RunWorkerTest {
    private final EngineHarness harness = new EngineHarness();

    private RunWorker worker(RunHandler handler) {
        return new RunWorker(harness.engine, handler, "worker-1", harness.scope(), Duration.ofSeconds(30));
    }

    private RunRecord queued() {
        UUID id = harness.run();
        return harness.engine.enqueue(harness.workspace, id, "system:test");
    }

    @Test
    void aStatelessWorkerCompletesBlocksAndResumesRunsFromTheirCheckpoints() {
        RunRecord run = queued();
        try (RunWorker worker = worker(context -> {
            if (context.checkpointState().isEmpty()) {
                context.checkpoint(Map.of("step", 1, "input", context.input()));
                return RunOutcome.blocked(RunBlockReason.HUMAN_INTERACTION, Map.of("question", "approve?"));
            }
            return RunOutcome.completed(Map.of("resumedFrom", context.checkpointState().get().path("step").intValue()));
        })) {
            assertThat(worker.pollOnce()).isEqualTo(RunWorker.Poll.EXECUTED);
            RunRecord blocked = harness.engine.run(harness.workspace, run.id());
            assertThat(blocked.lifecycle()).isEqualTo(RunLifecycle.BLOCKED);
            assertThat(blocked.control().leaseOwner()).isNull();
            assertThat(worker.pollOnce()).isEqualTo(RunWorker.Poll.IDLE);

            harness.engine.resume(harness.workspace, run.id(), "system:interaction");
            assertThat(worker.pollOnce()).isEqualTo(RunWorker.Poll.EXECUTED);
        }
        RunRecord done = harness.engine.run(harness.workspace, run.id());
        assertThat(done.lifecycle()).isEqualTo(RunLifecycle.SUCCEEDED);
        var last = harness.engine.history(harness.workspace, run.id()).getLast();
        assertThat(last.payload().path("output").path("resumedFrom").intValue()).isEqualTo(1);
        assertThat(harness.engine.verify(harness.workspace, run.id()).intact()).isTrue();
    }

    @Test
    void aSuspendedCohortPausesTheRunAtTheNextSafeBoundary() {
        RunRecord run = queued();
        try (RunWorker worker = worker(context -> {
            harness.store.cohort(harness.cohort, "SUSPENDED");
            context.checkpoint(Map.of("step", 1));
            if (context.suspended()) return RunOutcome.paused("COHORT_SUSPENDED");
            return RunOutcome.completed(null);
        })) {
            assertThat(worker.pollOnce()).isEqualTo(RunWorker.Poll.EXECUTED);
        }
        assertThat(harness.engine.run(harness.workspace, run.id()).lifecycle()).isEqualTo(RunLifecycle.PAUSED);
        harness.store.cohort(harness.cohort, "ROUTABLE");
    }

    @Test
    void handlerFailuresAndInDoubtEffectsTerminateExplicitly() {
        RunRecord failing = queued();
        try (RunWorker worker = worker(context -> {
            throw new IllegalStateException("model unavailable");
        })) {
            assertThat(worker.pollOnce()).isEqualTo(RunWorker.Poll.EXECUTED);
        }
        assertThat(harness.engine.run(harness.workspace, failing.id()).summary().terminalCode()).isEqualTo("HANDLER_FAILED");

        RunRecord inDoubt = queued();
        try (RunWorker worker = worker(context -> {
            context.effect("charge", Map.of("amount", 5), (key, request) -> {
                throw new java.io.IOException("connection reset");
            });
            return RunOutcome.completed(null);
        })) {
            assertThat(worker.pollOnce()).isEqualTo(RunWorker.Poll.EXECUTED);
        }
        RunRecord failed = harness.engine.run(harness.workspace, inDoubt.id());
        assertThat(failed.summary().terminalCode()).isEqualTo("EFFECT_IN_DOUBT");
        assertThat(harness.engine.effects(harness.workspace, inDoubt.id())).singleElement()
                .satisfies(effect -> assertThat(effect.state().name()).isEqualTo("PENDING"));
    }

    @Test
    void compiledCacheSaturationIsRecoverableBeforeEffectsAndTheWorkerRetriesAfterCapacityReturns() {
        RunRecord run = queued();
        String digest = "sha256:" + "d".repeat(64);
        CompiledReleaseCache cache = new CompiledReleaseCache(1);
        var occupied = cache.acquire(new CompiledReleaseCache.Key(
                new CompiledReleaseKey(UUID.randomUUID(), UUID.randomUUID(), 1, digest),
                new DeploymentOverlayKey(UUID.randomUUID(), UUID.randomUUID(), digest, digest)),
                () -> new CompiledRelease(new CompiledAgentDefinition("spring-ai", List.of()), List.of()));
        RunHandler route = new CompiledReleaseCachingRunHandler(context -> RunOutcome.completed(Map.of("ok", true)), cache);
        try (RunWorker worker = worker(route)) {
            assertThat(worker.pollOnce()).isEqualTo(RunWorker.Poll.RETRY_LATER);
            assertThat(harness.engine.run(harness.workspace, run.id()).lifecycle()).isEqualTo(RunLifecycle.ACTIVE);
            assertThat(harness.engine.history(harness.workspace, run.id())).noneMatch(event ->
                    event.type().equals("RUN_FAILED") || event.type().equals("EFFECT_STARTED"));
            occupied.close();
            harness.store.advance(Duration.ofSeconds(31));
            assertThat(worker.pollOnce()).isEqualTo(RunWorker.Poll.EXECUTED);
        }
        assertThat(harness.engine.run(harness.workspace, run.id()).lifecycle()).isEqualTo(RunLifecycle.SUCCEEDED);
    }

    @Test
    void aHandlerThatCatchesAnInDoubtEffectKeepsAValidLease() {
        RunRecord run = queued();
        try (RunWorker worker = worker(context -> {
            try {
                context.effect("charge", Map.of("amount", 5), (key, request) -> {
                    throw new java.io.IOException("connection reset");
                });
            } catch (DurableRunEngine.EffectInDoubtException inDoubt) {
                // The handler records the uncertainty and carries on to a safe boundary.
                context.emit(List.of(DurableRunEngine.EventDraft.of(
                        com.dvarahq.agentworks.domain.run.RunEventType.TOOL_FAILED, Map.of("stepId", "charge"))));
                context.checkpoint(Map.of("charge", "in-doubt"));
            }
            return RunOutcome.failed("CHARGE_IN_DOUBT", "the charge outcome is unknown");
        })) {
            assertThat(worker.pollOnce()).isEqualTo(RunWorker.Poll.EXECUTED);
        }
        RunRecord failed = harness.engine.run(harness.workspace, run.id());
        assertThat(failed.lifecycle()).isEqualTo(RunLifecycle.FAILED);
        assertThat(failed.summary().terminalCode()).isEqualTo("CHARGE_IN_DOUBT");
        assertThat(failed.summary().checkpointSequence()).isOne();
    }

    @Test
    void claimFailuresNeverEscapeThePollLoop() {
        RunRecord run = queued();
        for (RuntimeException failure : List.<RuntimeException>of(
                new com.dvarahq.agentworks.domain.run.RunAuthorityException("COHORT_NOT_ADMITTING", "SUSPENDED", null),
                new org.springframework.dao.DataAccessResourceFailureException("database unavailable"),
                new org.springframework.dao.CannotAcquireLockException("lock timeout"))) {
            var failing = new DurableRunEngine(new QuarantineDelegating(harness.store) {
                @Override
                public <T> T inTransaction(com.dvarahq.agentworks.domain.tenant.WorkspaceId workspace,
                        java.util.function.Function<com.dvarahq.agentworks.domain.run.RunTransaction, T> work) {
                    return harness.store.inTransaction(workspace, work);
                }

                @Override
                public <T> java.util.Optional<T> claimNext(com.dvarahq.agentworks.domain.run.ClaimScope scope,
                        java.util.function.BiFunction<RunRecord, com.dvarahq.agentworks.domain.run.RunTransaction, T> work) {
                    throw failure;
                }
            }, RunEventCodec.standard(), new ReleaseManifestReader(),
                    new com.dvarahq.agentworks.storage.lifecycle.LifecycleRetry(2));
            try (RunWorker worker = new RunWorker(failing, context -> RunOutcome.completed(null), "worker-1",
                    harness.scope(), Duration.ofSeconds(30))) {
                assertThat(worker.pollOnce()).as(failure.toString()).isEqualTo(RunWorker.Poll.RETRY_LATER);
            }
        }
        assertThat(harness.engine.run(harness.workspace, run.id()).lifecycle()).isEqualTo(RunLifecycle.QUEUED);
    }

    // #175 medium 1: a configuration error fails fast instead of making every poll fail silently.
    @Test
    void anInvalidLeaseDurationFailsFastAtConstruction() {
        for (Duration invalid : List.of(Duration.ZERO, Duration.ofSeconds(-1), DurableRunEngine.MAX_LEASE.plusSeconds(1))) {
            assertThatThrownBy(() -> new RunWorker(harness.engine, context -> RunOutcome.completed(null), "worker-1",
                    harness.scope(), invalid)).as(invalid.toString()).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("lease");
        }
        assertThatThrownBy(() -> new RunWorker(harness.engine, context -> RunOutcome.completed(null), "worker 1",
                harness.scope(), Duration.ofSeconds(30))).isInstanceOf(IllegalArgumentException.class);
    }

    /** An engine whose claims of {@code runId} fail inside the claim transaction, as a database guard would. */
    private DurableRunEngine failingClaimsOf(UUID runId, java.util.concurrent.atomic.AtomicInteger attempts,
            java.util.function.Supplier<RuntimeException> failure) {
        return new DurableRunEngine(new QuarantineDelegating(harness.store) {
            @Override
            public <T> T inTransaction(com.dvarahq.agentworks.domain.tenant.WorkspaceId workspace,
                    java.util.function.Function<com.dvarahq.agentworks.domain.run.RunTransaction, T> work) {
                return harness.store.inTransaction(workspace, work);
            }

            @Override
            public <T> java.util.Optional<T> claimNext(com.dvarahq.agentworks.domain.run.ClaimScope scope,
                    java.util.function.BiFunction<RunRecord, com.dvarahq.agentworks.domain.run.RunTransaction, T> work) {
                return harness.store.claimNext(scope, (run, tx) -> {
                    if (!run.id().equals(runId)) return work.apply(run, tx);
                    var refusing = (com.dvarahq.agentworks.domain.run.RunTransaction) java.lang.reflect.Proxy.newProxyInstance(
                            getClass().getClassLoader(),
                            new Class<?>[]{com.dvarahq.agentworks.domain.run.RunTransaction.class}, (proxy, method, args) -> {
                                if (method.getName().equals("insertWorkItem")) {
                                    attempts.incrementAndGet();
                                    throw failure.get();
                                }
                                try {
                                    return method.invoke(tx, args);
                                } catch (java.lang.reflect.InvocationTargetException thrown) {
                                    throw thrown.getCause();
                                }
                            });
                    return work.apply(run, refusing);
                });
            }
        }, RunEventCodec.standard(), new ReleaseManifestReader(),
                new com.dvarahq.agentworks.storage.lifecycle.LifecycleRetry(1));
    }

    // #175 review M1: failures that are not the run's own (a lost database, a cohort race) never quarantine it.
    @Test
    void transientClaimFailuresNeverQuarantineTheHeadRun() {
        RunRecord head = queued();
        java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
        for (java.util.function.Supplier<RuntimeException> failure : List.<java.util.function.Supplier<RuntimeException>>of(
                () -> new org.springframework.dao.DataAccessResourceFailureException("connection reset"),
                () -> new com.dvarahq.agentworks.domain.run.RunAuthorityException("COHORT_NOT_ADMITTING", "DRAINING", null),
                () -> new com.dvarahq.agentworks.domain.run.RunAuthorityException("COHORT_STATE_CHANGED", null, null))) {
            try (RunWorker worker = new RunWorker(failingClaimsOf(head.id(), attempts, failure),
                    context -> RunOutcome.completed(null), "worker-1", harness.scope(), Duration.ofSeconds(30))) {
                for (int poll = 0; poll < RunWorker.MAX_CONSECUTIVE_CLAIM_FAILURES * 2; poll++) {
                    assertThat(worker.pollOnce()).isEqualTo(RunWorker.Poll.RETRY_LATER);
                }
                assertThat(harness.quarantined()).isEmpty();
            }
        }
        assertThat(attempts.get()).isEqualTo(RunWorker.MAX_CONSECUTIVE_CLAIM_FAILURES * 6);
        assertThat(harness.engine.run(harness.workspace, head.id()).lifecycle()).isEqualTo(RunLifecycle.QUEUED);
    }

    // #175 review L4: a run cancelled mid-handler stops the handler through the heartbeat and leaks no interrupt.
    @Test
    void aRunCancelledWhileItsHandlerRunsInterruptsTheHandlerAndIsAbandoned() {
        RunRecord run = queued();
        java.util.concurrent.atomic.AtomicBoolean interrupted = new java.util.concurrent.atomic.AtomicBoolean();
        try (RunWorker worker = new RunWorker(harness.engine, context -> {
            harness.engine.cancel(harness.workspace, run.id(), "operator:ops", "not needed");
            try {
                Thread.sleep(Duration.ofSeconds(30));
            } catch (InterruptedException stopped) {
                interrupted.set(true);
                throw stopped;
            }
            return RunOutcome.completed(null);
        }, "worker-1", harness.scope(), Duration.ofMillis(600))) {
            java.util.concurrent.atomic.AtomicBoolean leaked = new java.util.concurrent.atomic.AtomicBoolean(true);
            RunWorker.Poll poll = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
                RunWorker.Poll result = worker.pollOnce();
                leaked.set(Thread.currentThread().isInterrupted());
                return result;
            });
            assertThat(poll).isEqualTo(RunWorker.Poll.LOST);
            assertThat(leaked).isFalse();
        }
        assertThat(interrupted).isTrue();
        assertThat(harness.engine.run(harness.workspace, run.id()).lifecycle()).isEqualTo(RunLifecycle.CANCELLED);
    }

    // #175 review L3: the deferral is bounded; an effect that outlives the grace is failed and left explicitly in doubt.
    @Test
    void anEffectThatOutlivesTheGraceIsFailedAndLeftExplicitlyInDoubt() {
        UUID id = harness.run(new RunLimits(null, null, null, 100L, null), Map.of("task", "t"));
        harness.engine.enqueue(harness.workspace, id, "system:test");
        try (RunWorker worker = new RunWorker(harness.engine, context -> {
            context.effect("charge", Map.of("amount", 5), (key, request) -> {
                // 300 ms of runtime against a 100 ms limit and a 100 ms grace.
                harness.store.advance(Duration.ofMillis(300));
                Thread.sleep(Duration.ofSeconds(30));
                return null;
            });
            return RunOutcome.completed(null);
        }, "worker-1", harness.scope(), Duration.ofMillis(600), Duration.ofMinutes(5), Duration.ofMillis(100))) {
            RunWorker.Poll poll = assertTimeoutPreemptively(Duration.ofSeconds(10), worker::pollOnce);
            assertThat(poll).isEqualTo(RunWorker.Poll.EXECUTED);
        }
        RunRecord failed = harness.engine.run(harness.workspace, id);
        assertThat(failed.summary().terminalCode()).isEqualTo("LIMIT_EXCEEDED");
        assertThat(harness.engine.effects(harness.workspace, id)).singleElement()
                .satisfies(effect -> assertThat(effect.state().name()).isEqualTo("PENDING"));
        assertThat(harness.engine.history(harness.workspace, id)).anySatisfy(event -> assertThat(
                event.payload().path("inDoubtEffects").toString()).contains("charge"));
    }

    // #175 medium 1: a run whose claim is always rejected is quarantined for a while instead of blocking its scope.
    @Test
    void aRunWhoseClaimAlwaysFailsIsQuarantinedAndTheScopeProceeds() throws Exception {
        RunRecord poisoned = queued();
        RunRecord next = queued();
        java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
        // The database refuses this run's claim every time, for example a guard trigger on its ledger item.
        var rejecting = failingClaimsOf(poisoned.id(), attempts,
                () -> new com.dvarahq.agentworks.domain.run.RunAuthorityException("WORK_ITEM_REJECTED", null, null));

        try (RunWorker worker = new RunWorker(rejecting, context -> RunOutcome.completed(null), "worker-1",
                harness.scope(), Duration.ofSeconds(30), Duration.ofSeconds(1), Duration.ZERO)) {
            for (int attempt = 1; attempt < RunWorker.MAX_CONSECUTIVE_CLAIM_FAILURES; attempt++) {
                assertThat(worker.pollOnce()).isEqualTo(RunWorker.Poll.RETRY_LATER);
            }
            assertThat(worker.pollOnce()).isEqualTo(RunWorker.Poll.REFUSED);
            assertThat(harness.quarantined()).containsExactly(poisoned.id());
            assertThat(worker.pollOnce()).isEqualTo(RunWorker.Poll.EXECUTED);
            assertThat(worker.pollOnce()).isEqualTo(RunWorker.Poll.IDLE);

            // The quarantine is bounded, in database time: the run is tried again once it lapses.
            harness.store.advance(Duration.ofMillis(1100));
            assertThat(harness.quarantined()).isEmpty();
            int before = attempts.get();
            assertThat(worker.pollOnce()).isEqualTo(RunWorker.Poll.RETRY_LATER);
            assertThat(attempts.get()).isGreaterThan(before);
        }
        assertThat(harness.engine.run(harness.workspace, poisoned.id()).lifecycle()).isEqualTo(RunLifecycle.QUEUED);
        assertThat(harness.engine.run(harness.workspace, next.id()).lifecycle()).isEqualTo(RunLifecycle.SUCCEEDED);
    }

    // #175 medium 2: a heartbeat that fails the run for its runtime limit ends the execution and frees the worker.
    @Test
    void aHeartbeatThatFailsTheRunInterruptsTheHandlerAndStopsBeating() {
        UUID id = harness.run(new RunLimits(null, null, null, 100L, null), Map.of("task", "t"));
        harness.engine.enqueue(harness.workspace, id, "system:test");
        java.util.concurrent.atomic.AtomicBoolean interrupted = new java.util.concurrent.atomic.AtomicBoolean();
        try (RunWorker worker = new RunWorker(harness.engine, context -> {
            harness.store.advance(Duration.ofMillis(300));
            try {
                Thread.sleep(Duration.ofSeconds(30));
            } catch (InterruptedException stopped) {
                interrupted.set(true);
                throw stopped;
            }
            return RunOutcome.completed(null);
        }, "worker-1", harness.scope(), Duration.ofMillis(600))) {
            java.util.concurrent.atomic.AtomicBoolean leaked = new java.util.concurrent.atomic.AtomicBoolean(true);
            RunWorker.Poll poll = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
                RunWorker.Poll result = worker.pollOnce();
                leaked.set(Thread.currentThread().isInterrupted());
                return result;
            });
            assertThat(poll).isEqualTo(RunWorker.Poll.EXECUTED);
            assertThat(leaked).as("the interrupt does not leak into the next poll").isFalse();
        }
        assertThat(interrupted).isTrue();
        RunRecord failed = harness.engine.run(harness.workspace, id);
        assertThat(failed.lifecycle()).isEqualTo(RunLifecycle.FAILED);
        assertThat(failed.summary().terminalCode()).isEqualTo("LIMIT_EXCEEDED");
        assertThat(harness.engine.verify(harness.workspace, id).intact()).isTrue();
    }

    // #175 medium 3: the heartbeat limit check never fails a run under an in-flight effect and orphans it as PENDING.
    @Test
    void aHeartbeatDefersTheRuntimeLimitUntilAnInFlightEffectIsRecorded() {
        UUID id = harness.run(new RunLimits(null, null, null, 100L, null), Map.of("task", "t"));
        harness.engine.enqueue(harness.workspace, id, "system:test");
        java.util.concurrent.atomic.AtomicReference<String> handlerSaw = new java.util.concurrent.atomic.AtomicReference<>();
        try (RunWorker worker = new RunWorker(harness.engine, context -> {
            try {
                context.effect("charge", Map.of("amount", 5), (key, request) -> {
                    harness.store.advance(Duration.ofMillis(300));
                    // Several heartbeats run while the effect is in flight and the runtime limit is exceeded.
                    Thread.sleep(800);
                    return harness.engine.codec().mapper().<tools.jackson.databind.JsonNode>valueToTree("charged");
                });
            } catch (RunEngineException limit) {
                // The outcome was recorded, and the run, past its limit, failed in the same transaction. A heartbeat
                // that runs before the handler returns must not turn that ending into a lost lease.
                handlerSaw.set(limit.code());
                Thread.sleep(500);
                throw limit;
            }
            return RunOutcome.completed(null);
        }, "worker-1", harness.scope(), Duration.ofMillis(600))) {
            RunWorker.Poll poll = assertTimeoutPreemptively(Duration.ofSeconds(10), worker::pollOnce);
            assertThat(poll).isEqualTo(RunWorker.Poll.EXECUTED);
        }
        assertThat(handlerSaw).hasValue("LIMIT_EXCEEDED");
        assertThat(harness.engine.effects(harness.workspace, id)).singleElement()
                .satisfies(effect -> assertThat(effect.state().name()).isEqualTo("COMMITTED"));
        RunRecord failed = harness.engine.run(harness.workspace, id);
        assertThat(failed.lifecycle()).isEqualTo(RunLifecycle.FAILED);
        assertThat(failed.summary().terminalCode()).isEqualTo("LIMIT_EXCEEDED");
    }

    // #175 review 2, item 1: the grace never lets a run finish after its limit; completing then fails the run.
    @Test
    void aRunPastItsLimitCannotCompleteBeforeTheNextHeartbeat() {
        UUID id = harness.run(new RunLimits(null, null, null, 100L, null), Map.of("task", "t"));
        harness.engine.enqueue(harness.workspace, id, "system:test");
        try (RunWorker worker = worker(context -> {
            harness.store.advance(Duration.ofMillis(300));
            return RunOutcome.completed("done");
        })) {
            assertThat(worker.pollOnce()).isEqualTo(RunWorker.Poll.EXECUTED);
        }
        RunRecord failed = harness.engine.run(harness.workspace, id);
        assertThat(failed.lifecycle()).isEqualTo(RunLifecycle.FAILED);
        assertThat(failed.summary().terminalCode()).isEqualTo("LIMIT_EXCEEDED");
    }

    // #175 review 2, item 2: a permanent database error raised by one run's own claim quarantines that run.
    @Test
    void aPermanentDatabaseErrorInOneRunsClaimQuarantinesIt() {
        RunRecord poisoned = queued();
        RunRecord next = queued();
        java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
        var violating = failingClaimsOf(poisoned.id(), attempts,
                () -> new org.springframework.dao.DataIntegrityViolationException("check constraint violated"));
        try (RunWorker worker = new RunWorker(violating, context -> RunOutcome.completed(null), "worker-1",
                harness.scope(), Duration.ofSeconds(30))) {
            for (int attempt = 1; attempt < RunWorker.MAX_CONSECUTIVE_CLAIM_FAILURES; attempt++) {
                assertThat(worker.pollOnce()).isEqualTo(RunWorker.Poll.RETRY_LATER);
            }
            assertThat(worker.pollOnce()).isEqualTo(RunWorker.Poll.REFUSED);
            assertThat(harness.quarantined()).containsExactly(poisoned.id());
            assertThat(worker.pollOnce()).isEqualTo(RunWorker.Poll.EXECUTED);
        }
        assertThat(harness.engine.run(harness.workspace, next.id()).lifecycle()).isEqualTo(RunLifecycle.SUCCEEDED);
        assertThat(RunWorker.transientDatabaseFailure(new org.springframework.dao.CannotAcquireLockException("x"))).isTrue();
        assertThat(RunWorker.transientDatabaseFailure(
                new org.springframework.dao.RecoverableDataAccessException("x"))).isTrue();
        assertThat(RunWorker.transientDatabaseFailure(
                new org.springframework.jdbc.CannotGetJdbcConnectionException("x"))).isTrue();
        assertThat(RunWorker.transientDatabaseFailure(
                new org.springframework.dao.DataIntegrityViolationException("x"))).isFalse();
    }

    // #175 review 2, item 4: two workers under SKIP LOCKED interleave so that each claims another run while the
    // other holds the poison run; those successes must not reset the poison run's count.
    @Test
    void twoInterleavedWorkersQuarantineAPoisonRunDespiteOtherSuccesses() {
        RunRecord poison = queued();
        for (int index = 0; index < 20; index++) queued();
        java.util.concurrent.atomic.AtomicBoolean lockedByOther = new java.util.concurrent.atomic.AtomicBoolean();
        DurableRunEngine engine = skipLockedEngine(poison.id(), lockedByOther);
        try (RunWorker a = new RunWorker(engine, context -> RunOutcome.completed(null), "worker-a", harness.scope(),
                Duration.ofSeconds(30));
             RunWorker b = new RunWorker(engine, context -> RunOutcome.completed(null), "worker-b", harness.scope(),
                Duration.ofSeconds(30))) {
            List<RunWorker.Poll> aPolls = new ArrayList<>();
            List<RunWorker.Poll> bPolls = new ArrayList<>();
            for (int round = 0; round < RunWorker.MAX_CONSECUTIVE_CLAIM_FAILURES; round++) {
                // Each worker fails the poison run, then, while the other worker holds its row, SKIP LOCKED hands it
                // another run, which succeeds.
                lockedByOther.set(false);
                aPolls.add(a.pollOnce());
                lockedByOther.set(true);
                bPolls.add(b.pollOnce());
                lockedByOther.set(false);
                bPolls.add(b.pollOnce());
                lockedByOther.set(true);
                aPolls.add(a.pollOnce());
            }
            // The first worker to see three failures quarantines the run for every node (#181); the other then never
            // selects it again.
            assertThat(aPolls).contains(RunWorker.Poll.EXECUTED, RunWorker.Poll.REFUSED);
            assertThat(bPolls).contains(RunWorker.Poll.EXECUTED).doesNotContain(RunWorker.Poll.REFUSED);
            assertThat(harness.quarantined()).containsExactly(poison.id());
        }
        assertThat(harness.engine.run(harness.workspace, poison.id()).lifecycle()).isEqualTo(RunLifecycle.QUEUED);
    }

    /**
     * An engine whose claims skip {@code poison} while {@code lockedByOther} is set (SKIP LOCKED while another worker
     * holds its row), and otherwise select it first and fail inside its claim, as a failing guard would.
     */
    private DurableRunEngine skipLockedEngine(UUID poison, java.util.concurrent.atomic.AtomicBoolean lockedByOther) {
        return new DurableRunEngine(new QuarantineDelegating(harness.store) {
            @Override
            public <T> T inTransaction(com.dvarahq.agentworks.domain.tenant.WorkspaceId workspace,
                    java.util.function.Function<com.dvarahq.agentworks.domain.run.RunTransaction, T> work) {
                return harness.store.inTransaction(workspace, work);
            }

            @Override
            public <T> java.util.Optional<T> claimNext(com.dvarahq.agentworks.domain.run.ClaimScope scope,
                    java.util.function.BiFunction<RunRecord, com.dvarahq.agentworks.domain.run.RunTransaction, T> work) {
                if (lockedByOther.get()) {
                    java.util.Set<UUID> excluded = new java.util.HashSet<>(scope.excludedRunIds());
                    excluded.add(poison);
                    return harness.store.claimNext(scope.excluding(excluded), work);
                }
                return harness.store.claimNext(scope, (run, tx) -> {
                    if (!run.id().equals(poison)) return work.apply(run, tx);
                    var refusing = (com.dvarahq.agentworks.domain.run.RunTransaction) java.lang.reflect.Proxy.newProxyInstance(
                            getClass().getClassLoader(),
                            new Class<?>[]{com.dvarahq.agentworks.domain.run.RunTransaction.class}, (proxy, method, args) -> {
                                if (method.getName().equals("insertWorkItem")) {
                                    throw new com.dvarahq.agentworks.domain.run.RunAuthorityException(
                                            "WORK_ITEM_REJECTED", null, null);
                                }
                                try {
                                    return method.invoke(tx, args);
                                } catch (java.lang.reflect.InvocationTargetException thrown) {
                                    throw thrown.getCause();
                                }
                            });
                    return work.apply(run, refusing);
                });
            }
        }, RunEventCodec.standard(), new ReleaseManifestReader(),
                new com.dvarahq.agentworks.storage.lifecycle.LifecycleRetry(1));
    }


    // #175 review 2, item 5: a beat already in flight when the handler returns decides the outcome first.
    @Test
    void aBeatInFlightWhenTheHandlerReturnsWinsOverTheHandlersOutcome() {
        UUID id = harness.run(new RunLimits(null, null, null, 100L, null), Map.of("task", "t"));
        harness.engine.enqueue(harness.workspace, id, "system:test");
        java.util.concurrent.CountDownLatch beatCommitted = new java.util.concurrent.CountDownLatch(1);
        var slowBeats = new DurableRunEngine(new QuarantineDelegating(harness.store) {
            @Override
            public <T> T inTransaction(com.dvarahq.agentworks.domain.tenant.WorkspaceId workspace,
                    java.util.function.Function<com.dvarahq.agentworks.domain.run.RunTransaction, T> work) {
                T result;
                try {
                    result = harness.store.inTransaction(workspace, work);
                } finally {
                    if (Thread.currentThread().getName().startsWith("agentworks-heartbeat-")) {
                        // The beat committed LIMIT_EXCEEDED but has not yet returned to the worker.
                        beatCommitted.countDown();
                        try {
                            Thread.sleep(300);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }
                return result;
            }

            @Override
            public <T> java.util.Optional<T> claimNext(com.dvarahq.agentworks.domain.run.ClaimScope scope,
                    java.util.function.BiFunction<RunRecord, com.dvarahq.agentworks.domain.run.RunTransaction, T> work) {
                return harness.store.claimNext(scope, work);
            }
        }, RunEventCodec.standard(), new ReleaseManifestReader(),
                new com.dvarahq.agentworks.storage.lifecycle.LifecycleRetry(1));
        try (RunWorker worker = new RunWorker(slowBeats, context -> {
            harness.store.advance(Duration.ofMillis(300));
            beatCommitted.await(5, java.util.concurrent.TimeUnit.SECONDS);
            return RunOutcome.completed("done");
        }, "worker-1", harness.scope(), Duration.ofMillis(600))) {
            assertThat(assertTimeoutPreemptively(Duration.ofSeconds(10), worker::pollOnce))
                    .isEqualTo(RunWorker.Poll.EXECUTED);
        }
        assertThat(harness.engine.run(harness.workspace, id).summary().terminalCode()).isEqualTo("LIMIT_EXCEEDED");
    }

    // #179 medium 1: a handler that restores the heartbeat's interrupt and rethrows a wrapper (the LifecycleRetry and
    // Hikari idiom) never leaves the poll thread interrupted.
    @Test
    void theHeartbeatsInterruptNeverLeaksOutOfPollOnce() {
        UUID id = harness.run(new RunLimits(null, null, null, 100L, null), Map.of("task", "t"));
        harness.engine.enqueue(harness.workspace, id, "system:test");
        RunRecord next = queued();
        try (RunWorker worker = new RunWorker(harness.engine, context -> {
            if (context.pins().id().equals(next.id())) return RunOutcome.completed("next");
            harness.store.advance(Duration.ofMillis(300));
            try {
                Thread.sleep(Duration.ofSeconds(30));
            } catch (InterruptedException fromHeartbeat) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted during backoff", fromHeartbeat);
            }
            return RunOutcome.completed(null);
        }, "worker-1", harness.scope(), Duration.ofMillis(600))) {
            java.util.concurrent.atomic.AtomicBoolean leaked = new java.util.concurrent.atomic.AtomicBoolean(true);
            java.util.concurrent.atomic.AtomicReference<RunWorker.Poll> second = new java.util.concurrent.atomic.AtomicReference<>();
            RunWorker.Poll poll = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
                RunWorker.Poll result = worker.pollOnce();
                leaked.set(Thread.currentThread().isInterrupted());
                second.set(worker.pollOnce());
                return result;
            });
            assertThat(poll).isEqualTo(RunWorker.Poll.EXECUTED);
            assertThat(leaked).as("the heartbeat's interrupt stays with the handler").isFalse();
            assertThat(second).hasValue(RunWorker.Poll.EXECUTED);
        }
        assertThat(harness.engine.run(harness.workspace, id).summary().terminalCode()).isEqualTo("LIMIT_EXCEEDED");
        assertThat(harness.engine.run(harness.workspace, next.id()).lifecycle()).isEqualTo(RunLifecycle.SUCCEEDED);
    }

    // #179 medium 1: a genuine interrupt of the poll thread (a shutdown) is kept, and the claim is abandoned unwritten.
    @Test
    void anExternalInterruptOfThePollThreadIsKeptAndAbandonsTheClaim() throws Exception {
        RunRecord run = queued();
        java.util.concurrent.CountDownLatch running = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean handlerInterrupted = new java.util.concurrent.atomic.AtomicBoolean();
        try (RunWorker worker = worker(context -> {
            running.countDown();
            try {
                Thread.sleep(Duration.ofSeconds(30));
            } catch (InterruptedException stopped) {
                handlerInterrupted.set(true);
                throw stopped;
            }
            return RunOutcome.completed(null);
        })) {
            java.util.concurrent.atomic.AtomicBoolean kept = new java.util.concurrent.atomic.AtomicBoolean();
            java.util.concurrent.atomic.AtomicReference<RunWorker.Poll> poll = new java.util.concurrent.atomic.AtomicReference<>();
            Thread pollThread = Thread.ofPlatform().start(() -> {
                poll.set(worker.pollOnce());
                kept.set(Thread.currentThread().isInterrupted());
            });
            assertThat(running.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            pollThread.interrupt();
            pollThread.join(Duration.ofSeconds(10));

            assertThat(poll).hasValue(RunWorker.Poll.LOST);
            assertThat(kept).as("the shutdown interrupt is not swallowed").isTrue();
            assertThat(handlerInterrupted).isTrue();
        }
        // Nothing was written for the abandoned claim: the lease lapses and a healthy worker recovers the run.
        RunRecord abandoned = harness.engine.run(harness.workspace, run.id());
        assertThat(abandoned.lifecycle()).isEqualTo(RunLifecycle.ACTIVE);
        assertThat(abandoned.control().leaseOwner()).isEqualTo("worker-1");
    }

    // #179 medium 3: a run whose row cannot even be read and decoded is attributed and quarantined.
    @Test
    void aRunThatCannotBeDecodedIsQuarantined() {
        RunRecord poisoned = queued();
        RunRecord next = queued();
        harness.store.undecodable.add(poisoned.id());
        try (RunWorker worker = worker(context -> RunOutcome.completed(null))) {
            for (int attempt = 1; attempt < RunWorker.MAX_CONSECUTIVE_CLAIM_FAILURES; attempt++) {
                assertThat(worker.pollOnce()).isEqualTo(RunWorker.Poll.RETRY_LATER);
            }
            assertThat(worker.pollOnce()).isEqualTo(RunWorker.Poll.REFUSED);
            assertThat(harness.quarantined()).containsExactly(poisoned.id());
            assertThat(worker.pollOnce()).isEqualTo(RunWorker.Poll.EXECUTED);
        } finally {
            harness.store.undecodable.clear();
        }
        assertThat(harness.engine.run(harness.workspace, next.id()).lifecycle()).isEqualTo(RunLifecycle.SUCCEEDED);
    }

    // #179 low 1: transaction infrastructure failures never escape the poll loop.
    @Test
    void transactionInfrastructureFailuresAreRetriedLater() {
        queued();
        var failing = new DurableRunEngine(new QuarantineDelegating(harness.store) {
            @Override
            public <T> T inTransaction(com.dvarahq.agentworks.domain.tenant.WorkspaceId workspace,
                    java.util.function.Function<com.dvarahq.agentworks.domain.run.RunTransaction, T> work) {
                return harness.store.inTransaction(workspace, work);
            }

            @Override
            public <T> java.util.Optional<T> claimNext(com.dvarahq.agentworks.domain.run.ClaimScope scope,
                    java.util.function.BiFunction<RunRecord, com.dvarahq.agentworks.domain.run.RunTransaction, T> work) {
                throw new org.springframework.transaction.CannotCreateTransactionException("no connection");
            }
        }, RunEventCodec.standard(), new ReleaseManifestReader(),
                new com.dvarahq.agentworks.storage.lifecycle.LifecycleRetry(1));
        try (RunWorker worker = new RunWorker(failing, context -> RunOutcome.completed(null), "worker-1",
                harness.scope(), Duration.ofSeconds(30))) {
            assertThat(worker.pollOnce()).isEqualTo(RunWorker.Poll.RETRY_LATER);
        }
    }

    // #179 low 2: a duplicate key (a race) or an environment error never counts against a run.
    @Test
    void racesAndEnvironmentErrorsNeverQuarantineARun() throws Exception {
        RunRecord head = queued();
        java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
        try (RunWorker worker = new RunWorker(failingClaimsOf(head.id(), attempts,
                () -> new org.springframework.dao.DuplicateKeyException("concurrent claim")),
                context -> RunOutcome.completed(null), "worker-1", harness.scope(), Duration.ofSeconds(30))) {
            for (int poll = 0; poll < RunWorker.MAX_CONSECUTIVE_CLAIM_FAILURES * 2; poll++) {
                assertThat(worker.pollOnce()).isEqualTo(RunWorker.Poll.RETRY_LATER);
            }
            assertThat(harness.quarantined()).isEmpty();
        }
        assertThat(RunWorker.countsAgainstRun(new org.springframework.dao.DuplicateKeyException("x"))).isFalse();
        assertThat(RunWorker.countsAgainstRun(new org.springframework.dao.PermissionDeniedDataAccessException("x",
                null))).isFalse();
        assertThat(RunWorker.countsAgainstRun(new org.springframework.jdbc.BadSqlGrammarException("x", "select",
                new java.sql.SQLException("syntax")))).isFalse();
        assertThat(RunWorker.countsAgainstRun(new org.springframework.dao.DataIntegrityViolationException("x"))).isTrue();
        // #34 review 3, item 8: deterministic per-run failures count; environment errors do not.
        assertThat(RunWorker.countsAgainstRun(new org.springframework.dao.DataRetrievalFailureException("x"))).isTrue();
        assertThat(RunWorker.countsAgainstRun(new org.springframework.dao.TypeMismatchDataAccessException("x"))).isTrue();
        assertThat(RunWorker.countsAgainstRun(new org.springframework.dao.InvalidDataAccessApiUsageException("x")))
                .isFalse();
        assertThat(RunWorker.countsAgainstRun(new org.springframework.jdbc.UncategorizedSQLException("x", "select",
                new java.sql.SQLException("guard", "P0001")))).isTrue();
        // #34 review 4, item 9: the whole resource-misuse family is environmental, except a type mismatch, and so is
        // Spring's legacy cleanup failure. Reflection proves binary compatibility without compiling against that
        // deprecated API.
        assertThat(RunWorker.countsAgainstRun(new org.springframework.dao.InvalidDataAccessResourceUsageException("x")))
                .isFalse();
        assertThat(RunWorker.countsAgainstRun(new org.springframework.jdbc.InvalidResultSetAccessException("x", "select",
                new java.sql.SQLException("no column")))).isFalse();
        assertThat(RunWorker.countsAgainstRun(legacyCleanupFailure())).isFalse();
    }

    private static org.springframework.dao.DataAccessException legacyCleanupFailure() throws Exception {
        Class<?> type = Class.forName("org.springframework.dao.CleanupFailureDataAccessException");
        return (org.springframework.dao.DataAccessException) type.getConstructor(String.class, Throwable.class)
                .newInstance("x", new IllegalStateException("close"));
    }

    // #179 low 5: the failure streak builds even when the quarantine is shorter than the time between polls.
    @Test
    void theFailureStreakIsIndependentOfTheQuarantineLength() throws Exception {
        RunRecord poisoned = queued();
        java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
        try (RunWorker worker = new RunWorker(failingClaimsOf(poisoned.id(), attempts,
                () -> new com.dvarahq.agentworks.domain.run.RunAuthorityException("WORK_ITEM_REJECTED", null, null)),
                context -> RunOutcome.completed(null), "worker-1", harness.scope(), Duration.ofSeconds(30),
                RunWorker.MIN_CLAIM_QUARANTINE, Duration.ZERO)) {
            List<RunWorker.Poll> polls = new ArrayList<>();
            // The polls are further apart than the quarantine is long: a streak window tied to the quarantine would reset.
            for (int poll = 0; poll < RunWorker.MAX_CONSECUTIVE_CLAIM_FAILURES; poll++) {
                Thread.sleep(RunWorker.MIN_CLAIM_QUARANTINE.toMillis() + 100);
                polls.add(worker.pollOnce());
            }
            assertThat(polls).last().isEqualTo(RunWorker.Poll.REFUSED);
        }
    }

    // #179 low 6: a hung heartbeat holds the worker for at most one lease duration.
    @Test
    void aHungHeartbeatDoesNotHangTheWorker() {
        RunRecord run = queued();
        java.util.concurrent.CountDownLatch beatEntered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        var hanging = new DurableRunEngine(new QuarantineDelegating(harness.store) {
            @Override
            public <T> T inTransaction(com.dvarahq.agentworks.domain.tenant.WorkspaceId workspace,
                    java.util.function.Function<com.dvarahq.agentworks.domain.run.RunTransaction, T> work) {
                if (Thread.currentThread().getName().startsWith("agentworks-heartbeat-")) {
                    beatEntered.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
                return harness.store.inTransaction(workspace, work);
            }

            @Override
            public <T> java.util.Optional<T> claimNext(com.dvarahq.agentworks.domain.run.ClaimScope scope,
                    java.util.function.BiFunction<RunRecord, com.dvarahq.agentworks.domain.run.RunTransaction, T> work) {
                return harness.store.claimNext(scope, work);
            }
        }, RunEventCodec.standard(), new ReleaseManifestReader(),
                new com.dvarahq.agentworks.storage.lifecycle.LifecycleRetry(1));
        try (RunWorker worker = new RunWorker(hanging, context -> {
            beatEntered.await(5, java.util.concurrent.TimeUnit.SECONDS);
            return RunOutcome.completed("done");
        }, "worker-1", harness.scope(), Duration.ofMillis(600))) {
            assertThat(assertTimeoutPreemptively(Duration.ofSeconds(10), worker::pollOnce))
                    .isEqualTo(RunWorker.Poll.EXECUTED);
        } finally {
            release.countDown();
        }
        assertThat(harness.engine.run(harness.workspace, run.id()).lifecycle()).isEqualTo(RunLifecycle.SUCCEEDED);
    }

    // #34 review M2: an Error from a handler or tool fails the run; it does not end the worker's thread.
    @Test
    void aHandlerErrorFailsTheRunInsteadOfTheWorker() {
        RunRecord run = queued();
        try (RunWorker worker = worker(context -> {
            throw new AssertionError("tool invariant broken");
        })) {
            assertThat(worker.pollOnce()).isEqualTo(RunWorker.Poll.EXECUTED);
        }
        RunRecord failed = harness.engine.run(harness.workspace, run.id());
        assertThat(failed.summary().terminalCode()).isEqualTo("HANDLER_FAILED");
    }

    // #34 review H1: a handler that ignores its interrupt after the run ended holds the worker for at most a lease,
    // and anything it writes afterwards is refused.
    @Test
    void anUnresponsiveHandlerIsLeftBehindAndItsLaterWritesAreRefused() throws Exception {
        UUID id = harness.run(new RunLimits(null, null, null, 100L, null), Map.of("task", "t"));
        harness.engine.enqueue(harness.workspace, id, "system:test");
        java.util.concurrent.atomic.AtomicBoolean release = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.CompletableFuture<String> laterWrite = new java.util.concurrent.CompletableFuture<>();
        try (RunWorker worker = new RunWorker(harness.engine, context -> {
            harness.store.advance(Duration.ofMillis(300));
            while (!release.get()) Thread.onSpinWait();
            try {
                context.emit(List.of(DurableRunEngine.EventDraft.of(
                        com.dvarahq.agentworks.domain.run.RunEventType.TOOL_STARTED, Map.of("stepId", "late"))));
                laterWrite.complete("accepted");
            } catch (RunEngineException refused) {
                laterWrite.complete(refused.code());
            }
            return RunOutcome.completed(null);
        }, "worker-1", harness.scope(), Duration.ofMillis(600))) {
            RunWorker.Poll poll = assertTimeoutPreemptively(Duration.ofSeconds(5), worker::pollOnce);
            assertThat(poll).isEqualTo(RunWorker.Poll.EXECUTED);
        } finally {
            release.set(true);
        }
        assertThat(laterWrite.get(5, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo("STALE_LEASE");
        assertThat(harness.engine.run(harness.workspace, id).summary().terminalCode()).isEqualTo("LIMIT_EXCEEDED");
    }

    // #34 review L4: a closed worker never claims, so no claim is left without a heartbeat.
    @Test
    void aClosedWorkerNeverClaims() {
        RunRecord run = queued();
        RunWorker worker = worker(context -> RunOutcome.completed(null));
        worker.close();

        assertThatThrownBy(worker::pollOnce).isInstanceOf(IllegalStateException.class);
        assertThat(harness.engine.run(harness.workspace, run.id()).lifecycle()).isEqualTo(RunLifecycle.QUEUED);
    }

    // #34 review 3, item 4: a stack overflow is confined to its handler thread: it fails the run, and the worker keeps
    // polling.
    @Test
    void aHandlerStackOverflowFailsTheRunAndTheWorkerKeepsPolling() {
        RunRecord overflowing = queued();
        RunRecord next = queued();
        try (RunWorker worker = worker(context -> {
            if (context.pins().id().equals(overflowing.id())) return recurse(context);
            return RunOutcome.completed("next");
        })) {
            assertThat(worker.pollOnce()).isEqualTo(RunWorker.Poll.EXECUTED);
            assertThat(worker.pollOnce()).isEqualTo(RunWorker.Poll.EXECUTED);
        }
        assertThat(harness.engine.run(harness.workspace, overflowing.id()).summary().terminalCode())
                .isEqualTo("HANDLER_FAILED");
        assertThat(harness.engine.run(harness.workspace, next.id()).lifecycle()).isEqualTo(RunLifecycle.SUCCEEDED);
    }

    private static RunOutcome recurse(RunContext context) {
        return recurse(context);
    }

    @Test
    void infrastructureFailuresLeaveTheRunForRecoveryInsteadOfFailingIt() {
        RunRecord run = queued();
        try (RunWorker worker = worker(context -> {
            throw new org.springframework.dao.DataAccessResourceFailureException("connection reset during failover");
        })) {
            assertThat(worker.pollOnce()).isEqualTo(RunWorker.Poll.LOST);
        }
        RunRecord orphaned = harness.engine.run(harness.workspace, run.id());
        assertThat(orphaned.lifecycle()).isEqualTo(RunLifecycle.ACTIVE);
        harness.store.advance(Duration.ofMinutes(1));
        try (RunWorker recovery = worker(context -> RunOutcome.completed(context.recovered()))) {
            assertThat(recovery.pollOnce()).isEqualTo(RunWorker.Poll.EXECUTED);
        }
        assertThat(harness.engine.run(harness.workspace, run.id()).lifecycle()).isEqualTo(RunLifecycle.SUCCEEDED);
    }

    @Test
    void aRunThisNodeCannotInterpretIsQuarantinedAndLeftUnchanged() {
        RunRecord run = uninterpretable();
        RunRecord other = queued();

        try (RunWorker worker = worker(context -> RunOutcome.completed(null))) {
            assertThat(worker.pollOnce()).isEqualTo(RunWorker.Poll.REFUSED);
            assertThat(harness.quarantined()).containsExactly(run.id());
            assertThat(worker.pollOnce()).isEqualTo(RunWorker.Poll.EXECUTED);
            assertThat(worker.pollOnce()).isEqualTo(RunWorker.Poll.IDLE);
        }
        assertThat(harness.store.raw().runs.get(run.id()).lifecycle()).isEqualTo(RunLifecycle.QUEUED);
        assertThat(harness.engine.run(harness.workspace, other.id()).lifecycle()).isEqualTo(RunLifecycle.SUCCEEDED);
    }

    /**
     * A queued run whose latest event was edited, breaking its digest chain, so every claim of it is refused with
     * CORRUPT_HISTORY.
     */
    private RunRecord uninterpretable() {
        RunRecord run = queued();
        List<RunEvent> history = harness.store.raw().events.get(run.id());
        RunEvent last = history.getLast();
        history.set(history.size() - 1, new RunEvent(last.id(), last.workspaceId(), last.runId(), last.sequence(),
                "RUN_FROM_THE_FUTURE", last.schemaVersion(), last.payloadJson(), last.payloadDigest(), last.eventDigest(),
                last.traceId(), last.spanId(), last.releaseId(), last.deploymentId(), last.revisionId(), last.cohortId(),
                last.actor(), last.leaseOwner(), last.leaseFence(), last.createdAt()));
        return run;
    }

    /** A queued run whose history no longer verifies, so every node refuses its claim with CORRUPT_HISTORY. */
    private RunRecord corrupt() {
        RunRecord run = queued();
        List<RunEvent> history = harness.store.raw().events.get(run.id());
        RunEvent last = history.getLast();
        history.set(history.size() - 1, new RunEvent(last.id(), last.workspaceId(), last.runId(), last.sequence(),
                last.type(), last.schemaVersion(), last.payloadJson(), last.payloadDigest(), "sha256:" + "0".repeat(64),
                last.traceId(), last.spanId(), last.releaseId(), last.deploymentId(), last.revisionId(), last.cohortId(),
                last.actor(), last.leaseOwner(), last.leaseFence(), last.createdAt()));
        return run;
    }

    /**
     * A queued run whose intact history ends with an event type a newer version wrote, so this node refuses its claim
     * with UNKNOWN_EVENT_TYPE: a version skew, not corruption.
     */
    private RunRecord fromANewerVersion() {
        RunRecord run = queued();
        List<RunEvent> history = harness.store.raw().events.get(run.id());
        RunEvent last = history.getLast();
        RunEvent newer = new RunEvent(last.id(), last.workspaceId(), last.runId(), last.sequence(), "RUN_FROM_THE_FUTURE",
                last.schemaVersion(), last.payloadJson(), last.payloadDigest(), last.eventDigest(), last.traceId(), last.spanId(),
                last.releaseId(), last.deploymentId(), last.revisionId(), last.cohortId(), last.actor(), last.leaseOwner(),
                last.leaseFence(), last.createdAt());
        String digest = RunEventCodec.eventDigest(history.size() > 1 ? history.get(history.size() - 2).eventDigest() : null,
                newer);
        history.set(history.size() - 1, new RunEvent(newer.id(), newer.workspaceId(), newer.runId(), newer.sequence(),
                newer.type(), newer.schemaVersion(), newer.payloadJson(), newer.payloadDigest(), digest, newer.traceId(),
                newer.spanId(), newer.releaseId(), newer.deploymentId(), newer.revisionId(), newer.cohortId(), newer.actor(),
                newer.leaseOwner(), newer.leaseFence(), newer.createdAt()));
        return run;
    }

    // #181 medium 2: the worker cannot livelock behind more poison runs than the old local exclusion set held (1024).
    // Quarantines are durable and the claim query skips them by predicate, so every poison run is refused once and
    // the healthy run behind them is claimed, and none of them is selected again.
    @Test
    void aHealthyRunBehindMoreThanAThousandPoisonRunsIsStillClaimed() {
        int poisoned = 1024 + 76;
        List<UUID> poison = new ArrayList<>();
        for (int index = 0; index < poisoned; index++) poison.add(uninterpretable().id());
        RunRecord healthy = queued();

        try (RunWorker worker = worker(context -> RunOutcome.completed("healthy"))) {
            List<RunWorker.Poll> polls = new ArrayList<>();
            for (int poll = 0; poll < poisoned * 2 && !polls.contains(RunWorker.Poll.EXECUTED); poll++) {
                polls.add(worker.pollOnce());
            }
            assertThat(polls).hasSize(poisoned + 1).last().isEqualTo(RunWorker.Poll.EXECUTED);
            assertThat(worker.pollOnce()).isEqualTo(RunWorker.Poll.IDLE);
        }
        assertThat(harness.engine.run(harness.workspace, healthy.id()).lifecycle()).isEqualTo(RunLifecycle.SUCCEEDED);
        assertThat(harness.quarantined()).containsExactlyInAnyOrderElementsOf(poison);
    }

    // #181 medium 2: the same holds for runs whose claim keeps failing: each costs its bounded streak once.
    @Test
    void aHealthyRunBehindMoreThanAThousandRunsWhoseClaimsFailIsStillClaimed() {
        int poisoned = 1024 + 16;
        java.util.Set<UUID> poison = new java.util.HashSet<>();
        for (int index = 0; index < poisoned; index++) poison.add(queued().id());
        RunRecord healthy = queued();
        var rejecting = new DurableRunEngine(new QuarantineDelegating(harness.store) {
            @Override
            public <T> T inTransaction(com.dvarahq.agentworks.domain.tenant.WorkspaceId workspace,
                    java.util.function.Function<com.dvarahq.agentworks.domain.run.RunTransaction, T> work) {
                return harness.store.inTransaction(workspace, work);
            }

            @Override
            public <T> java.util.Optional<T> claimNext(com.dvarahq.agentworks.domain.run.ClaimScope scope,
                    java.util.function.BiFunction<RunRecord, com.dvarahq.agentworks.domain.run.RunTransaction, T> work) {
                return harness.store.claimNext(scope, (run, tx) -> {
                    if (!poison.contains(run.id())) return work.apply(run, tx);
                    // The claim of a poison run fails inside its transaction, as a database guard would.
                    var refusing = (com.dvarahq.agentworks.domain.run.RunTransaction) java.lang.reflect.Proxy.newProxyInstance(
                            getClass().getClassLoader(),
                            new Class<?>[]{com.dvarahq.agentworks.domain.run.RunTransaction.class}, (proxy, method, args) -> {
                                if (method.getName().equals("insertWorkItem")) {
                                    throw new com.dvarahq.agentworks.domain.run.RunAuthorityException(
                                            "WORK_ITEM_REJECTED", null, null);
                                }
                                try {
                                    return method.invoke(tx, args);
                                } catch (java.lang.reflect.InvocationTargetException thrown) {
                                    throw thrown.getCause();
                                }
                            });
                    return work.apply(run, refusing);
                });
            }
        }, RunEventCodec.standard(), new ReleaseManifestReader(),
                new com.dvarahq.agentworks.storage.lifecycle.LifecycleRetry(1));

        try (RunWorker worker = new RunWorker(rejecting, context -> RunOutcome.completed(null), "worker-1",
                harness.scope(), Duration.ofSeconds(30))) {
            int polls = 0;
            RunWorker.Poll last = null;
            while (last != RunWorker.Poll.EXECUTED && polls < poisoned * RunWorker.MAX_CONSECUTIVE_CLAIM_FAILURES * 2) {
                last = worker.pollOnce();
                polls++;
            }
            assertThat(last).isEqualTo(RunWorker.Poll.EXECUTED);
            assertThat(polls).isEqualTo(poisoned * RunWorker.MAX_CONSECUTIVE_CLAIM_FAILURES + 1);
        }
        assertThat(harness.engine.run(harness.workspace, healthy.id()).lifecycle()).isEqualTo(RunLifecycle.SUCCEEDED);
        assertThat(harness.quarantined()).hasSize(poisoned);
    }

    // #181 medium 3: a corrupt run is quarantined until an operator releases it, with a typed, visible record, and
    // the quarantine never lapses on its own.
    @Test
    void aCorruptRunIsQuarantinedUntilAnOperatorReleasesIt() {
        RunRecord run = corrupt();
        RunRecord other = queued();

        try (RunWorker worker = worker(context -> RunOutcome.completed(null))) {
            assertThat(worker.pollOnce()).isEqualTo(RunWorker.Poll.REFUSED);
            assertThat(worker.pollOnce()).isEqualTo(RunWorker.Poll.EXECUTED);
            harness.store.advance(Duration.ofDays(30));
            assertThat(worker.pollOnce()).isEqualTo(RunWorker.Poll.IDLE);
            assertThat(harness.store.quarantines(harness.workspace)).singleElement().satisfies(quarantine -> {
                assertThat(quarantine.runId()).isEqualTo(run.id());
                assertThat(quarantine.reason()).isEqualTo(com.dvarahq.agentworks.domain.run.ClaimQuarantine.Reason.UNINTERPRETABLE);
                assertThat(quarantine.code()).isEqualTo("CORRUPT_HISTORY");
                assertThat(quarantine.indefinite()).isTrue();
                assertThat(quarantine.quarantinedBy()).isEqualTo("worker-1");
            });

            // The operator releases it (the administrative surface is #79): workers select it again at once.
            assertThat(harness.store.release(harness.workspace, run.id())).isOne();
            assertThat(harness.quarantined()).isEmpty();
            assertThat(worker.pollOnce()).isEqualTo(RunWorker.Poll.REFUSED);
        }
        assertThat(harness.quarantined()).containsExactly(run.id());
        assertThat(harness.store.raw().runs.get(run.id()).lifecycle()).isEqualTo(RunLifecycle.QUEUED);
        assertThat(harness.engine.run(harness.workspace, other.id()).lifecycle()).isEqualTo(RunLifecycle.SUCCEEDED);
    }

    // #181 medium 3: a run this node's version cannot read is quarantined for a time only (an upgraded node may read
    // it), each quarantine doubles the last, and after the timed ones the run waits for an operator, so the claim
    // attempts one run can cost the fleet are bounded.
    @Test
    void timedQuarantinesDoubleAndThenLastUntilReleased() {
        RunRecord run = fromANewerVersion();
        Duration base = Duration.ofMinutes(1);
        try (RunWorker worker = new RunWorker(harness.engine, context -> RunOutcome.completed(null), "worker-1",
                harness.scope(), Duration.ofSeconds(30), base, Duration.ZERO)) {
            for (int quarantine = 1; quarantine <= RunWorker.MAX_TIMED_QUARANTINES; quarantine++) {
                assertThat(worker.pollOnce()).isEqualTo(RunWorker.Poll.REFUSED);
                Duration length = base.multipliedBy(1L << (quarantine - 1));
                harness.store.advance(length.minusSeconds(1));
                assertThat(worker.pollOnce()).as("quarantine %s lasts %s", quarantine, length)
                        .isEqualTo(RunWorker.Poll.IDLE);
                harness.store.advance(Duration.ofSeconds(1));
            }
            assertThat(worker.pollOnce()).isEqualTo(RunWorker.Poll.REFUSED);
            harness.store.advance(Duration.ofDays(365));
            assertThat(worker.pollOnce()).isEqualTo(RunWorker.Poll.IDLE);
        }
        assertThat(harness.store.quarantines(harness.workspace)).singleElement().satisfies(quarantine -> {
            assertThat(quarantine.code()).isEqualTo("UNKNOWN_EVENT_TYPE");
            assertThat(quarantine.quarantines()).isEqualTo(RunWorker.MAX_TIMED_QUARANTINES + 1);
            assertThat(quarantine.indefinite()).isTrue();
        });
        assertThat(harness.store.raw().runs.get(run.id()).lifecycle()).isEqualTo(RunLifecycle.QUEUED);
    }

    // #181 medium 3: a zero quarantine would disable the exclusion entirely, so it is refused at construction (and so
    // at startup), as is a negative or excessive one.
    @Test
    void aQuarantineThatIsNotPositiveOrTooLongIsRefused() {
        for (Duration invalid : List.of(Duration.ZERO, Duration.ofSeconds(-1), Duration.ofNanos(1),
                RunWorker.MIN_CLAIM_QUARANTINE.minusNanos(1), RunWorker.MAX_CLAIM_QUARANTINE.plusSeconds(1))) {
            assertThatThrownBy(() -> new RunWorker(harness.engine, context -> RunOutcome.completed(null), "worker-1",
                    harness.scope(), Duration.ofSeconds(30), invalid, Duration.ZERO)).as(invalid.toString())
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("claimQuarantine");
        }
    }

    // #181: the quarantine write is fenced: a run another worker claimed meanwhile (a live lease) is not quarantined.
    @Test
    void aRunClaimedByAnotherWorkerIsNeverQuarantined() {
        RunRecord run = queued();
        harness.claim("worker-2");
        var request = new com.dvarahq.agentworks.domain.run.ClaimQuarantine.Request(harness.engine.compatibility(),
                com.dvarahq.agentworks.domain.run.ClaimQuarantine.Reason.CLAIM_FAILING, "WORK_ITEM_REJECTED", "worker-1",
                Duration.ofMinutes(5), RunWorker.MAX_TIMED_QUARANTINES, null);

        assertThat(harness.engine.quarantine(run.id(), request)).isEmpty();
        assertThat(harness.store.quarantines(harness.workspace)).isEmpty();
        // Once its lease lapses it is claimable again, and may be quarantined.
        harness.store.advance(Duration.ofMinutes(10));
        assertThat(harness.engine.quarantine(run.id(), request)).isPresent();
        // A second quarantine while the first is in force (a racing worker) keeps the first unchanged.
        assertThat(harness.engine.quarantine(run.id(), request)).hasValueSatisfying(quarantine ->
                assertThat(quarantine.quarantines()).isOne());
    }

    // #181 review: the escalation counts one unbroken episode of failed claims, not a run's whole life, so a long-lived
    // run that meets an unreadable node now and then never drifts into an indefinite quarantine.
    @Test
    void theQuarantineCountStartsAfreshAfterAQuietDay() {
        RunRecord run = queued();
        var request = new com.dvarahq.agentworks.domain.run.ClaimQuarantine.Request(harness.engine.compatibility(),
                com.dvarahq.agentworks.domain.run.ClaimQuarantine.Reason.CLAIM_FAILING, "WORK_ITEM_REJECTED", "worker-1",
                Duration.ofMinutes(5), RunWorker.MAX_TIMED_QUARANTINES, null);

        assertThat(harness.engine.quarantine(run.id(), request).orElseThrow().quarantines()).isOne();
        harness.store.advance(Duration.ofMinutes(6));
        assertThat(harness.engine.quarantine(run.id(), request).orElseThrow().quarantines()).isEqualTo(2);
        harness.store.advance(Duration.ofMinutes(11).plus(com.dvarahq.agentworks.domain.run.ClaimQuarantine.EPISODE_GAP));
        assertThat(harness.engine.quarantine(run.id(), request).orElseThrow().quarantines()).isOne();
    }

    // #181 review: a store that keeps no quarantine would bring the livelock back, so no worker is built on it.
    @Test
    void aStoreWithoutAQuarantineFailsLoudly() {
        RunRecord run = uninterpretable();
        var bare = new DurableRunEngine(new com.dvarahq.agentworks.domain.run.DurableRunStore() {
            @Override
            public <T> T inTransaction(com.dvarahq.agentworks.domain.tenant.WorkspaceId workspace,
                    java.util.function.Function<com.dvarahq.agentworks.domain.run.RunTransaction, T> work) {
                return harness.store.inTransaction(workspace, work);
            }

            @Override
            public <T> java.util.Optional<T> claimNext(com.dvarahq.agentworks.domain.run.ClaimScope scope,
                    java.util.function.BiFunction<RunRecord, com.dvarahq.agentworks.domain.run.RunTransaction, T> work) {
                return harness.store.claimNext(scope, work);
            }
        }, RunEventCodec.standard(), new ReleaseManifestReader(),
                new com.dvarahq.agentworks.storage.lifecycle.LifecycleRetry(1));
        assertThatThrownBy(() -> new RunWorker(bare, context -> RunOutcome.completed(null), "worker-1",
                harness.scope(), Duration.ofSeconds(30))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("claim quarantine");
        assertThat(harness.store.raw().runs.get(run.id()).lifecycle()).isEqualTo(RunLifecycle.QUEUED);
    }

    /** An engine over the harness's store whose nodes have another runtime compatibility identity. */
    private DurableRunEngine engineOfIdentity(String compatibility) {
        return new DurableRunEngine(harness.store, RunEventCodec.standard(), new ReleaseManifestReader(),
                new com.dvarahq.agentworks.storage.lifecycle.LifecycleRetry(1), compatibility);
    }

    // #181 round 2, high: a quarantine applies to the refusing node's compatibility identity only. An old node's
    // indefinite CORRUPT_HISTORY quarantine and its timed version-skew quarantine keep nodes of its identity away, while
    // a node of another identity still claims both runs.
    @Test
    void aQuarantineByOneCompatibilityIdentityNeverStopsAnother() {
        RunRecord corrupt = corrupt();
        RunRecord skewed = fromANewerVersion();
        DurableRunEngine old = engineOfIdentity("agentworks-old-0001");
        DurableRunEngine sameAsOld = engineOfIdentity("agentworks-old-0001");

        try (RunWorker refusing = new RunWorker(old, context -> RunOutcome.completed(null), "old-1", harness.scope(),
                Duration.ofSeconds(30))) {
            assertThat(refusing.pollOnce()).isEqualTo(RunWorker.Poll.REFUSED);
            assertThat(refusing.pollOnce()).isEqualTo(RunWorker.Poll.REFUSED);
            assertThat(refusing.pollOnce()).isEqualTo(RunWorker.Poll.IDLE);
        }
        assertThat(harness.store.quarantines(harness.workspace)).hasSize(2).allSatisfy(quarantine ->
                assertThat(quarantine.compatibility()).isEqualTo("agentworks-old-0001"));
        // Every node of the same identity honors it, even much later for the indefinite one.
        harness.store.advance(Duration.ofSeconds(1));
        assertThat(sameAsOld.claimNext("old-2", harness.scope(), EngineHarness.LEASE)).isEmpty();

        // A node of another identity is not held back: it selects both runs again. (Its codec here is the same, so it
        // refuses them too, for its own identity; an upgraded codec would execute them.)
        DurableRunEngine other = engineOfIdentity("agentworks-new-0002");
        try (RunWorker capable = new RunWorker(other, context -> RunOutcome.completed(null), "new-1", harness.scope(),
                Duration.ofSeconds(30))) {
            assertThat(capable.pollOnce()).isEqualTo(RunWorker.Poll.REFUSED);
            assertThat(capable.pollOnce()).isEqualTo(RunWorker.Poll.REFUSED);
            assertThat(capable.pollOnce()).isEqualTo(RunWorker.Poll.IDLE);
        }
        assertThat(harness.store.quarantines(harness.workspace)).extracting(
                com.dvarahq.agentworks.domain.run.ClaimQuarantine::compatibility)
                .contains("agentworks-old-0001", "agentworks-new-0002");
    }

    // #185 low: the local fallback skip replaced the caller's own exclusions instead of adding to them, so a run the
    // caller excluded was claimed while another run was skipped locally.
    @Test
    void theFallbackSkipKeepsTheCallersOwnExclusions() {
        RunRecord poison = corrupt();
        RunRecord excludedByCaller = queued();
        harness.store.failQuarantineWrites = true;
        try (RunWorker worker = new RunWorker(harness.engine, context -> RunOutcome.completed(null), "worker-1",
                harness.scope().excluding(java.util.Set.of(excludedByCaller.id())), Duration.ofSeconds(30))) {
            assertThat(worker.pollOnce()).isEqualTo(RunWorker.Poll.RETRY_LATER);
            // The poison run is skipped locally now; the caller's exclusion still stands.
            assertThat(worker.pollOnce()).isEqualTo(RunWorker.Poll.IDLE);
        } finally {
            harness.store.failQuarantineWrites = false;
        }
        assertThat(harness.engine.run(harness.workspace, excludedByCaller.id()).lifecycle())
                .isEqualTo(RunLifecycle.QUEUED);
        assertThat(harness.store.raw().runs.get(poison.id()).lifecycle()).isEqualTo(RunLifecycle.QUEUED);
    }

    // #185 low: the failure streak and the fallback skip were per worker, so each thread of a node's pool hit a poison
    // run on its own. Workers sharing their node's claim state accumulate one streak and skip the run together.
    @Test
    void aNodesWorkersShareOneFailureStreakAndFallbackSkip() {
        RunRecord poisoned = queued();
        java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
        var rejecting = failingClaimsOf(poisoned.id(), attempts,
                () -> new com.dvarahq.agentworks.domain.run.RunAuthorityException("WORK_ITEM_REJECTED", null, null));
        var node = new RunWorker.NodeClaimState();
        harness.store.failQuarantineWrites = true;
        try (RunWorker a = new RunWorker(rejecting, context -> RunOutcome.completed(null), "worker-a",
                harness.scope(), Duration.ofSeconds(30), Duration.ofMinutes(5), Duration.ZERO, node);
             RunWorker b = new RunWorker(rejecting, context -> RunOutcome.completed(null), "worker-b",
                harness.scope(), Duration.ofSeconds(30), Duration.ofMinutes(5), Duration.ZERO, node)) {
            // Failures alternate between the node's workers and still form one streak of MAX failures.
            for (int attempt = 0; attempt < RunWorker.MAX_CONSECUTIVE_CLAIM_FAILURES; attempt++) {
                assertThat((attempt % 2 == 0 ? a : b).pollOnce()).isEqualTo(RunWorker.Poll.RETRY_LATER);
            }
            assertThat(attempts).hasValue(RunWorker.MAX_CONSECUTIVE_CLAIM_FAILURES);
            // The quarantine write failed, so the run is skipped locally, by both workers of the node.
            assertThat(a.pollOnce()).isEqualTo(RunWorker.Poll.IDLE);
            assertThat(b.pollOnce()).isEqualTo(RunWorker.Poll.IDLE);
            assertThat(attempts).hasValue(RunWorker.MAX_CONSECUTIVE_CLAIM_FAILURES);
        } finally {
            harness.store.failQuarantineWrites = false;
        }
    }

    // #181 round 2, medium: a quarantine write that always fails (a missing grant, a lock failure) still does not
    // leave the poison run at the head of the queue: the worker skips it locally, and the healthy run behind it runs.
    @Test
    void aHealthyRunIsClaimedEvenWhenQuarantineWritesAlwaysFail() {
        RunRecord poison = corrupt();
        RunRecord healthy = queued();
        harness.store.failQuarantineWrites = true;
        try (RunWorker worker = worker(context -> RunOutcome.completed("healthy"))) {
            assertThat(worker.pollOnce()).isEqualTo(RunWorker.Poll.RETRY_LATER);
            assertThat(worker.pollOnce()).isEqualTo(RunWorker.Poll.EXECUTED);
            assertThat(worker.pollOnce()).isEqualTo(RunWorker.Poll.IDLE);
        } finally {
            harness.store.failQuarantineWrites = false;
        }
        assertThat(harness.engine.run(harness.workspace, healthy.id()).lifecycle()).isEqualTo(RunLifecycle.SUCCEEDED);
        assertThat(harness.store.raw().runs.get(poison.id()).lifecycle()).isEqualTo(RunLifecycle.QUEUED);
        assertThat(harness.quarantined()).isEmpty();
    }

    // #181 round 2, medium: the failure streak survives a failed quarantine write, so the next failure retries the
    // write at once rather than rebuilding a streak.
    @Test
    void theStreakSurvivesAFailedQuarantineWrite() throws Exception {
        RunRecord poisoned = queued();
        java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
        var rejecting = failingClaimsOf(poisoned.id(), attempts,
                () -> new com.dvarahq.agentworks.domain.run.RunAuthorityException("WORK_ITEM_REJECTED", null, null));
        try (RunWorker worker = new RunWorker(rejecting, context -> RunOutcome.completed(null), "worker-1",
                harness.scope(), Duration.ofSeconds(30), RunWorker.MIN_CLAIM_QUARANTINE, Duration.ZERO)) {
            harness.store.failQuarantineWrites = true;
            for (int attempt = 1; attempt <= RunWorker.MAX_CONSECUTIVE_CLAIM_FAILURES; attempt++) {
                assertThat(worker.pollOnce()).isEqualTo(RunWorker.Poll.RETRY_LATER);
            }
            // The worker skips the run locally meanwhile.
            assertThat(worker.pollOnce()).isEqualTo(RunWorker.Poll.IDLE);
            harness.store.failQuarantineWrites = false;
            // Once the local skip lapses, the very next failure records the quarantine: the streak was kept.
            Thread.sleep(RunWorker.MIN_CLAIM_QUARANTINE.toMillis() + 100);
            assertThat(worker.pollOnce()).isEqualTo(RunWorker.Poll.REFUSED);
        } finally {
            harness.store.failQuarantineWrites = false;
        }
        assertThat(attempts.get()).isEqualTo(RunWorker.MAX_CONSECUTIVE_CLAIM_FAILURES + 1);
        assertThat(harness.quarantined()).containsExactly(poisoned.id());
    }

    // #181 round 2, low: a quarantine is fenced on the run state the refused claim observed, so a delayed quarantine
    // never applies to a later state of the run.
    @Test
    void aDelayedQuarantineNeverAppliesToALaterRunState() {
        RunRecord run = queued();
        var observed = new com.dvarahq.agentworks.domain.run.ClaimQuarantine.Observed(run.summary().eventSequence(),
                run.summary().leaseFence());
        // The run moves on after the refusal observed it: another worker claims it and its lease lapses.
        harness.claim("worker-2");
        harness.store.advance(Duration.ofMinutes(10));
        var request = new com.dvarahq.agentworks.domain.run.ClaimQuarantine.Request(harness.engine.compatibility(),
                com.dvarahq.agentworks.domain.run.ClaimQuarantine.Reason.UNINTERPRETABLE, "CORRUPT_HISTORY", "worker-1",
                null, RunWorker.MAX_TIMED_QUARANTINES, observed);

        assertThat(harness.engine.quarantine(run.id(), request)).isEmpty();
        var current = harness.engine.run(harness.workspace, run.id()).summary();
        assertThat(harness.engine.quarantine(run.id(), new com.dvarahq.agentworks.domain.run.ClaimQuarantine.Request(
                harness.engine.compatibility(), com.dvarahq.agentworks.domain.run.ClaimQuarantine.Reason.UNINTERPRETABLE,
                "CORRUPT_HISTORY", "worker-1", null, RunWorker.MAX_TIMED_QUARANTINES,
                new com.dvarahq.agentworks.domain.run.ClaimQuarantine.Observed(current.eventSequence(),
                        current.leaseFence())))).isPresent();
    }

    // #181 round 2, low: a run that ends closes its quarantines, so they leave the active listing.
    @Test
    void aRunThatEndsClosesItsQuarantines() {
        RunRecord run = corrupt();
        try (RunWorker worker = worker(context -> RunOutcome.completed(null))) {
            assertThat(worker.pollOnce()).isEqualTo(RunWorker.Poll.REFUSED);
        }
        assertThat(harness.quarantined()).containsExactly(run.id());
        harness.engine.cancel(harness.workspace, run.id(), "operator:ops", "corrupt");
        assertThat(harness.quarantined()).isEmpty();
        assertThat(harness.store.quarantines(harness.workspace)).singleElement()
                .satisfies(quarantine -> assertThat(quarantine.closedAt()).isNotNull());
    }

    // #181 round 2, high: the compatibility identity is stable for one build and set of readable versions, and differs
    // when the events a node reads differ.
    @Test
    void theCompatibilityIdentityFollowsWhatANodeCanRead() {
        String standard = DurableRunEngine.compatibilityOf(RunEventCodec.standard());
        assertThat(standard).isEqualTo(DurableRunEngine.compatibilityOf(RunEventCodec.standard()))
                .matches(com.dvarahq.agentworks.domain.run.ClaimQuarantine.COMPATIBILITY_PATTERN);
        var newer = EventSchemas.builder().current(com.dvarahq.agentworks.domain.run.RunEventType.MODEL_RESPONSE_RECEIVED, 2)
                .upcaster(com.dvarahq.agentworks.domain.run.RunEventType.MODEL_RESPONSE_RECEIVED, 1, payload -> payload)
                .build();
        assertThat(DurableRunEngine.compatibilityOf(new RunEventCodec(newer))).isNotEqualTo(standard);
        assertThat(harness.engine.compatibility()).isEqualTo(standard);
    }

    // #185 medium 1: the identity told builds apart only by the jar's Implementation-Version, which is the project
    // version for every build, so a buggy build's quarantines also stopped the build that fixed it. The commit (and,
    // for a changed tree, the build time) now enters the identity; an unstamped build falls back deterministically.
    @Test
    void buildsOfDifferentCommitsOrChangedTreesHaveDifferentCompatibilityIdentities() {
        var codec = RunEventCodec.standard();
        String one = "1".repeat(40);
        String two = "2".repeat(40);
        String first = DurableRunEngine.compatibilityOf(codec, build(one, "true", "2026-09-26T10:00:00.000Z"));
        assertThat(first).startsWith("agentworks-0.0.1-SNAPSHOT+111111111111-")
                .matches(com.dvarahq.agentworks.domain.run.ClaimQuarantine.COMPATIBILITY_PATTERN)
                .as("another build of the same clean commit shares the identity")
                .isEqualTo(DurableRunEngine.compatibilityOf(codec, build(one, "true", "2026-09-27T10:00:00.000Z")));
        assertThat(DurableRunEngine.compatibilityOf(codec, build(two, "true", "2026-09-26T10:00:00.000Z")))
                .as("another commit").isNotEqualTo(first);
        String changed = DurableRunEngine.compatibilityOf(codec, build(one, "false", "2026-09-26T10:00:00.000Z"));
        assertThat(changed).startsWith("agentworks-0.0.1-SNAPSHOT+111111111111.dirty-").isNotEqualTo(first)
                .isNotEqualTo(DurableRunEngine.compatibilityOf(codec, build(one, "false", "2026-09-26T10:00:01.000Z")))
                .matches(com.dvarahq.agentworks.domain.run.ClaimQuarantine.COMPATIBILITY_PATTERN);
        var unstamped = DurableRunEngine.BuildIdentity.of(new java.util.Properties(), null);
        assertThat(DurableRunEngine.compatibilityOf(codec, unstamped)).startsWith("agentworks-dev-")
                .isEqualTo(DurableRunEngine.compatibilityOf(codec,
                        DurableRunEngine.BuildIdentity.of(new java.util.Properties(), "not a version!")));
        var malformed = new java.util.Properties();
        malformed.setProperty("commit", "not-a-commit");
        malformed.setProperty("clean", "true");
        assertThat(DurableRunEngine.BuildIdentity.of(malformed, "1.2.3").label()).isEqualTo("1.2.3");
    }

    // #185 medium 1: the Maven build stamps the commit it builds into the runtime, and the node's identity carries it.
    // CI always builds from a git checkout, so there the stamp is required; a build without git (a source archive)
    // records no commit and falls back, as documented, so outside CI this test is skipped for such a build.
    @Test
    void theMavenBuildStampsItsCommitIntoTheCompatibilityIdentity() throws Exception {
        var stamp = new java.util.Properties();
        try (var in = DurableRunEngine.class.getResourceAsStream(DurableRunEngine.BuildIdentity.RESOURCE)) {
            if (in != null) stamp.load(in);
        }
        // Checked before any assertion (#185 review round 3): an IDE build or a build without git skips, CI never does.
        if (!"true".equalsIgnoreCase(System.getenv("CI"))) {
            org.junit.jupiter.api.Assumptions.assumeTrue(!stamp.getProperty("commit", "").isEmpty(),
                    "not stamped by a Maven build from a git checkout");
        }
        assertThat(DurableRunEngine.buildStamped()).isTrue();
        assertThat(stamp.getProperty("commit")).matches("[0-9a-f]{40}|[0-9a-f]{64}");
        if ("true".equals(stamp.getProperty("clean"))) {
            assertThat(stamp.getProperty("built")).as("a clean build's stamp is reproducible").isEmpty();
        }
        assertThat(stamp.getProperty("clean")).isIn("true", "false");
        assertThat(DurableRunEngine.compatibilityOf(RunEventCodec.standard()))
                .contains("+" + stamp.getProperty("commit").substring(0, 12));
    }

    // #185 review round 3: an operator-named build is an identity of its own, distinct from every stamped build and
    // from the unstamped fallback, and a malformed name is refused.
    @Test
    void anOperatorNamedBuildIsItsOwnIdentity() {
        var codec = RunEventCodec.standard();
        String named = DurableRunEngine.compatibilityOf(codec, "2026.09.26-7");
        assertThat(named).contains("+build.2026.09.26-7-")
                .matches(com.dvarahq.agentworks.domain.run.ClaimQuarantine.COMPATIBILITY_PATTERN)
                .isEqualTo(DurableRunEngine.compatibilityOf(codec, "2026.09.26-7"))
                .isNotEqualTo(DurableRunEngine.compatibilityOf(codec, "2026.09.26-8"))
                .isNotEqualTo(DurableRunEngine.compatibilityOf(codec,
                        DurableRunEngine.BuildIdentity.of(new java.util.Properties(), null)));
        assertThat(DurableRunEngine.BuildIdentity.configured("x", "1.0").stamped()).isTrue();
        // #189: the project version is folded in, so one name left in place across versions never merges them.
        assertThat(DurableRunEngine.compatibilityOf(codec, DurableRunEngine.BuildIdentity.configured("x", "1.0")))
                .startsWith("agentworks-1.0+build.x-")
                .isNotEqualTo(DurableRunEngine.compatibilityOf(codec,
                        DurableRunEngine.BuildIdentity.configured("x", "1.1")));
        assertThat(DurableRunEngine.BuildIdentity.of(new java.util.Properties(), "1.0").stamped()).isFalse();
        assertThatThrownBy(() -> DurableRunEngine.compatibilityOf(codec, "has space"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("agentworks.runtime.build-id");
    }

    private static DurableRunEngine.BuildIdentity build(String commit, String clean, String built) {
        var stamp = new java.util.Properties();
        stamp.setProperty("commit", commit);
        stamp.setProperty("clean", clean);
        stamp.setProperty("built", built);
        return DurableRunEngine.BuildIdentity.of(stamp, "0.0.1-SNAPSHOT");
    }
}
