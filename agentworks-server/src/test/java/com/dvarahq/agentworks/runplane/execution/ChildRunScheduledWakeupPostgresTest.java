package com.dvarahq.agentworks.runplane.execution;

import com.dvarahq.agentworks.child.ChildFailurePolicy;
import com.dvarahq.agentworks.child.ChildRunBudget;
import com.dvarahq.agentworks.child.ChildRunException;
import com.dvarahq.agentworks.child.ChildRunRequest;
import com.dvarahq.agentworks.child.ChildRunStatus;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.CohortCommand;
import com.dvarahq.agentworks.domain.run.RunLifecycle;
import com.dvarahq.agentworks.domain.run.RunInvoker;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import com.dvarahq.agentworks.agent.springai.CapabilityToolCallbackProvider;
import com.dvarahq.agentworks.agent.springai.SpringAiAgentExecutor;
import com.dvarahq.agentworks.agent.springai.SpringAiExecutorSettings;
import com.dvarahq.agentworks.gateway.CapabilityPolicy;
import com.dvarahq.agentworks.gateway.ConfiguredGateway;
import com.dvarahq.agentworks.gateway.GatewayRegistry;
import com.dvarahq.agentworks.gateway.ModelGateway;
import com.dvarahq.agentworks.gateway.fake.DeterministicFakeGatewayAdapter;
import com.dvarahq.agentworks.capability.DeterministicCapabilityResolver;
import com.dvarahq.agentworks.storage.capability.JdbcCapabilityRegistry;
import io.micrometer.observation.ObservationRegistry;
import com.dvarahq.agentworks.session.InboxMessageType;
import com.dvarahq.agentworks.storage.child.JdbcChildRunAuthority;
import com.dvarahq.agentworks.storage.lifecycle.LifecycleRetry;
import com.dvarahq.agentworks.storage.session.JdbcSessionInboxAuthority;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** PostgreSQL authority and recovery evidence for issue #57. */
class ChildRunScheduledWakeupPostgresTest {

