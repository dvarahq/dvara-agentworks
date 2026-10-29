package com.dvarahq.agentworks.controlplane.agent.web;

import com.dvarahq.agentworks.domain.identity.WorkspaceRole;
import com.dvarahq.agentworks.domain.run.ClaimScope;
import com.dvarahq.agentworks.domain.run.RunBlockReason;
import com.dvarahq.agentworks.domain.run.RunLease;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import com.dvarahq.agentworks.DvaraAgentWorksApplication;
import com.dvarahq.agentworks.identity.TestIdentities;
import com.dvarahq.agentworks.runplane.execution.DurableRunEngine;
import com.dvarahq.agentworks.runplane.execution.ReleaseManifestReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #183 over HTTP: {@code POST /api/v1/runs/{id}/pause} pauses a queued run at once and requests an active run's
 * pause at its next safe boundary, records the authenticated actor, is idempotent, is authorized like cancel and resume,
 * fails closed across workspaces, and the pause is visible on the run's event stream.
 */
@SpringBootTest(classes = DvaraAgentWorksApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestWorkspaces.class, RunPauseApiTest.CheckpointingExecutor.class})
class RunPauseApiTest {
    private static final String JSON = "application/json";
    private static final Duration WAIT = Duration.ofSeconds(5);
    private static final String CHECKPOINTING = "checkpointing-test";

    /**
     * A test executor type that checkpoints at every safe boundary and declares itself resumable mid-exchange, so an
     * operator may pause its active runs (#183); the Spring AI executor may not.
     */
    @org.springframework.boot.test.context.TestConfiguration
    static class CheckpointingExecutor {
        @org.springframework.context.annotation.Bean
        com.dvarahq.agentworks.runplane.execution.ExecutorCapabilities.Declaring checkpointingExecutor() {
            return new com.dvarahq.agentworks.runplane.execution.ExecutorCapabilities.Declaring() {
                @Override
                public String type() {
                    return CHECKPOINTING;
                }

                @Override
                public com.dvarahq.agentworks.runplane.execution.ExecutorCapabilities capabilities() {
                    return new com.dvarahq.agentworks.runplane.execution.ExecutorCapabilities(true);
                }
            };
        }
    }

    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    DurableRunEngine engine;
    @Autowired
    TestIdentities identities;
    private AgentApiClient api;
    private UUID workspace;
    /** Runs of the checkpointing test executor, which an operator may pause while active. */
    private String deployment;
    /** Runs of the Spring AI executor, which an operator may pause only while queued. */
    private String springAi;

    @BeforeEach
    void setUp() {
        api = new AgentApiClient(port);
        workspace = TestWorkspaces.createWorkspace(jdbc);
        String environment = api.post("/api/v1/environments", JSON, "{\"name\":\"prod\"}", workspace).body()
                .path("id").asString();
        deployment = deploy(environment, "agent", CHECKPOINTING);
        springAi = deploy(environment, "assistant", "spring-ai");
    }

    private String deploy(String environment, String agent, String executorType) {
        String definition = api.post("/api/v1/agents", JSON, "{\"name\":\"" + agent + "\"}", workspace).body()
                .path("id").asString();
        String version = api.post("/api/v1/agents/" + definition + "/versions", "application/yaml",
                "apiVersion: dvarahq.com/v1alpha1\nkind: Agent\nmetadata: {name: " + agent + ", version: 1.0.0}\n"
                        + "spec:\n  executor: {type: " + executorType + "}\n", workspace).body().path("id").asString();
        String release = api.post("/api/v1/agent-versions/" + version + "/releases", JSON,
                "{\"name\":\"" + agent + "-1\"}", workspace).body().path("id").asString();
        return api.send("POST", "/api/v1/environments/" + environment + "/promotions",
                "{\"deployment\":\"" + agent + "\",\"releaseId\":\"" + release + "\"}", workspace, Map.of()).body()
                .path("deploymentId").asString();
    }

