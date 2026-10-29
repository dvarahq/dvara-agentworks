package com.dvarahq.agentworks.runplane.execution;

import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.CohortCommand;
import com.dvarahq.agentworks.domain.run.RunEventType;
import com.dvarahq.agentworks.domain.run.RunLifecycle;
import com.dvarahq.agentworks.domain.run.RunRecord;
import com.dvarahq.agentworks.runplane.execution.DurableRunEngine.ClaimedRun;
import com.dvarahq.agentworks.runplane.execution.DurableRunEngine.PauseResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.dvarahq.agentworks.runplane.execution.DurableRunLifecyclePostgresTest.assertCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Issue #183 on PostgreSQL: the fence and event guard admit exactly the new QUEUED -> PAUSED transition and its
 * lease-less RUN_PAUSED (V15_1), the plane roles hold exactly the pause-request privileges they need, and a pause racing
 * cancellation, completion, a claim, a suspension, or a lease expiry ends in exactly one legal outcome.
 */
class RunPausePostgresTest {
    private static final String OPERATOR = "user:ops";
    /** Runs of an executor that checkpoints at every boundary, so an operator may pause them while active. */
    private final RunScenario scenario = new RunScenario(
            com.dvarahq.agentworks.controlplane.lifecycle.LifecycleFixture.CHECKPOINTING);
    private final DurableRunEngine engine = scenario.engine;

    private long events(UUID run, RunEventType type) {
        return scenario.count("select count(*) from agent_run_event where run_id = ? and event_type = ?", run,
                type.name());
    }

    private long requests(UUID run) {
        return scenario.count("select count(*) from agent_run_pause_request where run_id = ?", run);
    }

    private void intact(UUID run) {
        assertThat(engine.verify(scenario.workspace, run).intact()).isTrue();
    }

    /** Runs {@code work} with a data source logged in as a fresh member of the Run Plane role, as dedicated planes are. */
    private void asRunPlaneLogin(java.util.function.Consumer<javax.sql.DataSource> work) {
        String login = "run_plane_" + UUID.randomUUID().toString().replace("-", "");
        scenario.fixture.jdbc.execute("create role " + login
                + " login password 'run-plane' in role agentworks_run_plane");
        scenario.fixture.jdbc.execute("alter role " + login + " set agentworks.workspace_id = '"
                + scenario.workspace.value() + "'");
        try {
            work.accept(new org.springframework.jdbc.datasource.DriverManagerDataSource(
                    com.dvarahq.agentworks.controlplane.lifecycle.LifecycleFixture.postgresDatabase().jdbcUrl(), login,
                    "run-plane"));
        } finally {
            scenario.fixture.jdbc.execute("drop role " + login);
        }
    }

    private UUID organizationOf(UUID workspace) {
        return scenario.fixture.jdbc.queryForObject("select organization_id from workspace where id = ?", UUID.class,
                workspace);
    }

    private static com.dvarahq.agentworks.domain.identity.AuthenticatedPrincipal operator(UUID organization,
            com.dvarahq.agentworks.domain.tenant.WorkspaceId workspace) {
        return new com.dvarahq.agentworks.domain.identity.AuthenticatedPrincipal(UUID.randomUUID(),
                new com.dvarahq.agentworks.domain.tenant.OrganizationId(organization), "ops", workspace,
                com.dvarahq.agentworks.domain.identity.WorkspaceRole.OPERATOR,
                com.dvarahq.agentworks.domain.identity.WorkspaceRole.OPERATOR.permissions(), false,
                com.dvarahq.agentworks.domain.identity.AuthenticationMethod.API_TOKEN, UUID.randomUUID(), false);
    }

