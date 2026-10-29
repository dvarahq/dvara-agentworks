package com.dvarahq.agentworks.controlplane.lifecycle;

import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.CohortCommand;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.EnvironmentView;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.PromotionResult;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.RetirementResult;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import com.dvarahq.agentworks.runplane.admission.RunAdmissionRejectedException;
import com.dvarahq.agentworks.runplane.admission.RunAdmissionService.AdmittedRun;
import com.dvarahq.agentworks.runplane.admission.RunAdmissionService.ContinuationKind;
import com.dvarahq.agentworks.domain.lifecycle.state.LifecycleReads;
import com.dvarahq.agentworks.domain.lifecycle.state.LifecycleRows.CohortRow;
import com.dvarahq.agentworks.domain.lifecycle.state.LifecycleRows.DeploymentRow;
import com.dvarahq.agentworks.domain.lifecycle.state.LifecycleRows.ReleaseRow;
import com.dvarahq.agentworks.domain.lifecycle.state.LifecycleRows.RevisionRow;
import com.dvarahq.agentworks.domain.lifecycle.state.LifecycleRows.RoutingGenerationRow;
import com.dvarahq.agentworks.domain.lifecycle.state.LifecycleRows.RoutingTargetRow;
import com.dvarahq.agentworks.domain.lifecycle.state.LifecycleRows.RunRow;
import com.dvarahq.agentworks.runplane.admission.RunAdmissionService;
import com.dvarahq.agentworks.storage.lifecycle.LifecycleRetry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.dvarahq.agentworks.controlplane.lifecycle.PromotionLifecyclePostgresTest.race;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Root/continuation admission, the cohort fence, quiescence, and retirement on PostgreSQL (ADR-027). */
class RunAdmissionFencePostgresTest {
    private LifecycleFixture fixture;
    private WorkspaceId workspace;
    private EnvironmentView environment;
    private PromotionResult r1;

    @BeforeEach
    void setUp() {
        fixture = LifecycleFixture.postgres();
        workspace = fixture.workspace();
        environment = fixture.environment(workspace, "prod");
        r1 = fixture.promote(workspace, environment, "api", fixture.release(workspace, "agent", "1.0.0"));
    }

    @Test
    void rootAdmissionPinsExactIdentitiesRoutingGenerationAndTarget() {
        RunRow run = fixture.admissions.admitRoot(workspace, r1.deploymentId(), null, "{}").run();

        assertThat(run.admission()).isEqualTo("ROOT");
        assertThat(run.rootRunId()).isEqualTo(run.id());
        assertThat(run.revisionId()).isEqualTo(r1.revisionId());
        assertThat(run.cohortId()).isEqualTo(r1.cohortId());
        assertThat(run.routingGenerationId()).isEqualTo(r1.routingGenerationId());
        assertThat(run.routingTargetOrdinal()).isZero();
        assertThat(run.environmentId()).isEqualTo(environment.id());
        assertThat(run.lifecycle()).isEqualTo("CREATED");
        assertThatThrownBy(() -> fixture.jdbc.update("update agent_run set revision_id = revision_id, cohort_id = ? "
                + "where id = ?", UUID.randomUUID(), run.id())).isInstanceOf(DataAccessException.class);
    }

    @Test
    void anExplicitMultiTargetRouteIsRepresentableButNeverSelectedByCoreAdmission() {
        var r2 = fixture.promote(workspace, environment, "api", fixture.release(workspace, "agent", "2.0.0"));
        UUID canary = UUID.randomUUID();
        // An optional canary policy can be persisted without changing run or revision identity.
        fixture.jdbc.update("insert into routing_generation(workspace_id, id, deployment_id, generation, policy, decision_id, "
                + "previous_generation_id, created_at) values (?, ?, ?, 3, 'EXPLICIT_MULTI_TARGET', ?, ?, current_timestamp)",
                workspace.value(), canary, r2.deploymentId(), r2.decisionId(), r2.routingGenerationId());
        fixture.jdbc.update("insert into routing_target(workspace_id, generation_id, ordinal, cohort_id, revision_id, weight) "
                + "values (?, ?, 0, ?, ?, 90), (?, ?, 1, ?, ?, 10)", workspace.value(), canary, r2.cohortId(),
                r2.revisionId(), workspace.value(), canary, r1.cohortId(), r1.revisionId());
        fixture.jdbc.update("update agent_deployment set current_routing_generation_id = ?, revision = revision + 1 "
                + "where id = ?", canary, r2.deploymentId());

        assertThatThrownBy(() -> fixture.admissions.admitRoot(workspace, r1.deploymentId(), null, "{}"))
                .isInstanceOfSatisfying(RunAdmissionRejectedException.class,
                        rejected -> assertThat(rejected.code()).isEqualTo("UNSUPPORTED_ROUTING_POLICY"));
        assertThat(fixture.count("select count(*) from agent_run where deployment_id = ?", r1.deploymentId())).isZero();
    }