    // PR #191 review, medium 3 (the user's decision): an active Spring AI run cannot be paused safely yet; the answer
    // is 409 PAUSE_UNSAFE, nothing is written, and its queued runs still pause at once. Requests and withdrawals are
    // audited with the actor (review low 8).
    @Test
    void anActiveSpringAiRunIsRefusedAsUnsafeAndPauseRequestsAreAudited() {
        UUID active = submit(springAi);
        long before = api.get("/api/v1/runs/" + active, workspace).body().path("eventSequence").asLong();
        claim(active);
        long claimed = api.get("/api/v1/runs/" + active, workspace).body().path("eventSequence").asLong();

        var unsafe = api.send("POST", "/api/v1/runs/" + active + "/pause", "{}", workspace, Map.of());

        assertThat(unsafe.status()).isEqualTo(409);
        assertThat(unsafe.code()).isEqualTo("PAUSE_UNSAFE");
        assertThat(api.get("/api/v1/runs/" + active, workspace).body().path("eventSequence").asLong())
                .isEqualTo(claimed).isGreaterThan(before);
        assertThat(api.send("POST", "/api/v1/runs/" + submit(springAi) + "/pause", "{}", workspace, Map.of()).status())
                .isEqualTo(200);

        UUID requested = submit();
        claim(requested);
        assertThat(api.send("POST", "/api/v1/runs/" + requested + "/pause", "{}", workspace, Map.of()).status())
                .isEqualTo(202);
        assertThat(api.send("POST", "/api/v1/runs/" + requested + "/resume", "{}", workspace, Map.of()).status())
                .isEqualTo(200);
        assertThat(jdbc.queryForList("select event_type || ':' || detail from auth_audit_event where target_id = ? "
                + "order by occurred_at", String.class, requested))
                .containsExactly("RUN_PAUSE_REQUESTED:REQUESTED", "RUN_PAUSE_WITHDRAWN:WITHDRAWN");
    }

    // #194 medium 3: the audit record was written best effort, in a separate transaction after the change committed,
    // so a failed audit write left an unaudited pause (and a withdrawal deleted the only other trace of the request).
    // The record is now written in the change's own transaction: when it cannot be written, nothing changes.
    @Test
    void aPauseOrWithdrawalWhoseAuditRecordFailsChangesNothing() {
        UUID active = submit();
        claim(active);
        UUID queued = submit();
        refuseAudit("test_refuse_pause_audit", "target_id not in ('" + active + "', '" + queued + "')", () -> {
            var requested = api.send("POST", "/api/v1/runs/" + active + "/pause", "{}", workspace, Map.of());
            var paused = api.send("POST", "/api/v1/runs/" + queued + "/pause", "{}", workspace, Map.of());

            assertThat(requested.status()).isEqualTo(500);
            assertThat(paused.status()).isEqualTo(500);
        });
        assertThat(jdbc.queryForObject("select count(*) from agent_run_pause_request where run_id = ?", Long.class,
                active)).isZero();
        assertThat(api.get("/api/v1/runs/" + queued, workspace).body().path("lifecycle").asString())
                .isEqualTo("QUEUED");
        assertThat(audited(active)).isEmpty();
        assertThat(audited(queued)).isEmpty();

        // A withdrawal whose record cannot be written leaves the request standing.
        assertThat(api.send("POST", "/api/v1/runs/" + active + "/pause", "{}", workspace, Map.of()).status())
                .isEqualTo(202);
        refuseAudit("test_refuse_withdrawal_audit", "target_id <> '" + active + "' or event_type <> "
                + "'RUN_PAUSE_WITHDRAWN'", () -> assertThat(api.send("POST", "/api/v1/runs/" + active + "/resume", "{}",
                workspace, Map.of()).status()).isEqualTo(500));
        assertThat(jdbc.queryForObject("select count(*) from agent_run_pause_request where run_id = ?", Long.class,
                active)).isOne();
        assertThat(audited(active)).containsExactly("RUN_PAUSE_REQUESTED:REQUESTED");
    }

