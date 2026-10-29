package com.dvarahq.agentworks.runplane.execution;

import com.dvarahq.agentworks.domain.run.AgentStep;
import com.dvarahq.agentworks.domain.run.DurableRunStore;
import com.dvarahq.agentworks.domain.run.RunBlockReason;
import com.dvarahq.agentworks.domain.run.RunEventType;
import com.dvarahq.agentworks.domain.run.RunLease;
import com.dvarahq.agentworks.domain.run.RunLifecycle;
import com.dvarahq.agentworks.domain.run.RunLimits;
import com.dvarahq.agentworks.domain.run.RunPins;
import com.dvarahq.agentworks.domain.run.RunRecord;
import com.dvarahq.agentworks.domain.run.RunTransaction;
import com.dvarahq.agentworks.domain.run.StepCategory;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleFixture;
import com.dvarahq.agentworks.runplane.admission.RunAdmissionService;
import com.dvarahq.agentworks.runplane.execution.DurableRunEngine.EventDraft;
import com.dvarahq.agentworks.storage.lifecycle.LifecycleRetry;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;

import static com.dvarahq.agentworks.runplane.execution.RunScenario.race;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Run creation, the state machine, events, the summary projection, steps, usage, and cancellation on PostgreSQL. */
class DurableRunLifecyclePostgresTest {
    private final RunScenario scenario = new RunScenario();
    private final DurableRunEngine engine = scenario.engine;

    @Test
    void runCreationAtomicallyPinsIdentitiesTraceAndItsCreationEvent() {
        String trace = "4bf92f3577b34da6a3ce929d0e0e4736";
        var admitted = scenario.fixture.admissions.admitRoot(scenario.workspace, scenario.promotion.deploymentId(), null,
                Map.of("question", "hello"), new RunAdmissionService.Options(true, trace));
        RunRecord run = engine.run(scenario.workspace, admitted.run().id());

        assertThat(run.lifecycle()).isEqualTo(RunLifecycle.QUEUED);
        assertThat(run.pins().traceId()).isEqualTo(trace);
        // #33's release service writes manifest version 2 with the resolved reasoning model, and admission reads it.
        assertThat(run.pins().manifestVersion()).isEqualTo(com.dvarahq.agentworks.controlplane.lifecycle.ReleaseService.MANIFEST_VERSION);
        assertThat(run.pins().revisionId()).isEqualTo(scenario.promotion.revisionId());
        assertThat(run.pins().cohortId()).isEqualTo(scenario.promotion.cohortId());
        assertThat(run.pins().routingGenerationId()).isEqualTo(scenario.promotion.routingGenerationId());
        assertThat(run.pins().environmentId()).isEqualTo(scenario.environment.id());
        var history = engine.history(scenario.workspace, run.id());
        assertThat(history).extracting(event -> event.type().name()).containsExactly("RUN_CREATED", "RUN_QUEUED");
        var created = history.getFirst();
        assertThat(created.payload().path("releaseId").stringValue()).isEqualTo(run.pins().releaseId().toString());
        assertThat(created.payload().path("revisionId").stringValue()).isEqualTo(run.pins().revisionId().toString());
        assertThat(created.payload().path("versionId").stringValue()).isEqualTo(run.pins().versionId().toString());
        assertThat(created.payload().path("input").path("question").stringValue()).isEqualTo("hello");
        assertThat(history).allSatisfy(event -> {
            assertThat(event.raw().traceId()).isEqualTo(trace);
            assertThat(event.raw().releaseId()).isEqualTo(run.pins().releaseId());
            assertThat(event.raw().deploymentId()).isEqualTo(run.pins().deploymentId());
            assertThat(event.raw().revisionId()).isEqualTo(run.pins().revisionId());
            assertThat(event.raw().cohortId()).isEqualTo(run.pins().cohortId());
        });

        // A creation that fails after the row insert commits neither the run nor any event.
        DurableRunStore failing = new FailingHistory(scenario.fixture.runStore);
        var broken = new RunAdmissionService(scenario.fixture.tenants, scenario.fixture.lifecycle,
                scenario.fixture.runRecords, scenario.fixture.clock, LifecycleRetry.standard(), null, failing, engine,
                RunLimitsPolicy.fixed(RunLimits.UNBOUNDED));
        long runs = scenario.count("select count(*) from agent_run where workspace_id = ?", scenario.workspace.value());
        assertThatThrownBy(() -> broken.admitRoot(scenario.workspace, scenario.promotion.deploymentId(), null, "{}",
                RunAdmissionService.Options.SUBMIT)).isInstanceOf(IllegalStateException.class);
        assertThat(scenario.count("select count(*) from agent_run where workspace_id = ?", scenario.workspace.value()))
                .isEqualTo(runs);
        assertThat(scenario.count("select count(*) from agent_run_event where workspace_id = ?", scenario.workspace.value()))
                .isEqualTo(2);
    }

