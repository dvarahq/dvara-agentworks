package com.dvarahq.agentworks.runplane.execution;

import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.CohortCommand;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.PromotionResult;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.RetirementResult;
import com.dvarahq.agentworks.domain.run.RunEffect;
import com.dvarahq.agentworks.domain.run.RunRecord;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.dvarahq.agentworks.runplane.execution.RunScenario.race;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The {@code cohort_work_item} ledger (docs/implementation/LIFECYCLE-CONTRACTS.md): its fence, its typed quiescence
 * blockers, and retirement races for the implemented work categories.
 */
class CohortWorkItemFencePostgresTest {
    private static final String RUN_PLANE = "agentworks_run_plane";
    private final RunScenario scenario = new RunScenario();

    @Test
    void theWorkItemLedgerIsFencedLikeRunsInEveryCohortState() {
        var fixture = scenario.fixture;
        UUID drained = scenario.promotion.cohortId();
        PromotionResult r2 = fixture.promote(scenario.workspace, scenario.environment, "api",
                fixture.release(scenario.workspace, "agent", "2.0.0"));

        insert(r2.cohortId(), "CLAIM", "ACTIVE");
        insert(drained, "CHECKPOINT", "ACTIVE");
        var suspended = fixture.deployments.suspend(scenario.workspace, new CohortCommand(r2.cohortId(), 2, null,
                "operator", "incident")).value();
        rejected(() -> insert(r2.cohortId(), "CLAIM", "ACTIVE"), "COHORT_NOT_ADMITTING SUSPENDED");
        rejected(() -> insert(r2.cohortId(), "OPERATION", "PENDING"), "COHORT_NOT_ADMITTING SUSPENDED");
        rejected(() -> insert(r2.cohortId(), "SANDBOX", "PENDING"), "COHORT_NOT_ADMITTING SUSPENDED");
        insert(r2.cohortId(), "CHECKPOINT", "ACTIVE");
        insert(r2.cohortId(), "OPERATION", "TERMINAL");
        fixture.deployments.revoke(scenario.workspace, new CohortCommand(r2.cohortId(), suspended.transitionVersion(),
                null, "security", "compromised"));
        rejected(() -> insert(r2.cohortId(), "CHECKPOINT", "ACTIVE"), "COHORT_NOT_ADMITTING REVOKED");
        insert(r2.cohortId(), "CLAIM", "TERMINAL");

        // Terminal items never resume, and the ledger is never deleted or re-identified.
        UUID item = scenario.fixture.jdbc.queryForObject("select id from cohort_work_item where cohort_id = ? and "
                + "state = 'TERMINAL' fetch first 1 rows only", UUID.class, r2.cohortId());
        rejected(() -> fixture.asRole(RUN_PLANE, "update cohort_work_item set state = 'ACTIVE', terminal_at = null "
                + "where id = ?", item), "TERMINAL_WORK");
        rejected(() -> fixture.jdbc.update("delete from cohort_work_item where id = ?", item), "IMMUTABLE_HISTORY");
        rejected(() -> fixture.jdbc.update("update cohort_work_item set cohort_id = ? where id = ?", drained, item),
                "IMMUTABLE_IDENTITY");
        rejected(() -> fixture.jdbc.execute("truncate cohort_work_item"), "IMMUTABLE_HISTORY");

        // Terminalize the drained cohort's work, retire it, and nothing non-terminal can join it again.
        fixture.asRole(RUN_PLANE, "update cohort_work_item set state = 'TERMINAL', terminal_at = current_timestamp, "
                + "state_changed_at = current_timestamp where cohort_id = ? and state <> 'TERMINAL'", drained);
        assertThat(retire(scenario.workspace, drained).outcome()).isEqualTo("RETIRED");
        rejected(() -> insert(drained, "OPERATION", "PENDING"), "COHORT_NOT_ADMITTING RETIRED");
        insert(drained, "CHECKPOINT", "TERMINAL");
    }