    // #194 low 4: a repeated request that changes nothing (ALREADY_PAUSED, REPEATED) is not audited again.
    @Test
    void onlyAPauseRequestThatChangesSomethingIsAudited() {
        UUID queued = submit();
        UUID active = submit();
        claim(active);
        for (int attempt = 0; attempt < 2; attempt++) {
            assertThat(api.send("POST", "/api/v1/runs/" + queued + "/pause", "{}", workspace, Map.of()).status())
                    .isEqualTo(200);
            assertThat(api.send("POST", "/api/v1/runs/" + active + "/pause", "{}", workspace, Map.of()).status())
                    .isEqualTo(202);
        }
        assertThat(audited(queued)).containsExactly("RUN_PAUSE_REQUESTED:PAUSED");
        assertThat(audited(active)).containsExactly("RUN_PAUSE_REQUESTED:REQUESTED");

        // PR #199 review, low 2: another operator's repeat of the standing request is on record as REPEATED; only a
        // true repeat by the same actor is not audited again. #201 low 1: that operator's retries of it are not audited
        // again either, however many.
        var other = identities.create(workspace, WorkspaceRole.OPERATOR);
        for (int attempt = 0; attempt < 3; attempt++) {
            assertThat(api.sendAs(other.token(), "POST", "/api/v1/runs/" + active + "/pause", "{}").status())
                    .isEqualTo(202);
        }
        assertThat(audited(active)).containsExactly("RUN_PAUSE_REQUESTED:REQUESTED", "RUN_PAUSE_REQUESTED:REPEATED");
        assertThat(jdbc.queryForObject("select count(*) from agent_run_pause_repeat where run_id = ?", Long.class,
                active)).isOne();

        // A withdrawal removes the request and its repeats; a repeat of the next standing request is audited again.
        assertThat(api.send("POST", "/api/v1/runs/" + active + "/resume", "{}", workspace, Map.of()).status())
                .isEqualTo(200);
        assertThat(jdbc.queryForObject("select count(*) from agent_run_pause_repeat where run_id = ?", Long.class,
                active)).isZero();
        assertThat(api.send("POST", "/api/v1/runs/" + active + "/pause", "{}", workspace, Map.of()).status())
                .isEqualTo(202);
        for (int attempt = 0; attempt < 2; attempt++) {
            assertThat(api.sendAs(other.token(), "POST", "/api/v1/runs/" + active + "/pause", "{}").status())
                    .isEqualTo(202);
        }
        assertThat(audited(active)).containsExactly("RUN_PAUSE_REQUESTED:REQUESTED", "RUN_PAUSE_REQUESTED:REPEATED",
                "RUN_PAUSE_WITHDRAWN:WITHDRAWN", "RUN_PAUSE_REQUESTED:REQUESTED", "RUN_PAUSE_REQUESTED:REPEATED");
    }

    /** Runs {@code work} while the audit trail refuses rows that fail {@code check}; other rows are unaffected. */
    private void refuseAudit(String name, String check, Runnable work) {
        jdbc.execute("alter table auth_audit_event add constraint " + name + " check (" + check + ")");
        try {
            work.run();
        } finally {
            jdbc.execute("alter table auth_audit_event drop constraint " + name);
        }
    }

    private List<String> audited(UUID run) {
        return jdbc.queryForList("select event_type || ':' || detail from auth_audit_event where target_id = ? "
                + "order by occurred_at", String.class, run);
    }

    @Test
    void aQueuedRunPausesAtOnceRecordingTheActorAndARepeatReturnsItUnchanged() {
        UUID run = submit();
        var pinsBefore = api.get("/api/v1/runs/" + run, workspace).body();

        var paused = api.send("POST", "/api/v1/runs/" + run + "/pause", "{\"reason\":\"maintenance\"}", workspace,
                Map.of());
        var again = api.send("POST", "/api/v1/runs/" + run + "/pause", null, workspace, Map.of());

        assertThat(paused.status()).isEqualTo(200);
        assertThat(paused.body().path("lifecycle").asString()).isEqualTo("PAUSED");
        assertThat(again.status()).isEqualTo(200);
        assertThat(again.body().path("eventSequence")).isEqualTo(paused.body().path("eventSequence"));
        var event = lastEvent(run);
        assertThat(event.path("type").asString()).isEqualTo("RUN_PAUSED");
        String actor = identities.member(workspace, WorkspaceRole.ADMIN).userId().toString();
        assertThat(event.path("actor").asString()).startsWith("user:" + actor);
        assertThat(event.path("payload").path("requestedBy").asString()).startsWith("user:" + actor);
        assertThat(event.path("payload").path("reason").asString()).isEqualTo(DurableRunEngine.OPERATOR_PAUSE);
        assertThat(event.path("payload").path("detail").asString()).isEqualTo("maintenance");
        // The existing resume queues it again; its pins never changed (ADR-030).
        var resumed = api.send("POST", "/api/v1/runs/" + run + "/resume", "{}", workspace, Map.of());
        assertThat(resumed.status()).isEqualTo(200);
        assertThat(resumed.body().path("lifecycle").asString()).isEqualTo("QUEUED");
        for (String pin : List.of("releaseId", "deploymentId", "revisionId", "cohortId", "versionId", "traceId")) {
            assertThat(resumed.body().path(pin)).as(pin).isEqualTo(pinsBefore.path(pin));
        }
    }