    @Test
    void aClaimLoadsTheResolvedModelPinsOfARealVersionTwoRelease() {
        scenario.submit(null);
        var claimed = scenario.claim("worker");

        assertThat(claimed.manifest().manifestVersion()).isEqualTo(com.dvarahq.agentworks.controlplane.lifecycle.ReleaseService.MANIFEST_VERSION);
        var reasoning = claimed.manifest().models().role(com.dvarahq.agentworks.gateway.ModelRole.REASONING);
        assertThat(reasoning.identity().providerModel()).isEqualTo("coding-model-2026-09-01");
        assertThat(reasoning.release().value()).isEqualTo(claimed.run().pins().releaseId());
        assertThat(reasoning.workspace()).isEqualTo(scenario.workspace);
        String stored = scenario.fixture.jdbc.queryForObject("select manifest_json from agent_release where id = ?",
                String.class, claimed.run().pins().releaseId());
        assertThat(com.dvarahq.agentworks.gateway.PinnedModelRoles.fromReleaseManifest(scenario.workspace,
                new com.dvarahq.agentworks.domain.lifecycle.AgentReleaseId(claimed.run().pins().releaseId()), stored)
                .identities()).isEqualTo(claimed.manifest().models().identities());
    }

    @Test
    void onlyLegalTransitionsCommitAndEachAppendsExactlyOneImmutableEvent() {
        RunRecord run = scenario.submit(null);
        RunLease lease = scenario.claim("worker-a").lease();
        engine.block(lease, RunBlockReason.CHILD_RUN, Map.of("child", "pending"));
        assertThat(engine.run(scenario.workspace, run.id()).summary().blockReason()).isEqualTo(RunBlockReason.CHILD_RUN);
        engine.resume(scenario.workspace, run.id(), "system:child-completed");
        lease = scenario.claim("worker-b").lease();
        engine.pause(lease, "operator");
        engine.resume(scenario.workspace, run.id(), "system:test");
        lease = scenario.claim("worker-c").lease();
        engine.complete(lease, Map.of("answer", 42));

        assertThat(engine.history(scenario.workspace, run.id())).extracting(event -> event.type().name()).containsExactly(
                "RUN_CREATED", "RUN_QUEUED", "RUN_ACTIVATED", "RUN_BLOCKED", "RUN_RESUMED", "RUN_ACTIVATED",
                "RUN_PAUSED", "RUN_RESUMED", "RUN_ACTIVATED", "RUN_SUCCEEDED");
        assertThat(scenario.count("select count(*) from agent_run_event where run_id = ?", run.id())).isEqualTo(10);
        // Nothing can rewrite, delete, or truncate history.
        for (String tamper : List.of("update agent_run_event set actor = 'forged' where run_id = ?",
                "delete from agent_run_event where run_id = ?")) {
            assertThatThrownBy(() -> scenario.fixture.jdbc.update(tamper, run.id())).isInstanceOf(DataAccessException.class)
                    .hasStackTraceContaining("IMMUTABLE_HISTORY");
        }
        assertThatThrownBy(() -> scenario.fixture.jdbc.execute("truncate agent_run_event cascade"))
                .hasStackTraceContaining("IMMUTABLE_HISTORY");
    }