    @Test
    void idempotentRootRetryAcrossPromotionReturnsTheOriginalPinsAndMismatchConflicts() {
        AdmittedRun first = fixture.admissions.admitRoot(workspace, r1.deploymentId(), "invoke-1", "{\"input\":1}");
        var r2 = fixture.promote(workspace, environment, "api", fixture.release(workspace, "agent", "2.0.0"));

        AdmittedRun retry = fixture.admissions.admitRoot(workspace, r1.deploymentId(), "invoke-1", "{\"input\":1}");
        RunRow fresh = fixture.admissions.admitRoot(workspace, r1.deploymentId(), "invoke-2", "{\"input\":1}").run();

        assertThat(retry.replayed()).isTrue();
        assertThat(retry.run()).isEqualTo(first.run());
        assertThat(retry.run().routingGenerationId()).isEqualTo(r1.routingGenerationId());
        assertThat(fresh.routingGenerationId()).isEqualTo(r2.routingGenerationId());
        assertThat(fresh.revisionId()).isEqualTo(r2.revisionId());
        assertThatThrownBy(() -> fixture.admissions.admitRoot(workspace, r1.deploymentId(), "invoke-1", "{\"input\":2}"))
                .isInstanceOfSatisfying(RunAdmissionRejectedException.class,
                        rejected -> assertThat(rejected.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED"));
        assertThat(fixture.count("select count(*) from agent_run where deployment_id = ?", r1.deploymentId())).isEqualTo(2);
    }

    @Test
    void aPromotionCommittingBetweenAdmissionReadsReroutesInsteadOfRefusing() throws Exception {
        var release2 = fixture.release(workspace, "agent", "2.0.0");
        var promoted = new java.util.concurrent.atomic.AtomicReference<PromotionResult>();
        // Commits a promotion exactly after admission read the deployment and before it reads the routed cohort.
        LifecycleReads interleaved = new LifecycleReads() {
            public Optional<DeploymentRow> findDeployment(UUID id) { return fixture.lifecycle.findDeployment(id); }
            public Optional<RoutingGenerationRow> findRoutingGeneration(UUID id) {
                return fixture.lifecycle.findRoutingGeneration(id);
            }
            public List<RoutingTargetRow> routingTargets(UUID id) { return fixture.lifecycle.routingTargets(id); }
            public Optional<RevisionRow> findRevision(UUID id) { return fixture.lifecycle.findRevision(id); }
            public Optional<ReleaseRow> findRelease(UUID id) { return fixture.lifecycle.findRelease(id); }
            public Optional<CohortRow> findCohort(UUID id) {
                if (promoted.get() == null) {
                    try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
                        promoted.set(executor.submit(() -> fixture.promote(workspace, environment, "api", release2)).get());
                    } catch (Exception failure) {
                        throw new IllegalStateException(failure);
                    }
                }
                return fixture.lifecycle.findCohort(id);
            }
        };
        var admissions = new RunAdmissionService(fixture.tenants, interleaved, fixture.runRecords, fixture.clock,
                LifecycleRetry.standard(), null, fixture.runStore, fixture.engine,
                com.dvarahq.agentworks.runplane.execution.RunLimitsPolicy.fixed(
                        com.dvarahq.agentworks.domain.run.RunLimits.UNBOUNDED));

        RunRow run = admissions.admitRoot(workspace, r1.deploymentId(), null, java.util.Map.of()).run();

        assertThat(fixture.deployments.cohort(workspace, r1.cohortId()).state()).isEqualTo("DRAINING");
        assertThat(run.cohortId()).isEqualTo(promoted.get().cohortId());
        assertThat(run.routingGenerationId()).isEqualTo(promoted.get().routingGenerationId());
    }

    @Test
    void concurrentAdmissionsAndPromotionShareOneAtomicCutoff() throws Exception {
        var release2 = fixture.release(workspace, "agent", "2.0.0");
        List<Object> outcomes = race(17, index -> index == 8
                ? fixture.promote(workspace, environment, "api", release2)
                : fixture.admissions.admitRoot(workspace, r1.deploymentId(), null, "{}").run());

        PromotionResult r2 = outcomes.stream().filter(PromotionResult.class::isInstance).map(PromotionResult.class::cast)
                .findFirst().orElseThrow();
        List<RunRow> runs = outcomes.stream().filter(RunRow.class::isInstance).map(RunRow.class::cast).toList();
        assertThat(runs).hasSize(16).allSatisfy(run -> assertThat(run).satisfiesAnyOf(
                pinned -> assertThat(List.of(pinned.routingGenerationId(), pinned.revisionId(), pinned.cohortId()))
                        .containsExactly(r1.routingGenerationId(), r1.revisionId(), r1.cohortId()),
                pinned -> assertThat(List.of(pinned.routingGenerationId(), pinned.revisionId(), pinned.cohortId()))
                        .containsExactly(r2.routingGenerationId(), r2.revisionId(), r2.cohortId())));
        // After the promotion commits, R1 is draining and every new root pins R2.
        assertThat(fixture.deployments.cohort(workspace, r1.cohortId()).state()).isEqualTo("DRAINING");
        assertThat(fixture.admissions.admitRoot(workspace, r1.deploymentId(), null, "{}").run().cohortId())
                .isEqualTo(r2.cohortId());
    }

    @Test
    void theFenceRejectsStaleRoutesAndRootsIntoNonRoutableCohortsEvenThroughDirectSql() {
        RunRow template = fixture.admissions.admitRoot(workspace, r1.deploymentId(), null, "{}").run();
        fixture.promote(workspace, environment, "api", fixture.release(workspace, "agent", "2.0.0"));
        String insert = "insert into agent_run(workspace_id, id, definition_id, version_id, release_id, deployment_id, "
                + "environment_id, revision_id, cohort_id, admission, routing_generation_id, routing_target_ordinal, "
                + "root_run_id, lifecycle, created_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, 'ROOT', ?, 0, ?, 'CREATED', current_timestamp)";
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> fixture.asRole("agentworks_run_plane", insert, workspace.value(), id,
                template.definitionId(), template.versionId(), template.releaseId(), template.deploymentId(),
                template.environmentId(), template.revisionId(), template.cohortId(), template.routingGenerationId(), id))
                .isInstanceOf(DataAccessException.class).hasStackTraceContaining("STALE_ROUTING_GENERATION");
        assertThat(fixture.count("select count(*) from agent_run where cohort_id = ?", r1.cohortId())).isOne();
    }