    // #194 medium 3, on PostgreSQL under a Run Plane login (as dedicated planes run): the pause request and its audit
    // record are written in one transaction on the engine's own connection, the record through
    // agentworks_append_run_control_audit (V17_1), so a record the database refuses leaves no change behind. The
    // record's organization is the workspace's, whatever the principal carries.
    @Test
    void aPauseRequestAndItsAuditRecordCommitTogetherUnderTheRunPlaneLogin() {
        asRunPlaneLogin(dataSource -> {
            var transactions = new org.springframework.transaction.support.TransactionTemplate(
                    new org.springframework.jdbc.support.JdbcTransactionManager(dataSource));
            var jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
            var runPlane = new DurableRunEngine(new com.dvarahq.agentworks.storage.run.JdbcDurableRunStore(jdbc,
                    transactions), RunEventCodec.standard(), new ReleaseManifestReader(),
                    com.dvarahq.agentworks.storage.lifecycle.LifecycleRetry.standard(), engine.compatibility(),
                    ExecutorCapabilities.Registry.of(Map.of(
                            com.dvarahq.agentworks.controlplane.lifecycle.LifecycleFixture.CHECKPOINTING,
                            new ExecutorCapabilities(true))));
            // A principal whose organization is not the workspace's: the record must still name the workspace's.
            var principal = operator(UUID.randomUUID(), scenario.workspace);
            var audit = com.dvarahq.agentworks.identity.ControlActionAudit.transactional(transactions,
                    new com.dvarahq.agentworks.identity.RunControlAuditLog(
                            new com.dvarahq.agentworks.storage.run.JdbcRunControlAudit(jdbc, true)), () -> principal,
                    com.dvarahq.agentworks.storage.lifecycle.LifecycleRetry.standard());
            UUID run = scenario.started("worker", RunScenario.LEASE).run().id();

            PauseResult requested = audit.record("RUN_PAUSE_REQUESTED",
                    () -> runPlane.requestPause(scenario.workspace, run, principal.actor(), null),
                    done -> new com.dvarahq.agentworks.identity.ControlActionAudit.Target("RUN", run,
                            done.outcome().name(), scenario.workspace));
            assertThat(requested.pending()).isTrue();
            assertThat(requests(run)).isOne();
            assertThat(scenario.count("select count(*) from auth_audit_event where target_id = ? and event_type = "
                    + "'RUN_PAUSE_REQUESTED' and detail = 'REQUESTED' and actor_id = ? and organization_id = ? and "
                    + "workspace_id = ?", run, principal.subjectId(), organizationOf(scenario.workspace.value()),
                    scenario.workspace.value())).isOne();

            // A withdrawal whose record the database refuses (here, one naming another workspace, which the function
            // refuses) rolls back with it.
            UUID otherWorkspace = new RunScenario(scenario.fixture,
                    com.dvarahq.agentworks.controlplane.lifecycle.LifecycleFixture.CHECKPOINTING).workspace.value();
            assertThatThrownBy(() -> audit.record("RUN_PAUSE_WITHDRAWN",
                    () -> runPlane.resumePaused(scenario.workspace, run, principal.actor()),
                    done -> new com.dvarahq.agentworks.identity.ControlActionAudit.Target("RUN", run, "WITHDRAWN",
                            new com.dvarahq.agentworks.domain.tenant.WorkspaceId(otherWorkspace))))
                    .hasStackTraceContaining("UNAUTHORIZED_AUDIT_WRITER");
            assertThat(requests(run)).isOne();
            assertThat(scenario.count("select count(*) from auth_audit_event where target_id = ?", run)).isOne();
        });
    }

    // #201 low 1, on PostgreSQL under a Run Plane login: another operator's repeat of a standing request is recorded
    // once (V20, granted by V20_1), and the record goes with the request when the Run Plane removes it, through the
    // foreign key's cascade, although the Run Plane cannot delete such records itself.
    @Test
    void aRepeatOfAStandingPauseRequestIsRecordedOncePerOperatorAndGoesWithTheRequest() {
        asRunPlaneLogin(dataSource -> {
            var transactions = new org.springframework.transaction.support.TransactionTemplate(
                    new org.springframework.jdbc.support.JdbcTransactionManager(dataSource));
            var runPlane = new DurableRunEngine(new com.dvarahq.agentworks.storage.run.JdbcDurableRunStore(
                    new org.springframework.jdbc.core.JdbcTemplate(dataSource), transactions), RunEventCodec.standard(),
                    new ReleaseManifestReader(), com.dvarahq.agentworks.storage.lifecycle.LifecycleRetry.standard(),
                    engine.compatibility(), ExecutorCapabilities.Registry.of(Map.of(
                            com.dvarahq.agentworks.controlplane.lifecycle.LifecycleFixture.CHECKPOINTING,
                            new ExecutorCapabilities(true))));
            UUID run = scenario.started("worker", RunScenario.LEASE).run().id();

            assertThat(runPlane.requestPause(scenario.workspace, run, OPERATOR, null).outcome())
                    .isEqualTo(PauseResult.Outcome.REQUESTED);
            assertThat(runPlane.requestPause(scenario.workspace, run, OPERATOR, null).firstRepeat()).isFalse();
            assertThat(runPlane.requestPause(scenario.workspace, run, "user:other", null).firstRepeat()).isTrue();
            assertThat(runPlane.requestPause(scenario.workspace, run, "user:other", null).firstRepeat()).isFalse();
            assertThat(scenario.count("select count(*) from agent_run_pause_repeat where run_id = ?", run)).isOne();
            var runPlaneJdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
            assertThatThrownBy(() -> runPlaneJdbc.update("delete from agent_run_pause_repeat where run_id = ?", run))
                    .hasStackTraceContaining("permission denied");
            // #201 review, low 8: the Run Plane only inserts, and never records the standing requester's own repeat.
            assertThatThrownBy(() -> runPlaneJdbc.queryForObject("select count(*) from agent_run_pause_repeat",
                    Long.class)).hasStackTraceContaining("permission denied");
            assertThatThrownBy(() -> runPlaneJdbc.update("insert into agent_run_pause_repeat(workspace_id, run_id, "
                    + "actor, repeated_at) values (?, ?, ?, now())", scenario.workspace.value(), run, OPERATOR))
                    .hasStackTraceContaining("PAUSE_REPEAT_BY_REQUESTER");

            assertThat(runPlane.resumePaused(scenario.workspace, run, OPERATOR).lifecycle())
                    .isEqualTo(RunLifecycle.ACTIVE);
            assertThat(requests(run)).isZero();
            assertThat(scenario.count("select count(*) from agent_run_pause_repeat where run_id = ?", run)).isZero();
            assertThat(runPlane.requestPause(scenario.workspace, run, OPERATOR, null).outcome())
                    .isEqualTo(PauseResult.Outcome.REQUESTED);
            assertThat(runPlane.requestPause(scenario.workspace, run, "user:other", null).firstRepeat()).isTrue();
        });
    }