    @Test
    void illegalTransitionsAndTransitionsWithoutEventsAreRejectedByTheDatabase() {
        RunRecord run = scenario.submit(null);
        String asRunPlane = "agentworks_run_plane";

        // QUEUED cannot jump to SUCCEEDED, and no lifecycle change commits without its event.
        assertThatThrownBy(() -> scenario.fixture.asRole(asRunPlane, "update agent_run set lifecycle = 'SUCCEEDED', "
                + "completed_at = current_timestamp, row_version = row_version + 1 where id = ?", run.id()))
                .hasStackTraceContaining("ILLEGAL_TRANSITION");
        assertThatThrownBy(() -> scenario.fixture.asRole(asRunPlane, "update agent_run set lifecycle = 'CANCELLED', "
                + "row_version = row_version + 1 where id = ?", run.id()))
                .hasStackTraceContaining("TRANSITION_WITHOUT_EVENT");
        // Advancing the counted sequence without appending the event fails at commit.
        assertThatThrownBy(() -> scenario.fixture.asRole(asRunPlane, "update agent_run set lifecycle = 'CANCELLED', "
                + "event_sequence = event_sequence + 1, row_version = row_version + 1 where id = ?", run.id()))
                .hasStackTraceContaining("HISTORY_MISMATCH");
        assertThatThrownBy(() -> scenario.fixture.asRole(asRunPlane, "update agent_run set lifecycle = 'BLOCKED', "
                + "row_version = row_version + 1 where id = ?", run.id())).isInstanceOf(RuntimeException.class);
        assertThat(engine.run(scenario.workspace, run.id()).lifecycle()).isEqualTo(RunLifecycle.QUEUED);
        assertThat(engine.verify(scenario.workspace, run.id()).intact()).isTrue();
    }

    @Test
    void transitionEventsCannotBeWrittenWithoutTheirTransitionOrSummaryFactsWithoutTheirEvent() {
        RunRecord run = scenario.submit(null);
        var claimed = scenario.claim("worker");
        String digest = "'sha256:" + "1".repeat(64) + "'";
        String advance = "update agent_run set event_sequence = event_sequence + 1, last_event_digest = " + digest
                + ", row_version = row_version + 1 where id = ?";
        String event = "insert into agent_run_event(workspace_id, run_id, sequence, id, event_type, schema_version, "
                + "payload_json, payload_digest, event_digest, trace_id, release_id, deployment_id, revision_id, cohort_id, "
                + "actor, lease_owner, lease_fence, created_at) select workspace_id, id, event_sequence, gen_random_uuid(), "
                + "?, 1, '{}'::jsonb, 'sha256:' || repeat('0', 64), " + digest + ", trace_id, release_id, deployment_id, "
                + "revision_id, cohort_id, 'system:forged', ?, lease_fence, current_timestamp from agent_run where id = ?";

        // A control event claiming cancellation while the run stays ACTIVE is refused by the event guard; a worker event
        // claiming success without the transition is refused at commit.
        assertThatThrownBy(() -> asRunPlane(List.of(advance, event), List.of(new Object[]{run.id()},
                new Object[]{"RUN_CANCELLED", null, run.id()}))).hasStackTraceContaining("UNAUTHORIZED_EVENT_WRITER");
        assertThatThrownBy(() -> asRunPlane(List.of(advance, event), List.of(new Object[]{run.id()},
                new Object[]{"RUN_SUCCEEDED", "worker", run.id()}))).hasStackTraceContaining("HISTORY_MISMATCH");
        // Projection facts cannot change without the transition that records them.
        scenario.engine.block(claimed.lease(), RunBlockReason.HUMAN_INTERACTION, null);
        assertThatThrownBy(() -> scenario.fixture.asRole("agentworks_run_plane", "update agent_run set block_reason = "
                + "'CAPACITY', row_version = row_version + 1 where id = ?", run.id()))
                .hasStackTraceContaining("SUMMARY_WITHOUT_TRANSITION");
        assertThat(engine.verify(scenario.workspace, run.id()).intact()).isTrue();
    }

