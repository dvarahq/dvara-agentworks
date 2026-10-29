package com.dvarahq.agentworks.controlplane.agent.web;

import com.dvarahq.agentworks.domain.identity.WorkspaceRole;
import com.dvarahq.agentworks.domain.run.ClaimScope;
import com.dvarahq.agentworks.domain.run.RunEventType;
import com.dvarahq.agentworks.domain.run.RunLease;
import com.dvarahq.agentworks.domain.security.CredentialRef;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import com.dvarahq.agentworks.DvaraAgentWorksApplication;
import com.dvarahq.agentworks.identity.TestIdentities;
import com.dvarahq.agentworks.interaction.HumanInteractionAssignment;
import com.dvarahq.agentworks.interaction.HumanInteractionDraft;
import com.dvarahq.agentworks.interaction.HumanInteractionPrompt;
import com.dvarahq.agentworks.runplane.execution.DurableHumanInteractionCoordinator;
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
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** HTTP evidence for issue #42's tenant-scoped interaction and operator-outcome boundary. */
@SpringBootTest(classes = DvaraAgentWorksApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestWorkspaces.class)
class HumanInteractionApiTest {
    private static final String JSON = "application/json";

    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    DurableRunEngine engine;
    @Autowired
    DurableHumanInteractionCoordinator coordinator;
    @Autowired
    TestIdentities identities;

    private AgentApiClient api;
    private UUID workspace;
    private String deployment;

    @BeforeEach
    void setUp() {
        api = new AgentApiClient(port);
        workspace = TestWorkspaces.createWorkspace(jdbc);
        String environment = api.post("/api/v1/environments", JSON, "{\"name\":\"prod\"}", workspace).body()
                .path("id").asString();
        String definition = api.post("/api/v1/agents", JSON, "{\"name\":\"interaction-agent\"}", workspace)
                .body().path("id").asString();
        String version = api.post("/api/v1/agents/" + definition + "/versions", "application/yaml",
                "apiVersion: dvarahq.com/v1alpha1\nkind: Agent\nmetadata: {name: interaction-agent, version: 1.0.0}\n"
                        + "spec:\n  executor: {type: spring-ai}\n", workspace).body().path("id").asString();
        String release = api.post("/api/v1/agent-versions/" + version + "/releases", JSON,
                "{\"name\":\"interaction-agent-1\"}", workspace).body().path("id").asString();
        deployment = api.send("POST", "/api/v1/environments/" + environment + "/promotions",
                "{\"deployment\":\"interaction-agent\",\"releaseId\":\"" + release + "\"}", workspace,
                Map.of()).body().path("deploymentId").asString();
    }

    @Test
    void assignedPrincipalRespondsAtomicallyAndSensitiveValuesStayRedacted() {
        var interaction = block(new HumanInteractionPrompt.Question("Input needed", "Supply the value",
                "{\"type\":\"string\"}"), "question");

        var open = api.get("/api/v1/interactions/" + interaction.id(), workspace);
        assertThat(open.status()).isEqualTo(200);
        assertThat(open.body().path("status").asString()).isEqualTo("OPEN");
        assertThat(open.body().path("runId").asString()).isEqualTo(interaction.runId().toString());
        assertThat(open.body().path("correlationId").asString()).isEqualTo("corr-question");
        assertThat(open.raw()).doesNotContain("idem-question", "idempotencyKey");

        var invalid = api.send("POST", "/api/v1/interactions/" + interaction.id() + "/response",
                "{\"approved\":true}", workspace, Map.of());
        assertThat(invalid.status()).isEqualTo(422);
        assertThat(invalid.code()).isEqualTo("INVALID_RESPONSE");

        var responded = api.send("POST", "/api/v1/interactions/" + interaction.id() + "/response",
                "{\"answer\":\"sensitive-answer\"}", workspace, Map.of());
        assertThat(responded.status()).isEqualTo(200);
        assertThat(responded.body().path("status").asString()).isEqualTo("RESPONDED");
        assertThat(responded.body().path("outcome").path("response").path("answerRedacted").asBoolean()).isTrue();
        assertThat(responded.raw()).doesNotContain("sensitive-answer");
        assertThat(api.get("/api/v1/runs/" + interaction.runId(), workspace).body().path("lifecycle").asString())
                .isEqualTo("QUEUED");

        var repeat = api.send("POST", "/api/v1/interactions/" + interaction.id() + "/response",
                "{\"answer\":\"another\"}", workspace, Map.of());
        assertThat(repeat.status()).isEqualTo(409);
        assertThat(repeat.code()).isEqualTo("ALREADY_TERMINAL");
    }

