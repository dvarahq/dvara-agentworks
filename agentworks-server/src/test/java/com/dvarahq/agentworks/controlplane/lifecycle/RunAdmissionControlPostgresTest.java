package com.dvarahq.agentworks.controlplane.lifecycle;

import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.DeploymentConfiguration;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.PlacementConstraints;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.PromotionCommand;
import com.dvarahq.agentworks.domain.run.RunLimits;
import com.dvarahq.agentworks.domain.run.ClaimScope;
import com.dvarahq.agentworks.runplane.admission.RunAdmissionPolicy;
import com.dvarahq.agentworks.runplane.admission.RunAdmissionPolicy.OverloadOutcome;
import com.dvarahq.agentworks.runplane.admission.RunAdmissionPolicy.PlacementCapabilities;
import com.dvarahq.agentworks.runplane.admission.RunAdmissionRejectedException;
import com.dvarahq.agentworks.runplane.admission.RunAdmissionService;
import com.dvarahq.agentworks.runplane.execution.RunLimitsPolicy;
import com.dvarahq.agentworks.runplane.reconciliation.DeploymentReconciler;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunAdmissionControlPostgresTest {
    @Test
    void deploymentBulkheadRefusesBeforeRunCreationAndPersistsDecisionEvidence() {
        LifecycleFixture fixture = LifecycleFixture.postgres();
        var workspace = fixture.workspace();
        var environment = fixture.environment(workspace, "capacity");
        var promoted = fixture.promote(workspace, environment, "api", fixture.release(workspace, "capacity", "1.0.0"));
        RunAdmissionService admissions = admissions(fixture, policy(1, OverloadOutcome.REJECTED, capabilities()));

        var first = admissions.admitRoot(workspace, promoted.deploymentId(), "one", Map.of()).run();

        assertThatThrownBy(() -> admissions.admitRoot(workspace, promoted.deploymentId(), "two", Map.of()))
                .isInstanceOfSatisfying(RunAdmissionRejectedException.class,
                        rejected -> assertThat(rejected.code()).isEqualTo("DEPLOYMENT_OUTSTANDING_LIMIT"));
        assertThat(fixture.count("select count(*) from agent_run where deployment_id = ?", promoted.deploymentId()))
                .isOne();
        assertThat(fixture.count("select count(*) from run_admission_decision where deployment_id = ? and outcome = 'ACCEPTED'",
                promoted.deploymentId())).isOne();
        assertThat(fixture.count("select count(*) from run_admission_decision where deployment_id = ? and outcome = 'REJECTED' "
                + "and code = 'DEPLOYMENT_OUTSTANDING_LIMIT' and run_id is null", promoted.deploymentId())).isOne();

        assertThat(admissions.admitRoot(workspace, promoted.deploymentId(), "one", Map.of()).run().id())
                .isEqualTo(first.id());
    }

    @Test
    void incompatiblePlacementAndDeferredOverloadAreTypedAndAudited() {
        LifecycleFixture fixture = LifecycleFixture.postgres();
        var workspace = fixture.workspace();
        var environment = fixture.environment(workspace, "placement");
        var release = fixture.release(workspace, "placement", "1.0.0");
        var configuration = new DeploymentConfiguration("ISOLATED", Map.of(), List.of(),
                new PlacementConstraints("zone-b", "us", "java", "trusted"));
        var promoted = fixture.deployments.promote(workspace, new PromotionCommand(environment.id(), "zoned",
                release.id(), configuration, null, null, "tester", "placement test")).value();
        var reconciler = new DeploymentReconciler(fixture.tenants, fixture.lifecycle);
        assertThat(reconciler.observeAssignment(workspace, promoted.deploymentId(), "SHARED").placementDrift()).isTrue();
        assertThat(reconciler.observeAssignment(workspace, promoted.deploymentId(), "ISOLATED").placementDrift()).isFalse();
        RunAdmissionService incompatible = admissions(fixture,
                policy(1, OverloadOutcome.DEFERRED, new PlacementCapabilities(Set.of("zone-a"), Set.of("us"),
                        Set.of("java"), Set.of("trusted"))));

        assertThatThrownBy(() -> incompatible.admitRoot(workspace, promoted.deploymentId(), null, Map.of()))
                .isInstanceOfSatisfying(RunAdmissionRejectedException.class,
                        rejected -> assertThat(rejected.code()).isEqualTo("PLACEMENT_INCOMPATIBLE"));

        RunAdmissionService bounded = admissions(fixture, policy(1, OverloadOutcome.DEFERRED,
                new PlacementCapabilities(Set.of("zone-b"), Set.of("us"), Set.of("java"), Set.of("trusted"))));
        bounded.admitRoot(workspace, promoted.deploymentId(), null, Map.of());
        assertThatThrownBy(() -> bounded.admitRoot(workspace, promoted.deploymentId(), null, Map.of()))
                .isInstanceOfSatisfying(RunAdmissionRejectedException.class,
                        rejected -> assertThat(rejected.code()).isEqualTo("ADMISSION_DEFERRED"));
        assertThat(fixture.count("select count(*) from run_admission_decision where deployment_id = ? and outcome = 'DEFERRED'",
                promoted.deploymentId())).isOne();
    }

    @Test
    void sharedWorkersServeAnotherWorkspaceBeforeReturningToAnOlderBacklog() {
        LifecycleFixture fixture = LifecycleFixture.postgres();
        String executorType = "fairness-test-executor";
        var workspaceA = fixture.workspace();
        var environmentA = fixture.environment(workspaceA, "fair-a");
        var deploymentA = fixture.promote(workspaceA, environmentA, "a",
                fixture.release(workspaceA, "fair-a", "1.0.0", executorType));
        var a1 = fixture.admissions.admitRoot(workspaceA, deploymentA.deploymentId(), null, Map.of(),
                RunAdmissionService.Options.SUBMIT).run();
        var a2 = fixture.admissions.admitRoot(workspaceA, deploymentA.deploymentId(), null, Map.of(),
                RunAdmissionService.Options.SUBMIT).run();

        var workspaceB = fixture.workspace();
        var environmentB = fixture.environment(workspaceB, "fair-b");
        var deploymentB = fixture.promote(workspaceB, environmentB, "b",
                fixture.release(workspaceB, "fair-b", "1.0.0", executorType));
        var b1 = fixture.admissions.admitRoot(workspaceB, deploymentB.deploymentId(), null, Map.of(),
                RunAdmissionService.Options.SUBMIT).run();

        ClaimScope scope = ClaimScope.any(Set.of(ReleaseService.MANIFEST_VERSION)).executing(Set.of(executorType))
                .compatibleWith("fairness-test");
        var first = fixture.runStore.claimNext(scope, (run, transaction) -> run).orElseThrow();
        var second = fixture.runStore.claimNext(scope.alsoExcluding(Set.of(first.id())),
                (run, transaction) -> run).orElseThrow();

        assertThat(Set.of(first.pins().workspaceId(), second.pins().workspaceId()))
                .containsExactlyInAnyOrder(workspaceA.value(), workspaceB.value());
    }

    @Test
    void workspaceScopedWorkersDoNotContendOnTheSharedPoolFairnessCursor() {
        LifecycleFixture fixture = LifecycleFixture.postgres();
        var workspace = fixture.workspace();
        var environment = fixture.environment(workspace, "pinned-worker");
        var deployment = fixture.promote(workspace, environment, "pinned",
                fixture.release(workspace, "pinned-worker", "1.0.0"));
        var run = fixture.admissions.admitRoot(workspace, deployment.deploymentId(), null, Map.of(),
                RunAdmissionService.Options.SUBMIT).run();
        fixture.jdbc.update("insert into run_admission_fairness(workspace_id, last_claimed_at) "
                + "values (?, timestamp with time zone '2000-01-01 00:00:00Z')", workspace.value());

        var claimed = fixture.runStore.claimNext(ClaimScope.any(Set.of(ReleaseService.MANIFEST_VERSION))
                .executing(Set.of("spring-ai")).in(workspace).compatibleWith("pinned-worker"),
                (candidate, transaction) -> candidate).orElseThrow();

        assertThat(claimed.id()).isEqualTo(run.id());
        assertThat(fixture.jdbc.queryForObject("select last_claimed_at = timestamp with time zone "
                + "'2000-01-01 00:00:00Z' from run_admission_fairness where workspace_id = ?", Boolean.class,
                workspace.value())).isTrue();
    }

    private static RunAdmissionService admissions(LifecycleFixture fixture, RunAdmissionPolicy policy) {
        return new RunAdmissionService(fixture.tenants, fixture.lifecycle, fixture.runRecords, fixture.clock,
                fixture.retry, LifecycleFixture.TEST_DIGESTS, fixture.runStore, fixture.engine,
                RunLimitsPolicy.fixed(RunLimits.UNBOUNDED), policy);
    }

    private static PlacementCapabilities capabilities() {
        return new PlacementCapabilities(Set.of(), Set.of(), Set.of(), Set.of());
    }

    private static RunAdmissionPolicy policy(long deploymentOutstanding, OverloadOutcome outcome,
            PlacementCapabilities capabilities) {
        return new RunAdmissionPolicy("test-v1", 10, deploymentOutstanding, 10, 10, 100,
                Duration.ofMinutes(1), 10, Map.of("SHARED", 10L, "ISOLATED", 10L, "DEDICATED", 10L),
                null, null, capabilities, outcome);
    }
}