    @Test
    void leaseLessControlEventsAreRefusedWhileAWorkerHoldsTheRun() {
        RunRecord run = scenario.submit(null);
        scenario.claim("worker");
        String digest = "'sha256:" + "2".repeat(64) + "'";

        for (String type : List.of("OPERATION_RESOLVED", "RUN_QUEUED", "RUN_CANCELLED",
                "HUMAN_INTERACTION_RESPONDED", "HUMAN_INTERACTION_EXPIRED", "HUMAN_INTERACTION_CANCELLED",
                "AUTHORIZATION_GRANTED", "AUTHORIZATION_FAILED", "AUTHORIZATION_REVOKED")) {
            assertThatThrownBy(() -> asRunPlane(List.of(
                    "update agent_run set event_sequence = event_sequence + 1, last_event_digest = " + digest
                            + ", row_version = row_version + 1 where id = ?",
                    "insert into agent_run_event(workspace_id, run_id, sequence, id, event_type, schema_version, "
                            + "payload_json, payload_digest, event_digest, trace_id, release_id, deployment_id, revision_id, "
                            + "cohort_id, actor, lease_owner, lease_fence, created_at) select workspace_id, id, event_sequence, "
                            + "gen_random_uuid(), ?, 1, '{}'::jsonb, 'sha256:' || repeat('0', 64), " + digest + ", trace_id, "
                            + "release_id, deployment_id, revision_id, cohort_id, 'operator:forged', null, lease_fence, "
                            + "current_timestamp from agent_run where id = ?"),
                    List.of(new Object[]{run.id()}, new Object[]{type, run.id()}))).as(type)
                    .hasStackTraceContaining("UNAUTHORIZED_EVENT_WRITER");
        }
        assertThat(engine.run(scenario.workspace, run.id()).lifecycle()).isEqualTo(RunLifecycle.ACTIVE);
        assertThat(engine.verify(scenario.workspace, run.id()).intact()).isTrue();
    }

    @Test
    void continuationsInheritTheirParentsCreationFactsEvenWhenThePolicyChanged() {
        var limited = new LifecycleFixture(scenario.fixture.dataSource, LifecycleRetry.standard(), Duration.ofHours(24),
                null, new RunLimits(500L, 5L, null, null, null));
        RunScenario bounded = new RunScenario(limited);
        RunRecord parent = bounded.submit(null);
        // A node whose limits policy differs admits the child: it must still carry the parent's pins.
        RunRecord child = bounded.engine.run(bounded.workspace, scenario.fixture.admissions.admitContinuation(
                bounded.workspace, parent.id(), com.dvarahq.agentworks.runplane.admission.RunAdmissionService
                        .ContinuationKind.CHILD).run().id());

        assertThat(child.pins().limits()).isEqualTo(parent.pins().limits());
        assertThat(child.pins().traceId()).isEqualTo(parent.pins().traceId());
        assertThat(child.pins().manifestVersion()).isEqualTo(parent.pins().manifestVersion());
        // The fence refuses a continuation whose creation facts differ from its parent's, even through direct SQL.
        UUID forged = UUID.randomUUID();
        assertThatThrownBy(() -> scenario.fixture.asRole("agentworks_run_plane", "insert into agent_run(workspace_id, id, "
                + "definition_id, version_id, release_id, deployment_id, environment_id, revision_id, cohort_id, admission, "
                + "parent_run_id, root_run_id, continuation_kind, lifecycle, created_at, trace_id, manifest_version, "
                + "limit_total_tokens, limit_model_calls, event_sequence, last_event_digest) select workspace_id, ?, "
                + "definition_id, version_id, release_id, deployment_id, environment_id, revision_id, cohort_id, "
                + "'CONTINUATION', id, root_run_id, 'CHILD', 'CREATED', current_timestamp, trace_id, manifest_version, "
                + "999999, limit_model_calls, 1, 'sha256:' || repeat('0', 64) from agent_run where id = ?", forged,
                parent.id())).hasStackTraceContaining("CONTINUATION_PROVENANCE_MISMATCH");
    }

    @Test
    void anOperatorFailsARunNoNodeCanExecuteWithoutALease() {
        RunRecord run = scenario.submit(null);
        RunRecord failed = engine.failRun(scenario.workspace, run.id(), "UNINTERPRETABLE", "operator:ops",
                "every node quarantined it");

        assertThat(failed.lifecycle()).isEqualTo(RunLifecycle.FAILED);
        assertThat(failed.summary().terminalCode()).isEqualTo("UNINTERPRETABLE");
        assertThat(engine.history(scenario.workspace, run.id()).getLast().raw().leaseOwner()).isNull();
        assertThat(engine.verify(scenario.workspace, run.id()).intact()).isTrue();
    }

