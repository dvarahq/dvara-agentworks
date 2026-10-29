package com.dvarahq.agentworks.controlplane.lifecycle;

import com.dvarahq.agentworks.controlplane.deployment.DeploymentLifecycleService;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.CohortCommand;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.ContinuityAction;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.DeploymentConfiguration;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.EvidenceKind;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.EvidenceReference;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.GateType;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.PromotionCommand;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.PromotionGate;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.PromotionPolicy;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.PromotionResult;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.PromotionEvidence;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.OverrideRequest;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.RollbackCommand;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import com.dvarahq.agentworks.spec.AgentSpecValidationException;
import com.dvarahq.agentworks.domain.lifecycle.state.LifecycleRows.CohortRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.ArrayList;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Promotion, rollback, redeployment, gates, cohort transitions, and immutability on PostgreSQL (ADR-007, ADR-027). */
class PromotionLifecyclePostgresTest {
    private LifecycleFixture fixture;
    private WorkspaceId workspace;

    @BeforeEach
    void setUp() {
        fixture = LifecycleFixture.postgres();
        workspace = fixture.workspace();
    }

    @Test
    void customerDefinedEnvironmentNamesAreAcceptedWithoutABuiltInSet() {
        for (String name : List.of("qa-eu-west", "Customer Acceptance", "prod.2026", "sandbox_7")) {
            assertThat(fixture.environment(workspace, name).name()).isEqualTo(name);
        }
        assertThat(fixture.environments.list(workspace)).extracting(LifecycleModel.EnvironmentView::name)
                .containsExactlyInAnyOrder("qa-eu-west", "Customer Acceptance", "prod.2026", "sandbox_7");
        assertThatThrownBy(() -> fixture.environment(workspace, "qa-eu-west"))
                .isInstanceOfSatisfying(LifecycleConflictException.class,
                        conflict -> assertThat(conflict.code()).isEqualTo("ENVIRONMENT_EXISTS"));
    }

    @Test
    void promotionAtomicallyRecordsDecisionRevisionCohortRouteDesiredStateAndOutbox() {
        var environment = fixture.environment(workspace, "blue");
        var release = fixture.release(workspace, "agent", "1.0.0");

        PromotionResult result = fixture.promote(workspace, environment, "api", release);

        assertThat(result.outcome()).isEqualTo("ALLOWED");
        assertThat(result.generation()).isOne();
        fixture.tenants.executeWithoutResult(workspace, () -> {
            var deployment = fixture.lifecycle.findDeployment(result.deploymentId()).orElseThrow();
            assertThat(deployment.generation()).isOne();
            assertThat(deployment.desiredReleaseId()).isEqualTo(release.id());
            assertThat(deployment.currentRevisionId()).isEqualTo(result.revisionId());
            assertThat(deployment.currentRoutingGenerationId()).isEqualTo(result.routingGenerationId());
            var revision = fixture.lifecycle.findRevision(result.revisionId()).orElseThrow();
            assertThat(revision.cause()).isEqualTo("PROMOTION");
            assertThat(revision.decisionId()).isEqualTo(result.decisionId());
            assertThat(revision.contentDigest()).startsWith("sha256:");
            assertThat(fixture.lifecycle.findCohort(result.cohortId()).orElseThrow().state()).isEqualTo("ROUTABLE");
            assertThat(fixture.lifecycle.transitions(result.cohortId())).extracting(row -> row.toState())
                    .containsExactly("PENDING", "ROUTABLE");
            assertThat(fixture.lifecycle.routingTargets(result.routingGenerationId())).singleElement()
                    .satisfies(target -> {
                        assertThat(target.cohortId()).isEqualTo(result.cohortId());
                        assertThat(target.weight()).isEqualTo(100);
                    });
            assertThat(fixture.lifecycle.findDecision(result.decisionId()).orElseThrow().outcome()).isEqualTo("ALLOWED");
            assertThat(fixture.lifecycle.outbox()).singleElement().satisfies(event -> {
                assertThat(event.eventType()).isEqualTo("DESIRED_DEPLOYMENT_CHANGED");
                assertThat(event.decisionId()).isEqualTo(result.decisionId());
                assertThat(event.payloadJson()).contains(result.revisionId().toString(), result.routingGenerationId().toString());
            });
        });
    }

