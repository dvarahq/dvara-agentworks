package com.dvarahq.agentworks.runplane.execution;

import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleFixture;
import com.dvarahq.agentworks.domain.run.ClaimScope;
import com.dvarahq.agentworks.domain.run.DurableRunStore;
import com.dvarahq.agentworks.domain.run.RunBlockReason;
import com.dvarahq.agentworks.domain.run.RunEffect;
import com.dvarahq.agentworks.domain.run.RunEvent;
import com.dvarahq.agentworks.domain.run.RunEventType;
import com.dvarahq.agentworks.domain.run.RunLifecycle;
import com.dvarahq.agentworks.domain.run.RunRecord;
import com.dvarahq.agentworks.domain.run.RunTransaction;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import com.dvarahq.agentworks.runplane.execution.DurableRunEngine.EventDraft;
import com.dvarahq.agentworks.storage.lifecycle.LifecycleRetry;
import com.dvarahq.agentworks.storage.run.JdbcDurableRunStore;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Function;

import static com.dvarahq.agentworks.runplane.execution.DurableRunLifecyclePostgresTest.assertCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Recovery after real worker termination, interrupted transactions, duplicate delivery, and history integrity. */
class RunRecoveryPostgresTest {
    private final RunScenario scenario = new RunScenario();
    private final DurableRunEngine engine = scenario.engine;

    @Test
    void aKilledWorkerProcessRecoversFromItsCheckpointWithoutRepeatingTheEffect() throws Exception {
        scenario.fixture.jdbc.execute("create table if not exists external_charge(external_key varchar(64) primary key, "
                + "amount integer not null, applied_at timestamp with time zone default clock_timestamp())");
        RunRecord run = scenario.submit(Map.of("order", "A-1"));
        var database = LifecycleFixture.postgresDatabase();

        Process worker = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp",
                System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                CrashWorkerMain.class.getName(), database.jdbcUrl(), database.username(), database.password(),
                scenario.workspace.value().toString(), run.id().toString()).redirectErrorStream(true).start();
        assertThat(worker.waitFor(60, TimeUnit.SECONDS)).isTrue();
        assertThat(worker.exitValue()).as(new String(worker.getInputStream().readAllBytes())).isEqualTo(CrashWorkerMain.EXIT);

        // The dead worker left its checkpoint, a pending effect it had applied externally, and an unreleased lease.
        RunRecord orphaned = engine.run(scenario.workspace, run.id());
        assertThat(orphaned.lifecycle()).isEqualTo(RunLifecycle.ACTIVE);
        assertThat(orphaned.control().leaseOwner()).isEqualTo("crash-worker");
        assertThat(orphaned.summary().checkpointSequence()).isOne();
        assertThat(engine.effects(scenario.workspace, run.id())).singleElement()
                .satisfies(effect -> assertThat(effect.state()).isEqualTo(RunEffect.State.PENDING));
        assertThat(scenario.count("select count(*) from external_charge")).isOne();

        scenario.expireLease(run.id());
        AtomicInteger restored = new AtomicInteger();
        try (var recovery = new RunWorker(engine, context -> {
            restored.set(context.checkpointState().orElseThrow().path("step").intValue());
            assertThat(context.recovered()).isTrue();
            var outcome = context.effect("charge-42", Map.of("amount", 42), (externalKey, request) -> {
                scenario.fixture.jdbc.update("insert into external_charge(external_key, amount) values (?, ?) "
                        + "on conflict (external_key) do nothing", externalKey, 42);
                return JsonNodeFactory.instance.objectNode().put("charged", 42);
            });
            return RunOutcome.completed(Map.of("receipt", outcome.result()));
        }, "recovery-worker", scenario.scope().excluding(others(run.id())), RunScenario.LEASE)) {
            assertThat(recovery.pollOnce()).isEqualTo(RunWorker.Poll.EXECUTED);
        }