    // #194 and the PR #196 review: the Run Plane holds no privilege on auth_audit_event; it appends only through
    // agentworks_append_run_control_audit, which admits exactly the run-control pairs, for a run of the given workspace,
    // with that workspace's organization.
    @Test
    void theRunPlaneAppendsOnlyRunControlRecordsThroughTheFunction() {
        UUID workspace = scenario.workspace.value();
        UUID run = scenario.submit(Map.of("task", "t")).id();
        UUID otherWorkspace = new RunScenario(scenario.fixture,
                com.dvarahq.agentworks.controlplane.lifecycle.LifecycleFixture.CHECKPOINTING).workspace.value();
        asRunPlaneLogin(dataSource -> {
            var jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
            var writer = new com.dvarahq.agentworks.storage.run.JdbcRunControlAudit(jdbc, true);
            // Every outcome the engine reports, and the withdrawal, is admitted.
            for (PauseResult.Outcome outcome : PauseResult.Outcome.values()) {
                writer.append(workspace, run, "RUN_PAUSE_REQUESTED", outcome.name(), UUID.randomUUID(), "api_token",
                        null, null);
            }
            writer.append(workspace, run, "RUN_PAUSE_WITHDRAWN", "WITHDRAWN", UUID.randomUUID(), "session", null,
                    "127.0.0.1");
            assertThat(scenario.count("select count(*) from auth_audit_event where target_id = ? and organization_id = "
                    + "? and outcome = 'SUCCEEDED' and target_type = 'RUN'", run, organizationOf(workspace)))
                    .isEqualTo(PauseResult.Outcome.values().length + 1);

            // The function checks every rule itself, whatever the caller does (#198: a NULL event type or detail
            // made the pair check NULL, which passed).
            String append = "select agentworks_append_run_control_audit(?, ?, ?, ?, ?, 'api_token', null, null)";
            java.util.List<Object[]> forged = new java.util.ArrayList<>(List.of(
                    new Object[]{"LOGIN_SUCCEEDED", "REQUESTED", run, workspace},
                    new Object[]{"RUN_PAUSE_REQUESTED", "WITHDRAWN", run, workspace},
                    new Object[]{"RUN_PAUSE_WITHDRAWN", "REQUESTED", run, workspace},
                    new Object[]{"RUN_PAUSE_REQUESTED", "free text", run, workspace},
                    new Object[]{"RUN_PAUSE_REQUESTED", "REQUESTED", UUID.randomUUID(), workspace},
                    new Object[]{"RUN_PAUSE_REQUESTED", "REQUESTED", run, otherWorkspace}));
            forged.add(new Object[]{"RUN_PAUSE_REQUESTED", null, run, workspace});
            forged.add(new Object[]{"RUN_PAUSE_WITHDRAWN", null, run, workspace});
            forged.add(new Object[]{null, "REQUESTED", run, workspace});
            forged.add(new Object[]{null, null, run, workspace});
            for (Object[] record : forged) {
                assertThatThrownBy(() -> jdbc.queryForObject(append, UUID.class, record[3], record[2], record[0],
                        record[1], UUID.randomUUID())).as(java.util.Arrays.toString(record))
                        .hasStackTraceContaining("UNAUTHORIZED_AUDIT_WRITER");
            }
            // The writer refuses the same pairs before reaching the database.
            assertThatThrownBy(() -> writer.append(workspace, run, "RUN_PAUSE_REQUESTED", null, UUID.randomUUID(),
                    "api_token", null, null)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> writer.append(otherWorkspace, run, "RUN_PAUSE_REQUESTED", "REQUESTED",
                    UUID.randomUUID(), "api_token", null, null)).hasStackTraceContaining("UNAUTHORIZED_AUDIT_WRITER");
            // No direct access to the table: not to append, read, or change a record.
            assertThatThrownBy(() -> jdbc.update("insert into auth_audit_event(id, occurred_at, event_type, outcome, "
                    + "organization_id, workspace_id, target_type, target_id, detail) values (?, now(), "
                    + "'RUN_PAUSE_REQUESTED', 'SUCCEEDED', ?, ?, 'RUN', ?, 'REQUESTED')", UUID.randomUUID(),
                    organizationOf(workspace), workspace, run)).hasStackTraceContaining("permission denied");
            assertThatThrownBy(() -> jdbc.queryForObject("select count(*) from auth_audit_event", Long.class))
                    .hasStackTraceContaining("permission denied");
        });
        assertThat(scenario.count("select count(*) from auth_audit_event where target_id = ?", run))
                .isEqualTo(PauseResult.Outcome.values().length + 1);
        // The function is owned by its own NOLOGIN role (#198), with a pinned search path, and only the Run Plane may
        // execute it. The shared fence owner holds no audit authority.
        String function = "agentworks_append_run_control_audit(uuid, uuid, varchar, varchar, uuid, varchar, uuid, "
                + "varchar)";
        assertThat(scenario.fixture.jdbc.queryForObject("select proowner::regrole::text from pg_proc where proname = "
                + "'agentworks_append_run_control_audit'", String.class)).isEqualTo("agentworks_run_control_auditor");
        assertThat(scenario.fixture.jdbc.queryForObject("select rolcanlogin from pg_roles where rolname = "
                + "'agentworks_run_control_auditor'", Boolean.class)).isFalse();
        // PR #199 review, low 5: the auditor reads only explicitly granted columns, never whole rows. Issue #59
        // additionally grants the columns required by the tenant-safe shared-worker candidate function.
        for (String table : List.of("workspace", "agent_run")) {
            assertThat(scenario.fixture.jdbc.queryForObject("select has_table_privilege("
                    + "'agentworks_run_control_auditor', ?, 'SELECT')", Boolean.class, table)).as(table).isFalse();
        }
        for (String[] column : List.of(new String[]{"workspace", "id", "true"},
                new String[]{"workspace", "organization_id", "true"}, new String[]{"workspace", "name", "false"},
                new String[]{"agent_run", "workspace_id", "true"}, new String[]{"agent_run", "id", "true"},
                new String[]{"agent_run", "lifecycle", "true"}, new String[]{"agent_run", "lease_until", "true"},
                new String[]{"agent_run", "manifest_version", "true"},
                new String[]{"agent_run", "executor_type", "true"},
                new String[]{"agent_run", "created_at", "true"}, new String[]{"agent_run", "cohort_id", "true"},
                new String[]{"agent_run", "terminal_code", "false"})) {
            assertThat(scenario.fixture.jdbc.queryForObject("select has_column_privilege("
                    + "'agentworks_run_control_auditor', ?, ?, 'SELECT')", Boolean.class, column[0], column[1]))
                    .as(column[0] + "." + column[1]).isEqualTo(Boolean.parseBoolean(column[2]));
        }
        assertThat(scenario.fixture.jdbc.queryForObject("select has_table_privilege('agentworks_fence_owner', "
                + "'auth_audit_event', 'INSERT') or has_table_privilege('agentworks_fence_owner', 'workspace', "
                + "'SELECT')", Boolean.class)).isFalse();
        assertThat(scenario.fixture.jdbc.queryForObject("select prosecdef and array_to_string(proconfig, ',') like "
                + "'search_path=%pg_temp' from pg_proc where proname = 'agentworks_append_run_control_audit'",
                Boolean.class)).isTrue();
        for (String role : List.of("public", "agentworks_control_plane")) {
            assertThat(scenario.fixture.jdbc.queryForObject("select has_function_privilege(?, ?, 'EXECUTE')",
                    Boolean.class, role, function)).as(role).isFalse();
        }
        assertThat(scenario.fixture.jdbc.queryForObject("select has_function_privilege('agentworks_run_plane', ?, "
                + "'EXECUTE')", Boolean.class, function)).isTrue();
    }