    @Test
    void secondPromotionDrainsThePriorCohortAndKeepsExactlyOneRoutableCohort() {
        var environment = fixture.environment(workspace, "green");
        var first = fixture.promote(workspace, environment, "api", fixture.release(workspace, "agent", "1.0.0"));
        var second = fixture.promote(workspace, environment, "api", fixture.release(workspace, "agent", "1.1.0"));

        assertThat(second.drainedCohortIds()).containsExactly(first.cohortId());
        List<CohortRow> cohorts = fixture.deployments.cohorts(workspace, second.deploymentId());
        assertThat(cohorts).extracting(CohortRow::state).containsExactly("DRAINING", "ROUTABLE");
        assertThat(cohorts.getFirst().drainStartedAt()).isNotNull();
        assertThat(fixture.deployments.routingGenerations(workspace, second.deploymentId()))
                .extracting(row -> row.generation()).containsExactly(1L, 2L);
    }

    @Test
    void deniedGateRecordsImmutableEvidenceWithoutChangingDesiredState() {
        var staging = fixture.environment(workspace, "staging");
        var production = fixture.environments.create(workspace, "production", null, new PromotionPolicy(
                List.of(new PromotionGate(GateType.REQUIRE_PRIOR_ENVIRONMENT, "staging")), null));
        var release = fixture.release(workspace, "agent", "1.0.0");

        PromotionResult denied = fixture.promote(workspace, production, "api", release);

        assertThat(denied.outcome()).isEqualTo("DENIED");
        assertThat(denied.gates()).singleElement().satisfies(gate -> assertThat(gate.result()).isEqualTo("FAILED"));
        assertThat(fixture.deployments.deployments(workspace)).isEmpty();
        assertThat(fixture.tenants.execute(workspace, fixture.lifecycle::outbox)).isEmpty();
        var decision = fixture.deployments.decision(workspace, denied.decisionId());
        assertThat(decision.policyVersion()).isOne();
        assertThat(decision.policyDigest()).isEqualTo(production.policyDigest());

        fixture.promote(workspace, staging, "api", release);
        PromotionResult allowed = fixture.promote(workspace, production, "api", release);
        assertThat(allowed.outcome()).isEqualTo("ALLOWED");
        assertThat(allowed.gates()).singleElement().satisfies(gate -> assertThat(gate.result()).isEqualTo("PASSED"));
    }

