package com.dvarahq.agentworks.runplane.execution;

import com.dvarahq.agentworks.domain.run.RunBlockReason;
import com.dvarahq.agentworks.domain.run.RunEffect;
import com.dvarahq.agentworks.domain.run.RunEvent;
import com.dvarahq.agentworks.domain.run.RunEventType;
import com.dvarahq.agentworks.domain.run.RunLease;
import com.dvarahq.agentworks.domain.run.RunLifecycle;
import com.dvarahq.agentworks.domain.run.RunLimits;
import com.dvarahq.agentworks.domain.run.RunPins;
import com.dvarahq.agentworks.domain.run.RunRecord;
import com.dvarahq.agentworks.domain.run.RunTransaction;
import com.dvarahq.agentworks.domain.run.WorkItem;
import com.dvarahq.agentworks.runplane.execution.DurableRunEngine.EffectFailedException;
import com.dvarahq.agentworks.runplane.execution.DurableRunEngine.EffectInDoubtException;
import com.dvarahq.agentworks.runplane.execution.DurableRunEngine.EventDraft;
import com.dvarahq.agentworks.runplane.execution.RunEventCodec.DecodedEvent;
import com.dvarahq.agentworks.storage.lifecycle.LifecycleRetry;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Runtime-domain rules of the durable run engine, run against the persistence port (AC 10). */
class DurableRunEngineTest {
    private final EngineHarness harness = new EngineHarness();
    private final DurableRunEngine engine = harness.engine;

    // #181 low 7: the in-memory store, like the claim query's executor_type in (...), selects no run without a recorded
    // executor type for a scope restricted to executor types; it failed the claim with a NullPointerException instead.
    @Test
    void aScopeRestrictedToExecutorTypesSkipsARunWithoutOne() {
        UUID id = harness.run();
        engine.enqueue(harness.workspace, id, "system:test");
        harness.store.manifest(harness.release, "{\"manifestVersion\":2}");

        assertThat(engine.claimNext("worker-a", harness.scope().executing(java.util.Set.of("spring-ai")),
                EngineHarness.LEASE)).isEmpty();
        assertThat(engine.run(harness.workspace, id).lifecycle()).isEqualTo(RunLifecycle.QUEUED);
    }

    @Test
    void claimLoadsOnlyCredentialReferencesFromTheRunsPinnedDeploymentRevision() {
        UUID id = harness.run();
        UUID revision = engine.run(harness.workspace, id).pins().revisionId();
        harness.store.deploymentConfiguration(revision,
                "{\"credentialReferences\":{\"calendar\":\"vault://workspaces/demo/calendar\"}}");
        harness.store.deploymentConfiguration(UUID.randomUUID(),
                "{\"credentialReferences\":{\"calendar\":\"vault://workspaces/other/calendar\"}}");
        engine.enqueue(harness.workspace, id, "system:test");

        DurableRunEngine.ClaimedRun claimed = harness.claim("worker-a");

        assertThat(claimed.credentialReferences()).hasSize(1);
        assertThat(claimed.credentialReferences().get("calendar").id())
                .isEqualTo("vault://workspaces/demo/calendar");
    }

    @Test
    void malformedPinnedCredentialReferencesRefuseTheClaimWithoutActivatingTheRun() {
        UUID id = harness.run();
        UUID revision = engine.run(harness.workspace, id).pins().revisionId();
        harness.store.deploymentConfiguration(revision,
                "{\"credentialReferences\":{\"calendar\":\"raw-secret-canary\"}}");
        engine.enqueue(harness.workspace, id, "system:test");

        assertThatThrownBy(() -> harness.claim("worker-a"))
                .isInstanceOfSatisfying(RunClaimRefusedException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("INVALID_DEPLOYMENT_CONFIGURATION"));
        assertThat(engine.run(harness.workspace, id).lifecycle()).isEqualTo(RunLifecycle.QUEUED);
        assertThat(engine.history(harness.workspace, id).toString()).doesNotContain("secret-canary");
    }

    @Test
    void everyLegalTransitionAppendsExactlyItsEventAndTheSummaryRebuildsFromHistory() {
        UUID id = harness.run();
        engine.enqueue(harness.workspace, id, "system:test");
        RunLease lease = harness.claim("worker-a").lease();
        engine.block(lease, RunBlockReason.HUMAN_INTERACTION, Map.of("interaction", "approve"));
        assertThat(engine.run(harness.workspace, id).summary().blockReason()).isEqualTo(RunBlockReason.HUMAN_INTERACTION);
        engine.resume(harness.workspace, id, "system:test");
        lease = harness.claim("worker-b").lease();
        engine.pause(lease, "operator");
        engine.resume(harness.workspace, id, "system:test");
        lease = harness.claim("worker-c").lease();
        RunRecord done = engine.complete(lease, Map.of("answer", 42));

        assertThat(types(id)).containsExactly("RUN_CREATED", "RUN_QUEUED", "RUN_ACTIVATED", "RUN_BLOCKED", "RUN_RESUMED",
                "RUN_ACTIVATED", "RUN_PAUSED", "RUN_RESUMED", "RUN_ACTIVATED", "RUN_SUCCEEDED");
        assertThat(done.lifecycle()).isEqualTo(RunLifecycle.SUCCEEDED);
        assertThat(done.summary().leaseFence()).isEqualTo(3);
        assertThat(done.control().leaseOwner()).isNull();
        var report = engine.verify(harness.workspace, id);
        assertThat(report.intact()).as(report.findings().toString()).isTrue();
        assertThat(report.rebuilt()).isEqualTo(done.summary());
    }