    // #198 lows 1 and 2: the direct path (H2, or both planes on the owner connection) checks the same pairs as the
    // function, and both stamp the record in database time, never the caller's clock.
    @Test
    void theDirectPathChecksTheSamePairsAndStampsDatabaseTime() {
        UUID workspace = scenario.workspace.value();
        UUID run = scenario.submit(Map.of("task", "t")).id();
        var writer = new com.dvarahq.agentworks.storage.run.JdbcRunControlAudit(scenario.fixture.jdbc, false);
        assertThat(com.dvarahq.agentworks.storage.run.JdbcRunControlAudit.PAIRS.get("RUN_PAUSE_REQUESTED"))
                .containsExactlyInAnyOrderElementsOf(java.util.Arrays.stream(PauseResult.Outcome.values())
                        .map(Enum::name).toList());
        for (String[] pair : List.of(new String[]{"RUN_PAUSE_WITHDRAWN", "PAUSED"},
                new String[]{"RUN_PAUSE_REQUESTED", "WITHDRAWN"}, new String[]{"RUN_PAUSE_REQUESTED", null},
                new String[]{"LOGIN_SUCCEEDED", "REQUESTED"})) {
            assertThatThrownBy(() -> writer.append(workspace, run, pair[0], pair[1], UUID.randomUUID(), "api_token",
                    null, null)).isInstanceOf(IllegalArgumentException.class);
        }
        Boolean databaseTime = scenario.fixture.transactions.execute(status -> {
            writer.append(workspace, run, "RUN_PAUSE_REQUESTED", "REQUESTED", UUID.randomUUID(), "api_token", null,
                    null);
            return scenario.fixture.jdbc.queryForObject("select occurred_at = current_timestamp from auth_audit_event "
                    + "where target_id = ?", Boolean.class, run);
        });
        assertThat(databaseTime).isTrue();
        assertThat(scenario.count("select count(*) from auth_audit_event where target_id = ?", run)).isOne();
    }

