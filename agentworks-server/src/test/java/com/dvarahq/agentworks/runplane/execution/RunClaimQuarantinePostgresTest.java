package com.dvarahq.agentworks.runplane.execution;

import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleFixture;
import com.dvarahq.agentworks.domain.run.ClaimQuarantine;
import com.dvarahq.agentworks.domain.run.ClaimScope;
import com.dvarahq.agentworks.domain.run.RunLifecycle;
import com.dvarahq.agentworks.domain.run.RunRecord;
import com.dvarahq.agentworks.storage.lifecycle.LifecycleRetry;
import com.dvarahq.agentworks.storage.run.JdbcClaimQuarantineRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Issue #181 on PostgreSQL: the claim quarantine (V11, V11_1) is durable and scoped by runtime compatibility identity,
 * the claim query skips quarantined runs by predicate however many there are, the quarantine write is fenced by the
 * run's row lock and observed state, quarantined runs are typed quiescence blockers, a run that ends closes its
 * quarantines, and the plane roles hold exactly the privileges they need: the Run Plane records, the Control Plane
 * lists and releases.
 */
class RunClaimQuarantinePostgresTest {
    private static final int POISON_RUNS = 1_100;
    private final RunScenario scenario = new RunScenario();

    private ClaimQuarantine.Request request(ClaimQuarantine.Reason reason, String code, Duration base) {
        return new ClaimQuarantine.Request(scenario.engine.compatibility(), reason, code, "worker-1", base,
                RunWorker.MAX_TIMED_QUARANTINES, null);
    }

    private static LifecycleFixture login(LifecycleFixture fixture, String login, String password, String role) {
        fixture.jdbc.execute("do $$ begin if not exists (select 1 from pg_roles where rolname = '" + login + "') then "
                + "create role " + login + " login password '" + password + "' in role " + role + "; end if; end $$");
        return new LifecycleFixture(new DriverManagerDataSource(LifecycleFixture.postgresDatabase().jdbcUrl(), login, password),
                LifecycleRetry.standard(), Duration.ofHours(24));
    }

    private static JdbcClaimQuarantineRepository repository(LifecycleFixture fixture) {
        return new JdbcClaimQuarantineRepository(fixture.jdbc, fixture.transactions);
    }

    @Test
    void aHealthyRunBehindMoreThanAThousandQuarantinedRunsIsClaimed() {
        RunRecord healthy = scenario.submit(null);
        // More than a thousand queued runs, all older than the healthy one, written directly and each quarantined for
        // this node's identity as a worker would have recorded it: the old local exclusion set held at most 1024.
        scenario.fixture.bypassFence("insert into agent_run select (jsonb_populate_record(null::agent_run, to_jsonb(r) "
                + "|| jsonb_build_object('id', s.id, 'root_run_id', s.id, 'trace_id', replace(s.id::text, '-', ''), "
                + "'created_at', r.created_at - interval '1 hour' + s.ordinal * interval '1 millisecond'))).* "
                + "from agent_run r cross join lateral (select gen_random_uuid() as id, ordinal "
                + "from generate_series(1, ?) ordinal) s where r.id = ?", POISON_RUNS, healthy.id());
        scenario.fixture.asRole("agentworks_run_plane", "insert into agent_run_claim_quarantine(workspace_id, run_id, "
                + "compatibility, reason, code, quarantines, quarantined_by, quarantined_at, not_before) select "
                + "workspace_id, id, ?, 'UNINTERPRETABLE', 'CORRUPT_HISTORY', 1, 'worker-1', clock_timestamp(), null "
                + "from agent_run where workspace_id = ? and id <> ?", scenario.engine.compatibility(),
                scenario.workspace.value(), healthy.id());
        try {
            var claimed = scenario.engine.claimNext("worker-2", scenario.scope(), RunScenario.LEASE).orElseThrow();

            assertThat(claimed.run().id()).isEqualTo(healthy.id());
            assertThat(scenario.engine.claimNext("worker-2", scenario.scope(), RunScenario.LEASE)).isEmpty();
            // The active listing pages through them by an immutable key (#185): a quarantine recorded again between
            // pages never shifts a later page, so every quarantine is listed exactly once.
            var quarantines = repository(scenario.fixture);
            var listed = new java.util.ArrayList<ClaimQuarantine>();
            JdbcClaimQuarantineRepository.After after = null;
            for (var page = quarantines.active(scenario.workspace, 500, after); !page.isEmpty();
                    page = quarantines.active(scenario.workspace, 500, after)) {
                assertThat(page.size()).isLessThanOrEqualTo(500);
                page.forEach(entry -> listed.add(entry.quarantine()));
                after = JdbcClaimQuarantineRepository.After.of(page.getLast());
                scenario.fixture.bypassFence("update agent_run_claim_quarantine set quarantined_at = clock_timestamp() "
                        + "where workspace_id = ? and run_id = ?", scenario.workspace.value(),
                        page.getFirst().quarantine().runId());
            }
            assertThat(listed).hasSize(POISON_RUNS);
            assertThat(listed.stream().map(ClaimQuarantine::runId).distinct()).hasSize(POISON_RUNS);
        } finally {
            // The database is shared with other tests: the synthetic runs are removed as they were written.
            scenario.fixture.bypassFence("delete from agent_run_claim_quarantine where workspace_id = ?",
                    scenario.workspace.value());
            scenario.fixture.bypassFence("delete from agent_run where workspace_id = ? and id <> ?",
                    scenario.workspace.value(), healthy.id());
        }
    }