    @Test
    void interactionEventsNotifyTheRunStreamInOrderWithoutSecrets() {
        var interaction = block(new HumanInteractionPrompt.Question("Private question", "Supply a value",
                "{\"type\":\"string\"}"), "stream-question");
        var history = engine.history(new WorkspaceId(workspace), interaction.runId());
        long activated = history.stream().filter(event -> event.type() == RunEventType.RUN_ACTIVATED)
                .findFirst().orElseThrow().raw().sequence();

        try (SseClient stream = SseClient.open(port, "/api/v1/runs/" + interaction.runId() + "/stream",
                identities.token(workspace), Map.of("Last-Event-ID", interaction.runId() + ":" + activated))) {
            assertThat(stream.status()).isEqualTo(200);
            SseClient.Event requested = stream.next(Duration.ofSeconds(5));
            SseClient.Event blocked = stream.next(Duration.ofSeconds(5));
            assertThat(requested.name()).isEqualTo("HUMAN_INTERACTION_REQUESTED");
            assertThat(requested.json().path("payload").path("interactionId").asString())
                    .isEqualTo(interaction.id().toString());
            assertThat(blocked.name()).isEqualTo("RUN_BLOCKED");
            assertThat(requested.json().toString()).doesNotContain("idem-stream-question", "Supply a value");

            var responded = api.send("POST", "/api/v1/interactions/" + interaction.id() + "/response",
                    "{\"answer\":\"stream-secret\"}", workspace, Map.of());
            assertThat(responded.status()).isEqualTo(200);
            SseClient.Event outcome = stream.next(Duration.ofSeconds(5));
            SseClient.Event resumed = stream.next(Duration.ofSeconds(5));
            assertThat(outcome.name()).isEqualTo("HUMAN_INTERACTION_RESPONDED");
            assertThat(outcome.json().path("payload").path("status").asString()).isEqualTo("RESPONDED");
            assertThat(resumed.name()).isEqualTo("RUN_RESUMED");
            assertThat(outcome.json().toString()).doesNotContain("stream-secret", "answer");
        }
    }

    @Test
    void workspacePermissionDoesNotBypassTheDurableAssignment() {
        var interaction = block(new HumanInteractionPrompt.Approval("Approve", "Proceed?", "Production"),
                "assignment");
        var otherAdmin = identities.create(workspace, WorkspaceRole.ADMIN);

        var refused = api.sendAs(otherAdmin.token(), "POST",
                "/api/v1/interactions/" + interaction.id() + "/response", "{\"approved\":true}");

        assertThat(refused.status()).isEqualTo(403);
        assertThat(refused.code()).isEqualTo("UNAUTHORIZED_RESPONDER");
        assertThat(api.get("/api/v1/interactions/" + interaction.id(), workspace).body().path("status").asString())
                .isEqualTo("OPEN");
        assertThat(api.get("/api/v1/runs/" + interaction.runId(), workspace).body().path("lifecycle").asString())
                .isEqualTo("BLOCKED");
    }

    @Test
    void anotherWorkspaceCannotObserveOrRespondToTheInteraction() {
        var interaction = block(new HumanInteractionPrompt.Confirmation("Confirm", "Continue?", "Production"),
                "tenant");
        TestWorkspaces.ensureOtherWorkspace(jdbc);

        assertThat(api.get("/api/v1/interactions/" + interaction.id(), TestWorkspaces.OTHER_WORKSPACE).status())
                .isEqualTo(404);
        var response = api.send("POST", "/api/v1/interactions/" + interaction.id() + "/response",
                "{\"confirmed\":true}", TestWorkspaces.OTHER_WORKSPACE, Map.of());
        assertThat(response.status()).isEqualTo(404);
        assertThat(response.code()).isEqualTo("NOT_FOUND");
    }