    @Test
    void childPinsItsOwnApprovedRouteBlocksWithoutAWorkerAndResumesExactlyOnce() {
        RunScenario scenario = new RunScenario();
        RunInvoker invoker = new RunInvoker(UUID.randomUUID(), scenario.workspace.value(), UUID.randomUUID(),
                UUID.randomUUID(), "session");
        var admittedParent = scenario.fixture.admissions.admitRoot(scenario.workspace,
                scenario.promotion.deploymentId(), null, Map.of("task", "test", "secret", "parent-only-secret"),
                new com.dvarahq.agentworks.runplane.admission.RunAdmissionService.Options(true, null,
                        "test:authenticated", invoker));
        var parent = scenario.claim("parent-worker");
        assertThat(parent.run().id()).isEqualTo(admittedParent.run().id());
        var coordinator = children(scenario);
        ChildRunRequest request = request(scenario, "child-one");

        var relationship = coordinator.start(parent.lease(), request);
        assertThat(relationship.status()).isEqualTo(ChildRunStatus.RUNNING);
        assertThat(relationship.childReleaseId()).isEqualTo(parent.run().pins().releaseId());
        assertThat(relationship.childDeploymentId()).isEqualTo(scenario.promotion.deploymentId());
        assertThat(scenario.engine.run(scenario.workspace, parent.run().id()).lifecycle()).isEqualTo(RunLifecycle.BLOCKED);
        assertThat(scenario.engine.run(scenario.workspace, parent.run().id()).control().leaseOwner()).isNull();
        assertThat(coordinator.start(parent.lease(), request)).isEqualTo(relationship);

        var child = scenario.claim("child-worker");
        assertThat(child.run().id()).isEqualTo(relationship.childRunId());
        assertThat(child.run().pins().admission()).isEqualTo("CHILD");
        assertThat(child.run().pins().parentRunId()).isEqualTo(parent.run().id());
        assertThat(child.run().pins().rootRunId()).isEqualTo(parent.run().pins().rootRunId());
        assertThat(child.invoker()).contains(invoker);

        var childRequestSeen = new java.util.concurrent.atomic.AtomicReference<
                com.dvarahq.agentworks.gateway.ModelRequest>();
        var adapter = DeterministicFakeGatewayAdapter.builder("direct")
                .model("coding-premium", "coding-model-2026-09-01")
                .script(invocation -> {
                    childRequestSeen.set(invocation.request());
                    return com.dvarahq.agentworks.gateway.fake.FakeReply.text(java.util.List.of("child done"),
                            com.dvarahq.agentworks.gateway.ModelUsage.UNREPORTED);
                }).build();
        var gateway = new ModelGateway(GatewayRegistry.of(java.util.List.of(
                new ConfiguredGateway(adapter, CapabilityPolicy.DEFAULT, null))), null, null, event -> { },
                Clock.systemUTC());
        var executor = new SpringAiAgentExecutor(gateway, () -> new org.springframework.ai.tool.ToolCallback[0],
                CapabilityToolCallbackProvider.from(), ObservationRegistry.NOOP,
                new SpringAiExecutorSettings(Duration.ofSeconds(10), 64, 4));
        var resolver = new RunCapabilityResolver(scenario.fixture.tenants,
                new JdbcCapabilityRegistry(scenario.fixture.jdbc), new DeterministicCapabilityResolver(),
                RunCapabilityResolver.Settings.DEFAULTS);
        var handler = new CapabilityResolvingRunHandler(executor, resolver);
        try (var worker = new RunWorker(scenario.engine, handler, "real-spring-ai-child", scenario.scope(),
                RunScenario.LEASE)) {
            // The child was claimed above only to inspect its durable execution context; release that lease by
            // expiring it so the production worker can recover and execute the exact admitted child.
            scenario.expireLease(child.run().id());
            assertThat(worker.pollOnce()).isEqualTo(RunWorker.Poll.EXECUTED);
        }
        assertThat(scenario.engine.run(scenario.workspace, child.run().id()))
                .satisfies(executed -> assertThat(executed.lifecycle())
                        .as("production Spring AI child terminal code %s", executed.summary().terminalCode())
                        .isEqualTo(RunLifecycle.SUCCEEDED));
        assertThat(childRequestSeen.get().messages()).extracting(message -> message.content())
                .anySatisfy(content -> assertThat(content).contains("public-42"))
                .allSatisfy(content -> assertThat(content).doesNotContain("parent-only-secret"));
        assertThat(coordinator.reconcile(scenario.workspace, 10)).isEqualTo(1);
        assertThat(coordinator.reconcile(scenario.workspace, 10)).isZero();
        assertThat(scenario.engine.run(scenario.workspace, parent.run().id()).lifecycle()).isEqualTo(RunLifecycle.QUEUED);
        assertThat(new JdbcChildRunAuthority(scenario.fixture.jdbc, scenario.fixture.transactions)
                .findChild(scenario.workspace, relationship.id()).orElseThrow().status())
                .isEqualTo(ChildRunStatus.SUCCEEDED);
        assertThat(scenario.count("select count(*) from cohort_work_item where workspace_id = ? and category = 'CHILD_WORK' "
                + "and subject_id = ? and state = 'TERMINAL'", scenario.workspace.value(), relationship.id())).isOne();
    }

    @Test
    void depthFanOutBudgetAndReleaseChecksFailBeforeAllocatingMoreWork() {
        RunScenario scenario = new RunScenario();
        var parent = scenario.started("bounded-parent", RunScenario.LEASE);
        var authority = new JdbcChildRunAuthority(scenario.fixture.jdbc, scenario.fixture.transactions);
        var coordinator = new DurableChildRunCoordinator(scenario.fixture.transactions, LifecycleRetry.standard(),
                scenario.engine, scenario.fixture.admissions, authority, new DurableChildRunCoordinator.Policy(8, 1, 1));

        ChildRunRequest oversized = new ChildRunRequest(scenario.promotion.deploymentId(), parent.run().pins().releaseId(),
                "too expensive", Map.of(), Set.of(), new ChildRunBudget(Long.MAX_VALUE, 0, 0, 1,
                BigDecimal.ZERO.setScale(6)), ChildFailurePolicy.FAIL_PARENT, "oversized");
        assertThatThrownBy(() -> coordinator.start(parent.lease(), oversized))
                .isInstanceOfSatisfying(ChildRunException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ChildRunException.Code.BUDGET_EXCEEDED));