    @Test
    void lateWorkItemsInvalidateAQuiescenceObservation() {
        var fixture = scenario.fixture;
        UUID cohort = scenario.promotion.cohortId();
        fixture.promote(scenario.workspace, scenario.environment, "api", fixture.release(scenario.workspace, "agent", "2.0.0"));
        assertThat(fixture.deployments.observeQuiescence(scenario.workspace, cohort, "tester").state()).isEqualTo("QUIESCENT");

        insert(cohort, "CHECKPOINT", "ACTIVE");

        assertThat(fixture.deployments.cohort(scenario.workspace, cohort).state()).isEqualTo("DRAINING");
        assertThat(fixture.tenants.execute(scenario.workspace, () -> fixture.lifecycle.transitions(cohort)).getLast())
                .satisfies(transition -> {
                    assertThat(transition.cause()).isEqualTo("LATE_WORK");
                    assertThat(transition.fromState()).isEqualTo("QUIESCENT");
                });
        RetirementResult blocked = retire(scenario.workspace, cohort);
        assertThat(blocked.outcome()).isEqualTo("BLOCKED");
        assertThat(blocked.blockers()).extracting(LifecycleModel.Blocker::category).containsExactly("RESTORABLE_CHECKPOINT");
    }

    @Test
    void lateSandboxWorkInvalidatesAQuiescenceObservation() {
        var fixture = scenario.fixture;
        UUID cohort = scenario.promotion.cohortId();
        fixture.promote(scenario.workspace, scenario.environment, "api",
                fixture.release(scenario.workspace, "agent", "2.0.0"));
        assertThat(fixture.deployments.observeQuiescence(scenario.workspace, cohort, "tester").state())
                .isEqualTo("QUIESCENT");

        insert(cohort, "SANDBOX", "PENDING");

        assertThat(fixture.deployments.cohort(scenario.workspace, cohort).state()).isEqualTo("DRAINING");
        RetirementResult blocked = retire(scenario.workspace, cohort);
        assertThat(blocked.outcome()).isEqualTo("BLOCKED");
        assertThat(blocked.blockers()).extracting(LifecycleModel.Blocker::category)
                .containsExactly("RETAINED_SANDBOX");
    }

    @Test
    void retainedSandboxesAreTypedQuiescenceBlockersUntilCleanup() {
        UUID cohort = scenario.promotion.cohortId();
        scenario.fixture.promote(scenario.workspace, scenario.environment, "api",
                scenario.fixture.release(scenario.workspace, "agent", "2.0.0"));
        insert(cohort, "SANDBOX", "ACTIVE");

        RetirementResult blocked = retire(scenario.workspace, cohort);
        assertThat(blocked.outcome()).isEqualTo("BLOCKED");
        assertThat(blocked.blockers()).extracting(LifecycleModel.Blocker::category, LifecycleModel.Blocker::state)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("RETAINED_SANDBOX", "ACTIVE"));