    @Test
    void aQueuedRunPausesThroughTheFenceEvenUnderASuspendedCohortAndTheHistoryVerifies() {
        RunRecord queued = scenario.submit(Map.of("task", "t"));
        scenario.fixture.deployments.suspend(scenario.workspace, new CohortCommand(scenario.promotion.cohortId(), 2,
                null, "operator", "incident"));

        PauseResult paused = engine.requestPause(scenario.workspace, queued.id(), OPERATOR, "hold");

        assertThat(paused.pending()).isFalse();
        assertThat(paused.run().lifecycle()).isEqualTo(RunLifecycle.PAUSED);
        assertThat(events(queued.id(), RunEventType.RUN_PAUSED)).isOne();
        assertThat(scenario.count("select count(*) from agent_run_event where run_id = ? and event_type = 'RUN_PAUSED' "
                + "and lease_owner is null and actor = ?", queued.id(), OPERATOR)).isOne();
        intact(queued.id());
        // The resume still needs an executing cohort, exactly as for any paused run.
        assertCode(() -> engine.resumePaused(scenario.workspace, queued.id(), OPERATOR), "COHORT_NOT_ADMITTING");
    }

    // PR #191 review, medium 3 (the user's decision), on PostgreSQL: an active run of the Spring AI executor, which
    // cannot resume mid-exchange without repeating work, is refused PAUSE_UNSAFE with nothing written.
    @Test
    void anActiveSpringAiRunIsRefusedAsUnsafeWithNothingWritten() {
        RunScenario springAi = new RunScenario(scenario.fixture, "spring-ai");
        ClaimedRun active = springAi.started("worker", RunScenario.LEASE);
        UUID run = active.run().id();

        assertCode(() -> engine.requestPause(springAi.workspace, run, OPERATOR, null), "PAUSE_UNSAFE");

        assertThat(engine.run(springAi.workspace, run).summary().eventSequence())
                .isEqualTo(active.run().summary().eventSequence());
        assertThat(requests(run)).isZero();
        RunRecord queued = springAi.submit(Map.of("task", "q"));
        assertThat(engine.requestPause(springAi.workspace, queued.id(), OPERATOR, null).run().lifecycle())
                .isEqualTo(RunLifecycle.PAUSED);
    }

    @Test
    void theFenceNeverAcceptsAPauseEventThatNoTransitionRecords() {
        RunRecord queued = scenario.submit(Map.of("task", "t"));
        engine.requestPause(scenario.workspace, queued.id(), OPERATOR, null);
        long last = engine.run(scenario.workspace, queued.id()).summary().eventSequence();
        // A second lease-less RUN_PAUSED appended to the paused run, as direct SQL by the Run Plane, is refused at commit:
        // no transition counts it.
        assertThatThrownBy(() -> scenario.fixture.asRole("agentworks_run_plane", "insert into agent_run_event select "
                + "(jsonb_populate_record(null::agent_run_event, to_jsonb(e) || jsonb_build_object('id', "
                + "gen_random_uuid(), 'sequence', e.sequence + 1))).* from agent_run_event e where e.run_id = ? "
                + "and e.sequence = ?", queued.id(), last)).hasStackTraceContaining("UNAUTHORIZED_EVENT_WRITER");
        assertThat(events(queued.id(), RunEventType.RUN_PAUSED)).isOne();
        // A worker's pause of an active run still needs its lease: a lease-less RUN_PAUSED of an active run is refused.
        ClaimedRun active = scenario.started("worker", RunScenario.LEASE);
        long head = active.run().summary().eventSequence();
        assertThatThrownBy(() -> scenario.fixture.asRole("agentworks_run_plane", "insert into agent_run_event select "
                + "(jsonb_populate_record(null::agent_run_event, to_jsonb(e) || jsonb_build_object('id', "
                + "gen_random_uuid(), 'sequence', e.sequence + 1, 'event_type', 'RUN_PAUSED', 'lease_owner', null))).* "
                + "from agent_run_event e where e.run_id = ? and e.sequence = ?", active.run().id(), head))
                .hasStackTraceContaining("UNAUTHORIZED_EVENT_WRITER");
    }