        RunRecord done = engine.run(scenario.workspace, run.id());
        assertThat(done.lifecycle()).isEqualTo(RunLifecycle.SUCCEEDED);
        assertThat(restored).hasValue(1);
        assertThat(scenario.count("select count(*) from external_charge")).isOne();
        assertThat(engine.effects(scenario.workspace, run.id())).singleElement().satisfies(effect -> {
            assertThat(effect.state()).isEqualTo(RunEffect.State.COMMITTED);
            assertThat(effect.attempts()).isEqualTo(2);
        });
        assertThat(engine.history(scenario.workspace, run.id())).extracting(event -> event.type().name())
                .containsSubsequence("RUN_ACTIVATED", "CHECKPOINT_CREATED", "OPERATION_STARTED", "RUN_RECOVERED",
                        "CHECKPOINT_RESTORED", "OPERATION_PROGRESS", "OPERATION_COMPLETED", "RUN_SUCCEEDED");
        assertThat(done.summary().usage().runtimeMillis()).isPositive();
        assertThat(engine.verify(scenario.workspace, run.id()).intact()).isTrue();
    }

    @Test
    void anInterruptedTransactionCommitsNoEventCheckpointOrSummaryChange() {
        var claimed = scenario.started("worker", RunScenario.LEASE);
        RunRecord before = engine.run(scenario.workspace, claimed.run().id());
        var interrupted = new DurableRunEngine(new InterruptingStore(scenario.fixture.runStore), RunEventCodec.standard(),
                new ReleaseManifestReader(), LifecycleRetry.standard());

        assertThatThrownBy(() -> interrupted.checkpoint(claimed.lease(), Map.of("step", 1)))
                .isInstanceOf(InjectedInterruption.class);

        assertThat(engine.run(scenario.workspace, claimed.run().id())).isEqualTo(before);
        assertThat(scenario.count("select count(*) from agent_run_checkpoint where run_id = ?", claimed.run().id())).isZero();
        assertThat(scenario.count("select count(*) from agent_run_event where run_id = ?", claimed.run().id()))
                .isEqualTo(before.summary().eventSequence());
        assertThat(engine.checkpoint(claimed.lease(), Map.of("step", 1)).run().summary().checkpointSequence()).isOne();
    }

    @Test
    void duplicateDeliveryReplaysACommittedEffectInsteadOfRepeatingIt() {
        var first = scenario.started("worker-a", RunScenario.LEASE);
        AtomicInteger applied = new AtomicInteger();
        DurableRunEngine.EffectInvoker send = (key, request) -> {
            applied.incrementAndGet();
            return JsonNodeFactory.instance.objectNode().put("messageId", "m-1");
        };
        engine.executeEffect(first.lease(), "send-invoice", Map.of("invoice", 7), send);
        scenario.expireLease(first.run().id());
        var redelivered = scenario.claim("worker-b");

        var outcome = engine.executeEffect(redelivered.lease(), "send-invoice", Map.of("invoice", 7), send);

        assertThat(outcome.replayed()).isTrue();
        assertThat(outcome.result().path("messageId").stringValue()).isEqualTo("m-1");
        assertThat(applied).hasValue(1);
        assertCode(() -> engine.executeEffect(outcome.lease(), "send-invoice", Map.of("invoice", 8), send),
                "EFFECT_KEY_REUSED");
        assertThat(scenario.count("select count(*) from agent_run_event where run_id = ? and event_type in "
                + "('OPERATION_STARTED', 'OPERATION_COMPLETED')", first.run().id())).isEqualTo(2);
        // The database refuses a second outcome for the same effect key.
        assertThatThrownBy(() -> scenario.fixture.asRole("agentworks_run_plane", "update agent_run_effect set "
                + "state = 'COMMITTED' where run_id = ?", first.run().id())).hasStackTraceContaining("IMMUTABLE_HISTORY");
    }

    @Test
    void aRestartedEngineResumesABlockedRunFromItsLatestCheckpoint() {
        var claimed = scenario.started("worker", RunScenario.LEASE);
        var first = engine.checkpoint(claimed.lease(), Map.of("step", 1));
        var second = engine.checkpoint(first.lease(), Map.of("step", 2, "notes", List.of("a", "b")));
        engine.block(second.lease(), RunBlockReason.DURABLE_OPERATION, Map.of("operation", "ci"));

        var restarted = new DurableRunEngine(new JdbcDurableRunStore(scenario.fixture.jdbc, scenario.fixture.transactions),
                RunEventCodec.standard(), new ReleaseManifestReader(), LifecycleRetry.standard());
        restarted.resume(scenario.workspace, claimed.run().id(), "system:operation-completed");
        var resumed = restarted.claimNext("worker-2", scenario.scope().excluding(others(claimed.run().id())),
                RunScenario.LEASE).orElseThrow();

        assertThat(resumed.recovered()).isFalse();
        assertThat(resumed.checkpoint()).hasValueSatisfying(checkpoint -> {
            assertThat(checkpoint.sequence()).isEqualTo(2);
            assertThat(checkpoint.state().path("step").intValue()).isEqualTo(2);
            assertThat(checkpoint.state().path("notes").get(1).stringValue()).isEqualTo("b");
        });
        assertThat(resumed.input().path("task").stringValue()).isEqualTo("test");
        assertThat(restarted.history(scenario.workspace, claimed.run().id()).getLast().type())
                .isEqualTo(RunEventType.CHECKPOINT_RESTORED);
    }

    @Test
    void unsupportedEventVersionsAreQuarantinedWithoutChangingTheRun() {
        RunRecord run = scenario.submit(null);
        appendRaw(run, "MODEL_RESPONSE_RECEIVED", 99, "{\"model\":\"future\"}");
        RunRecord before = engine.run(scenario.workspace, run.id());

        try (var worker = new RunWorker(engine, context -> RunOutcome.completed(null), "worker",
                scenario.scope().excluding(others(run.id())), RunScenario.LEASE)) {
            assertThat(worker.pollOnce()).isEqualTo(RunWorker.Poll.REFUSED);
            // A version this node cannot read is quarantined durably, for a time only: an upgraded node may read it.
            assertThat(scenario.quarantines()).singleElement().satisfies(quarantine -> {
                assertThat(quarantine.runId()).isEqualTo(run.id());
                assertThat(quarantine.code()).isEqualTo("UNSUPPORTED_EVENT_VERSION");
                assertThat(quarantine.indefinite()).isFalse();
            });
            assertThat(worker.pollOnce()).isEqualTo(RunWorker.Poll.IDLE);
        }
        assertThat(engine.run(scenario.workspace, run.id())).isEqualTo(before);
        assertCode(() -> engine.history(scenario.workspace, run.id()), "UNSUPPORTED_EVENT_VERSION");
    }

    @Test
    void olderEventVersionsStoredInJsonbAreUpcastOnRead() {
        var claimed = scenario.started("worker", RunScenario.LEASE);
        engine.complete(engine.append(claimed.lease(), List.of(EventDraft.of(RunEventType.MODEL_RESPONSE_RECEIVED,
                Map.of("stepId", "m-1", "model", "coding-premium", "latencyMillis", 1.50)))).lease(), null);
        EventSchemas upgraded = EventSchemas.builder().current(RunEventType.MODEL_RESPONSE_RECEIVED, 2)
                .upcaster(RunEventType.MODEL_RESPONSE_RECEIVED, 1, payload -> payload.put("finishReason", "UNKNOWN"))
                .build();
        var reader = new DurableRunEngine(scenario.fixture.runStore, new RunEventCodec(upgraded),
                new ReleaseManifestReader(), LifecycleRetry.standard());

        var event = reader.history(scenario.workspace, claimed.run().id()).stream()
                .filter(decoded -> decoded.type() == RunEventType.MODEL_RESPONSE_RECEIVED).findFirst().orElseThrow();

        assertThat(event.raw().schemaVersion()).isOne();
        assertThat(event.payload().path("finishReason").stringValue()).isEqualTo("UNKNOWN");
        assertThat(event.payload().path("latencyMillis").decimalValue()).isEqualByComparingTo("1.5");
        assertThat(reader.verify(scenario.workspace, claimed.run().id()).intact()).isTrue();
    }

    @Test
    void corruptOrMissingHistoryIsDetectedAndNeverRepairedByInventingEvents() {
        var claimed = scenario.started("worker", RunScenario.LEASE);
        UUID id = claimed.run().id();
        engine.append(claimed.lease(), List.of(EventDraft.of(RunEventType.MODEL_RESPONSE_RECEIVED,
                Map.of("usage", Map.of("inputTokens", 10, "outputTokens", 5, "modelCalls", 1)))));

        // A summary tampered around the fence is detected and restored from intact history.
        scenario.fixture.bypassFence("update agent_run set input_tokens = 0, total_tokens = output_tokens where id = ?", id);
        assertThat(engine.verify(scenario.workspace, id).findings()).containsExactly("SUMMARY_MISMATCH: usage");
        assertThat(engine.repairSummary(scenario.workspace, id).summary().usage().inputTokens()).isEqualTo(10);
        assertThat(engine.verify(scenario.workspace, id).intact()).isTrue();
        // A repair that would lower a counter is refused by the database instead of silently accepted.
        scenario.fixture.bypassFence("update agent_run set input_tokens = 999, total_tokens = 999 + output_tokens "
                + "where id = ?", id);
        assertCode(() -> engine.repairSummary(scenario.workspace, id), "SUMMARY_REGRESSION");

        // Tampered and missing history are reported, refuse execution, and are never papered over.
        long events = scenario.count("select count(*) from agent_run_event where run_id = ?", id);
        scenario.fixture.bypassFence("update agent_run_event set payload_json = '{\"forged\":true}'::jsonb "
                + "where run_id = ? and sequence = 2", id);
        assertThat(engine.verify(scenario.workspace, id).findings()).anyMatch(finding -> finding.startsWith("CORRUPT_HISTORY"));
        assertCode(() -> engine.repairSummary(scenario.workspace, id), "CORRUPT_HISTORY");
        scenario.fixture.bypassFence("delete from agent_run_event where run_id = ? and sequence = 3", id);
        assertThat(engine.verify(scenario.workspace, id).intact()).isFalse();
        assertThat(scenario.count("select count(*) from agent_run_event where run_id = ?", id)).isEqualTo(events - 1);
        scenario.fixture.bypassFence("update agent_run set lease_until = clock_timestamp() - interval '1 second' "
                + "where id = ?", id);
        assertThatThrownBy(() -> engine.claimNext("worker-2", scenario.scope().excluding(others(id)), RunScenario.LEASE))
                .isInstanceOfSatisfying(RunClaimRefusedException.class,
                        refused -> assertThat(refused.code()).isEqualTo("CORRUPT_HISTORY"));
    }

    @Test
    void runsAdmittedBeforeTheEngineAreNeverClaimedOrQueuedButCanBeCancelled() {
        RunRecord template = scenario.submit(null);
        UUID legacy = UUID.randomUUID();
        scenario.fixture.bypassFence("insert into agent_run(workspace_id, id, definition_id, version_id, release_id, "
                + "deployment_id, environment_id, revision_id, cohort_id, admission, routing_generation_id, "
                + "routing_target_ordinal, root_run_id, lifecycle, created_at) select workspace_id, ?, definition_id, "
                + "version_id, release_id, deployment_id, environment_id, revision_id, cohort_id, 'ROOT', "
                + "routing_generation_id, routing_target_ordinal, ?, 'QUEUED', current_timestamp from agent_run where id = ?",
                legacy, legacy, template.id());

        assertThat(engine.claimNext("worker", scenario.scope().excluding(Set.of(template.id())), RunScenario.LEASE))
                .isEmpty();
        RunRecord cancelled = engine.cancel(scenario.workspace, legacy, "operator:ops", "retire legacy run");
        assertThat(cancelled.lifecycle()).isEqualTo(RunLifecycle.CANCELLED);
        assertThat(engine.verify(scenario.workspace, legacy).intact()).isTrue();
    }

    private Set<UUID> others(UUID runId) {
        return new HashSet<>(scenario.fixture.jdbc.queryForList("select id from agent_run where workspace_id = ? "
                + "and id <> ?", UUID.class, scenario.workspace.value(), runId));
    }

    /** Appends a correctly chained event of any type and version outside the engine, as a newer node would. */
    private void appendRaw(RunRecord run, String type, int version, String payload) {
        RunRecord current = engine.run(scenario.workspace, run.id());
        long sequence = current.summary().eventSequence() + 1;
        var now = java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        String canonical = engine.codec().canonicalPayload(payload);
        RunEvent unsigned = new RunEvent(UUID.randomUUID(), scenario.workspace.value(), run.id(), sequence, type, version,
                canonical, RunEventCodec.sha256(canonical), RunEventCodec.GENESIS, run.pins().traceId(), null,
                run.pins().releaseId(), run.pins().deploymentId(), run.pins().revisionId(), run.pins().cohortId(),
                "system:newer-node", null, current.summary().leaseFence(), now);
        String digest = RunEventCodec.eventDigest(current.summary().lastEventDigest(), unsigned);
        scenario.fixture.bypassFence("insert into agent_run_event(workspace_id, run_id, sequence, id, event_type, "
                + "schema_version, payload_json, payload_digest, event_digest, trace_id, release_id, deployment_id, "
                + "revision_id, cohort_id, actor, lease_fence, created_at) values (?, ?, ?, ?, ?, ?, cast(? as jsonb), ?, ?, "
                + "?, ?, ?, ?, ?, ?, ?, ?)", scenario.workspace.value(), run.id(), sequence, unsigned.id(), type, version,
                canonical, unsigned.payloadDigest(), digest, run.pins().traceId(), run.pins().releaseId(),
                run.pins().deploymentId(), run.pins().revisionId(), run.pins().cohortId(), unsigned.actor(),
                unsigned.leaseFence(), java.time.OffsetDateTime.ofInstant(now, java.time.ZoneOffset.UTC));
        scenario.fixture.bypassFence("update agent_run set event_sequence = ?, last_event_digest = ? where id = ?",
                sequence, digest, run.id());
    }

    private static final class InjectedInterruption extends RuntimeException {}

    /** Interrupts a transaction after its events and checkpoint were written, before it commits. */
    private record InterruptingStore(DurableRunStore delegate) implements DurableRunStore {
        @Override
        public java.util.Optional<com.dvarahq.agentworks.domain.run.ClaimQuarantine> quarantine(java.util.UUID runId,
                com.dvarahq.agentworks.domain.run.ClaimQuarantine.Request request) {
            return delegate.quarantine(runId, request);
        }

        @Override
        public boolean keepsClaimQuarantine() {
            return delegate.keepsClaimQuarantine();
        }
        @Override
        public <T> T inTransaction(WorkspaceId workspace, Function<RunTransaction, T> work) {
            return delegate.inTransaction(workspace, tx -> work.apply((RunTransaction) Proxy.newProxyInstance(
                    RunTransaction.class.getClassLoader(), new Class<?>[]{RunTransaction.class}, (proxy, method, args) -> {
                        Object result = method.invoke(tx, args);
                        if (method.getName().equals("insertCheckpoint")) throw new InjectedInterruption();
                        return result;
                    })));
        }

        @Override
        public <T> Optional<T> claimNext(ClaimScope scope, BiFunction<RunRecord, RunTransaction, T> work) {
            return delegate.claimNext(scope, work);
        }
    }
}