    private void asRunPlane(List<String> statements, List<Object[]> arguments) {
        try (var connection = scenario.fixture.dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement("select set_config('agentworks.workspace_id', ?, true)")) {
                statement.setString(1, scenario.workspace.value().toString());
                statement.execute();
            }
            try (var role = connection.createStatement()) {
                role.execute("set local role agentworks_run_plane");
            }
            var jdbc = new org.springframework.jdbc.core.JdbcTemplate(
                    new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection, true));
            for (int index = 0; index < statements.size(); index++) jdbc.update(statements.get(index), arguments.get(index));
            connection.commit();
        } catch (java.sql.SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    @Test
    void rebuildingTheSummaryFromEventsYieldsThePersistedState() {
        RunRecord run = scenario.submit(Map.of("n", 1));
        var claimed = scenario.claim("worker");
        var written = engine.append(claimed.lease(), List.of(model("m-1", 100, 40), tool("t-1")));
        written = engine.checkpoint(written.lease(), Map.of("step", 1));
        var effect = engine.executeEffect(written.lease(), "notify", Map.of("to", "ops"),
                (key, request) -> tools.jackson.databind.node.JsonNodeFactory.instance.objectNode().put("sent", true));
        engine.block(effect.lease(), RunBlockReason.AUTHORIZATION, null);
        engine.resume(scenario.workspace, run.id(), "system:test");
        var second = scenario.claim("worker");
        RunRecord done = engine.complete(engine.append(second.lease(), List.of(model("m-2", 10, 5))).lease(), "done");

        var report = engine.verify(scenario.workspace, run.id());
        assertThat(report.intact()).as(report.findings().toString()).isTrue();
        assertThat(report.rebuilt()).isEqualTo(done.summary());
        assertThat(done.summary().usage().inputTokens()).isEqualTo(110);
        assertThat(done.summary().usage().outputTokens()).isEqualTo(45);
        assertThat(done.summary().usage().totalTokens()).isEqualTo(155);
        assertThat(done.summary().usage().modelCalls()).isEqualTo(2);
        assertThat(done.summary().usage().toolCalls()).isEqualTo(1);
        assertThat(done.summary().usage().estimatedCostUsd()).isEqualByComparingTo("0.020000");
        assertThat(done.summary().checkpointSequence()).isOne();
        // The persisted row is exactly what PostgreSQL stores, not a cached object.
        assertThat(scenario.fixture.runStore.inTransaction(scenario.workspace, tx -> tx.findRun(run.id())).orElseThrow()
                .summary()).isEqualTo(report.rebuilt());
    }

    @Test
    void stepProjectionRebuildsDeterministicallyFromCanonicalEventsOnly() {
        RunRecord run = scenario.submit(null);
        var claimed = scenario.claim("worker");
        engine.complete(engine.append(claimed.lease(), List.of(model("m-1", 1, 1), tool("t-1"),
                EventDraft.of(RunEventType.TOOL_COMPLETED, Map.of("stepId", "t-1")))).lease(), null);

        List<AgentStep> steps = engine.steps(scenario.workspace, run.id());

        assertThat(steps).isEqualTo(engine.steps(scenario.workspace, run.id()));
        assertThat(steps).filteredOn(step -> step.category() == StepCategory.MODEL).singleElement()
                .satisfies(step -> assertThat(step.status()).isEqualTo(AgentStep.Status.COMPLETED));
        assertThat(steps.getFirst().eventTypes()).containsExactly("RUN_CREATED");
        assertThat(steps).filteredOn(step -> step.stepId().equals("t-1")).singleElement()
                .satisfies(step -> assertThat(step.status()).isEqualTo(AgentStep.Status.COMPLETED));
        // No step table exists: the projection cannot drift into an alternate source of truth.
        assertThat(scenario.count("select count(*) from information_schema.tables where table_name like '%step%'"))
                .isZero();
    }

    @Test
    void usageLimitsFailTheRunInTheSameTransactionAndCountersNeverDecrease() {
        var limited = new LifecycleFixture(scenario.fixture.dataSource, LifecycleRetry.standard(), Duration.ofHours(24),
                null, new RunLimits(100L, null, null, null, new BigDecimal("0.050000")));
        RunScenario bounded = new RunScenario(limited);
        RunRecord run = bounded.submit(null);
        var claimed = bounded.claim("worker");
        assertThat(run.pins().limits().maxTotalTokens()).isEqualTo(100L);

        var near = bounded.engine.append(claimed.lease(), List.of(model("m-1", 60, 30)));
        var over = bounded.engine.append(near.lease(), List.of(model("m-2", 10, 10)));

        assertThat(over.ended()).isTrue();
        RunRecord failed = bounded.engine.run(bounded.workspace, run.id());
        assertThat(failed.lifecycle()).isEqualTo(RunLifecycle.FAILED);
        assertThat(failed.summary().terminalCode()).isEqualTo("LIMIT_EXCEEDED");
        assertThat(failed.summary().usage().totalTokens()).isEqualTo(110);
        assertThat(bounded.engine.history(bounded.workspace, run.id())).extracting(event -> event.type().name())
                .containsSubsequence("LIMIT_WARNING", "MODEL_RESPONSE_RECEIVED", "LIMIT_EXCEEDED", "RUN_FAILED");
        assertThatThrownBy(() -> scenario.fixture.asRole("agentworks_run_plane", "update agent_run set "
                + "input_tokens = 0, total_tokens = output_tokens where id = ?", run.id()))
                .hasStackTraceContaining("SUMMARY_REGRESSION");
        // A restarted engine reads the same counters from PostgreSQL.
        var restarted = new DurableRunEngine(new com.dvarahq.agentworks.storage.run.JdbcDurableRunStore(
                scenario.fixture.jdbc, scenario.fixture.transactions), RunEventCodec.standard(),
                new ReleaseManifestReader(), LifecycleRetry.standard());
        assertThat(restarted.run(bounded.workspace, run.id()).summary().usage()).isEqualTo(failed.summary().usage());
        assertThat(restarted.verify(bounded.workspace, run.id()).intact()).isTrue();
    }

    @Test
    void cancellationRevokesTheActiveLeaseSoTheWorkerStopsAtItsNextWrite() {
        RunRecord run = scenario.submit(null);
        var claimed = scenario.claim("worker");

        RunRecord cancelled = engine.cancel(scenario.workspace, run.id(), "operator:ops", "customer request");

        assertThat(cancelled.lifecycle()).isEqualTo(RunLifecycle.CANCELLED);
        assertThat(cancelled.summary().leaseFence()).isEqualTo(claimed.lease().fence() + 1);
        assertThat(cancelled.control().leaseOwner()).isNull();
        assertCode(() -> engine.append(claimed.lease(), List.of(model("late", 1, 1))), "STALE_LEASE");
        assertCode(() -> engine.complete(claimed.lease(), null), "STALE_LEASE");
        // A stale worker writing directly is refused by the event guard as well.
        assertThatThrownBy(() -> scenario.fixture.asRole("agentworks_run_plane", "insert into agent_run_event("
                + "workspace_id, run_id, sequence, id, event_type, schema_version, payload_json, payload_digest, "
                + "event_digest, trace_id, release_id, deployment_id, revision_id, cohort_id, actor, lease_owner, "
                + "lease_fence, created_at) select workspace_id, id, event_sequence + 1, gen_random_uuid(), "
                + "'MODEL_REQUESTED', 1, '{}'::jsonb, 'sha256:' || repeat('0', 64), 'sha256:' || repeat('0', 64), "
                + "trace_id, release_id, deployment_id, revision_id, cohort_id, 'worker:worker', 'worker', ?, "
                + "current_timestamp from agent_run where id = ?", claimed.lease().fence(), run.id()))
                .hasStackTraceContaining("STALE_LEASE");
        assertThat(engine.history(scenario.workspace, run.id()).getLast().type()).isEqualTo(RunEventType.RUN_CANCELLED);
    }

    @Test
    void cancellationRacingCompletionCommitsExactlyOneTerminalOutcome() throws Exception {
        for (int round = 0; round < 8; round++) {
            RunRecord run = scenario.submit(null);
            var claimed = engine.claimNext("worker-" + round, scenario.scope(), RunScenario.LEASE).orElseThrow();
            List<Object> outcomes = race(2, index -> index == 0
                    ? engine.complete(claimed.lease(), "done")
                    : engine.cancel(scenario.workspace, run.id(), "operator:ops", "race"));

            RunRecord finished = engine.run(scenario.workspace, run.id());
            assertThat(finished.lifecycle()).isIn(RunLifecycle.SUCCEEDED, RunLifecycle.CANCELLED);
            long terminalEvents = engine.history(scenario.workspace, run.id()).stream()
                    .filter(event -> event.type() == RunEventType.RUN_SUCCEEDED || event.type() == RunEventType.RUN_CANCELLED)
                    .count();
            assertThat(terminalEvents).isOne();
            Object loser = finished.lifecycle() == RunLifecycle.SUCCEEDED ? outcomes.get(1) : outcomes.get(0);
            assertThat(loser).isInstanceOfSatisfying(RunEngineException.class,
                    failure -> assertThat(failure.code()).isIn("RUN_TERMINAL", "STALE_LEASE"));
            assertThat(engine.verify(scenario.workspace, run.id()).intact()).isTrue();
        }
    }

    @Test
    void noEngineOrDirectPathChangesAnAdmittedRunsPins() {
        RunRecord run = scenario.submit(null);
        RunPins pins = run.pins();
        var claimed = scenario.claim("worker-a");
        engine.checkpoint(claimed.lease(), Map.of());
        // A promotion after admission changes routing for new work only.
        var next = scenario.fixture.promote(scenario.workspace, scenario.environment, "api",
                scenario.fixture.release(scenario.workspace, "agent", "2.0.0"));
        scenario.expireLease(run.id());
        var recovered = scenario.claim("worker-b");
        engine.block(recovered.lease(), RunBlockReason.SCHEDULED_WAKEUP, null);
        engine.resume(scenario.workspace, run.id(), "system:test");
        engine.cancel(scenario.workspace, run.id(), "system:test", null);

        assertThat(engine.run(scenario.workspace, run.id()).pins()).isEqualTo(pins);
        assertThat(pins.revisionId()).isNotEqualTo(next.revisionId());
        for (String column : List.of("version_id", "release_id", "revision_id", "deployment_id", "routing_generation_id",
                "cohort_id", "trace_id", "manifest_version", "limit_total_tokens")) {
            String value = switch (column) {
                case "trace_id" -> "'" + "a".repeat(32) + "'";
                case "manifest_version" -> "7";
                case "limit_total_tokens" -> "7";
                default -> "'" + UUID.randomUUID() + "'";
            };
            assertThatThrownBy(() -> scenario.fixture.jdbc.update("update agent_run set " + column + " = " + value
                    + " where id = ?", run.id())).as(column).isInstanceOf(DataAccessException.class);
        }
        assertThatThrownBy(() -> scenario.fixture.jdbc.update("update agent_run set trace_id = ? where id = ?",
                "b".repeat(32), run.id())).hasStackTraceContaining("IMMUTABLE_PIN");
        assertThat(engine.run(scenario.workspace, run.id()).pins()).isEqualTo(pins);
    }

    private static EventDraft model(String step, int input, int output) {
        return EventDraft.of(RunEventType.MODEL_RESPONSE_RECEIVED, Map.of("stepId", step, "model", "coding-premium",
                "usage", Map.of("inputTokens", input, "outputTokens", output, "modelCalls", 1,
                        "estimatedCostUsd", "0.010000")));
    }

    private static EventDraft tool(String step) {
        return EventDraft.of(RunEventType.TOOL_STARTED, Map.of("stepId", step, "name", "search",
                "usage", Map.of("toolCalls", 1)));
    }

    static void assertCode(Runnable call, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(RunEngineException.class,
                failure -> assertThat(failure.code()).isEqualTo(code));
    }

    static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    /** Joins the admission transaction and fails after the run row was inserted. */
    private record FailingHistory(DurableRunStore delegate) implements DurableRunStore {
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
            return delegate.inTransaction(workspace, tx -> {
                throw new IllegalStateException("injected failure after the run insert");
            });
        }

        @Override
        public <T> Optional<T> claimNext(com.dvarahq.agentworks.domain.run.ClaimScope scope,
                BiFunction<RunRecord, RunTransaction, T> work) {
            return delegate.claimNext(scope, work);
        }
    }
}