    // PR #191 review, medium 1: the event guard admitted a lease-less RUN_PAUSED for any run already marked PAUSED, so a
    // Run Plane session could pause an active run under a live lease without holding it. The guard now requires the
    // history to prove the pause (the run was queued, or the event carries the next fence), and the fence refuses a
    // fence-advancing pause of a live lease.
    @Test
    void noSessionPausesAnActiveRunUnderALiveLeaseWithoutTheLease() {
        ClaimedRun active = scenario.started("worker", RunScenario.LEASE);
        UUID run = active.run().id();
        long head = active.run().summary().eventSequence();
        String pause = "do $$ begin update agent_run set lifecycle = 'PAUSED', lease_owner = null, lease_until = null, "
                + "active_since = null, event_sequence = event_sequence + 1, row_version = row_version + 1%s where id = '" + run + "'; "
                + "insert into agent_run_event select (jsonb_populate_record(null::agent_run_event, to_jsonb(e) || "
                + "jsonb_build_object('id', gen_random_uuid(), 'sequence', e.sequence + 1, 'event_type', 'RUN_PAUSED', "
                + "'lease_owner', null%s))).* from agent_run_event e where e.run_id = '" + run + "' and e.sequence = "
                + head + "; end $$";

        assertThatThrownBy(() -> scenario.fixture.asRole("agentworks_run_plane", pause.formatted("", "")))
                .hasStackTraceContaining("UNAUTHORIZED_EVENT_WRITER");
        assertThatThrownBy(() -> scenario.fixture.asRole("agentworks_run_plane", pause.formatted(
                ", lease_fence = lease_fence + 1", ", 'lease_fence', e.lease_fence + 1")))
                .hasStackTraceContaining("LEASE_HELD");
        assertThat(engine.run(scenario.workspace, run).lifecycle()).isEqualTo(RunLifecycle.ACTIVE);
        assertThat(events(run, RunEventType.RUN_PAUSED)).isZero();

        // Nor pause a queued run with a new fence, which no engine path writes and no rebuild could account for.
        RunRecord queued = scenario.submit(Map.of("task", "q"));
        long queuedHead = queued.summary().eventSequence();
        assertThatThrownBy(() -> scenario.fixture.asRole("agentworks_run_plane", "do $$ begin update agent_run set "
                + "lifecycle = 'PAUSED', lease_fence = lease_fence + 1, event_sequence = event_sequence + 1, "
                + "row_version = row_version + 1 where id = '" + queued.id() + "'; end $$"))
                .hasStackTraceContaining("ILLEGAL_TRANSITION");
        assertThat(engine.run(scenario.workspace, queued.id()).summary().eventSequence()).isEqualTo(queuedHead);
    }

    // PR #191 review, medium 4, on PostgreSQL: an active run whose lease expired pauses at once through the fence,
    // even under a suspended cohort (the pause is not a claim), and the lost worker's writes are refused.
    @Test
    void anActiveRunWhoseLeaseExpiredPausesAtOnceThroughTheFenceEvenUnderASuspendedCohort() {
        ClaimedRun lost = scenario.started("worker-lost", RunScenario.LEASE);
        UUID run = lost.run().id();
        scenario.expireLease(run);
        scenario.fixture.deployments.suspend(scenario.workspace, new CohortCommand(scenario.promotion.cohortId(), 2,
                null, "operator", "incident"));

        PauseResult paused = engine.requestPause(scenario.workspace, run, OPERATOR, "stuck");

        assertThat(paused.outcome()).isEqualTo(PauseResult.Outcome.PAUSED);
        assertThat(paused.run().summary().leaseFence()).isEqualTo(lost.lease().fence() + 1);
        assertThat(scenario.count("select count(*) from agent_run_event where run_id = ? and event_type = 'RUN_PAUSED' "
                + "and lease_owner is null and actor = ?", run, OPERATOR)).isOne();
        assertCode(() -> engine.complete(lost.lease(), Map.of()), "STALE_LEASE");
        intact(run);
    }