    @Test
    void sessionObjectsCannotShadowTheTablesTheSecurityDefinerFenceReads() throws Exception {
        RunRow template = fixture.admissions.admitRoot(workspace, r1.deploymentId(), null, "{}").run();
        fixture.deployments.revoke(workspace, new CohortCommand(r1.cohortId(), 2, null, "tester", "compromised"));
        UUID id = UUID.randomUUID();

        try (var connection = fixture.dataSource.getConnection(); var statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute("set local role agentworks_run_plane");
            statement.execute("create temporary table runtime_cohort(workspace_id uuid, id uuid, state varchar(16)) on commit drop");
            statement.execute("insert into runtime_cohort values ('" + workspace.value() + "', '" + r1.cohortId() + "', 'ROUTABLE')");
            assertThatThrownBy(() -> statement.execute("insert into agent_run(workspace_id, id, definition_id, version_id, "
                    + "release_id, deployment_id, environment_id, revision_id, cohort_id, admission, routing_generation_id, "
                    + "routing_target_ordinal, root_run_id, lifecycle, created_at) values ('" + workspace.value() + "', '" + id
                    + "', '" + template.definitionId() + "', '" + template.versionId() + "', '" + template.releaseId() + "', '"
                    + template.deploymentId() + "', '" + template.environmentId() + "', '" + template.revisionId() + "', '"
                    + template.cohortId() + "', 'ROOT', '" + template.routingGenerationId() + "', 0, '" + id
                    + "', 'CREATED', current_timestamp)")).hasMessageContaining("COHORT_NOT_ADMITTING REVOKED");
            connection.rollback();
        }
        assertThat(fixture.count("select count(*) from agent_run where cohort_id = ?", r1.cohortId())).isOne();
    }