    @Test
    void anActiveRunIsRequestedWith202AndPausesAtItsWorkersNextBoundaryVisibleOnTheStream() {
        UUID run = submit();
        RunLease lease = claim(run);
        String token = identities.token(workspace);
        try (SseClient stream = SseClient.open(port, "/api/v1/runs/" + run + "/stream", token, Map.of())) {
            assertThat(stream.next(WAIT).name()).isEqualTo("RUN_CREATED");
            assertThat(stream.next(WAIT).name()).isEqualTo("RUN_QUEUED");
            assertThat(stream.next(WAIT).name()).isEqualTo("RUN_ACTIVATED");

            var requested = api.send("POST", "/api/v1/runs/" + run + "/pause", "{}", workspace, Map.of());
            assertThat(requested.status()).isEqualTo(202);
            assertThat(requested.body().path("lifecycle").asString()).isEqualTo("ACTIVE");
            assertThat(api.send("POST", "/api/v1/runs/" + run + "/pause", "{}", workspace, Map.of()).status())
                    .isEqualTo(202);
            // Nothing was written under the worker's lease; its next write observes the request.
            var write = engine.checkpoint(lease, Map.of("step", 1));
            assertThat(write.pauseRequested()).isTrue();
            engine.pause(write.lease(), DurableRunEngine.OPERATOR_PAUSE);

            assertThat(stream.next(WAIT).name()).isEqualTo("CHECKPOINT_CREATED");
            var pausedEvent = stream.next(WAIT);
            assertThat(pausedEvent.name()).isEqualTo("RUN_PAUSED");
            assertThat(pausedEvent.json().path("payload").path("requestedBy").asString()).startsWith("user:");
        }
        assertThat(api.get("/api/v1/runs/" + run, workspace).body().path("lifecycle").asString()).isEqualTo("PAUSED");
        assertThat(api.send("POST", "/api/v1/runs/" + run + "/pause", "{}", workspace, Map.of()).status())
                .isEqualTo(200);
    }

    @Test
    void blockedAndTerminalRunsAreTypedConflictsAndOtherWorkspacesAndViewersAreRefused() {
        UUID blocked = submit();
        engine.block(claim(blocked), RunBlockReason.HUMAN_INTERACTION, null);
        var refused = api.send("POST", "/api/v1/runs/" + blocked + "/pause", "{}", workspace, Map.of());
        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.code()).isEqualTo("RUN_BLOCKED");

        UUID cancelled = submit();
        api.send("POST", "/api/v1/runs/" + cancelled + "/cancel", "{}", workspace, Map.of());
        var terminal = api.send("POST", "/api/v1/runs/" + cancelled + "/pause", "{}", workspace, Map.of());
        assertThat(terminal.status()).isEqualTo(409);
        assertThat(terminal.code()).isEqualTo("RUN_TERMINAL");

        UUID run = submit();
        UUID other = TestWorkspaces.createWorkspace(jdbc);
        var foreign = api.send("POST", "/api/v1/runs/" + run + "/pause", "{}", other, Map.of());
        assertThat(foreign.status()).isEqualTo(404);
        assertThat(foreign.code()).isEqualTo("NOT_FOUND");
        var viewer = identities.create(workspace, WorkspaceRole.VIEWER);
        assertThat(api.sendAs(viewer.token(), "POST", "/api/v1/runs/" + run + "/pause", "{}").status()).isEqualTo(403);
        var operator = identities.create(workspace, WorkspaceRole.OPERATOR);
        assertThat(api.sendAs(operator.token(), "POST", "/api/v1/runs/" + run + "/pause", "{}").status()).isEqualTo(200);
        var tooLong = api.send("POST", "/api/v1/runs/" + submit() + "/pause",
                "{\"reason\":\"" + "x".repeat(1025) + "\"}", workspace, Map.of());
        assertThat(tooLong.status()).isEqualTo(422);
        assertThat(api.get("/api/v1/runs/" + run, workspace).body().path("lifecycle").asString()).isEqualTo("PAUSED");
    }

    private tools.jackson.databind.JsonNode lastEvent(UUID run) {
        var items = api.get("/api/v1/runs/" + run + "/events?limit=200", workspace).body().path("items");
        return items.get(items.size() - 1);
    }

    private UUID submit() {
        return submit(deployment);
    }

    private UUID submit(String into) {
        var response = api.send("POST", "/api/v1/deployments/" + into + "/runs", "{\"input\":{\"q\":1}}",
                workspace, Map.of());
        assertThat(response.status()).isEqualTo(201);
        return UUID.fromString(response.body().path("id").asString());
    }

    private RunLease claim(UUID run) {
        var scope = ClaimScope.any(ReleaseManifestReader.SUPPORTED_VERSIONS).in(new WorkspaceId(workspace));
        var others = Set.copyOf(jdbc.queryForList("select id from agent_run where workspace_id = ? and id <> ?",
                UUID.class, workspace, run));
        var claimed = engine.claimNext("pause-test-worker", scope.excluding(others), Duration.ofMinutes(1))
                .orElseThrow();
        assertThat(claimed.run().id()).isEqualTo(run);
        return claimed.lease();
    }
}