    @Test
    void illegalTransitionsAreRejectedWithoutWritingAnything() {
        UUID id = harness.run();
        long before = engine.history(harness.workspace, id).size();

        assertCode(() -> engine.resume(harness.workspace, id, "system:test"), "ILLEGAL_TRANSITION");
        engine.enqueue(harness.workspace, id, "system:test");
        assertThat(engine.enqueue(harness.workspace, id, "system:test").lifecycle()).isEqualTo(RunLifecycle.QUEUED);
        RunLease lease = harness.claim("worker").lease();
        engine.complete(lease, null);
        assertCode(() -> engine.enqueue(harness.workspace, id, "system:test"), "RUN_TERMINAL");
        assertCode(() -> engine.resume(harness.workspace, id, "system:test"), "RUN_TERMINAL");
        assertCode(() -> engine.cancel(harness.workspace, id, "system:test", null), "RUN_TERMINAL");
        assertCode(() -> engine.complete(lease, null), "STALE_LEASE");
        assertCode(() -> engine.append(lease, List.of(EventDraft.of(RunEventType.RUN_SUCCEEDED, Map.of()))),
                "INVALID_EVENT");
        assertThat(engine.history(harness.workspace, id)).hasSize((int) before + 3);
    }

    @Test
    void aStaleLeaseCannotWriteOnceAnotherWorkerRecoveredTheRun() {
        var first = harness.started("worker-a");
        harness.store.advance(EngineHarness.LEASE.plusSeconds(1));
        var second = harness.claim("worker-b");

        assertThat(second.recovered()).isTrue();
        assertThat(second.lease().fence()).isEqualTo(first.lease().fence() + 1);
        assertCode(() -> engine.append(first.lease(), List.of(model(1))), "STALE_LEASE");
        assertCode(() -> engine.checkpoint(first.lease(), Map.of()), "STALE_LEASE");
        assertCode(() -> engine.heartbeat(first.lease(), EngineHarness.LEASE), "STALE_LEASE");
        assertCode(() -> engine.complete(first.lease(), null), "STALE_LEASE");
        engine.complete(second.lease(), null);
        assertThat(types(first.run().id())).containsSubsequence("RUN_ACTIVATED", "RUN_RECOVERED", "RUN_SUCCEEDED")
                .doesNotContain("MODEL_RESPONSE_RECEIVED");
    }

    @Test
    void workerClockSkewCannotGrantOwnershipOrExtendAnExpiredLease() {
        var claimed = harness.started("worker-a");
        // The worker's own view of its expiry is advisory: forging it far into the future grants nothing.
        RunLease skewed = new RunLease(claimed.lease().workspace(), claimed.lease().runId(), claimed.lease().cohortId(),
                claimed.lease().owner(), claimed.lease().fence(), claimed.lease().rowVersion(),
                claimed.lease().leaseUntil().plus(Duration.ofDays(365)));
        harness.store.advance(EngineHarness.LEASE.plusMillis(1));

        assertCode(() -> engine.heartbeat(skewed, EngineHarness.LEASE), "STALE_LEASE");
        assertCode(() -> engine.append(skewed, List.of(model(1))), "STALE_LEASE");
        // Conversely, a worker that believes its lease expired still holds it while database time says so.
        engine.cancel(harness.workspace, claimed.run().id(), "system:test", null);
        var fresh = harness.started("worker-b");
        RunLease pessimistic = new RunLease(fresh.lease().workspace(), fresh.lease().runId(), fresh.lease().cohortId(),
                fresh.lease().owner(), fresh.lease().fence(), fresh.lease().rowVersion(), fresh.lease().leaseUntil()
                .minus(Duration.ofDays(365)));
        assertThat(engine.append(pessimistic, List.of(model(1))).ended()).isFalse();
    }