    // #181 round 2, high: an indefinite quarantine recorded by nodes of another identity never keeps this node away.
    @Test
    void aQuarantineOfAnotherCompatibilityIdentityIsIgnored() {
        RunRecord run = scenario.submit(Map.of("task", "t"));
        var old = new DurableRunEngine(scenario.fixture.runStore, RunEventCodec.standard(), new ReleaseManifestReader(),
                LifecycleRetry.standard(), "agentworks-old-0001");
        assertThat(old.quarantine(run.id(), new ClaimQuarantine.Request(old.compatibility(),
                ClaimQuarantine.Reason.UNINTERPRETABLE, "CORRUPT_HISTORY", "old-1", null, RunWorker.MAX_TIMED_QUARANTINES,
                null))).isPresent();

        // Nodes of the refusing identity skip it...
        assertThat(old.claimNext("old-2", scenario.scope(), RunScenario.LEASE)).isEmpty();
        // ...a node of this identity claims it.
        assertThat(scenario.engine.claimNext("new-1", scenario.scope(), RunScenario.LEASE)).hasValueSatisfying(
                claimed -> assertThat(claimed.run().id()).isEqualTo(run.id()));
    }

    @Test
    void aQuarantineIsDurableDoublesAndEndsInDatabaseTime() {
        RunRecord run = scenario.submit(Map.of("task", "t"));
        var first = scenario.engine.quarantine(run.id(), request(ClaimQuarantine.Reason.CLAIM_FAILING,
                "WORK_ITEM_REJECTED", Duration.ofMinutes(5))).orElseThrow();

        assertThat(first.quarantines()).isOne();
        assertThat(Duration.between(first.quarantinedAt(), first.notBefore())).isEqualTo(Duration.ofMinutes(5));
        // No worker of this identity, with any engine instance, selects it while the quarantine is in force.
        assertThat(scenario.engine.claimNext("worker-2", scenario.scope(), RunScenario.LEASE)).isEmpty();
        scenario.fixture.bypassFence("update agent_run_claim_quarantine set not_before = clock_timestamp() - interval "
                + "'1 millisecond', quarantined_at = clock_timestamp() - interval '1 hour' where run_id = ?", run.id());
        var second = scenario.engine.quarantine(run.id(), request(ClaimQuarantine.Reason.CLAIM_FAILING,
                "WORK_ITEM_REJECTED", Duration.ofMinutes(5))).orElseThrow();
        assertThat(second.quarantines()).isEqualTo(2);
        assertThat(Duration.between(second.quarantinedAt(), second.notBefore())).isEqualTo(Duration.ofMinutes(10));

        // Released on the Control Plane, the run is selected again at once.
        var quarantines = repository(scenario.fixture);
        assertThat(quarantines.release(scenario.workspace, run.id())).isOne();
        assertThat(quarantines.release(scenario.workspace, run.id())).isZero();
        assertThat(scenario.engine.claimNext("worker-2", scenario.scope(), RunScenario.LEASE)).hasValueSatisfying(
                claimed -> assertThat(claimed.run().id()).isEqualTo(run.id()));
    }

