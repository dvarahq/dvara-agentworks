package com.dvarahq.agentworks.runplane.execution;

import com.dvarahq.agentworks.domain.run.RunRecord;
import com.dvarahq.agentworks.runplane.admission.RunAdmissionRejectedException;
import com.dvarahq.agentworks.runplane.admission.RunAdmissionService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Least-privilege roles, guard ownership, and bounded JSONB payloads for durable runs on PostgreSQL. */
class RunAuthorityPostgresTest {
    private static final List<String> GUARDS = List.of("agentworks_fence_run", "agentworks_verify_run_history",
            "agentworks_guard_run_event", "agentworks_verify_counted", "agentworks_guard_run_checkpoint",
            "agentworks_guard_run_effect", "agentworks_fence_work_item", "agentworks_fence_run_executor_type");
    private final RunScenario scenario = new RunScenario();

    // Issue #34 (V8_1): the Run Plane reads a run's pinned AgentVersion, which it can never author or change.
    @Test
    void theRunPlaneReadsButCannotWriteAgentVersions() {
        RunRecord run = scenario.submit(null);
        var fixture = scenario.fixture;

        assertThat(fixture.countAsRole("agentworks_run_plane", "select count(*) from agent_version where workspace_id = ? "
                + "and id = ?", scenario.workspace.value(), run.pins().versionId())).isOne();
        for (String forbidden : List.of(
                "update agent_version set version_label = version_label where id = ?",
                "delete from agent_version where id = ?")) {
            assertThatThrownBy(() -> fixture.asRole("agentworks_run_plane", forbidden, run.pins().versionId()))
                    .as(forbidden).hasStackTraceContaining("permission denied");
        }
        // The engine hands a claim the version whose digest the manifest pinned, read as the run's workspace.
        var claimed = scenario.engine.claimNext("worker", scenario.scope(), RunScenario.LEASE).orElseThrow();
        assertThat(claimed.agentVersion()).hasValueSatisfying(version -> {
            assertThat(version.versionId()).isEqualTo(run.pins().versionId());
            assertThat(version.specDigest()).isEqualTo(claimed.manifest().specDigest());
        });
    }

    @Test
    void theRunPlaneCanCheckpointArtifactReferencesWithoutReceivingArtifactAuthority() throws Exception {
        var claimed = scenario.started("worker", RunScenario.LEASE);
        try (Connection connection = scenario.fixture.dataSource.getConnection()) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("set role agentworks_run_plane");
            }
            var source = new SingleConnectionDataSource(connection, true);
            var runPlane = new DurableRunEngine(
                    new com.dvarahq.agentworks.storage.run.JdbcDurableRunStore(new JdbcTemplate(source),
                            new TransactionTemplate(new DataSourceTransactionManager(source))),
                    RunEventCodec.standard(), new ReleaseManifestReader(),
                    com.dvarahq.agentworks.storage.lifecycle.LifecycleRetry.standard());