        scenario.fixture.asRole(RUN_PLANE, "update cohort_work_item set state = 'TERMINAL', "
                + "terminal_at = current_timestamp, state_changed_at = current_timestamp where cohort_id = ? "
                + "and category = 'SANDBOX'", cohort);
        assertThat(retire(scenario.workspace, cohort).outcome()).isEqualTo("RETIRED");
    }

    @Test
    void claimsOperationsAndCheckpointsAreTypedQuiescenceBlockersUntilResolved() {
        var engine = scenario.engine;
        var claimed = scenario.started("worker", RunScenario.LEASE);
        UUID runId = claimed.run().id();
        var boundary = engine.checkpoint(claimed.lease(), Map.of("step", 1));
        try {
            engine.executeEffect(boundary.lease(), "deploy", Map.of(), (key, request) -> {
                throw new java.io.IOException("timeout");
            });
        } catch (DurableRunEngine.EffectInDoubtException expected) {
            // The effect stays pending and in doubt.
        }
        UUID cohort = scenario.promotion.cohortId();
        scenario.fixture.promote(scenario.workspace, scenario.environment, "api",
                scenario.fixture.release(scenario.workspace, "agent", "2.0.0"));

        RetirementResult blocked = retire(scenario.workspace, cohort);
        assertThat(blocked.outcome()).isEqualTo("BLOCKED");
        assertThat(blocked.blockers()).extracting(LifecycleModel.Blocker::category).containsExactly("NON_TERMINAL_RUN",
                "RESTORABLE_CHECKPOINT", "ACTIVE_CLAIM", "PENDING_OPERATION");
        assertThat(blocked.blockers()).extracting(LifecycleModel.Blocker::state).containsExactly("ACTIVE", "ACTIVE",
                "ACTIVE", "PENDING");

        engine.cancel(scenario.workspace, runId, "operator:ops", "stop");
        assertThat(retire(scenario.workspace, cohort).blockers()).extracting(LifecycleModel.Blocker::category)
                .containsExactly("PENDING_OPERATION");
        engine.resolveEffect(scenario.workspace, runId, "deploy", "operator:ops", "confirmed not deployed");
        assertThat(retire(scenario.workspace, cohort).outcome()).isEqualTo("RETIRED");
        assertThat(engine.effects(scenario.workspace, runId).getFirst().state()).isEqualTo(RunEffect.State.ABANDONED);
    }

    @Test
    void retirementRacingEachWorkCategoryNeverRetiresResumableWork() throws Exception {
        for (String category : List.of("CLAIM", "OPERATION", "CHECKPOINT", "INBOX", "SANDBOX")) {
            for (int round = 0; round < 3; round++) {
                WorkspaceId space = scenario.fixture.workspace();
                var environment = scenario.fixture.environment(space, "prod");
                PromotionResult first = scenario.fixture.promote(space, environment, "api",
                        scenario.fixture.release(space, "agent", "1.0.0"));
                scenario.fixture.promote(space, environment, "api", scenario.fixture.release(space, "agent", "2.0.0"));
                String state = List.of("OPERATION", "INBOX").contains(category) ? "PENDING" : "ACTIVE";

                List<Object> outcomes = race(2, index -> index == 0
                        ? tryInsert(first.cohortId(), category, state)
                        : retire(space, first.cohortId()));

                RetirementResult retirement = (RetirementResult) outcomes.get(1);
                long open = scenario.count("select count(*) from cohort_work_item where cohort_id = ? "
                        + "and state <> 'TERMINAL'", first.cohortId());
                if ("RETIRED".equals(retirement.outcome())) {
                    assertThat(open).as(category).isZero();
                    assertThat(outcomes.getFirst()).as(category).asString().contains("COHORT_NOT_ADMITTING RETIRED");
                } else {
                    assertThat(open).as(category).isOne();
                    assertThat(outcomes.getFirst()).as(category).isEqualTo("INSERTED");
                    assertThat(retirement.blockers()).extracting(LifecycleModel.Blocker::category)
                            .containsExactly(blocker(category));
                }
            }
        }
    }

    @Test
    void anInDoubtOperationRacingRetirementIsEitherResolvedFirstOrBlocksIt() throws Exception {
        for (int round = 0; round < 3; round++) {
            RunScenario space = new RunScenario(scenario.fixture);
            var claimed = space.started("worker", RunScenario.LEASE);
            try {
                space.engine.executeEffect(claimed.lease(), "notify", Map.of(), (key, request) -> {
                    throw new java.io.IOException("unknown");
                });
            } catch (DurableRunEngine.EffectInDoubtException expected) {
                // pending
            }
            UUID runId = claimed.run().id();
            space.engine.cancel(space.workspace, runId, "operator:ops", "stop");
            space.fixture.promote(space.workspace, space.environment, "api",
                    space.fixture.release(space.workspace, "agent", "2.0.0"));
            UUID cohort = space.promotion.cohortId();

            List<Object> outcomes = race(2, index -> index == 0
                    ? space.engine.resolveEffect(space.workspace, runId, "notify", "operator:ops", "verified")
                    : retire(space.workspace, cohort));

            RetirementResult retirement = (RetirementResult) outcomes.get(1);
            RunEffect.State effect = space.engine.effects(space.workspace, runId).getFirst().state();
            assertThat(outcomes.getFirst()).isInstanceOf(RunRecord.class);
            assertThat(effect).isEqualTo(RunEffect.State.ABANDONED);
            if ("BLOCKED".equals(retirement.outcome())) {
                assertThat(retirement.blockers()).extracting(LifecycleModel.Blocker::category)
                        .containsExactly("PENDING_OPERATION");
                assertThat(retire(space.workspace, cohort).outcome()).isEqualTo("RETIRED");
            } else {
                assertThat(retirement.outcome()).isEqualTo("RETIRED");
            }
        }
    }

    @Test
    void anOperationItemEndsOnlyAfterItsEffectHasAnOutcome() {
        var claimed = scenario.started("worker", RunScenario.LEASE);
        try {
            scenario.engine.executeEffect(claimed.lease(), "deploy", Map.of(), (key, request) -> {
                throw new java.io.IOException("timeout");
            });
        } catch (DurableRunEngine.EffectInDoubtException expected) {
            // pending
        }
        UUID runId = claimed.run().id();

        rejected(() -> scenario.fixture.asRole(RUN_PLANE, "update cohort_work_item set state = 'TERMINAL', "
                + "terminal_at = current_timestamp, state_changed_at = current_timestamp where run_id = ? "
                + "and category = 'OPERATION'", runId), "EFFECT_IN_DOUBT");
        assertThat(scenario.count("select count(*) from cohort_work_item where run_id = ? and category = 'OPERATION' "
                + "and state = 'PENDING'", runId)).isOne();
        scenario.engine.cancel(scenario.workspace, runId, "operator:ops", "stop");
        scenario.engine.resolveEffect(scenario.workspace, runId, "deploy", "operator:ops", "verified");
        assertThat(scenario.count("select count(*) from cohort_work_item where run_id = ? and category = 'OPERATION' "
                + "and state = 'TERMINAL'", runId)).isOne();
    }

    @Test
    void theMigrationTermOfTheQuiescencePredicateIsAlwaysEmptyInCore10() {
        // ADR-030: paused-run migration is deferred to OSS 1.1. The ledger cannot hold migration work, so the
        // predicate's migration term is always empty and no retirement can wait on it.
        rejected(() -> insert(scenario.promotion.cohortId(), "MIGRATION", "ACTIVE"), "cohort_work_item_category_ck");
        var claimed = scenario.started("worker", RunScenario.LEASE);
        scenario.engine.checkpoint(claimed.lease(), Map.of());
        var report = scenario.fixture.deployments.observeQuiescence(scenario.workspace, scenario.promotion.cohortId(),
                "tester");
        assertThat(report.blockers()).extracting(LifecycleModel.Blocker::category)
                .isNotEmpty().noneMatch(category -> category.contains("MIGRATION"));
        assertThat(scenario.count("select count(*) from cohort_work_item where category like '%MIGRAT%'")).isZero();
    }

    private void insert(UUID cohort, String category, String state) {
        scenario.fixture.asRole(RUN_PLANE, "insert into cohort_work_item(workspace_id, id, cohort_id, run_id, category, "
                + "subject_id, state, created_at, state_changed_at, terminal_at) select workspace_id, gen_random_uuid(), "
                + "id, null, ?, gen_random_uuid(), ?, current_timestamp, current_timestamp, case when ? = 'TERMINAL' "
                + "then current_timestamp end from runtime_cohort where id = ?", category, state, state, cohort);
    }

    private Object tryInsert(UUID cohort, String category, String state) {
        try {
            insert(cohort, category, state);
            return "INSERTED";
        } catch (RuntimeException rejected) {
            return String.valueOf(rejected.getCause() == null ? rejected : rejected.getCause().getMessage());
        }
    }

    private RetirementResult retire(WorkspaceId workspace, UUID cohort) {
        long version = scenario.fixture.deployments.cohort(workspace, cohort).transitionVersion();
        return scenario.fixture.deployments.retire(workspace, new CohortCommand(cohort, version, null, "tester", null))
                .value();
    }

    private static String blocker(String category) {
        return switch (category) {
            case "CLAIM" -> "ACTIVE_CLAIM";
            case "OPERATION" -> "PENDING_OPERATION";
            case "CHECKPOINT" -> "RESTORABLE_CHECKPOINT";
            case "INBOX" -> "PENDING_INBOX";
            default -> "RETAINED_SANDBOX";
        };
    }

    private static void rejected(Runnable write, String reason) {
        assertThatThrownBy(write::run).hasStackTraceContaining(reason);
    }
}