    @Test
    void capturedEvidenceOverrideAndDecisionReplayAreImmutableAndAtomic() {
        var release = fixture.release(workspace, "evidence-agent", "1.0.0");
        List<PromotionEvidence> captured = new ArrayList<>();
        List<PromotionGate> gates = new ArrayList<>();
        List<EvidenceReference> references = new ArrayList<>();
        for (EvidenceKind kind : EvidenceKind.values()) {
            UUID id = UUID.randomUUID();
            String status = kind == EvidenceKind.HUMAN_APPROVAL ? "APPROVED"
                    : kind == EvidenceKind.RELEASE_EVIDENCE ? "VERIFIED" : "PASSED";
            captured.add(new PromotionEvidence(id, kind, release.id(),
                    com.dvarahq.agentworks.storage.lifecycle.Digests.prefixed(kind.name()), status,
                    kind == EvidenceKind.COST ? Map.of("usd", new BigDecimal("2.00")) : Map.of(),
                    kind == EvidenceKind.HUMAN_APPROVAL ? "approver" : null,
                    Instant.parse("2026-10-15T00:00:00Z")));
            references.add(new EvidenceReference(id, kind));
            gates.add(new PromotionGate(GateType.valueOf(kind.name()), null, kind, status,
                    kind == EvidenceKind.COST ? "usd" : null,
                    kind == EvidenceKind.COST ? LifecycleModel.NumericComparison.AT_MOST : null,
                    kind == EvidenceKind.COST ? new BigDecimal("1.00") : null));
        }
        gates.add(new PromotionGate(GateType.SEPARATION_OF_DUTIES, null, EvidenceKind.HUMAN_APPROVAL,
                "APPROVED", null, null, null));
        var environment = fixture.environments.create(workspace, "evidence", null,
                new PromotionPolicy(gates, null, ContinuityAction.FAIL, true));
        var service = new DeploymentLifecycleService(fixture.tenants, fixture.lifecycle, fixture.idempotencyRecords,
                fixture.clock, fixture.retry, java.time.Duration.ofHours(24), LifecycleFixture.TEST_DIGESTS,
                (selectedWorkspace, selectedEnvironment, policyDigest, selectedRelease, selected) -> captured);
        var command = new PromotionCommand(environment.id(), "api", release.id(), null, 0L, "evidence-1",
                "promoter", "controlled exception", references,
                new OverrideRequest("approved risk acceptance", "RISK-123"), true);

        PromotionResult first = service.promote(workspace, command).value();
        var replay = service.promote(workspace, new PromotionCommand(command.environmentId(), command.deploymentName(),
                command.releaseId(), command.configuration(), command.expectedGeneration(), command.idempotencyKey(),
                command.actor(), command.reason(), command.evidence(), command.override(), false));

        assertThat(first.outcome()).isEqualTo("ALLOWED");
        assertThat(first.gates()).filteredOn(gate -> gate.gate().type() == GateType.COST).singleElement()
                .satisfies(gate -> assertThat(gate.result()).isEqualTo("OVERRIDDEN"));
        assertThat(first.inputDigest()).startsWith("sha256:");
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.value()).isEqualTo(first);
        fixture.tenants.executeWithoutResult(workspace, () -> {
            var stored = fixture.lifecycle.findDecision(first.decisionId()).orElseThrow();
            assertThat(stored.gateResultsJson()).contains(first.inputDigest(), "RISK-123", "OVERRIDDEN",
                    references.getFirst().id().toString());
            assertThat(fixture.lifecycle.outbox()).extracting(row -> row.eventType())
                    .containsExactly("DESIRED_DEPLOYMENT_CHANGED");
        });
    }

    @Test
    void unauthorizedOverrideFailsBeforeAnyDecisionOrDesiredStateMutation() {
        var release = fixture.release(workspace, "override-agent", "1.0.0");
        var environment = fixture.environments.create(workspace, "override", null,
                new PromotionPolicy(List.of(new PromotionGate(GateType.CHANGE_FREEZE, null)), null,
                        ContinuityAction.FAIL, true));
        var command = new PromotionCommand(environment.id(), "api", release.id(), null, 0L, null, "operator",
                "try", List.of(), new OverrideRequest("not authorized", "RISK-9"), false);

        assertThatThrownBy(() -> fixture.deployments.promote(workspace, command))
                .isInstanceOfSatisfying(LifecycleConflictException.class,
                        conflict -> assertThat(conflict.code()).isEqualTo("OVERRIDE_NOT_AUTHORIZED"));
        assertThat(fixture.deployments.deployments(workspace)).isEmpty();
        assertThat(fixture.tenants.execute(workspace, fixture.lifecycle::outbox)).isEmpty();
    }

    @Test
    void dependencyDriftFailsOrDegradesExplicitlyWithoutChangingExistingRunPins() {
        var environment = fixture.environments.create(workspace, "continuity", null,
                new PromotionPolicy(List.of(), null, ContinuityAction.FAIL, false));
        var firstRelease = fixture.release(workspace, "continuity-agent", "1.0.0", "spring-ai");
        var changedRuntime = fixture.release(workspace, "continuity-agent", "2.0.0", "other-executor");
        PromotionResult first = fixture.promote(workspace, environment, "api", firstRelease);

        PromotionResult denied = fixture.deployments.promote(workspace, new PromotionCommand(environment.id(), "api",
                changedRuntime.id(), null, 1L, null, "operator", "runtime changed")).value();

        assertThat(denied.outcome()).isEqualTo("DENIED");
        assertThat(denied.continuity().state()).isEqualTo("FAILED");
        assertThat(denied.continuity().drift()).extracting(drift -> drift.type().name()).contains("RUNTIME");
        assertThat(fixture.deployments.deployment(workspace, first.deploymentId()).generation()).isOne();

        fixture.environments.updatePolicy(workspace, environment.id(), 1,
                new PromotionPolicy(List.of(), null, ContinuityAction.DEGRADE, false));
        PromotionResult degraded = fixture.deployments.promote(workspace, new PromotionCommand(environment.id(), "api",
                changedRuntime.id(), null, 1L, null, "operator", "explicit degrade")).value();

        assertThat(degraded.outcome()).isEqualTo("ALLOWED");
        assertThat(degraded.continuity().state()).isEqualTo("DEGRADED");
        assertThat(fixture.deployments.cohort(workspace, first.cohortId()).state()).isEqualTo("DRAINING");
        assertThat(fixture.tenants.execute(workspace, fixture.lifecycle::outbox)).extracting(row -> row.eventType())
                    .containsExactly("DESIRED_DEPLOYMENT_CHANGED", "DEPENDENCY_DRIFT_DETECTED",
                            "DESIRED_DEPLOYMENT_CHANGED",
                        "DEPENDENCY_DRIFT_DETECTED");
    }

    @Test
    void openCompatibilityPolicyAllowsASecondReleaseAndRecordsItsDrift() {
        var environment = fixture.environment(workspace, "open-continuity");
        var firstRelease = fixture.release(workspace, "open-agent", "1.0.0", "spring-ai");
        var secondRelease = fixture.release(workspace, "open-agent", "2.0.0", "other-executor");
        PromotionResult first = fixture.promote(workspace, environment, "api", firstRelease);

        PromotionResult second = fixture.deployments.promote(workspace, new PromotionCommand(environment.id(), "api",
                secondRelease.id(), null, 1L, null, "operator", "planned open-policy release")).value();

        assertThat(second.outcome()).isEqualTo("ALLOWED");
        assertThat(second.continuity().state()).isEqualTo("DEGRADED");
        assertThat(fixture.deployments.deployment(workspace, first.deploymentId()).generation()).isEqualTo(2L);
        assertThat(fixture.tenants.execute(workspace, fixture.lifecycle::outbox)).extracting(row -> row.eventType())
                .containsExactly("DESIRED_DEPLOYMENT_CHANGED", "DESIRED_DEPLOYMENT_CHANGED",
                        "DEPENDENCY_DRIFT_DETECTED");
    }

    @Test
    void changeFreezeDeniesEveryPromotionAndPolicyUpdatesAreVersioned() {
        var environment = fixture.environment(workspace, "frozen");
        var updated = fixture.environments.updatePolicy(workspace, environment.id(), 1,
                new PromotionPolicy(List.of(new PromotionGate(GateType.CHANGE_FREEZE, null)), "PT1H"));
        assertThat(updated.policyVersion()).isEqualTo(2);
        assertThatThrownBy(() -> fixture.environments.updatePolicy(workspace, environment.id(), 1, PromotionPolicy.open()))
                .isInstanceOfSatisfying(LifecycleConflictException.class,
                        conflict -> assertThat(conflict.code()).isEqualTo("STALE_VERSION"));

        PromotionResult denied = fixture.promote(workspace, environment, "api", fixture.release(workspace, "agent", "1.0.0"));

        assertThat(denied.outcome()).isEqualTo("DENIED");
        assertThat(fixture.deployments.decision(workspace, denied.decisionId()).policyVersion()).isEqualTo(2);
        assertThat(fixture.count("select count(*) from promotion_policy_version where environment_id = ?",
                environment.id())).isEqualTo(2);
    }

    @Test
    void rollbackCreatesANewRevisionAndCohortWithoutReactivatingThePriorCohort() {
        var environment = fixture.environment(workspace, "prod");
        var r1 = fixture.release(workspace, "agent", "1.0.0");
        var first = fixture.promote(workspace, environment, "api", r1);
        var second = fixture.promote(workspace, environment, "api", fixture.release(workspace, "agent", "2.0.0"));

        PromotionResult rollback = fixture.deployments.rollback(workspace, new RollbackCommand(second.deploymentId(),
                r1.id(), 2, null, "tester", "unhealthy")).value();

        assertThat(rollback.kind()).isEqualTo("ROLLBACK");
        assertThat(rollback.generation()).isEqualTo(3);
        assertThat(rollback.cohortId()).isNotIn(first.cohortId(), second.cohortId());
        assertThat(rollback.gates()).isEmpty();
        var revision = fixture.tenants.execute(workspace, () -> fixture.lifecycle.findRevision(rollback.revisionId()).orElseThrow());
        assertThat(revision.releaseId()).isEqualTo(r1.id());
        assertThat(revision.cause()).isEqualTo("ROLLBACK");
        assertThat(fixture.deployments.cohorts(workspace, second.deploymentId())).extracting(CohortRow::state)
                .containsExactly("DRAINING", "DRAINING", "ROUTABLE");
        assertThatThrownBy(() -> fixture.deployments.resume(workspace, new CohortCommand(first.cohortId(),
                fixture.deployments.cohort(workspace, first.cohortId()).transitionVersion(), null, "tester", null)))
                .isInstanceOfSatisfying(LifecycleConflictException.class,
                        conflict -> assertThat(conflict.code()).isEqualTo("INVALID_TRANSITION"));
        assertThatThrownBy(() -> fixture.deployments.rollback(workspace, new RollbackCommand(second.deploymentId(),
                fixture.release(workspace, "agent", "3.0.0").id(), 3, null, "tester", null)))
                .isInstanceOf(AgentSpecValidationException.class);
    }

    @Test
    void sameReleaseRedeploymentCreatesAFreshRevisionAndCohort() {
        var environment = fixture.environment(workspace, "prod");
        var release = fixture.release(workspace, "agent", "1.0.0");
        var first = fixture.promote(workspace, environment, "api", release);

        var redeploy = fixture.deployments.promote(workspace, new PromotionCommand(environment.id(), "api", release.id(),
                new DeploymentConfiguration("ISOLATED", Map.of(), List.of()), 1L, null, "tester", "isolate")).value();

        assertThat(redeploy.revisionId()).isNotEqualTo(first.revisionId());
        assertThat(redeploy.cohortId()).isNotEqualTo(first.cohortId());
        var revision = fixture.tenants.execute(workspace, () -> fixture.lifecycle.findRevision(redeploy.revisionId()).orElseThrow());
        assertThat(revision.cause()).isEqualTo("REDEPLOYMENT");
        assertThat(revision.placement()).isEqualTo("ISOLATED");
        assertThat(revision.previousRevisionId()).isEqualTo(first.revisionId());
    }

    @Test
    void staleExpectedGenerationChangesNothing() {
        var environment = fixture.environment(workspace, "prod");
        var release = fixture.release(workspace, "agent", "1.0.0");
        fixture.promote(workspace, environment, "api", release);

        assertThatThrownBy(() -> fixture.deployments.promote(workspace, new PromotionCommand(environment.id(), "api",
                fixture.release(workspace, "agent", "2.0.0").id(), null, 0L, null, "tester", null)))
                .isInstanceOfSatisfying(LifecycleConflictException.class,
                        conflict -> assertThat(conflict.code()).isEqualTo("STALE_VERSION"));
        assertThat(fixture.count("select count(*) from deployment_revision where workspace_id = ?", workspace.value())).isOne();
        assertThat(fixture.count("select count(*) from lifecycle_decision where workspace_id = ?", workspace.value())).isOne();
    }

    @Test
    void concurrentPromotionsHaveOneAuthoritativeOutcomeWithoutPartialState() throws Exception {
        var environment = fixture.environment(workspace, "prod");
        var base = fixture.promote(workspace, environment, "api", fixture.release(workspace, "agent", "1.0.0"));
        List<UUID> releases = new ArrayList<>();
        for (int index = 0; index < 6; index++) releases.add(fixture.release(workspace, "agent", "2.0." + index).id());

        List<Object> outcomes = race(releases.size(), index -> fixture.deployments.promote(workspace, new PromotionCommand(
                environment.id(), "api", releases.get(index), null, 1L, null, "tester", null)).value());

        assertThat(outcomes).filteredOn(PromotionResult.class::isInstance).hasSize(1);
        assertThat(outcomes).filteredOn(LifecycleConflictException.class::isInstance).hasSize(releases.size() - 1)
                .allSatisfy(outcome -> assertThat(((LifecycleConflictException) outcome).code()).isEqualTo("STALE_VERSION"));
        assertThat(fixture.deployments.cohorts(workspace, base.deploymentId())).extracting(CohortRow::state)
                .containsExactly("DRAINING", "ROUTABLE");
        assertThat(fixture.count("select count(*) from deployment_revision where deployment_id = ?", base.deploymentId())).isEqualTo(2);
        assertThat(fixture.count("select count(*) from routing_generation where deployment_id = ?", base.deploymentId())).isEqualTo(2);
        assertThat(fixture.count("select count(*) from lifecycle_decision where deployment_id = ?", base.deploymentId())).isEqualTo(2);
        assertThat(fixture.count("select count(*) from control_plane_outbox where workspace_id = ?", workspace.value())).isEqualTo(2);
    }

    @Test
    void suspensionAndResumeKeepOneImmutableCohortAndRevocationIsTerminal() {
        var environment = fixture.environment(workspace, "prod");
        var promotion = fixture.promote(workspace, environment, "api", fixture.release(workspace, "agent", "1.0.0"));
        UUID cohort = promotion.cohortId();

        var suspended = fixture.deployments.suspend(workspace, new CohortCommand(cohort, 2, null, "tester", "incident")).value();
        assertThatThrownBy(() -> fixture.deployments.resume(workspace, new CohortCommand(cohort, 2, null, "tester", null)))
                .isInstanceOfSatisfying(LifecycleConflictException.class,
                        conflict -> assertThat(conflict.code()).isEqualTo("STALE_VERSION"));
        var resumed = fixture.deployments.resume(workspace, new CohortCommand(cohort, suspended.transitionVersion(), null,
                "tester", "resolved")).value();
        var revoked = fixture.deployments.revoke(workspace, new CohortCommand(cohort, resumed.transitionVersion(), null,
                "tester", "compromised")).value();

        assertThat(List.of(suspended.state(), resumed.state(), revoked.state()))
                .containsExactly("SUSPENDED", "ROUTABLE", "REVOKED");
        assertThat(fixture.deployments.cohorts(workspace, promotion.deploymentId())).singleElement()
                .satisfies(row -> assertThat(row.revisionId()).isEqualTo(promotion.revisionId()));
        assertThatThrownBy(() -> fixture.deployments.resume(workspace, new CohortCommand(cohort,
                revoked.transitionVersion(), null, "tester", null)))
                .isInstanceOfSatisfying(LifecycleConflictException.class,
                        conflict -> assertThat(conflict.code()).isEqualTo("INVALID_TRANSITION"));
        assertThat(fixture.tenants.execute(workspace, () -> fixture.lifecycle.transitions(cohort)))
                .extracting(row -> row.toState()).containsExactly("PENDING", "ROUTABLE", "SUSPENDED", "ROUTABLE", "REVOKED");
    }

    @Test
    void theDatabaseRejectsReactivationOfTerminalOrDrainingCohortsEvenThroughDirectSql() {
        var environment = fixture.environment(workspace, "prod");
        var first = fixture.promote(workspace, environment, "api", fixture.release(workspace, "agent", "1.0.0"));
        fixture.promote(workspace, environment, "api", fixture.release(workspace, "agent", "2.0.0"));

        assertThatThrownBy(() -> fixture.jdbc.update("update runtime_cohort set state = 'ROUTABLE', "
                + "transition_version = transition_version + 1 where id = ?", first.cohortId()))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("INVALID_TRANSITION");
        assertThatThrownBy(() -> fixture.jdbc.update("update runtime_cohort set revision_id = ? where id = ?",
                UUID.randomUUID(), first.cohortId())).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void lifecycleHistoryIsAppendOnlyForApplicationRolesAndDirectSql() {
        var environment = fixture.environment(workspace, "prod");
        var release = fixture.release(workspace, "agent", "1.0.0");
        var promotion = fixture.promote(workspace, environment, "api", release);
        List<String> mutations = List.of(
                "update agent_release set manifest_json = '{}' where id = '" + release.id() + "'",
                "delete from agent_release where id = '" + release.id() + "'",
                "update deployment_revision set placement = 'DEDICATED' where id = '" + promotion.revisionId() + "'",
                "delete from deployment_revision where id = '" + promotion.revisionId() + "'",
                "update routing_generation set policy = 'EXPLICIT_MULTI_TARGET' where id = '" + promotion.routingGenerationId() + "'",
                "update routing_target set weight = 50 where generation_id = '" + promotion.routingGenerationId() + "'",
                "update lifecycle_decision set outcome = 'DENIED' where id = '" + promotion.decisionId() + "'",
                "delete from cohort_transition where cohort_id = '" + promotion.cohortId() + "'",
                "update control_plane_outbox set payload_json = '{}' where decision_id = '" + promotion.decisionId() + "'",
                "update promotion_policy_version set policy_json = '{}' where environment_id = '" + environment.id() + "'",
                "delete from agent_deployment where id = '" + promotion.deploymentId() + "'",
                "update agent_deployment set name = 'renamed' where id = '" + promotion.deploymentId() + "'",
                "update agent_deployment set current_routing_generation_id = null where id = '" + promotion.deploymentId() + "'",
                "update agent_deployment set revision = revision + 1 where id = '" + promotion.deploymentId() + "'",
                "update agent_environment set promotion_policy_json = '{}' where id = '" + environment.id() + "'");
        for (String mutation : mutations) {
            assertThatThrownBy(() -> fixture.jdbc.update(mutation)).as(mutation)
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> fixture.asRole("agentworks_control_plane", mutation)).as("control plane: " + mutation)
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
        }
        assertThatThrownBy(() -> fixture.jdbc.execute("truncate lifecycle_decision cascade"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void runPlaneRoleCannotAuthorControlPlaneLifecycleState() {
        var environment = fixture.environment(workspace, "prod");
        var release = fixture.release(workspace, "agent", "1.0.0");
        var promotion = fixture.promote(workspace, environment, "api", release);
        List<String> denied = List.of(
                "insert into agent_environment(workspace_id, id, name, policy_version, promotion_policy_json, "
                        + "promotion_policy_digest, created_at) values ('" + workspace.value() + "', gen_random_uuid(), 'x', 1, '{}', 'sha256:"
                        + "0".repeat(64) + "', current_timestamp)",
                "update agent_deployment set revision = revision + 1 where id = '" + promotion.deploymentId() + "'",
                "update runtime_cohort set state = 'SUSPENDED', transition_version = transition_version + 1 where id = '"
                        + promotion.cohortId() + "'",
                "insert into lifecycle_decision(workspace_id, id, kind, outcome, environment_id, definition_id, "
                        + "gate_results_json, actor, created_at) values ('" + workspace.value() + "', gen_random_uuid(), "
                        + "'PROMOTION', 'ALLOWED', '" + environment.id() + "', '" + release.definitionId() + "', '[]', 'run', current_timestamp)",
                "update release_alias set release_id = release_id",
                "insert into control_plane_outbox(workspace_id, id, event_type, decision_id, payload_json, created_at) "
                        + "values ('" + workspace.value() + "', gen_random_uuid(), 'FORGED', '" + promotion.decisionId() + "', '{}', current_timestamp)",
                "update agent_run set revision_id = revision_id");
        for (String statement : denied) {
            assertThatThrownBy(() -> fixture.asRole("agentworks_run_plane", statement)).as(statement)
                    .isInstanceOf(org.springframework.dao.DataAccessException.class)
                    .hasStackTraceContaining("permission denied");
        }
        assertThat(fixture.deployments.deployment(workspace, promotion.deploymentId()).generation()).isOne();
    }

    @Test
    void revisionsCarryCredentialReferencesOnlyAndSecretRotationCreatesNoRevision() {
        var environment = fixture.environment(workspace, "prod");
        var release = fixture.release(workspace, "agent", "1.0.0");
        String secretCanary = "sk-live-canary-" + UUID.randomUUID();

        assertThatThrownBy(() -> fixture.deployments.promote(workspace, new PromotionCommand(environment.id(), "api",
                release.id(), new DeploymentConfiguration("SHARED", Map.of("OPENAI_KEY", secretCanary), List.of()),
                null, null, "tester", null))).isInstanceOfSatisfying(AgentSpecValidationException.class,
                        invalid -> assertThat(invalid.diagnostics().toString()).doesNotContain(secretCanary));
        var promotion = fixture.deployments.promote(workspace, new PromotionCommand(environment.id(), "api", release.id(),
                new DeploymentConfiguration("SHARED", Map.of("OPENAI_KEY", "vault://agents/openai"),
                        List.of("tool-policy@3")), null, null, "tester", null)).value();

        // Rotating the value behind vault://agents/openai is a JIT credential operation (ADR-012) with no lifecycle
        // entry point: the revision still holds only the reference and no rotation creates a revision.
        assertThat(fixture.count("select count(*) from deployment_revision where deployment_id = ?",
                promotion.deploymentId())).isOne();
        var revision = fixture.tenants.execute(workspace, () -> fixture.lifecycle.findRevision(promotion.revisionId()).orElseThrow());
        assertThat(revision.configurationJson()).contains("vault://agents/openai", "tool-policy@3");
        for (String table : List.of("deployment_revision", "lifecycle_decision", "control_plane_outbox",
                "lifecycle_idempotency", "cohort_transition", "routing_generation")) {
            assertThat(fixture.count("select count(*) from " + table + " t where cast(row_to_json(t) as text) like ?",
                    "%" + secretCanary + "%")).as(table).isZero();
        }
    }

    @Test
    void anotherWorkspaceCannotSeeOrCommandTheLifecycle() {
        var environment = fixture.environment(workspace, "prod");
        var release = fixture.release(workspace, "agent", "1.0.0");
        var promotion = fixture.promote(workspace, environment, "api", release);
        WorkspaceId other = fixture.workspace();

        assertThat(fixture.deployments.deployments(other)).isEmpty();
        assertThat(fixture.environments.list(other)).isEmpty();
        assertThat(fixture.releases.listReleases(other)).isEmpty();
        for (Runnable attempt : List.<Runnable>of(
                () -> fixture.deployments.deployment(other, promotion.deploymentId()),
                () -> fixture.deployments.cohort(other, promotion.cohortId()),
                () -> fixture.deployments.decision(other, promotion.decisionId()),
                () -> fixture.releases.getRelease(other, release.id()),
                () -> fixture.deployments.promote(other, new PromotionCommand(environment.id(), "api", release.id(), null,
                        null, null, "tester", null)),
                () -> fixture.deployments.suspend(other, new CohortCommand(promotion.cohortId(), 2, null, "tester", null)))) {
            assertThatThrownBy(attempt::run).isInstanceOf(LifecycleNotFoundException.class);
        }
    }

    static List<Object> race(int workers, java.util.function.IntFunction<Object> work) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(workers)) {
            for (int index = 0; index < workers; index++) {
                int worker = index;
                Callable<Object> task = () -> {
                    start.await();
                    try {
                        return work.apply(worker);
                    } catch (RuntimeException exception) {
                        return exception;
                    }
                };
                futures.add(executor.submit(task));
            }
            start.countDown();
            List<Object> outcomes = new ArrayList<>();
            for (Future<Object> future : futures) outcomes.add(future.get(120, TimeUnit.SECONDS));
            return outcomes;
        }
    }
}