    @Test
    void theQuarantineWriteIsFencedByTheRunsClaimabilityAndObservedState() {
        var claimed = scenario.started("worker-2", RunScenario.LEASE);
        var request = request(ClaimQuarantine.Reason.CLAIM_FAILING, "WORK_ITEM_REJECTED", Duration.ofMinutes(5));

        // Another worker holds a live lease: the run is not quarantined.
        assertThat(scenario.engine.quarantine(claimed.run().id(), request)).isEmpty();
        // A lapsed lease makes it claimable again; a refusal that observed its earlier state is still not applied.
        scenario.expireLease(claimed.run().id());
        assertThat(scenario.engine.quarantine(claimed.run().id(), new ClaimQuarantine.Request(
                scenario.engine.compatibility(), ClaimQuarantine.Reason.CLAIM_FAILING, "WORK_ITEM_REJECTED", "worker-1",
                Duration.ofMinutes(5), RunWorker.MAX_TIMED_QUARANTINES, new ClaimQuarantine.Observed(1, 0)))).isEmpty();
        assertThat(scenario.engine.quarantine(claimed.run().id(), request)).isPresent();
        scenario.engine.cancel(scenario.workspace, claimed.run().id(), "operator:ops", "done");
        // A finished run is never quarantined again, its quarantine is closed, and the run itself was never changed.
        assertThat(scenario.engine.quarantine(claimed.run().id(), request)).isEmpty();
        var quarantines = repository(scenario.fixture);
        assertThat(quarantines.active(scenario.workspace, 10, null)).isEmpty();
        assertThat(quarantines.list(scenario.workspace, true, 10, null)).singleElement()
                .satisfies(entry -> assertThat(entry.quarantine().closedAt()).isNotNull());
        assertThat(scenario.engine.run(scenario.workspace, claimed.run().id()).lifecycle())
                .isEqualTo(RunLifecycle.CANCELLED);
        assertThat(scenario.engine.verify(scenario.workspace, claimed.run().id()).intact()).isTrue();
    }

    // #181 round 2, medium: a quarantined run blocks its cohort's quiescence as a typed QUARANTINED_RUN, by reason and
    // code, rather than as generic non-terminal work.
    @Test
    void aQuarantinedRunIsATypedQuiescenceBlocker() {
        RunRecord quarantined = scenario.submit(Map.of("task", "q"));
        RunRecord waiting = scenario.submit(Map.of("task", "w"));
        scenario.engine.quarantine(quarantined.id(), request(ClaimQuarantine.Reason.UNINTERPRETABLE, "CORRUPT_HISTORY",
                null)).orElseThrow();

        var report = scenario.fixture.deployments.observeQuiescence(scenario.workspace, quarantined.pins().cohortId(),
                "tester");

        assertThat(report.quiescent()).isFalse();
        assertThat(report.blockers()).anySatisfy(blocker -> {
            assertThat(blocker.category()).isEqualTo("QUARANTINED_RUN");
            assertThat(blocker.state()).isEqualTo("UNINTERPRETABLE CORRUPT_HISTORY held_back_for="
                    + scenario.engine.compatibility() + " other_identities_live=unknown");
            assertThat(blocker.count()).isOne();
        }).anySatisfy(blocker -> {
            assertThat(blocker.category()).isEqualTo("NON_TERMINAL_RUN");
            assertThat(blocker.count()).isOne();
        });
        assertThat(waiting.lifecycle()).isEqualTo(RunLifecycle.QUEUED);
    }

    // #185 medium 2: a quarantine of one compatibility identity was reported as in force (a QUARANTINED_RUN blocker,
    // and an active listing) while a node of another identity held the run under a live lease, so an operator could
    // cancel a healthy run in progress. It is reported only while no live lease exists, labelled with its identity.
    @Test
    void aRunHeldUnderALiveLeaseIsNeverReportedAsQuarantined() {
        RunRecord run = scenario.submit(Map.of("task", "t"));
        var old = new DurableRunEngine(scenario.fixture.runStore, RunEventCodec.standard(), new ReleaseManifestReader(),
                LifecycleRetry.standard(), "agentworks-old-0001");
        assertThat(old.quarantine(run.id(), new ClaimQuarantine.Request(old.compatibility(),
                ClaimQuarantine.Reason.UNINTERPRETABLE, "UNKNOWN_EVENT_VERSION", "old-1", null,
                RunWorker.MAX_TIMED_QUARANTINES, null))).isPresent();
        var quarantines = repository(scenario.fixture);
        // Queued and refused by the old identity: held back, and labelled with the identity that refuses it.
        assertThat(quarantines.active(scenario.workspace, 10, null)).singleElement().satisfies(entry -> {
            assertThat(entry.quarantine().compatibility()).isEqualTo("agentworks-old-0001");
            assertThat(entry.runLifecycle()).isEqualTo("QUEUED");
            assertThat(entry.cohortState()).isEqualTo("ROUTABLE");
            assertThat(entry.heldBack()).isTrue();
        });
        assertThat(blockers(run)).containsExactly(HELD_BACK);

        // A node of this identity claims and runs it: a live lease, so nothing holds it back.
        var claimed = scenario.engine.claimNext("new-1", scenario.scope(), RunScenario.LEASE).orElseThrow();
        assertThat(claimed.run().id()).isEqualTo(run.id());
        assertThat(quarantines.active(scenario.workspace, 10, null)).isEmpty();
        assertThat(quarantines.list(scenario.workspace, true, 10, null)).singleElement()
                .satisfies(entry -> assertThat(entry.quarantine().compatibility()).isEqualTo("agentworks-old-0001"));
        assertThat(blockers(run)).containsExactly("NON_TERMINAL_RUN ACTIVE 1");

        // Its lease lapses: claimable again, so the old identity's quarantine holds it back once more.
        scenario.expireLease(run.id());
        assertThat(quarantines.active(scenario.workspace, 10, null)).hasSize(1);
        assertThat(blockers(run)).containsExactly(HELD_BACK);
    }