        ChildRunRequest wrongRelease = new ChildRunRequest(scenario.promotion.deploymentId(), UUID.randomUUID(),
                "wrong release", Map.of(), Set.of(), budget(), ChildFailurePolicy.FAIL_PARENT, "wrong-release");
        assertThatThrownBy(() -> coordinator.start(parent.lease(), wrongRelease))
                .isInstanceOfSatisfying(ChildRunException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ChildRunException.Code.RELEASE_MISMATCH));
        assertThat(scenario.engine.run(scenario.workspace, parent.run().id()).lifecycle()).isEqualTo(RunLifecycle.ACTIVE);
        assertThat(scenario.count("select count(*) from child_run where workspace_id = ?", scenario.workspace.value())).isZero();
    }

    @Test
    void nestedDepthRootTotalsAndReservationsRemainBoundedBeforeAllocation() {
        RunScenario depthScenario = new RunScenario();
        var root = depthScenario.started("depth-root", RunScenario.LEASE);
        var depthCoordinator = new DurableChildRunCoordinator(depthScenario.fixture.transactions,
                LifecycleRetry.standard(), depthScenario.engine, depthScenario.fixture.admissions,
                new JdbcChildRunAuthority(depthScenario.fixture.jdbc, depthScenario.fixture.transactions),
                new DurableChildRunCoordinator.Policy(1, 10, 10));
        depthCoordinator.start(root.lease(), request(depthScenario, "depth-one"));
        var child = depthScenario.claim("depth-child");
        long beforeDepth = depthScenario.count("select count(*) from child_run where workspace_id = ?",
                depthScenario.workspace.value());
        assertThatThrownBy(() -> depthCoordinator.start(child.lease(), request(depthScenario, "depth-two")))
                .isInstanceOfSatisfying(ChildRunException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ChildRunException.Code.DEPTH_LIMIT));
        assertThat(depthScenario.count("select count(*) from child_run where workspace_id = ?",
                depthScenario.workspace.value())).isEqualTo(beforeDepth);

        RunScenario totalScenario = new RunScenario();
        var totalRoot = totalScenario.started("total-root", RunScenario.LEASE);
        var totalCoordinator = new DurableChildRunCoordinator(totalScenario.fixture.transactions,
                LifecycleRetry.standard(), totalScenario.engine, totalScenario.fixture.admissions,
                new JdbcChildRunAuthority(totalScenario.fixture.jdbc, totalScenario.fixture.transactions),
                new DurableChildRunCoordinator.Policy(8, 10, 1));
        var first = totalCoordinator.start(totalRoot.lease(), request(totalScenario, "total-one"));
        var firstChild = totalScenario.claim("total-child");
        totalScenario.engine.complete(firstChild.lease(), Map.of());
        totalCoordinator.reconcile(totalScenario.workspace, 10);
        var resumed = totalScenario.claim("total-root-resumed");
        assertThatThrownBy(() -> totalCoordinator.start(resumed.lease(), request(totalScenario, "total-two")))
                .isInstanceOfSatisfying(ChildRunException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ChildRunException.Code.CHILD_LIMIT));
        assertThat(totalScenario.count("select count(*) from child_run where workspace_id = ?",
                totalScenario.workspace.value())).isOne();
        assertThat(first.rootRunId()).isEqualTo(totalRoot.run().id());

        RunScenario fanOutScenario = new RunScenario();
        var fanOutRoot = fanOutScenario.started("fanout-root", RunScenario.LEASE);
        var fanOutCoordinator = new DurableChildRunCoordinator(fanOutScenario.fixture.transactions,
                LifecycleRetry.standard(), fanOutScenario.engine, fanOutScenario.fixture.admissions,
                new JdbcChildRunAuthority(fanOutScenario.fixture.jdbc, fanOutScenario.fixture.transactions),
                new DurableChildRunCoordinator.Policy(8, 1, 10));
        fanOutCoordinator.start(fanOutRoot.lease(), request(fanOutScenario, "fanout-one"));
        var fanOutChild = fanOutScenario.claim("fanout-child");
        fanOutScenario.engine.complete(fanOutChild.lease(), Map.of());
        fanOutCoordinator.reconcile(fanOutScenario.workspace, 10);
        var fanOutResumed = fanOutScenario.claim("fanout-root-resumed");
        assertThatThrownBy(() -> fanOutCoordinator.start(fanOutResumed.lease(), request(fanOutScenario, "fanout-two")))
                .isInstanceOfSatisfying(ChildRunException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ChildRunException.Code.FAN_OUT_LIMIT));
        assertThat(fanOutScenario.count("select count(*) from child_run where workspace_id = ?",
                fanOutScenario.workspace.value())).isOne();

        RunScenario budgetScenario = new RunScenario();
        var budgetRoot = budgetScenario.started("budget-root", RunScenario.LEASE);
        ChildRunBudget aggregate = new ChildRunBudget(150, 10, 10, 60_000, new BigDecimal("2.000000"));
        var budgetCoordinator = new DurableChildRunCoordinator(budgetScenario.fixture.transactions,
                LifecycleRetry.standard(), budgetScenario.engine, budgetScenario.fixture.admissions,
                new JdbcChildRunAuthority(budgetScenario.fixture.jdbc, budgetScenario.fixture.transactions),
                new DurableChildRunCoordinator.Policy(8, 10, 10, aggregate));
        budgetCoordinator.start(budgetRoot.lease(), request(budgetScenario, "budget-one"));
        var budgetChild = budgetScenario.claim("budget-child");
        budgetScenario.engine.complete(budgetChild.lease(), Map.of());
        budgetCoordinator.reconcile(budgetScenario.workspace, 10);
        var budgetResumed = budgetScenario.claim("budget-root-resumed");
        assertThatThrownBy(() -> budgetCoordinator.start(budgetResumed.lease(), request(budgetScenario, "budget-two")))
                .isInstanceOfSatisfying(ChildRunException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ChildRunException.Code.BUDGET_EXCEEDED));
        assertThat(budgetScenario.count("select count(*) from child_run where workspace_id = ?",
                budgetScenario.workspace.value())).isOne();
    }

    @Test
    void concurrentSameKeyReplaysOneChildAndMismatchedFingerprintIsRejected() throws Exception {
        RunScenario scenario = new RunScenario();
        var parent = scenario.started("race-parent", RunScenario.LEASE);
        var coordinator = children(scenario);
        ChildRunRequest request = request(scenario, "same-key");

        var outcomes = RunScenario.race(2, ignored -> coordinator.start(parent.lease(), request));
        assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome).isInstanceOf(
                com.dvarahq.agentworks.child.ChildRunRecord.class));
        assertThat(outcomes).extracting(outcome -> ((com.dvarahq.agentworks.child.ChildRunRecord) outcome).id())
                .containsOnly(((com.dvarahq.agentworks.child.ChildRunRecord) outcomes.getFirst()).id());
        assertThat(scenario.count("select count(*) from child_run where workspace_id = ?", scenario.workspace.value()))
                .isOne();
        ChildRunRequest mismatch = new ChildRunRequest(request.targetDeploymentId(), request.expectedReleaseId(),
                "different task", request.context(), request.tools(), request.budget(), request.failurePolicy(),
                request.idempotencyKey());
        assertThatThrownBy(() -> coordinator.start(parent.lease(), mismatch))
                .isInstanceOfSatisfying(ChildRunException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ChildRunException.Code.IDEMPOTENCY_CONFLICT));
    }

    @Test
    void concurrentDifferentKeysCannotOversubscribeRootCountOrReservations() throws Exception {
        RunScenario countScenario = new RunScenario();
        var countParent = countScenario.started("count-race", RunScenario.LEASE);
        var countCoordinator = new DurableChildRunCoordinator(countScenario.fixture.transactions,
                LifecycleRetry.standard(), countScenario.engine, countScenario.fixture.admissions,
                new JdbcChildRunAuthority(countScenario.fixture.jdbc, countScenario.fixture.transactions),
                new DurableChildRunCoordinator.Policy(8, 10, 1));
        var countOutcomes = RunScenario.race(2, index -> countCoordinator.start(countParent.lease(),
                request(countScenario, "different-count-" + index)));
        assertOneAdmissionAndOneRefusal(countOutcomes, ChildRunException.Code.CHILD_LIMIT);
        assertThat(countScenario.count("select count(*) from child_run where workspace_id = ?",
                countScenario.workspace.value())).isOne();

        RunScenario budgetScenario = new RunScenario();
        var budgetParent = budgetScenario.started("budget-race", RunScenario.LEASE);
        var aggregate = new ChildRunBudget(150, 10, 10, 120_000, new BigDecimal("2.000000"));
        var budgetCoordinator = new DurableChildRunCoordinator(budgetScenario.fixture.transactions,
                LifecycleRetry.standard(), budgetScenario.engine, budgetScenario.fixture.admissions,
                new JdbcChildRunAuthority(budgetScenario.fixture.jdbc, budgetScenario.fixture.transactions),
                new DurableChildRunCoordinator.Policy(8, 10, 10, aggregate));
        var budgetOutcomes = RunScenario.race(2, index -> budgetCoordinator.start(budgetParent.lease(),
                request(budgetScenario, "different-budget-" + index)));
        assertOneAdmissionAndOneRefusal(budgetOutcomes, ChildRunException.Code.BUDGET_EXCEEDED);
        assertThat(budgetScenario.count("select count(*) from child_run where workspace_id = ?",
                budgetScenario.workspace.value())).isOne();
    }

    @Test
    void suspensionAndRevocationSerializeAgainstChildCreation() throws Exception {
        for (boolean revoke : java.util.List.of(false, true)) {
            RunScenario scenario = new RunScenario();
            var parent = scenario.started(revoke ? "revoke-race" : "suspend-race", RunScenario.LEASE);
            var coordinator = children(scenario);
            var outcomes = RunScenario.race(2, index -> {
                if (index == 0) return coordinator.start(parent.lease(), request(scenario, "state-race"));
                return revoke
                        ? scenario.fixture.deployments.revoke(scenario.workspace, new CohortCommand(
                                scenario.promotion.cohortId(), 2, null, "security", "incident"))
                        : scenario.fixture.deployments.suspend(scenario.workspace, new CohortCommand(
                                scenario.promotion.cohortId(), 2, null, "operator", "incident"));
            });
            assertThat(outcomes).anySatisfy(outcome -> assertThat(outcome)
                    .isNotInstanceOf(com.dvarahq.agentworks.child.ChildRunRecord.class));
            assertThat(scenario.count("select count(*) from child_run where workspace_id = ?",
                    scenario.workspace.value())).isLessThanOrEqualTo(1);
            long before = scenario.count("select count(*) from child_run where workspace_id = ?",
                    scenario.workspace.value());
            assertThatThrownBy(() -> coordinator.start(parent.lease(), request(scenario, "after-state-change")))
                    .isInstanceOfAny(ChildRunException.class, RunEngineException.class,
                            com.dvarahq.agentworks.runplane.admission.RunAdmissionRejectedException.class);
            assertThat(scenario.count("select count(*) from child_run where workspace_id = ?",
                    scenario.workspace.value())).isEqualTo(before);

            RunScenario fenced = new RunScenario();
            var fencedParent = fenced.started(revoke ? "revoked-parent" : "suspended-parent", RunScenario.LEASE);
            if (revoke) {
                fenced.fixture.deployments.revoke(fenced.workspace, new CohortCommand(fenced.promotion.cohortId(),
                        2, null, "security", "incident"));
            } else {
                fenced.fixture.deployments.suspend(fenced.workspace, new CohortCommand(fenced.promotion.cohortId(),
                        2, null, "operator", "incident"));
            }
            assertThatThrownBy(() -> children(fenced).start(fencedParent.lease(), request(fenced, "fenced")))
                    .isInstanceOfAny(ChildRunException.class, RunEngineException.class,
                            com.dvarahq.agentworks.runplane.admission.RunAdmissionRejectedException.class);
            assertThat(fenced.count("select count(*) from child_run where workspace_id = ?",
                    fenced.workspace.value())).isZero();
        }
    }

    @Test
    void parentCancellationCancelsItsChildAndClosesBothCohortBlockers() {
        RunScenario scenario = new RunScenario();
        var parent = scenario.started("cancel-parent", RunScenario.LEASE);
        var coordinator = children(scenario);
        var relationship = coordinator.start(parent.lease(), request(scenario, "cancel-child"));
        scenario.engine.cancel(scenario.workspace, parent.run().id(), "operator:test", "stop tree");

        assertThat(coordinator.reconcile(scenario.workspace, 10)).isEqualTo(1);
        assertThat(scenario.engine.run(scenario.workspace, relationship.childRunId()).lifecycle())
                .isEqualTo(RunLifecycle.CANCELLED);
        assertThat(new JdbcChildRunAuthority(scenario.fixture.jdbc, scenario.fixture.transactions)
                .findChild(scenario.workspace, relationship.id()).orElseThrow().status())
                .isEqualTo(ChildRunStatus.CANCELLED);
    }

    @Test
    void postgresRlsHidesChildRelationshipsFromAnotherTenant() {
        RunScenario scenario = new RunScenario();
        var parent = scenario.started("tenant-parent", RunScenario.LEASE);
        var relationship = children(scenario).start(parent.lease(), request(scenario, "tenant-child"));
        WorkspaceId foreign = new WorkspaceId(UUID.randomUUID());

        Long visible = scenario.fixture.transactions.execute(status -> {
            scenario.fixture.jdbc.execute("set local role agentworks_run_plane");
            scenario.fixture.jdbc.queryForObject("select set_config('agentworks.workspace_id', ?, true)",
                    String.class, foreign.value().toString());
            return scenario.fixture.jdbc.queryForObject("select count(*) from child_run where id = ?",
                    Long.class, relationship.id());
        });

        assertThat(visible).isZero();
    }

    @Test
    void failParentPolicyPropagatesAChildFailureInsteadOfResuming() {
        RunScenario scenario = new RunScenario();
        var parent = scenario.started("failure-parent", RunScenario.LEASE);
        var coordinator = children(scenario);
        ChildRunRequest base = request(scenario, "failing-child");
        var relationship = coordinator.start(parent.lease(), new ChildRunRequest(base.targetDeploymentId(),
                base.expectedReleaseId(), base.task(), base.context(), base.tools(), base.budget(),
                ChildFailurePolicy.FAIL_PARENT, base.idempotencyKey()));
        var child = scenario.claim("failing-child-worker");
        scenario.engine.fail(child.lease(), "CHILD_TEST_FAILURE", "expected child failure");

        assertThat(coordinator.reconcile(scenario.workspace, 10)).isEqualTo(1);
        assertThat(scenario.engine.run(scenario.workspace, relationship.parentRunId()).lifecycle())
                .isEqualTo(RunLifecycle.FAILED);
        assertThat(scenario.engine.run(scenario.workspace, relationship.parentRunId()).summary().terminalCode())
                .isEqualTo("CHILD_RUN_FAILED");
    }

    @Test
    void databaseTimeWakeupSurvivesReconstructionAndDeliversOneInboxEvent() {
        RunScenario scenario = new RunScenario();
        var active = scenario.started("sleeping-parent", RunScenario.LEASE);
        var inbox = new JdbcSessionInboxAuthority(scenario.fixture.jdbc, scenario.fixture.transactions);
        UUID sessionId = UUID.randomUUID();
        inbox.create(scenario.workspace, sessionId, active.run().pins().deploymentId());
        inbox.linkRun(scenario.workspace, sessionId, active.run().id());
        var authority = new JdbcChildRunAuthority(scenario.fixture.jdbc, scenario.fixture.transactions);
        var first = new ScheduledWakeupCoordinator(scenario.fixture.transactions, LifecycleRetry.standard(),
                scenario.engine, authority, inbox);
        var scheduled = first.schedule(active.lease(), sessionId, Instant.EPOCH, "wake-correlation", "wake-once",
                Map.of("reason", "timer"));
        assertThat(scenario.engine.run(scenario.workspace, active.run().id()).lifecycle()).isEqualTo(RunLifecycle.BLOCKED);
        assertThat(authority.reconciliationCandidates(10)).singleElement()
                .satisfies(candidate -> assertThat(candidate.dueWakeups()).isTrue());

        var restarted = new ScheduledWakeupCoordinator(scenario.fixture.transactions, LifecycleRetry.standard(),
                scenario.engine, new JdbcChildRunAuthority(scenario.fixture.jdbc, scenario.fixture.transactions),
                new JdbcSessionInboxAuthority(scenario.fixture.jdbc, scenario.fixture.transactions));
        assertThat(restarted.deliverOne(scenario.workspace)).isTrue();
        assertThat(restarted.deliverOne(scenario.workspace)).isFalse();
        assertThat(scenario.engine.run(scenario.workspace, active.run().id()).lifecycle()).isEqualTo(RunLifecycle.QUEUED);
        assertThat(inbox.list(scenario.workspace, sessionId, null, null, 10)).singleElement()
                .satisfies(message -> {
                    assertThat(message.type()).isEqualTo(InboxMessageType.SCHEDULED_WAKEUP);
                    assertThat(message.payload()).containsEntry("wakeupId", scheduled.id().toString());
                });
        assertThat(scenario.count("select count(*) from cohort_work_item where workspace_id = ? and category = 'WAKEUP' "
                + "and subject_id = ? and state = 'TERMINAL'", scenario.workspace.value(), scheduled.id())).isOne();
    }

    @Test
    void terminalRunCancelsFutureWakeupAndLedgerWithoutWaitingForDueTime() {
        RunScenario scenario = new RunScenario();
        var active = scenario.started("future-wakeup", RunScenario.LEASE);
        var inbox = new JdbcSessionInboxAuthority(scenario.fixture.jdbc, scenario.fixture.transactions);
        UUID sessionId = UUID.randomUUID();
        inbox.create(scenario.workspace, sessionId, active.run().pins().deploymentId());
        inbox.linkRun(scenario.workspace, sessionId, active.run().id());
        var authority = new JdbcChildRunAuthority(scenario.fixture.jdbc, scenario.fixture.transactions);
        var wakeups = new ScheduledWakeupCoordinator(scenario.fixture.transactions, LifecycleRetry.standard(),
                scenario.engine, authority, inbox);
        var scheduled = wakeups.schedule(active.lease(), sessionId, Instant.now().plusSeconds(86_400),
                "future", "future-once", Map.of());
        scenario.engine.cancel(scenario.workspace, active.run().id(), "operator:test", "cancel timer");

        assertThat(authority.cancelTerminalWakeups(scenario.workspace, 10)).isOne();
        assertThat(scenario.count("select count(*) from scheduled_wakeup where workspace_id = ? and id = ? "
                + "and status = 'CANCELLED'", scenario.workspace.value(), scheduled.id())).isOne();
        assertThat(scenario.count("select count(*) from cohort_work_item where workspace_id = ? and category = 'WAKEUP' "
                + "and subject_id = ? and state = 'TERMINAL'", scenario.workspace.value(), scheduled.id())).isOne();
    }

    private static DurableChildRunCoordinator children(RunScenario scenario) {
        return new DurableChildRunCoordinator(scenario.fixture.transactions, LifecycleRetry.standard(), scenario.engine,
                scenario.fixture.admissions, new JdbcChildRunAuthority(scenario.fixture.jdbc, scenario.fixture.transactions),
                DurableChildRunCoordinator.Policy.defaults());
    }
    private static ChildRunRequest request(RunScenario scenario, String key) {
        UUID releaseId = scenario.engine.run(scenario.workspace,
                scenario.fixture.jdbc.queryForObject("select id from agent_run where workspace_id = ? and lifecycle = 'ACTIVE'",
                        UUID.class, scenario.workspace.value())).pins().releaseId();
        return new ChildRunRequest(scenario.promotion.deploymentId(), releaseId, "perform bounded work",
                Map.of("documentId", "public-42"), Set.of("lookup"), budget(), ChildFailurePolicy.RETURN_OUTCOME, key);
    }
    private static ChildRunBudget budget() {
        return new ChildRunBudget(100, 2, 2, 60_000, new BigDecimal("1.000000"));
    }

    private static void assertOneAdmissionAndOneRefusal(java.util.List<Object> outcomes,
            ChildRunException.Code expected) {
        assertThat(outcomes).filteredOn(com.dvarahq.agentworks.child.ChildRunRecord.class::isInstance).hasSize(1);
        assertThat(outcomes).filteredOn(ChildRunException.class::isInstance).singleElement()
                .satisfies(outcome -> assertThat(((ChildRunException) outcome).code()).isEqualTo(expected));
    }
}