    @Test
    void thePlanesHoldExactlyThePauseRequestPrivilegesTheyNeedAndTheGuardAdmitsOnlyActiveRuns() {
        ClaimedRun active = scenario.started("worker", RunScenario.LEASE);
        UUID run = active.run().id();
        String insert = "insert into agent_run_pause_request(workspace_id, run_id, requested_by, requested_at) "
                + "values (?, ?, 'user:x', clock_timestamp())";
        assertThatThrownBy(() -> scenario.fixture.asRole("agentworks_control_plane", insert,
                scenario.workspace.value(), run)).hasStackTraceContaining("permission denied");
        assertThatThrownBy(() -> scenario.fixture.countAsRole("agentworks_control_plane", "select count(*) from "
                + "agent_run_pause_request where run_id = ?", run)).hasStackTraceContaining("permission denied");
        // The guard admits a request only for an active run, even through direct SQL.
        RunRecord queued = scenario.submit(Map.of("task", "q"));
        assertThatThrownBy(() -> scenario.fixture.asRole("agentworks_run_plane", insert, scenario.workspace.value(),
                queued.id())).hasStackTraceContaining("PAUSE_REQUEST_NOT_ACTIVE");
        scenario.fixture.asRole("agentworks_run_plane", insert, scenario.workspace.value(), run);
        assertThat(scenario.fixture.countAsRole("agentworks_run_plane", "select count(*) from "
                + "agent_run_pause_request where run_id = ?", run)).isOne();
        assertThatThrownBy(() -> scenario.fixture.asRole("agentworks_run_plane", "update agent_run_pause_request "
                + "set requested_by = 'user:y' where run_id = ?", run)).hasStackTraceContaining("permission denied");
        assertThatThrownBy(() -> scenario.fixture.asRole("agentworks_control_plane", "delete from "
                + "agent_run_pause_request where run_id = ?", run)).hasStackTraceContaining("permission denied");
        scenario.fixture.asRole("agentworks_run_plane", "delete from agent_run_pause_request where run_id = ?", run);
        assertThat(requests(run)).isZero();
    }

    @Test
    void aPauseRacingACancellationOfAQueuedRunEndsCancelledOnce() throws Exception {
        for (int round = 0; round < 5; round++) {
            RunRecord queued = scenario.submit(Map.of("round", round));
            RunScenario.race(2, party -> party == 0
                    ? engine.requestPause(scenario.workspace, queued.id(), OPERATOR, null)
                    : engine.cancel(scenario.workspace, queued.id(), OPERATOR, "stop"));
            assertThat(engine.run(scenario.workspace, queued.id()).lifecycle()).isEqualTo(RunLifecycle.CANCELLED);
            assertThat(events(queued.id(), RunEventType.RUN_CANCELLED)).isOne();
            assertThat(events(queued.id(), RunEventType.RUN_PAUSED)).isLessThanOrEqualTo(1);
            assertThat(requests(queued.id())).isZero();
            intact(queued.id());
        }
    }

    @Test
    void aPauseRacingTheCompletionOfAnActiveRunEndsSucceededOnce() throws Exception {
        for (int round = 0; round < 5; round++) {
            ClaimedRun active = scenario.started("worker-" + round, RunScenario.LEASE);
            UUID run = active.run().id();
            List<Object> outcomes = RunScenario.race(2, party -> party == 0
                    ? engine.requestPause(scenario.workspace, run, OPERATOR, null)
                    : engine.complete(active.lease(), Map.of("round", 1)));
            assertThat(engine.run(scenario.workspace, run).lifecycle()).isEqualTo(RunLifecycle.SUCCEEDED);
            // The request either came first (and ended with the run) or found the run terminal.
            assertThat(outcomes.getFirst()).satisfiesAnyOf(
                    outcome -> assertThat(outcome).isInstanceOfSatisfying(PauseResult.class,
                            result -> assertThat(result.pending()).isTrue()),
                    outcome -> assertThat(outcome).isInstanceOfSatisfying(RunEngineException.class,
                            failure -> assertThat(failure.code()).isEqualTo("RUN_TERMINAL")));
            assertThat(events(run, RunEventType.RUN_PAUSED)).isZero();
            assertThat(requests(run)).isZero();
            intact(run);
        }
    }

    @Test
    void aPauseRacingAClaimOfAQueuedRunPausesItExactlyOnce() throws Exception {
        for (int round = 0; round < 5; round++) {
            RunRecord queued = scenario.submit(Map.of("round", round));
            String worker = "worker-" + round;
            List<Object> outcomes = RunScenario.race(2, party -> party == 0
                    ? engine.requestPause(scenario.workspace, queued.id(), OPERATOR, null)
                    : engine.claimNext(worker, scenario.scope(), RunScenario.LEASE));
            @SuppressWarnings("unchecked")
            Optional<ClaimedRun> claimed = (Optional<ClaimedRun>) outcomes.get(1);
            if (claimed.isPresent()) {
                // The claim came first: the request is pending, and the worker observes it at its claim or its next
                // write, and pauses there.
                boolean observed = claimed.get().pauseRequested()
                        || engine.checkpoint(claimed.get().lease(), Map.of("step", 1)).pauseRequested();
                assertThat(observed).isTrue();
                RunRecord current = engine.run(scenario.workspace, queued.id());
                engine.pause(new com.dvarahq.agentworks.domain.run.RunLease(scenario.workspace, queued.id(),
                        current.pins().cohortId(), current.control().leaseOwner(), current.summary().leaseFence(),
                        current.control().rowVersion(), current.control().leaseUntil()), DurableRunEngine.OPERATOR_PAUSE);
            }
            assertThat(engine.run(scenario.workspace, queued.id()).lifecycle()).isEqualTo(RunLifecycle.PAUSED);
            assertThat(events(queued.id(), RunEventType.RUN_PAUSED)).isOne();
            assertThat(requests(queued.id())).isZero();
            intact(queued.id());
        }
    }