    private static final String HELD_BACK = "QUARANTINED_RUN UNINTERPRETABLE UNKNOWN_EVENT_VERSION "
            + "held_back_for=agentworks-old-0001 other_identities_live=unknown 1";

    // #189 medium 3: the listing's heldBack and the quiescence blocker disagreed about the cohort: a quarantined queued
    // run of a cohort that admits no claims was listed as not held back but reported as QUARANTINED_RUN. Both now use
    // one predicate: such a run waits for its cohort, not for the quarantine.
    @Test
    void theListingAndTheQuiescenceReportAgreeThatACohortAdmittingNoClaimsIsNotAQuarantine() {
        RunRecord run = scenario.submit(Map.of("task", "t"));
        var old = new DurableRunEngine(scenario.fixture.runStore, RunEventCodec.standard(), new ReleaseManifestReader(),
                LifecycleRetry.standard(), "agentworks-old-0001");
        assertThat(old.quarantine(run.id(), new ClaimQuarantine.Request(old.compatibility(),
                ClaimQuarantine.Reason.UNINTERPRETABLE, "UNKNOWN_EVENT_VERSION", "old-1", null,
                RunWorker.MAX_TIMED_QUARANTINES, null))).isPresent();
        String state = scenario.fixture.jdbc.queryForObject("select state from runtime_cohort where id = ?",
                String.class, run.pins().cohortId());
        scenario.fixture.bypassFence("update runtime_cohort set state = 'SUSPENDED' where id = ?", run.pins().cohortId());
        try {
            assertThat(repository(scenario.fixture).active(scenario.workspace, 10, null)).singleElement()
                    .satisfies(entry -> {
                        assertThat(entry.cohortState()).isEqualTo("SUSPENDED");
                        assertThat(entry.heldBack()).isFalse();
                    });
            assertThat(blockers(run)).containsExactly("NON_TERMINAL_RUN QUEUED 1");
        } finally {
            scenario.fixture.bypassFence("update runtime_cohort set state = ? where id = ?", state,
                    run.pins().cohortId());
        }
        assertThat(blockers(run)).containsExactly(HELD_BACK);
    }

    // #185 review round 3: an in-force quarantine on a paused (or blocked) run is still listed, with the run's state,
    // and marked as not what holds the run back: the run waits for a resume, and is reported as the paused run it is.
    @Test
    void aQuarantineOnAPausedRunIsListedWithTheRunsStateAndNotAsHoldingItBack() {
        RunRecord run = scenario.submit(Map.of("task", "t"));
        var old = new DurableRunEngine(scenario.fixture.runStore, RunEventCodec.standard(), new ReleaseManifestReader(),
                LifecycleRetry.standard(), "agentworks-old-0001");
        assertThat(old.quarantine(run.id(), new ClaimQuarantine.Request(old.compatibility(),
                ClaimQuarantine.Reason.UNINTERPRETABLE, "UNKNOWN_EVENT_VERSION", "old-1", null,
                RunWorker.MAX_TIMED_QUARANTINES, null))).isPresent();
        var claimed = scenario.engine.claimNext("new-1", scenario.scope(), RunScenario.LEASE).orElseThrow();
        scenario.engine.pause(claimed.lease(), "operator");

        assertThat(repository(scenario.fixture).active(scenario.workspace, 10, null)).singleElement()
                .satisfies(entry -> {
                    assertThat(entry.quarantine().compatibility()).isEqualTo("agentworks-old-0001");
                    assertThat(entry.runLifecycle()).isEqualTo("PAUSED");
                    assertThat(entry.heldBack()).isFalse();
                });
        assertThat(blockers(run)).containsExactly("NON_TERMINAL_RUN PAUSED 1");
    }