    @Test
    void limitsFailTheRunAtomicallyAndUsageNeverDecreases() {
        UUID id = harness.run(new RunLimits(null, 2L, null, null, new BigDecimal("1.00")), null);
        engine.enqueue(harness.workspace, id, "system:test");
        RunLease lease = harness.claim("worker").lease();
        var afterOne = engine.append(lease, List.of(model(1)));
        var afterTwo = engine.append(afterOne.lease(), List.of(model(1)));
        var exceeded = engine.append(afterTwo.lease(), List.of(model(1)));

        assertThat(exceeded.ended()).isTrue();
        assertThat(exceeded.run().lifecycle()).isEqualTo(RunLifecycle.FAILED);
        assertThat(exceeded.run().summary().terminalCode()).isEqualTo("LIMIT_EXCEEDED");
        assertThat(exceeded.run().summary().usage().modelCalls()).isEqualTo(3);
        assertThat(exceeded.run().summary().usage().totalTokens()).isEqualTo(3 * 30);
        assertThat(types(id)).containsSubsequence("LIMIT_WARNING", "LIMIT_EXCEEDED", "RUN_FAILED");
        assertThat(harness.store.raw().items.values()).allMatch(item -> item.state() == WorkItem.State.TERMINAL);
        assertThat(engine.verify(harness.workspace, id).rebuilt()).isEqualTo(exceeded.run().summary());
        assertCode(() -> engine.append(harness.started("other").lease(), List.of(EventDraft.of(
                RunEventType.MODEL_RESPONSE_RECEIVED, Map.of("usage", Map.of("inputTokens", -5)))))
                , "INVALID_PAYLOAD");
    }

    @Test
    void committedEffectsAreReplayedAndInDoubtEffectsAreRetriedWithTheSameExternalKey() {
        var first = harness.started("worker-a");
        Map<String, Integer> external = new java.util.HashMap<>();
        AtomicInteger invocations = new AtomicInteger();
        List<String> keys = new ArrayList<>();
        DurableRunEngine.EffectInvoker charge = (key, request) -> {
            invocations.incrementAndGet();
            keys.add(key);
            external.merge(key, 1, (a, b) -> a);
            return JsonNodeFactory.instance.objectNode().put("charged", request.path("amount").intValue());
        };
        // The first worker dies after the external system applied the effect but before the commit.
        assertThatThrownBy(() -> engine.executeEffect(first.lease(), "charge-42", Map.of("amount", 42), (key, request) -> {
            charge.invoke(key, request);
            throw new IllegalStateException("worker lost");
        })).isInstanceOf(EffectInDoubtException.class);
        assertThat(engine.effects(harness.workspace, first.run().id())).singleElement()
                .satisfies(effect -> assertThat(effect.state()).isEqualTo(RunEffect.State.PENDING));
        harness.store.advance(EngineHarness.LEASE.plusSeconds(1));
        var recovered = harness.claim("worker-b");

        var outcome = engine.executeEffect(recovered.lease(), "charge-42", Map.of("amount", 42), charge);
        var replay = engine.executeEffect(outcome.lease(), "charge-42", Map.of("amount", 42), charge);

        assertThat(outcome.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.result().path("charged").intValue()).isEqualTo(42);
        assertThat(invocations).hasValue(2);
        assertThat(keys).containsOnly(outcome.externalKey());
        assertThat(external).hasSize(1);
        assertCode(() -> engine.executeEffect(replay.lease(), "charge-42", Map.of("amount", 43), charge),
                "EFFECT_KEY_REUSED");
        assertThat(types(first.run().id())).containsSubsequence("OPERATION_STARTED", "RUN_RECOVERED",
                "OPERATION_PROGRESS", "OPERATION_COMPLETED");
        engine.complete(replay.lease(), null);
        assertThat(engine.verify(harness.workspace, first.run().id()).intact()).isTrue();
    }