    @Test
    void aPauseRacingASuspensionPausesTheRunOnceAndTheEventNamesTheOperator() throws Exception {
        ClaimedRun active = scenario.started("worker", RunScenario.LEASE);
        UUID run = active.run().id();
        RunScenario.race(2, party -> party == 0
                ? engine.requestPause(scenario.workspace, run, OPERATOR, null)
                : scenario.fixture.deployments.suspend(scenario.workspace, new CohortCommand(
                        scenario.promotion.cohortId(), 2, null, "operator", "incident")));

        var boundary = engine.checkpoint(active.lease(), Map.of("step", 1));
        assertThat(boundary.cohortState()).isEqualTo("SUSPENDED");
        assertThat(boundary.pauseRequested()).isTrue();
        engine.pause(boundary.lease(), "COHORT_SUSPENDED");

        assertThat(events(run, RunEventType.RUN_PAUSED)).isOne();
        assertThat(engine.history(scenario.workspace, run).getLast().payload().path("requestedBy").stringValue())
                .isEqualTo(OPERATOR);
        assertThat(requests(run)).isZero();
        intact(run);
    }

    @Test
    void cancellingAnActiveRunWithARequestAndWithdrawingOneEndIt() {
        ClaimedRun cancelled = scenario.started("worker-1", RunScenario.LEASE);
        engine.requestPause(scenario.workspace, cancelled.run().id(), OPERATOR, null);
        engine.cancel(scenario.workspace, cancelled.run().id(), "user:other", "stop");
        assertThat(requests(cancelled.run().id())).isZero();
        assertThat(engine.history(scenario.workspace, cancelled.run().id()).getLast().payload()
                .path("pauseRequestedBy").stringValue()).isEqualTo(OPERATOR);
        intact(cancelled.run().id());

        ClaimedRun withdrawn = scenario.started("worker-2", RunScenario.LEASE);
        engine.requestPause(scenario.workspace, withdrawn.run().id(), OPERATOR, null);
        assertThat(engine.resumePaused(scenario.workspace, withdrawn.run().id(), OPERATOR).lifecycle())
                .isEqualTo(RunLifecycle.ACTIVE);
        assertThat(requests(withdrawn.run().id())).isZero();
        assertThat(engine.checkpoint(withdrawn.lease(), Map.of("step", 1)).pauseRequested()).isFalse();
    }

    // The worker's claim-time pause on PostgreSQL: a request that outlived its worker's lease pauses the run at the
    // next worker's claim, before its handler runs.
    @Test
    void aWorkerPausesARecoveredRunAtItsClaimBeforeRunningItsHandler() {
        ClaimedRun active = scenario.started("worker-lost", RunScenario.LEASE);
        UUID run = active.run().id();
        engine.requestPause(scenario.workspace, run, OPERATOR, null);
        scenario.expireLease(run);
        var invoked = new java.util.concurrent.atomic.AtomicBoolean();
        try (RunWorker worker = new RunWorker(engine, context -> {
            invoked.set(true);
            return RunOutcome.completed(null);
        }, "worker-2", scenario.scope(), RunScenario.LEASE)) {
            assertThat(worker.pollOnce()).isEqualTo(RunWorker.Poll.EXECUTED);
        }
        assertThat(invoked).isFalse();
        assertThat(engine.run(scenario.workspace, run).lifecycle()).isEqualTo(RunLifecycle.PAUSED);
        assertThat(engine.history(scenario.workspace, run).getLast().payload().path("requestedBy").stringValue())
                .isEqualTo(OPERATOR);
        assertThat(requests(run)).isZero();
        intact(run);
    }

    @Test
    void aRecoveryClaimAfterTheLeaseExpiredObservesTheRequestAndPausesAtTheClaim() {
        ClaimedRun active = scenario.started("worker-lost", RunScenario.LEASE);
        UUID run = active.run().id();
        assertThat(engine.requestPause(scenario.workspace, run, OPERATOR, null).pending()).isTrue();
        scenario.expireLease(run);

        ClaimedRun recovered = scenario.claim("worker-2");

        assertThat(recovered.run().id()).isEqualTo(run);
        assertThat(recovered.recovered()).isTrue();
        assertThat(recovered.pauseRequested()).isTrue();
        engine.pause(recovered.lease(), DurableRunEngine.OPERATOR_PAUSE);
        assertThat(engine.run(scenario.workspace, run).lifecycle()).isEqualTo(RunLifecycle.PAUSED);
        assertThat(requests(run)).isZero();
        // The stale holder's late write is refused: one outcome.
        assertCode(() -> engine.complete(active.lease(), Map.of()), "STALE_LEASE");
        intact(run);
    }
}