    @Test
    void newRootsSelectTheCurrentRevisionWhileExistingRunsKeepTheirPins() {
        RunRow old = fixture.admissions.admitRoot(workspace, r1.deploymentId(), null, "{}").run();
        fixture.setRunLifecycle(old.id(), "BLOCKED");
        var r2 = fixture.promote(workspace, environment, "api", fixture.release(workspace, "agent", "2.0.0"));

        RunRow next = fixture.admissions.admitRoot(workspace, r1.deploymentId(), null, "{}").run();
        fixture.setRunLifecycle(old.id(), "ACTIVE");

        assertThat(next.revisionId()).isEqualTo(r2.revisionId());
        assertThat(fixture.admissions.run(workspace, old.id())).satisfies(reloaded -> {
            assertThat(reloaded.revisionId()).isEqualTo(r1.revisionId());
            assertThat(reloaded.lifecycle()).isEqualTo("ACTIVE");
        });
    }

    @Test
    void drainingCohortAdmitsOnlyProvenanceBearingContinuationsOfNonTerminalParents() {
        RunRow parent = fixture.admissions.admitRoot(workspace, r1.deploymentId(), null, "{}").run();
        fixture.promote(workspace, environment, "api", fixture.release(workspace, "agent", "2.0.0"));

        RunRow child = fixture.admissions.admitContinuation(workspace, parent.id(), ContinuationKind.CHILD).run();
        RunRow retry = fixture.admissions.admitContinuation(workspace, child.id(), ContinuationKind.RETRY).run();

        assertThat(List.of(child, retry)).allSatisfy(run -> {
            assertThat(run.admission()).isEqualTo("CONTINUATION");
            assertThat(run.rootRunId()).isEqualTo(parent.id());
            assertThat(run.cohortId()).isEqualTo(r1.cohortId());
            assertThat(run.routingGenerationId()).isNull();
        });
        assertThat(retry.parentRunId()).isEqualTo(child.id());
        fixture.setRunLifecycle(parent.id(), "SUCCEEDED");
        assertThatThrownBy(() -> fixture.admissions.admitContinuation(workspace, parent.id(), ContinuationKind.COMPENSATION))
                .isInstanceOfSatisfying(RunAdmissionRejectedException.class,
                        rejected -> assertThat(rejected.code()).isEqualTo("PARENT_NOT_RESUMABLE"));
    }

    @Test
    void retirementReportsTypedBlockersUntilTheFinalDurableWorkCompletes() {
        RunRow parent = fixture.admissions.admitRoot(workspace, r1.deploymentId(), null, "{}").run();
        fixture.setRunLifecycle(parent.id(), "BLOCKED");
        fixture.promote(workspace, environment, "api", fixture.release(workspace, "agent", "2.0.0"));
        RunRow child = fixture.admissions.admitContinuation(workspace, parent.id(), ContinuationKind.CHILD).run();

        RetirementResult blocked = retire(r1.cohortId());
        assertThat(blocked.outcome()).isEqualTo("BLOCKED");
        assertThat(blocked.blockers()).extracting(LifecycleModel.Blocker::state, LifecycleModel.Blocker::count)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("BLOCKED", 1L), org.assertj.core.groups.Tuple.tuple("CREATED", 1L));
        assertThat(fixture.deployments.decision(workspace, blocked.decisionId()).outcome()).isEqualTo("DENIED");

        fixture.setRunLifecycle(child.id(), "SUCCEEDED");
        assertThat(retire(r1.cohortId()).blockers()).singleElement()
                .satisfies(blocker -> assertThat(blocker.state()).isEqualTo("BLOCKED"));
        fixture.setRunLifecycle(parent.id(), "FAILED");
        RetirementResult retired = retire(r1.cohortId());