    @Test
    void aDefiniteEffectFailureIsRecordedAndReplayedWithoutInvokingAgain() {
        var claimed = harness.started("worker");
        AtomicInteger invocations = new AtomicInteger();
        var failed = engine.executeEffect(claimed.lease(), "notify", Map.of(), (key, request) -> {
            invocations.incrementAndGet();
            throw new EffectFailedException("recipient rejected");
        });
        var replay = engine.executeEffect(failed.lease(), "notify", Map.of(), (key, request) -> {
            invocations.incrementAndGet();
            return null;
        });

        assertThat(failed.failed()).isTrue();
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.failure()).isEqualTo("recipient rejected");
        assertThat(invocations).hasValue(1);
        assertThat(types(claimed.run().id())).containsSubsequence("OPERATION_STARTED", "OPERATION_FAILED");
    }

    @Test
    void cancellationFencesTheActiveWorkerAndKeepsInDoubtEffectsExplicit() {
        var claimed = harness.started("worker");
        UUID id = claimed.run().id();
        RunLease afterCheckpoint = engine.checkpoint(claimed.lease(), Map.of("step", 1)).lease();
        assertThatThrownBy(() -> engine.executeEffect(afterCheckpoint, "deploy", Map.of(), (key, request) -> {
            throw new java.io.IOException("timeout");
        })).isInstanceOf(EffectInDoubtException.class);

        RunRecord cancelled = engine.cancel(harness.workspace, id, "operator:alice", "no longer needed");

        assertThat(cancelled.lifecycle()).isEqualTo(RunLifecycle.CANCELLED);
        assertThat(cancelled.summary().leaseFence()).isEqualTo(claimed.lease().fence() + 1);
        assertCode(() -> engine.complete(afterCheckpoint, null), "STALE_LEASE");
        assertThat(engine.cancel(harness.workspace, id, "operator:alice", null)).isEqualTo(cancelled);
        DecodedEvent event = engine.history(harness.workspace, id).getLast();
        assertThat(event.type()).isEqualTo(RunEventType.RUN_CANCELLED);
        assertThat(event.payload().path("inDoubtEffects").get(0).stringValue()).isEqualTo("deploy");
        assertThat(harness.store.raw().items.values()).filteredOn(item -> item.state() != WorkItem.State.TERMINAL)
                .singleElement().satisfies(item -> assertThat(item.category()).isEqualTo(WorkItem.Category.OPERATION));

        engine.resolveEffect(harness.workspace, id, "deploy", "operator:alice", "verified not applied");
        assertThat(harness.store.raw().items.values()).allMatch(item -> item.state() == WorkItem.State.TERMINAL);
        assertThat(engine.effects(harness.workspace, id).getFirst().state()).isEqualTo(RunEffect.State.ABANDONED);
        assertThat(engine.verify(harness.workspace, id).intact()).isTrue();
    }

    @Test
    void completionIsRefusedWhileAnEffectIsInDoubt() {
        var claimed = harness.started("worker");
        RunLease lease;
        try {
            engine.executeEffect(claimed.lease(), "charge", Map.of(), (key, request) -> {
                throw new java.io.IOException("unknown");
            });
            throw new AssertionError("expected in doubt");
        } catch (EffectInDoubtException inDoubt) {
            lease = inDoubt.lease();
        }
        RunLease current = lease;
        assertCode(() -> engine.complete(current, null), "EFFECT_IN_DOUBT");
        assertThat(engine.fail(current, "EFFECT_IN_DOUBT", "charge outcome unknown").lifecycle())
                .isEqualTo(RunLifecycle.FAILED);
    }

    @Test
    void aClaimRefusesHistoryItCannotInterpretAndChangesNothing() {
        UUID id = harness.run();
        engine.enqueue(harness.workspace, id, "system:test");
        List<RunEvent> history = harness.store.raw().events.get(id);
        RunEvent last = history.getLast();
        history.set(history.size() - 1, new RunEvent(last.id(), last.workspaceId(), last.runId(), last.sequence(),
                last.type(), last.schemaVersion(), "{\"tampered\":true}", last.payloadDigest(), last.eventDigest(),
                last.traceId(), last.spanId(), last.releaseId(), last.deploymentId(), last.revisionId(), last.cohortId(),
                last.actor(), last.leaseOwner(), last.leaseFence(), last.createdAt()));
        RunRecord before = harness.store.raw().runs.get(id);

        assertThatThrownBy(() -> harness.claim("worker")).isInstanceOfSatisfying(RunClaimRefusedException.class,
                refused -> {
                    assertThat(refused.runId()).isEqualTo(id);
                    assertThat(refused.code()).isEqualTo("CORRUPT_HISTORY");
                });
        assertThat(harness.store.raw().runs.get(id)).isEqualTo(before);
        assertThat(engine.verify(harness.workspace, id).intact()).isFalse();
        assertCode(() -> engine.repairSummary(harness.workspace, id), "CORRUPT_HISTORY");
    }

    @Test
    void interruptedTransactionsCommitNothing() {
        var claimed = harness.started("worker");
        UUID id = claimed.run().id();
        RunRecord before = engine.run(harness.workspace, id);
        var failing = new DurableRunEngine(new FailingStore(harness.store), RunEventCodec.standard(),
                new ReleaseManifestReader(), new LifecycleRetry(1));

        assertThatThrownBy(() -> failing.checkpoint(claimed.lease(), Map.of("step", 1)))
                .isInstanceOf(InjectedFailure.class);

        assertThat(engine.run(harness.workspace, id)).isEqualTo(before);
        assertThat(harness.store.raw().checkpoints.getOrDefault(id, List.of())).isEmpty();
        assertThat(engine.checkpoint(claimed.lease(), Map.of("step", 1)).run().summary().checkpointSequence()).isOne();
    }

    @Test
    void checkpointCarriesDurableArtifactAndExplicitSandboxRestoreEvidence() {
        var claimed = harness.started("worker");
        UUID artifact = UUID.randomUUID();
        UUID sandbox = UUID.randomUUID();
        harness.store.checkpointResources(claimed.run().id(), new RunTransaction.CheckpointResources(
                List.of(new RunTransaction.CheckpointArtifact(artifact, "sha256:" + "a".repeat(64),
                        "ACTIVE", "ACCEPTED")),
                List.of(new RunTransaction.CheckpointSandbox(sandbox, "docker", "ACTIVE", "RECONCILE_ONLY"))));

        engine.checkpoint(claimed.lease(), Map.of("cursor", 7));

        var payload = engine.codec().object(harness.store.raw().checkpoints.get(claimed.run().id()).getFirst()
                .payloadJson());
        assertThat(payload.path("artifacts").get(0).path("id").stringValue()).isEqualTo(artifact.toString());
        assertThat(payload.path("sandboxes").get(0).path("id").stringValue()).isEqualTo(sandbox.toString());
        assertThat(payload.path("sandboxes").get(0).path("restoreAssurance").stringValue())
                .isEqualTo("RECONCILE_ONLY");
        assertThat(payload.path("state").path("cursor").intValue()).isEqualTo(7);
        assertThat(payload.path("pins").path("cohortId").stringValue()).isEqualTo(harness.cohort.toString());
        assertThat(payload.path("pendingWork")).anySatisfy(item ->
                assertThat(item.path("category").stringValue()).isEqualTo("CLAIM"));
    }

    @Test
    void recoveryObserverMeasuresDatabaseTimeAfterTheExpiredLease() {
        java.util.concurrent.atomic.AtomicReference<Duration> delay = new java.util.concurrent.atomic.AtomicReference<>();
        RunEventCodec codec = RunEventCodec.standard();
        var observed = new DurableRunEngine(harness.store, codec, new ReleaseManifestReader(), new LifecycleRetry(2),
                DurableRunEngine.compatibilityOf(codec), EngineHarness.EXECUTORS, delay::set);
        UUID id = harness.run();
        observed.enqueue(harness.workspace, id, "system:test");
        observed.claimNext("worker-a", harness.scope(), EngineHarness.LEASE).orElseThrow();
        harness.store.advance(EngineHarness.LEASE.plusSeconds(3));

        assertThat(observed.claimNext("worker-b", harness.scope(), EngineHarness.LEASE).orElseThrow().recovered())
                .isTrue();
        assertThat(delay).hasValue(Duration.ofSeconds(3));
    }

    @Test
    void aStuckButHeartbeatingHandlerStillHitsItsRuntimeLimit() {
        UUID id = harness.run(new RunLimits(null, null, null, 1_000L, null), null);
        engine.enqueue(harness.workspace, id, "system:test");
        RunLease lease = harness.claim("worker").lease();
        harness.store.advance(Duration.ofMillis(600));
        RunLease renewed = engine.heartbeat(lease, EngineHarness.LEASE);
        harness.store.advance(Duration.ofMillis(600));

        assertCode(() -> engine.heartbeat(renewed, EngineHarness.LEASE), "LIMIT_EXCEEDED");

        RunRecord failed = engine.run(harness.workspace, id);
        assertThat(failed.lifecycle()).isEqualTo(RunLifecycle.FAILED);
        assertThat(failed.summary().terminalCode()).isEqualTo("LIMIT_EXCEEDED");
        assertThat(failed.summary().usage().runtimeMillis()).isEqualTo(1_200);
        assertThat(failed.control().leaseOwner()).isNull();
        assertCode(() -> engine.append(renewed, List.of(model(1))), "STALE_LEASE");
        assertThat(engine.verify(harness.workspace, id).intact()).isTrue();
    }

    // #175 review 2, item 1: no outcome other than failure is recorded after a limit, whatever the heartbeat deferred.
    @Test
    void completingBlockingOrPausingPastTheRuntimeLimitFailsTheRunInstead() {
        List<java.util.function.Consumer<RunLease>> releases = List.of(
                lease -> engine.complete(lease, Map.of("answer", 42)),
                lease -> engine.block(lease, RunBlockReason.HUMAN_INTERACTION, null),
                lease -> engine.pause(lease, "operator"));
        for (var release : releases) {
            UUID id = harness.run(new RunLimits(null, null, null, 100L, null), null);
            engine.enqueue(harness.workspace, id, "system:test");
            RunLease lease = harness.claim("worker").lease();
            harness.store.advance(Duration.ofMillis(300));

            assertCode(() -> release.accept(lease), "LIMIT_EXCEEDED");

            RunRecord failed = engine.run(harness.workspace, id);
            assertThat(failed.lifecycle()).isEqualTo(RunLifecycle.FAILED);
            assertThat(failed.summary().terminalCode()).isEqualTo("LIMIT_EXCEEDED");
            assertThat(failed.control().leaseOwner()).isNull();
            assertThat(engine.history(harness.workspace, id).stream().map(event -> event.type()).toList())
                    .endsWith(RunEventType.LIMIT_EXCEEDED, RunEventType.RUN_FAILED);
            assertThat(engine.verify(harness.workspace, id).intact()).isTrue();
        }
        // Within the limit the same writes succeed, and failing a run is never redirected.
        UUID within = harness.run(new RunLimits(null, null, null, 100L, null), null);
        engine.enqueue(harness.workspace, within, "system:test");
        assertThat(engine.complete(harness.claim("worker").lease(), null).lifecycle()).isEqualTo(RunLifecycle.SUCCEEDED);
        UUID failing = harness.run(new RunLimits(null, null, null, 100L, null), null);
        engine.enqueue(harness.workspace, failing, "system:test");
        RunLease lease = harness.claim("worker").lease();
        harness.store.advance(Duration.ofMillis(300));
        assertThat(engine.fail(lease, "HANDLER_FAILED", null).summary().terminalCode()).isEqualTo("HANDLER_FAILED");
    }

    // #175 review 2, item 1: an effect outcome recorded after the limit is kept, and the run fails right after it.
    @Test
    void anEffectOutcomeRecordedPastTheLimitIsKeptAndFailsTheRun() {
        UUID id = harness.run(new RunLimits(null, null, null, 100L, null), null);
        engine.enqueue(harness.workspace, id, "system:test");
        RunLease lease = harness.claim("worker").lease();

        assertCode(() -> engine.executeEffect(lease, "charge", Map.of("amount", 5), (key, request) -> {
            harness.store.advance(Duration.ofMillis(300));
            return JsonNodeFactory.instance.numberNode(7);
        }), "LIMIT_EXCEEDED");

        assertThat(engine.effects(harness.workspace, id)).singleElement()
                .satisfies(effect -> assertThat(effect.state()).isEqualTo(RunEffect.State.COMMITTED));
        RunRecord failed = engine.run(harness.workspace, id);
        assertThat(failed.summary().terminalCode()).isEqualTo("LIMIT_EXCEEDED");
        var types = engine.history(harness.workspace, id).stream().map(event -> event.type()).toList();
        assertThat(types).endsWith(RunEventType.OPERATION_COMPLETED, RunEventType.LIMIT_EXCEEDED, RunEventType.RUN_FAILED);
        assertThat(engine.history(harness.workspace, id).getLast().payload().path("inDoubtEffects").size()).isZero();
        assertThat(harness.store.raw().items.values()).allMatch(item -> item.state() == WorkItem.State.TERMINAL);
        assertThat(engine.verify(harness.workspace, id).intact()).isTrue();
    }

    // #175 review 2, item 7: a failure is never attributed to a run that only an earlier retry attempt selected.
    @Test
    void aClaimFailureIsAttributedOnlyToTheRunOfTheSameAttempt() {
        UUID id = harness.run();
        engine.enqueue(harness.workspace, id, "system:test");
        AtomicInteger attempts = new AtomicInteger();
        var flaky = new DurableRunEngine(new com.dvarahq.agentworks.domain.run.DurableRunStore() {
            @Override
            public <T> T inTransaction(com.dvarahq.agentworks.domain.tenant.WorkspaceId workspace,
                    java.util.function.Function<RunTransaction, T> work) {
                return harness.store.inTransaction(workspace, work);
            }

            @Override
            public <T> java.util.Optional<T> claimNext(com.dvarahq.agentworks.domain.run.ClaimScope scope,
                    java.util.function.BiFunction<RunRecord, RunTransaction, T> work) {
                if (attempts.incrementAndGet() == 1) {
                    // The first attempt fails inside the run's claim with a retryable lock failure...
                    return harness.store.claimNext(scope, (run, tx) -> work.apply(run,
                            (RunTransaction) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                                    new Class<?>[]{RunTransaction.class}, (proxy, method, args) -> {
                                        if (method.getName().equals("updateRun")) {
                                            throw new org.springframework.dao.CannotAcquireLockException("lock timeout");
                                        }
                                        try {
                                            return method.invoke(tx, args);
                                        } catch (java.lang.reflect.InvocationTargetException thrown) {
                                            throw thrown.getCause();
                                        }
                                    })));
                }
                // ...and the retry fails before it selects any run.
                throw new org.springframework.dao.DataIntegrityViolationException("candidate query failed");
            }
        }, RunEventCodec.standard(), new ReleaseManifestReader(), new LifecycleRetry(2));
        java.util.concurrent.atomic.AtomicReference<DurableRunEngine.FailedClaim> failed =
                new java.util.concurrent.atomic.AtomicReference<>();

        assertThatThrownBy(() -> flaky.claimNext("worker", harness.scope(), EngineHarness.LEASE, failed::set))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        assertThat(attempts).hasValue(2);
        assertThat(failed.get()).isNull();
    }

    // #179 medium 2, refuted: at most one effect of a lease holder is ever in flight. A second effect cannot start
    // while one is in flight (the holder's ordered write token is stale), so no second outcome exists to orphan.
    @Test
    void aSecondEffectCannotStartWhileOneIsInFlight() throws Exception {
        var claimed = harness.started("worker");
        java.util.concurrent.CountDownLatch inFlight = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch finish = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Object> first = new java.util.concurrent.atomic.AtomicReference<>();
        Thread thread = Thread.ofPlatform().start(() -> {
            try {
                first.set(engine.executeEffect(claimed.lease(), "first", Map.of(), (key, request) -> {
                    inFlight.countDown();
                    finish.await(5, java.util.concurrent.TimeUnit.SECONDS);
                    return JsonNodeFactory.instance.numberNode(1);
                }));
            } catch (RuntimeException failed) {
                first.set(failed);
            }
        });
        assertThat(inFlight.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        assertCode(() -> engine.executeEffect(claimed.lease(), "second", Map.of(),
                (key, request) -> JsonNodeFactory.instance.numberNode(2)), "STALE_LEASE");

        finish.countDown();
        thread.join(5_000);
        assertThat(first.get()).isInstanceOf(DurableRunEngine.EffectOutcome.class);
        assertThat(engine.effects(harness.workspace, claimed.run().id())).singleElement()
                .satisfies(effect -> assertThat(effect.effectKey()).isEqualTo("first"));
    }

    @Test
    void workerPayloadsCannotCarryEngineSummaryFacts() {
        var claimed = harness.started("worker");
        for (String reserved : List.of("runtimeMillis", "fence", "checkpointSequence")) {
            assertCode(() -> engine.append(claimed.lease(), List.of(EventDraft.of(RunEventType.TOOL_COMPLETED,
                    Map.of("stepId", "t-1", reserved, 250)))), "INVALID_EVENT");
        }
        // Nested durations are ordinary data and leave the summary untouched.
        var written = engine.append(claimed.lease(), List.of(EventDraft.of(RunEventType.TOOL_COMPLETED,
                Map.of("stepId", "t-1", "timing", Map.of("runtimeMillis", 250)))));
        assertThat(written.run().summary().usage().runtimeMillis()).isZero();
        assertThat(engine.verify(harness.workspace, claimed.run().id()).intact()).isTrue();
    }

    @Test
    void nonActiveAndUnrecoverableRunsCanBeFailedWithoutALease() {
        UUID created = harness.run();
        UUID queued = harness.run();
        engine.enqueue(harness.workspace, queued, "system:test");
        RunRecord failedQueued = engine.failRun(harness.workspace, queued, "UNINTERPRETABLE", "operator:ops", "no node");
        RunRecord failedCreated = engine.failRun(harness.workspace, created, "ABANDONED", "operator:ops", null);
        var active = harness.started("worker");
        RunRecord failedActive = engine.failRun(harness.workspace, active.run().id(), "STUCK", "operator:ops", null);

        assertThat(List.of(failedQueued, failedCreated, failedActive)).allSatisfy(run -> {
            assertThat(run.lifecycle()).isEqualTo(RunLifecycle.FAILED);
            assertThat(engine.verify(harness.workspace, run.id()).intact()).isTrue();
        });
        assertThat(failedQueued.summary().terminalCode()).isEqualTo("UNINTERPRETABLE");
        assertThat(failedActive.summary().leaseFence()).isEqualTo(active.lease().fence() + 1);
        assertCode(() -> engine.complete(active.lease(), null), "STALE_LEASE");
        assertCode(() -> engine.failRun(harness.workspace, queued, "AGAIN", "operator:ops", null), "RUN_TERMINAL");
        assertCode(() -> engine.failRun(harness.workspace, created, "bad code", "operator:ops", null), "INVALID_EVENT");
    }

    @Test
    void resumingAnActiveRunIsRefusedSoARacingCompletionIsNotLost() {
        var claimed = harness.started("worker");
        assertCode(() -> engine.resume(harness.workspace, claimed.run().id(), "system:interaction"), "RUN_ACTIVE");
        engine.block(claimed.lease(), RunBlockReason.HUMAN_INTERACTION, null);
        assertThat(engine.resume(harness.workspace, claimed.run().id(), "system:interaction").lifecycle())
                .isEqualTo(RunLifecycle.QUEUED);
    }

    @Test
    void transientFailuresRetryBoundedAndExhaustionCommitsNothing() {
        var claimed = harness.started("worker");
        RunRecord before = engine.run(harness.workspace, claimed.run().id());
        AtomicInteger attempts = new AtomicInteger();
        var contended = new com.dvarahq.agentworks.domain.run.DurableRunStore() {
            @Override
            public <T> T inTransaction(com.dvarahq.agentworks.domain.tenant.WorkspaceId workspace,
                    java.util.function.Function<RunTransaction, T> work) {
                return harness.store.inTransaction(workspace, tx -> {
                    work.apply(tx);
                    attempts.incrementAndGet();
                    throw new org.springframework.dao.CannotAcquireLockException("lock timeout before commit");
                });
            }

            @Override
            public <T> java.util.Optional<T> claimNext(com.dvarahq.agentworks.domain.run.ClaimScope scope,
                    java.util.function.BiFunction<RunRecord, RunTransaction, T> work) {
                return harness.store.claimNext(scope, work);
            }
        };
        var retrying = new DurableRunEngine(contended, RunEventCodec.standard(), new ReleaseManifestReader(),
                new LifecycleRetry(3));

        assertThatThrownBy(() -> retrying.checkpoint(claimed.lease(), Map.of("step", 1)))
                .isInstanceOf(com.dvarahq.agentworks.storage.lifecycle.LifecycleRetryExhaustedException.class);

        assertThat(attempts).hasValue(3);
        assertThat(engine.run(harness.workspace, claimed.run().id())).isEqualTo(before);
        assertThat(harness.store.raw().checkpoints.getOrDefault(claimed.run().id(), List.of())).isEmpty();
    }

    @Test
    void pinsNeverChangeAcrossTheLifecycle() {
        UUID id = harness.run();
        RunPins pins = engine.run(harness.workspace, id).pins();
        engine.enqueue(harness.workspace, id, "system:test");
        var first = harness.claim("worker-a");
        var written = engine.checkpoint(first.lease(), Map.of());
        engine.block(written.lease(), RunBlockReason.CHILD_RUN, null);
        engine.resume(harness.workspace, id, "system:test");
        harness.claim("worker-b");
        harness.store.advance(EngineHarness.LEASE.plusSeconds(1));
        harness.claim("worker-c");
        engine.cancel(harness.workspace, id, "system:test", null);

        assertThat(engine.run(harness.workspace, id).pins()).isEqualTo(pins);
        assertThat(engine.history(harness.workspace, id)).allSatisfy(event -> {
            assertThat(event.raw().releaseId()).isEqualTo(pins.releaseId());
            assertThat(event.raw().revisionId()).isEqualTo(pins.revisionId());
            assertThat(event.raw().cohortId()).isEqualTo(pins.cohortId());
            assertThat(event.raw().traceId()).isEqualTo(pins.traceId());
        });
    }

    @Test
    void blockedAndPausedRunsHoldNoLeaseAndAreNotClaimable() {
        var claimed = harness.started("worker");
        RunRecord blocked = engine.block(claimed.lease(), RunBlockReason.DURABLE_OPERATION, null);

        assertThat(blocked.control().leaseOwner()).isNull();
        assertThat(blocked.control().leaseUntil()).isNull();
        assertThat(engine.claimNext("worker", harness.scope(), EngineHarness.LEASE)).isEmpty();
        assertThat(harness.store.raw().items.values()).allMatch(item -> item.state() == WorkItem.State.TERMINAL);
    }

    private List<String> types(UUID id) {
        return engine.history(harness.workspace, id).stream().map(event -> event.type().name()).toList();
    }

    static EventDraft model(int calls) {
        return EventDraft.of(RunEventType.MODEL_RESPONSE_RECEIVED, Map.of("stepId", UUID.randomUUID().toString(),
                "usage", Map.of("inputTokens", 20 * calls, "outputTokens", 10 * calls, "modelCalls", calls,
                        "estimatedCostUsd", "0.010000")));
    }

    static void assertCode(Runnable call, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(RunEngineException.class,
                failure -> assertThat(failure.code()).isEqualTo(code));
    }

    private static final class InjectedFailure extends RuntimeException {}

    /** Fails a transaction after its events were appended, before anything else commits. */
    private static final class FailingStore implements com.dvarahq.agentworks.domain.run.DurableRunStore {
        private final InMemoryDurableRunStore delegate;

        private FailingStore(InMemoryDurableRunStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public <T> T inTransaction(com.dvarahq.agentworks.domain.tenant.WorkspaceId workspace,
                java.util.function.Function<RunTransaction, T> work) {
            return delegate.inTransaction(workspace, tx -> work.apply((RunTransaction) java.lang.reflect.Proxy.newProxyInstance(
                    RunTransaction.class.getClassLoader(), new Class<?>[]{RunTransaction.class}, (proxy, method, args) -> {
                        Object result = method.invoke(tx, args);
                        if (method.getName().equals("appendEvents")) throw new InjectedFailure();
                        return result;
                    })));
        }

        @Override
        public <T> java.util.Optional<T> claimNext(com.dvarahq.agentworks.domain.run.ClaimScope scope,
                java.util.function.BiFunction<com.dvarahq.agentworks.domain.run.RunRecord, RunTransaction, T> work) {
            return delegate.claimNext(scope, work);
        }
    }
}