    private List<String> blockers(RunRecord run) {
        return scenario.fixture.deployments.observeQuiescence(scenario.workspace, run.pins().cohortId(), "tester")
                .blockers().stream().filter(blocker -> blocker.category().endsWith("_RUN"))
                .map(blocker -> blocker.category() + " " + blocker.state() + " " + blocker.count()).toList();
    }

    @Test
    void aRunPlaneLoginRecordsAndHonorsQuarantinesAndAControlPlaneLoginReleasesThem() {
        RunRecord run = scenario.submit(Map.of("task", "t"));
        var runPlane = login(scenario.fixture, "rp_login", "rp", "agentworks_run_plane");
        var controlPlane = login(scenario.fixture, "cp_login", "cp", "agentworks_control_plane");

        // The quarantine write locks the run row under the Run Plane's own privileges.
        assertThat(runPlane.engine.quarantine(run.id(), new ClaimQuarantine.Request(runPlane.engine.compatibility(),
                ClaimQuarantine.Reason.UNINTERPRETABLE, "CORRUPT_HISTORY", "worker-1", null,
                RunWorker.MAX_TIMED_QUARANTINES, null))).hasValueSatisfying(quarantine ->
                assertThat(quarantine.indefinite()).isTrue());
        assertThat(runPlane.engine.claimNext("worker-2", scenario.scope(), RunScenario.LEASE)).isEmpty();
        // The Run Plane cannot release a quarantine; the Control Plane lists and releases it.
        assertThatThrownBy(() -> repository(runPlane).release(scenario.workspace, run.id()))
                .hasStackTraceContaining("permission denied");
        assertThat(repository(controlPlane).active(scenario.workspace, 10, null)).hasSize(1);
        assertThat(repository(controlPlane).release(scenario.workspace, run.id())).isOne();
        assertThat(runPlane.engine.claimNext("worker-2", ClaimScope.any(ReleaseManifestReader.SUPPORTED_VERSIONS),
                RunScenario.LEASE)).isPresent();
    }

    @Test
    void thePlanesHoldExactlyTheQuarantinePrivilegesTheyNeed() {
        RunRecord run = scenario.submit(Map.of("task", "t"));
        var fixture = scenario.fixture;
        String insert = "insert into agent_run_claim_quarantine(workspace_id, run_id, compatibility, reason, code, "
                + "quarantines, quarantined_by, quarantined_at, not_before) values (?, ?, 'agentworks-test', "
                + "'CLAIM_FAILING', 'X', 1, 'w', clock_timestamp(), clock_timestamp() + interval '1 minute')";

        assertThatThrownBy(() -> fixture.asRole("agentworks_control_plane", insert, scenario.workspace.value(), run.id()))
                .hasStackTraceContaining("permission denied");
        fixture.asRole("agentworks_run_plane", insert, scenario.workspace.value(), run.id());
        assertThat(fixture.countAsRole("agentworks_control_plane", "select count(*) from agent_run_claim_quarantine "
                + "where run_id = ?", run.id())).isOne();
        fixture.asRole("agentworks_run_plane", "update agent_run_claim_quarantine set quarantines = 2 where run_id = ?",
                run.id());
        fixture.asRole("agentworks_control_plane", "update agent_run_claim_quarantine set quarantines = 3 where run_id = ?",
                run.id());
        // A quarantine can never end before it starts, nor carry an unknown reason.
        assertThatThrownBy(() -> fixture.asRole("agentworks_run_plane", "update agent_run_claim_quarantine set "
                + "not_before = quarantined_at where run_id = ?", run.id())).hasStackTraceContaining("check");
        assertThatThrownBy(() -> fixture.asRole("agentworks_run_plane", "update agent_run_claim_quarantine set "
                + "reason = 'OTHER' where run_id = ?", run.id())).hasStackTraceContaining("check");
        // Only the Control Plane releases one.
        assertThatThrownBy(() -> fixture.asRole("agentworks_run_plane", "delete from agent_run_claim_quarantine "
                + "where run_id = ?", run.id())).hasStackTraceContaining("permission denied");
        fixture.asRole("agentworks_control_plane", "delete from agent_run_claim_quarantine where run_id = ?", run.id());
        assertThat(fixture.count("select count(*) from agent_run_claim_quarantine where run_id = ?", run.id())).isZero();
    }
}