    @Test
    void interactionsAreListedNewestFirstWithStableFiltersAndWorkspaceScope() {
        var open = block(new HumanInteractionPrompt.Approval("Open approval", "Proceed?", "Production"),
                "list-open");
        var answered = block(new HumanInteractionPrompt.Question("Answered question", "Value?",
                "{\"type\":\"string\"}"), "list-answered");
        assertThat(api.send("POST", "/api/v1/interactions/" + answered.id() + "/response",
                "{\"answer\":\"private\"}", workspace, Map.of()).status()).isEqualTo(200);

        var openPage = api.get("/api/v1/interactions?status=OPEN", workspace);
        assertThat(openPage.status()).isEqualTo(200);
        assertThat(openPage.body().path("items").size()).isEqualTo(1);
        assertThat(openPage.body().path("items").get(0).path("id").asString()).isEqualTo(open.id().toString());

        var byRun = api.get("/api/v1/interactions?runId=" + answered.runId(), workspace);
        assertThat(byRun.body().path("items").size()).isEqualTo(1);
        assertThat(byRun.body().path("items").get(0).path("status").asString()).isEqualTo("RESPONDED");

        var first = api.get("/api/v1/interactions?limit=1", workspace);
        String cursor = first.body().path("nextCursor").asString();
        assertThat(cursor).isNotBlank();
        var second = api.get("/api/v1/interactions?limit=1&cursor=" + cursor, workspace);
        assertThat(second.body().path("items").size()).isEqualTo(1);
        assertThat(second.body().path("items").get(0).path("id").asString())
                .isNotEqualTo(first.body().path("items").get(0).path("id").asString());

        TestWorkspaces.ensureOtherWorkspace(jdbc);
        assertThat(api.get("/api/v1/interactions", TestWorkspaces.OTHER_WORKSPACE).body().path("items").size())
                .isZero();
        var invalid = api.get("/api/v1/interactions?status=UNKNOWN", workspace);
        assertThat(invalid.status()).isEqualTo(400);
        assertThat(invalid.code()).isEqualTo("INVALID_FILTER");
    }

    @Test
    void operatorExpiresCancelsAndRevokesThroughOneWinnerAtomicOutcomes() throws Exception {
        var tooEarly = block(new HumanInteractionPrompt.Approval("Wait", "Still waiting?", "Production"),
                "early-expiry");
        var early = api.send("POST", "/api/v1/interactions/" + tooEarly.id() + "/expire", null,
                workspace, Map.of());
        assertThat(early.status()).isEqualTo(409);
        assertThat(early.code()).isEqualTo("DEADLINE_NOT_REACHED");
        assertThat(api.get("/api/v1/runs/" + tooEarly.runId(), workspace).body().path("lifecycle").asString())
                .isEqualTo("BLOCKED");

        var expiring = block(new HumanInteractionPrompt.Question("Timed", "Answer quickly",
                "{\"type\":\"string\"}"), "expiry", Duration.ofMillis(1));
        Thread.sleep(20);
        var expired = api.send("POST", "/api/v1/interactions/" + expiring.id() + "/expire", null,
                workspace, Map.of());
        assertThat(expired.status()).isEqualTo(200);
        assertThat(expired.body().path("status").asString()).isEqualTo("EXPIRED");
        assertThat(api.get("/api/v1/runs/" + expiring.runId(), workspace).body().path("lifecycle").asString())
                .isEqualTo("QUEUED");

        var cancellable = block(new HumanInteractionPrompt.Confirmation("Cancel", "Continue?", "Production"),
                "cancel");
        var operator = identities.create(workspace, WorkspaceRole.OPERATOR);
        var cancelled = api.sendAs(operator.token(), "POST",
                "/api/v1/interactions/" + cancellable.id() + "/cancel", null);
        assertThat(cancelled.status()).isEqualTo(200);
        assertThat(cancelled.body().path("status").asString()).isEqualTo("CANCELLED");
        var repeat = api.send("POST", "/api/v1/interactions/" + cancellable.id() + "/cancel", null,
                workspace, Map.of());
        assertThat(repeat.status()).isEqualTo(409);
        assertThat(repeat.code()).isEqualTo("ALREADY_TERMINAL");

        var authorization = block(new HumanInteractionPrompt.Authorization("Authorize", "Connect account",
                new CredentialRef("env://calendar-token"), Set.of("calendar.read")), "revoke");
        var revoked = api.send("POST", "/api/v1/interactions/" + authorization.id() + "/revoke", null,
                workspace, Map.of());
        assertThat(revoked.status()).isEqualTo(200);
        assertThat(revoked.body().path("status").asString()).isEqualTo("REVOKED");

        var approval = block(new HumanInteractionPrompt.Approval("Not authorization", "Proceed?", "Production"),
                "invalid-revoke");
        var invalid = api.send("POST", "/api/v1/interactions/" + approval.id() + "/revoke", null,
                workspace, Map.of());
        assertThat(invalid.status()).isEqualTo(409);
        assertThat(invalid.code()).isEqualTo("INVALID_TRANSITION");
        assertThat(api.get("/api/v1/interactions/" + approval.id(), workspace).body().path("status").asString())
                .isEqualTo("OPEN");
    }