        assertThat(retired.outcome()).isEqualTo("RETIRED");
        assertThat(fixture.tenants.execute(workspace, () -> fixture.lifecycle.transitions(r1.cohortId())))
                .extracting(row -> row.toState()).endsWith("DRAINING", "QUIESCENT", "RETIRED");
        assertThat(fixture.count("select count(*) from agent_run where cohort_id = ?", r1.cohortId())).isEqualTo(2);
        assertThat(fixture.count("select count(*) from deployment_revision where id = ?", r1.revisionId())).isOne();
    }

    @Test
    void lateWorkInvalidatesAQuiescenceObservationBeforeRetirement() {
        RunRow run = fixture.admissions.admitRoot(workspace, r1.deploymentId(), null, "{}").run();
        fixture.promote(workspace, environment, "api", fixture.release(workspace, "agent", "2.0.0"));
        fixture.setRunLifecycle(run.id(), "SUCCEEDED");
        assertThat(fixture.deployments.observeQuiescence(workspace, r1.cohortId(), "tester").state()).isEqualTo("QUIESCENT");

        // Work written outside the fence appears after quiescence was observed; its first resumable write (queueing)
        // passes through the fence.
        var late = fixture.lateWork(run);
        fixture.setRunLifecycle(late, "ACTIVE");

        assertThat(fixture.deployments.cohort(workspace, r1.cohortId()).state()).isEqualTo("DRAINING");
        assertThat(fixture.tenants.execute(workspace, () -> fixture.lifecycle.transitions(r1.cohortId())).getLast())
                .satisfies(transition -> {
                    assertThat(transition.cause()).isEqualTo("LATE_WORK");
                    assertThat(transition.fromState()).isEqualTo("QUIESCENT");
                });
        assertThat(retire(r1.cohortId()).outcome()).isEqualTo("BLOCKED");
    }

    /**
     * Races a continuation writer against terminalization plus retirement. Whichever commits first, the cohort is
     * never retired while resumable work exists: either the child blocks retirement, or the parent is terminal and
     * the child is refused.
     */
    @Test
    void retirementAndContinuationRacesNeverRetireResumableWork() throws Exception {
        for (int round = 0; round < 5; round++) {
            WorkspaceId space = fixture.workspace();
            EnvironmentView env = fixture.environment(space, "prod");
            PromotionResult first = fixture.promote(space, env, "api", fixture.release(space, "agent", "1.0.0"));
            RunRow parent = fixture.admissions.admitRoot(space, first.deploymentId(), null, "{}").run();
            // The parent is claimed before the race, as a run that spawns a child is (PR #208 CI): a claim skips rows
            // locked by others, and the continuation's fence holds the parent FOR SHARE while it inserts the child,
            // so a claim racing it found nothing and the parent was never terminated. Completing a claimed run waits
            // for that lock instead.
            fixture.setRunLifecycle(parent.id(), "ACTIVE");
            fixture.promote(space, env, "api", fixture.release(space, "agent", "2.0.0"));
            long version = fixture.deployments.cohort(space, first.cohortId()).transitionVersion();

            List<Object> outcomes = race(2, index -> index == 0
                    ? fixture.admissions.admitContinuation(space, parent.id(), ContinuationKind.CHILD).run()
                    : terminateThenRetire(space, parent.id(), first.cohortId(), version));

            String cohortState = fixture.deployments.cohort(space, first.cohortId()).state();
            long nonTerminal = fixture.count("select count(*) from agent_run where cohort_id = ? and lifecycle not in "
                    + "('SUCCEEDED', 'FAILED', 'CANCELLED')", first.cohortId());
            if ("RETIRED".equals(cohortState)) {
                assertThat(nonTerminal).isZero();
                assertThat(outcomes.getFirst()).isInstanceOf(RunAdmissionRejectedException.class);
            } else {
                assertThat(outcomes.getFirst()).isInstanceOf(RunRow.class);
                assertThat(nonTerminal).isOne();
            }
        }
    }

    @Test
    void retiredSuspendedAndRevokedCohortsFenceResumableWrites() {
        RunRow drained = fixture.admissions.admitRoot(workspace, r1.deploymentId(), null, "{}").run();
        var r2 = fixture.promote(workspace, environment, "api", fixture.release(workspace, "agent", "2.0.0"));
        fixture.setRunLifecycle(drained.id(), "CANCELLED");
        assertThat(retire(r1.cohortId()).outcome()).isEqualTo("RETIRED");
        assertThatThrownBy(() -> fixture.setRunLifecycle(drained.id(), "QUEUED"))
                .isInstanceOfSatisfying(com.dvarahq.agentworks.runplane.execution.RunEngineException.class,
                        terminal -> assertThat(terminal.code()).isEqualTo("RUN_TERMINAL"));
        assertThatThrownBy(() -> fixture.asRole("agentworks_run_plane", "update agent_run set lifecycle = 'QUEUED' "
                + "where id = ?", drained.id())).hasStackTraceContaining("TERMINAL_RUN");
        var unfenced = fixture.lateWork(drained);
        assertThatThrownBy(() -> fixture.setRunLifecycle(unfenced, "QUEUED"))
                .hasStackTraceContaining("COHORT_NOT_ADMITTING RETIRED");

        RunRow active = fixture.admissions.admitRoot(workspace, r1.deploymentId(), null, "{}").run();
        fixture.setRunLifecycle(active.id(), "ACTIVE");
        var suspended = fixture.deployments.suspend(workspace, new CohortCommand(r2.cohortId(), 2, null, "tester", null)).value();
        assertThatThrownBy(() -> fixture.admissions.admitRoot(workspace, r1.deploymentId(), null, "{}"))
                .isInstanceOfSatisfying(RunAdmissionRejectedException.class,
                        rejected -> assertThat(rejected.code()).isEqualTo("COHORT_NOT_ADMITTING"));
        fixture.setRunLifecycle(active.id(), "PAUSED");
        assertThatThrownBy(() -> fixture.setRunLifecycle(active.id(), "ACTIVE"))
                .hasStackTraceContaining("COHORT_NOT_ADMITTING SUSPENDED");

        fixture.deployments.revoke(workspace, new CohortCommand(r2.cohortId(), suspended.transitionVersion(), null,
                "tester", "compromised"));
        assertThatThrownBy(() -> fixture.setRunLifecycle(active.id(), "BLOCKED"))
                .hasStackTraceContaining("COHORT_NOT_ADMITTING REVOKED");
        fixture.setRunLifecycle(active.id(), "CANCELLED");
        assertThat(fixture.admissions.run(workspace, active.id()).lifecycle()).isEqualTo("CANCELLED");
    }

    @Test
    void manyAgentsAndDeploymentsAdmitConcurrentlyWithoutCrossPinning() throws Exception {
        List<PromotionResult> deployments = new ArrayList<>(List.of(r1));
        deployments.add(fixture.promote(workspace, environment, "billing", fixture.release(workspace, "billing-agent", "1.0.0")));
        WorkspaceId other = fixture.workspace();
        deployments.add(fixture.promote(other, fixture.environment(other, "prod"), "api",
                fixture.release(other, "agent", "1.0.0")));

        List<Object> outcomes = race(12, index -> fixture.admissions.admitRoot(index % 3 == 2 ? other : workspace,
                deployments.get(index % 3).deploymentId(), null, "{}").run());

        for (int index = 0; index < outcomes.size(); index++) {
            RunRow run = (RunRow) outcomes.get(index);
            PromotionResult expected = deployments.get(index % 3);
            assertThat(run.deploymentId()).isEqualTo(expected.deploymentId());
            assertThat(run.revisionId()).isEqualTo(expected.revisionId());
            assertThat(run.workspaceId()).isEqualTo((index % 3 == 2 ? other : workspace).value());
        }
        assertThatThrownBy(() -> fixture.admissions.admitRoot(other, r1.deploymentId(), null, "{}"))
                .isInstanceOfSatisfying(RunAdmissionRejectedException.class,
                        rejected -> assertThat(rejected.code()).isEqualTo("NOT_FOUND"));
    }

    private Object terminateThenRetire(WorkspaceId space, UUID parent, UUID cohort, long version) {
        fixture.setRunLifecycle(parent, "SUCCEEDED");
        return fixture.deployments.retire(space, new CohortCommand(cohort, version, null, "tester", null)).value();
    }

    private RetirementResult retire(UUID cohort) {
        long version = fixture.deployments.cohort(workspace, cohort).transitionVersion();
        return fixture.deployments.retire(workspace, new CohortCommand(cohort, version, null, "tester", "drained")).value();
    }
}