            assertThat(runPlane.checkpoint(claimed.lease(), Map.of("step", 1)).run().summary().checkpointSequence())
                    .isOne();
            assertThatThrownBy(() -> new JdbcTemplate(source).queryForObject(
                    "select provenance_actor from artifact_object where workspace_id = ? fetch first 1 rows only",
                    String.class, scenario.workspace.value())).hasStackTraceContaining("permission denied");
            try (Statement statement = connection.createStatement()) {
                statement.execute("reset role");
            }
        }
    }

    @Test
    void theRunPlaneRecordsRunHistoryButCannotAuthorControlStatePinsOrRewriteHistory() {
        RunRecord run = scenario.submit(null);
        var fixture = scenario.fixture;
        for (String forbidden : List.of(
                "update agent_run set revision_id = revision_id where id = ?",
                "update agent_run set trace_id = trace_id where id = ?",
                "delete from agent_run_event where run_id = ?",
                "delete from agent_run_checkpoint where run_id = ?",
                "update agent_run_event set payload_json = '{}' where run_id = ?",
                "delete from cohort_work_item where run_id = ?",
                "update runtime_cohort set state = 'RETIRED' where id = (select cohort_id from agent_run where id = ?)",
                "insert into control_plane_outbox_delivery(consumer, workspace_id, outbox_id, delivered_at) "
                        + "select 'forged', workspace_id, id, current_timestamp from control_plane_outbox "
                        + "where ? is not null",
                "delete from control_plane_outbox_attempt where ? is not null")) {
            assertThatThrownBy(() -> fixture.asRole("agentworks_run_plane", forbidden, run.id())).as(forbidden)
                    .hasStackTraceContaining("permission denied");
        }
        // The Control Plane reads run evidence for quiescence but never writes run history.
        for (String forbidden : List.of(
                "update agent_run set lifecycle = lifecycle where id = ?",
                "insert into agent_run_event select * from agent_run_event where run_id = ?",
                "update cohort_work_item set state = 'TERMINAL' where run_id = ?",
                "update agent_run_effect set state = 'ABANDONED' where run_id = ?")) {
            assertThatThrownBy(() -> fixture.asRole("agentworks_control_plane", forbidden, run.id())).as(forbidden)
                    .hasStackTraceContaining("permission denied");
        }
        fixture.asRole("agentworks_control_plane", "insert into control_plane_outbox_delivery(consumer, workspace_id, "
                + "outbox_id, delivered_at) select ?, workspace_id, id, current_timestamp from control_plane_outbox "
                + "where false", "none");
    }

    @Test
    void durableRunGuardsRunAsTheFenceOwnerWithAPinnedSearchPathAndNoPublicExecute() {
        for (String guard : GUARDS) {
            var row = scenario.fixture.jdbc.queryForMap("select pg_get_userbyid(p.proowner) as owner, p.prosecdef as definer, "
                    + "array_to_string(p.proconfig, ',') as config, has_function_privilege('agentworks_run_plane', p.oid, "
                    + "'EXECUTE') as run_plane, has_function_privilege('agentworks_control_plane', p.oid, 'EXECUTE') "
                    + "as control_plane from pg_proc p where p.proname = ?", guard);
            assertThat(row.get("owner")).as(guard).isEqualTo("agentworks_fence_owner");
            assertThat(row.get("definer")).as(guard).isEqualTo(true);
            assertThat((String) row.get("config")).as(guard).startsWith("search_path=").endsWith("pg_temp");
            assertThat(row.get("run_plane")).as(guard).isEqualTo(false);
            assertThat(row.get("control_plane")).as(guard).isEqualTo(false);
        }
        assertThat(scenario.count("select count(*) from pg_trigger where tgname = 'agent_run_history_backed' "
                + "and tgdeferrable and tginitdeferred")).isOne();
    }

    @Test
    void eventPayloadsAreBoundedJsonbObjects() {
        RunRecord run = scenario.submit(Map.of("unicode", "é✓", "number", 1.25));
        assertThat(scenario.fixture.jdbc.queryForObject("select data_type from information_schema.columns "
                + "where table_name = 'agent_run_event' and column_name = 'payload_json'", String.class)).isEqualTo("jsonb");
        assertThat(scenario.engine.verify(scenario.workspace, run.id()).intact()).isTrue();

        assertThatThrownBy(() -> scenario.fixture.admissions.admitRoot(scenario.workspace,
                scenario.promotion.deploymentId(), null, Map.of("blob", "x".repeat(RunEventCodec.MAX_PAYLOAD_BYTES)),
                RunAdmissionService.Options.SUBMIT)).isInstanceOfSatisfying(RunAdmissionRejectedException.class,
                rejected -> assertThat(rejected.code()).isEqualTo("PAYLOAD_TOO_LARGE"));
        // The database bounds and shapes payloads even for a writer that bypasses the engine's validation.
        for (String payload : List.of("'[1,2]'::jsonb", "jsonb_build_object('blob', repeat('x', 262144))")) {
            assertThatThrownBy(() -> scenario.fixture.bypassFence("insert into agent_run_event(workspace_id, run_id, "
                    + "sequence, id, event_type, schema_version, payload_json, payload_digest, event_digest, trace_id, "
                    + "release_id, deployment_id, revision_id, cohort_id, actor, lease_fence, created_at) select workspace_id, "
                    + "id, 99, gen_random_uuid(), 'MODEL_REQUESTED', 1, " + payload + ", 'sha256:' || repeat('0', 64), "
                    + "'sha256:' || repeat('0', 64), trace_id, release_id, deployment_id, revision_id, cohort_id, 'x', 0, "
                    + "current_timestamp from agent_run where id = ?", run.id())).as(payload)
                    .hasStackTraceContaining("agent_run_event_payload_ck");
        }
    }
}
