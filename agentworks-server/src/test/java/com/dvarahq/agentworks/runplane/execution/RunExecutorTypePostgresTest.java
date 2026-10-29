package com.dvarahq.agentworks.runplane.execution;

import com.dvarahq.agentworks.domain.run.ClaimScope;
import com.dvarahq.agentworks.domain.run.RunLifecycle;
import com.dvarahq.agentworks.domain.run.RunRecord;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Issue #34, review 4, items 1 and 2: a run's executor type is an immutable creation fact recorded at admission from
 * its pinned release manifest, and the claim query selects only runs of the claiming node's executor types.
 */
class RunExecutorTypePostgresTest {
    private static final int FOREIGN_RUNS = 1_100;
    private final RunScenario scenario = new RunScenario();

    @Test
    void admissionRecordsThePinnedReleasesExecutorType() {
        RunRecord run = scenario.submit(null);

        assertThat(scenario.fixture.jdbc.queryForObject("select executor_type from agent_run where id = ?", String.class,
                run.id())).isEqualTo("spring-ai");
    }

    @Test
    void aNodeNeverSelectsARunOfAnotherExecutorTypeEvenBehindAThousandOlderOnes() {
        RunRecord own = scenario.submit(null);
        // More than a thousand queued runs of another executor type, all older than this node's run, written directly
        // as a node of that type would find them.
        scenario.fixture.bypassFence("insert into agent_run select (jsonb_populate_record(null::agent_run, to_jsonb(r) "
                + "|| jsonb_build_object('id', s.id, 'root_run_id', s.id, 'executor_type', 'remote-http', 'trace_id', "
                + "replace(s.id::text, '-', ''), 'created_at', r.created_at - interval '1 hour' "
                + "+ s.ordinal * interval '1 millisecond'))).* from agent_run r cross join lateral "
                + "(select gen_random_uuid() as id, ordinal from generate_series(1, ?) ordinal) s where r.id = ?",
                FOREIGN_RUNS, own.id());
        assertThat(scenario.count("select count(*) from agent_run where workspace_id = ? and executor_type = "
                + "'remote-http' and lifecycle = 'QUEUED' and created_at < ?", scenario.workspace.value(),
                java.sql.Timestamp.from(own.pins().createdAt()))).isEqualTo(FOREIGN_RUNS);
        ClaimScope springNode = scenario.scope().executing(Set.of("spring-ai"));

        var claimed = scenario.engine.claimNext("spring-node", springNode, RunScenario.LEASE).orElseThrow();

        assertThat(claimed.run().id()).isEqualTo(own.id());
        assertThat(scenario.engine.claimNext("spring-node", springNode, RunScenario.LEASE)).isEmpty();
        // A node of no present type selects nothing at all, rather than claiming and refusing foreign runs.
        assertThat(scenario.engine.claimNext("other-node", scenario.scope().executing(Set.of("python")),
                RunScenario.LEASE)).isEmpty();
        assertThat(scenario.count("select count(*) from agent_run where workspace_id = ? and executor_type = "
                + "'remote-http' and lifecycle = 'QUEUED' and lease_owner is null", scenario.workspace.value()))
                .isEqualTo(FOREIGN_RUNS);
        // The database is shared with other tests: the synthetic runs are removed as they were written.
        scenario.fixture.bypassFence("delete from agent_run where workspace_id = ? and executor_type = 'remote-http'",
                scenario.workspace.value());
    }

    // #181 low 6: the guard fires only for inserts and for updates that name executor_type (V19_1), not on every run
    // write; theExecutorTypeIsGuardedByTheDatabase shows it still refuses every change.
    @Test
    void theExecutorTypeGuardFiresOnlyForWritesThatCanSetIt() {
        assertThat(scenario.fixture.jdbc.queryForObject("select pg_get_triggerdef(oid) from pg_trigger where tgname = "
                + "'agent_run_fence_executor_type'", String.class))
                .contains("BEFORE INSERT OR UPDATE OF executor_type ON");
    }

    @Test
    void theExecutorTypeIsGuardedByTheDatabase() {
        RunRecord run = scenario.submit(null);
        var fixture = scenario.fixture;

        // It can never change, not even by the table owner outside the application.
        assertThatThrownBy(() -> fixture.jdbc.update("update agent_run set executor_type = 'remote-http' where id = ?",
                run.id())).hasStackTraceContaining("IMMUTABLE_PIN");
        // The Run Plane has no privilege on the column at all.
        assertThatThrownBy(() -> fixture.asRole("agentworks_run_plane", "update agent_run set executor_type = "
                + "'remote-http' where id = ?", run.id())).hasStackTraceContaining("permission denied");
        // A run is created with its pinned release's executor type only: a forged insert with another is refused.
        assertThatThrownBy(() -> fixture.asRole("agentworks_run_plane", "insert into agent_run(workspace_id, id, "
                + "definition_id, version_id, release_id, deployment_id, environment_id, revision_id, cohort_id, admission, "
                + "parent_run_id, root_run_id, continuation_kind, lifecycle, created_at, trace_id, manifest_version, "
                + "limit_total_tokens, limit_model_calls, limit_tool_calls, limit_runtime_millis, limit_cost_usd, "
                + "event_sequence, last_event_digest, executor_type) select workspace_id, gen_random_uuid(), "
                + "definition_id, version_id, release_id, deployment_id, environment_id, revision_id, cohort_id, "
                + "'CONTINUATION', id, root_run_id, 'CHILD', 'CREATED', current_timestamp, trace_id, manifest_version, "
                + "limit_total_tokens, limit_model_calls, limit_tool_calls, limit_runtime_millis, limit_cost_usd, 1, "
                + "'sha256:' || repeat('0', 64), 'remote-http' from agent_run where id = ?", run.id()))
                .hasStackTraceContaining("CREATION_FACTS_REQUIRED");
        // A durable run always carries one.
        assertThat(fixture.count("select count(*) from agent_run where trace_id is not null and executor_type is null"))
                .isZero();
        assertThat(scenario.engine.run(scenario.workspace, run.id()).lifecycle()).isEqualTo(RunLifecycle.QUEUED);
    }
}