    @Test
    void viewerAndAnotherWorkspaceCannotOperateAnInteraction() {
        var interaction = block(new HumanInteractionPrompt.Approval("Operator only", "Proceed?", "Production"),
                "operator-boundary");
        var viewer = identities.create(workspace, WorkspaceRole.VIEWER);
        assertThat(api.sendAs(viewer.token(), "POST",
                "/api/v1/interactions/" + interaction.id() + "/cancel", null).status()).isEqualTo(403);

        TestWorkspaces.ensureOtherWorkspace(jdbc);
        var crossTenant = api.send("POST", "/api/v1/interactions/" + interaction.id() + "/cancel", null,
                TestWorkspaces.OTHER_WORKSPACE, Map.of());
        assertThat(crossTenant.status()).isEqualTo(404);
        assertThat(crossTenant.code()).isEqualTo("NOT_FOUND");
        assertThat(api.get("/api/v1/interactions/" + interaction.id(), workspace).body().path("status").asString())
                .isEqualTo("OPEN");
    }

    private Blocked block(HumanInteractionPrompt prompt, String key) {
        return block(prompt, key, Duration.ofMinutes(5));
    }

    private Blocked block(HumanInteractionPrompt prompt, String key, Duration timeout) {
        UUID runId = UUID.fromString(api.send("POST", "/api/v1/deployments/" + deployment + "/runs",
                "{\"input\":{}}", workspace, Map.of()).body().path("id").asString());
        RunLease lease = claim(runId);
        var run = engine.run(new WorkspaceId(workspace), runId);
        UUID assigned = identities.member(workspace, WorkspaceRole.ADMIN).userId();
        UUID id = UUID.randomUUID();
        var draft = new HumanInteractionDraft(id, workspace, runId, run.pins().releaseId(),
                run.pins().deploymentId(), run.pins().revisionId(), run.pins().cohortId(), "corr-" + key,
                "idem-" + key, prompt, HumanInteractionAssignment.user(workspace, assigned), timeout);
        coordinator.block(lease, draft);
        return new Blocked(id, runId);
    }

    private RunLease claim(UUID run) {
        var scope = ClaimScope.any(ReleaseManifestReader.SUPPORTED_VERSIONS).in(new WorkspaceId(workspace));
        var others = Set.copyOf(jdbc.queryForList("select id from agent_run where workspace_id = ? and id <> ?",
                UUID.class, workspace, run));
        return engine.claimNext("interaction-api-test", scope.excluding(others), Duration.ofMinutes(1))
                .orElseThrow().lease();
    }

    private record Blocked(UUID id, UUID runId) {}
}
