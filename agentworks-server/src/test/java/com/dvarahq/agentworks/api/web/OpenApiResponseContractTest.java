package com.dvarahq.agentworks.api.web;

import com.dvarahq.agentworks.domain.identity.WorkspaceRole;
import com.dvarahq.agentworks.domain.run.ClaimScope;
import com.dvarahq.agentworks.domain.security.CredentialRef;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import com.dvarahq.agentworks.DvaraAgentWorksApplication;
import com.dvarahq.agentworks.identity.TestIdentities;
import com.dvarahq.agentworks.identity.web.IdentityHttp;
import com.dvarahq.agentworks.identity.web.IdentityTestConfiguration;
import com.dvarahq.agentworks.interaction.HumanInteractionAssignment;
import com.dvarahq.agentworks.interaction.HumanInteractionDraft;
import com.dvarahq.agentworks.interaction.HumanInteractionPrompt;
import com.dvarahq.agentworks.runplane.execution.DurableHumanInteractionCoordinator;
import com.dvarahq.agentworks.runplane.execution.DurableRunEngine;
import com.dvarahq.agentworks.runplane.execution.ReleaseManifestReader;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static com.dvarahq.agentworks.identity.web.IdentityTestConfiguration.PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * An API journey through every documented operation (issue #35: TC-001 to TC-005 over the public API), validating
 * each actual response, success and error alike, against the published OpenAPI description: its status must be
 * documented and its body must match the documented schema, which forbids undocumented members. SSE events are
 * validated against their documented data schemas.
 */
@SpringBootTest(classes = DvaraAgentWorksApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(IdentityTestConfiguration.class)
class OpenApiResponseContractTest {
    /** Operations exercised only through a documented refusal, because success is a one-time installation event. */
    private static final Set<String> REFUSAL_ONLY = Set.of("POST /api/v1/auth/bootstrap",
            "GET /api/v1/operations/{operationId}", "POST /api/v1/operations/{operationId}/cancel",
            "POST /api/v1/operation-callbacks/{workspaceId}/{operationId}",
            "POST /api/v1/releases/{id}/bundle", "GET /api/v1/releases/{id}/bundle",
            "POST /api/v1/releases/{id}/bundle/anchor", "POST /api/v1/release-bundles/import");
    private static final Path ARTIFACT_ROOT = Path.of(System.getProperty("java.io.tmpdir"),
            "agentworks-openapi-contract-artifacts-" + UUID.randomUUID());

    @DynamicPropertySource
    static void artifactProperties(DynamicPropertyRegistry properties) {
        properties.add("agentworks.artifacts.enabled", () -> "true");
        properties.add("agentworks.artifacts.root", ARTIFACT_ROOT::toString);
        properties.add("agentworks.artifacts.maintenance.enabled", () -> "false");
    }

    private final OpenApiContract contract = OpenApiContract.load();

    @LocalServerPort
    int port;
    @Autowired
    TestIdentities identities;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    DurableRunEngine engine;
    @Autowired
    DurableHumanInteractionCoordinator interactions;

    @Test
    void everyOperationAnswersExactlyWhatTheDescriptionDocuments() throws Exception {
        UUID workspace = workspace();
        var admin = identities.create(workspace, WorkspaceRole.ADMIN, PASSWORD, "ADMIN");
        IdentityHttp anonymous = new IdentityHttp(port);
        IdentityHttp cli = new IdentityHttp(port).bearer(admin.token());
        IdentityHttp browser = new IdentityHttp(port);

        // Authentication and sessions.
        call(anonymous, "GET", "/api/v1/openapi.yaml", "/api/v1/openapi.yaml", null, Map.of("Accept", "application/yaml"));
        assertThat(call(browser, "POST", "/api/v1/auth/login", "/api/v1/auth/login",
                Map.of("username", admin.username(), "password", PASSWORD)).status()).isEqualTo(200);
        call(anonymous, "POST", "/api/v1/auth/login", "/api/v1/auth/login", Map.of("username", "nobody", "password", "x"));
        call(cli, "GET", "/api/v1/auth/session", "/api/v1/auth/session", null);
        call(anonymous, "GET", "/api/v1/auth/session", "/api/v1/auth/session", null);
        call(browser, "GET", "/api/v1/auth/workspaces", "/api/v1/auth/workspaces", null);
        call(browser, "PUT", "/api/v1/auth/session/workspace", "/api/v1/auth/session/workspace",
                Map.of("workspaceId", workspace.toString()));
        IdentityHttp second = new IdentityHttp(port);
        assertThat(second.login(admin.username(), PASSWORD).status()).isEqualTo(200);
        JsonNode sessions = call(browser, "GET", "/api/v1/auth/sessions", "/api/v1/auth/sessions", null).json();
        String other = null;
        for (JsonNode session : sessions.path("items")) {
            if (!session.path("current").asBoolean()) other = session.path("id").asString();
        }
        assertThat(call(browser, "DELETE", "/api/v1/auth/sessions/{id}", "/api/v1/auth/sessions/" + other, null).status())
                .isEqualTo(204);

        // Users, memberships, and API tokens.
        call(browser, "GET", "/api/v1/users", "/api/v1/users", null);
        var created = call(browser, "POST", "/api/v1/users", "/api/v1/users", Map.of("username", "user-" + UUID.randomUUID(),
                "displayName", "Operator", "organizationRole", "MEMBER", "workspaceRole", "VIEWER"));
        assertThat(created.status()).isEqualTo(201);
        String user = created.json().path("user").path("id").asString();
        call(browser, "POST", "/api/v1/users/{id}/password-reset", "/api/v1/users/" + user + "/password-reset", null);
        call(browser, "POST", "/api/v1/users/{id}/disable", "/api/v1/users/" + user + "/disable", null);
        call(browser, "POST", "/api/v1/users/{id}/enable", "/api/v1/users/" + user + "/enable", null);
        call(cli, "GET", "/api/v1/members", "/api/v1/members", null);
        call(browser, "PUT", "/api/v1/members/{userId}", "/api/v1/members/" + user, Map.of("role", "OPERATOR"));
        call(cli, "PUT", "/api/v1/members/{userId}", "/api/v1/members/" + user, Map.of("role", "OPERATOR"));
        assertThat(call(browser, "DELETE", "/api/v1/members/{userId}", "/api/v1/members/" + user, null).status())
                .isEqualTo(204);
        call(cli, "GET", "/api/v1/tokens", "/api/v1/tokens", null);
        var token = call(browser, "POST", "/api/v1/tokens", "/api/v1/tokens", Map.of("name", "ci"));
        assertThat(token.status()).isEqualTo(201);
        call(cli, "DELETE", "/api/v1/tokens/{id}", "/api/v1/tokens/" + token.json().path("metadata").path("id").asString(),
                null);

        // Agents and versions.
        var definition = call(cli, "POST", "/api/v1/agents", "/api/v1/agents", Map.of("name", "agent"));
        String definitionId = definition.json().path("id").asString();
        call(cli, "POST", "/api/v1/agents", "/api/v1/agents", Map.of("name", "agent"));
        call(cli, "GET", "/api/v1/agents", "/api/v1/agents?limit=1", null);
        call(cli, "GET", "/api/v1/agents", "/api/v1/agents?cursor=garbage", null);
        call(cli, "GET", "/api/v1/agents/{definitionId}", "/api/v1/agents/" + definitionId, null);
        call(cli, "GET", "/api/v1/agents/{definitionId}", "/api/v1/agents/" + UUID.randomUUID(), null);
        String spec = "apiVersion: dvarahq.com/v1alpha1\nkind: Agent\nmetadata: {name: agent, version: 1.0.0}\n"
                + "spec:\n  executor: {type: spring-ai}\n";
        var version = call(cli, "POST", "/api/v1/agents/{definitionId}/versions",
                "/api/v1/agents/" + definitionId + "/versions", spec, Map.of("Content-Type", "application/yaml"));
        assertThat(version.status()).isEqualTo(201);
        assertThat(call(cli, "POST", "/api/v1/agents/{definitionId}/versions", "/api/v1/agents/" + definitionId
                + "/versions", spec, Map.of("Content-Type", "application/yaml")).status()).isEqualTo(200);
        call(cli, "POST", "/api/v1/agents/{definitionId}/versions", "/api/v1/agents/" + definitionId + "/versions",
                "{\"apiVersion\":\"nope\"}", Map.of());
        String versionId = version.json().path("id").asString();
        call(cli, "GET", "/api/v1/agents/{definitionId}/versions", "/api/v1/agents/" + definitionId + "/versions", null);
        call(cli, "GET", "/api/v1/agents/{definitionId}/versions/{version}",
                "/api/v1/agents/" + definitionId + "/versions/1.0.0", null);

        // Deterministic evaluation definitions, report, cancellation/resume semantics, and baseline comparison.
        var dataset = call(cli, "POST", "/api/v1/evaluation-datasets", "/api/v1/evaluation-datasets", Map.of(
                "name", "quality", "version", 1, "sensitive", false,
                "cases", List.of(Map.of("id", "case-1", "input", Map.of("question", "q"),
                        "expected", Map.of("answer", 42), "tags", Map.of("suite", "golden")))));
        String datasetId = dataset.json().path("id").asString();
        call(cli, "GET", "/api/v1/evaluation-datasets", "/api/v1/evaluation-datasets", null);
        call(cli, "GET", "/api/v1/evaluation-datasets/{id}", "/api/v1/evaluation-datasets/" + datasetId, null);
        call(cli, "POST", "/api/v1/evaluation-datasets/purge-expired",
                "/api/v1/evaluation-datasets/purge-expired", null);
        var evaluator = call(cli, "POST", "/api/v1/evaluators", "/api/v1/evaluators", Map.of(
                "name", "exact", "version", 1, "type", "EXACT", "configuration", Map.of()));
        String evaluatorId = evaluator.json().path("id").asString();
        call(cli, "GET", "/api/v1/evaluators", "/api/v1/evaluators", null);
        call(cli, "GET", "/api/v1/evaluators/{id}", "/api/v1/evaluators/" + evaluatorId, null);
        var evaluation = call(cli, "POST", "/api/v1/evaluation-runs", "/api/v1/evaluation-runs", Map.ofEntries(
                Map.entry("datasetId", datasetId), Map.entry("evaluatorIds", List.of(evaluatorId)),
                Map.entry("subjectIdentity", "release:test"), Map.entry("releaseEvidenceReference", "pending:#54"),
                Map.entry("observations", Map.of("case-1", Map.ofEntries(Map.entry("caseId", "case-1"),
                        Map.entry("output", Map.of("answer", 42)), Map.entry("toolTrace", List.of()),
                        Map.entry("policyPassed", true), Map.entry("latencyMillis", 10), Map.entry("costUsd", 0.01),
                        Map.entry("sourceIdentity", "run:test"), Map.entry("sourceDigest", "sha256:" + "a".repeat(64))))),
                Map.entry("budget", Map.of("maximumCostUsd", 1, "maximumCaseLatencyMillis", 100,
                        "maximumRuntime", "PT5S", "maximumConcurrency", 1)), Map.entry("idempotencyKey", "eval-1")));
        String evaluationId = evaluation.json().path("run").path("id").asString();
        call(cli, "GET", "/api/v1/evaluation-runs/{id}", "/api/v1/evaluation-runs/" + evaluationId, null);
        call(cli, "GET", "/api/v1/evaluation-runs/{id}/report", "/api/v1/evaluation-runs/" + evaluationId + "/report", null);
        call(cli, "POST", "/api/v1/evaluation-runs/{id}/cancel", "/api/v1/evaluation-runs/" + evaluationId + "/cancel", null);
        call(cli, "POST", "/api/v1/evaluation-runs/{id}/resume", "/api/v1/evaluation-runs/" + evaluationId + "/resume", null);
        var baseline = call(cli, "POST", "/api/v1/evaluation-baselines", "/api/v1/evaluation-baselines", Map.of(
                "name", "main", "version", 1, "runId", evaluationId,
                "thresholds", Map.of("minimumPassRate", 0.9), "cohort", Map.of("model", "fake-v1")));
        String baselineId = baseline.json().path("id").asString();
        call(cli, "GET", "/api/v1/evaluation-baselines", "/api/v1/evaluation-baselines", null);
        call(cli, "GET", "/api/v1/evaluation-baselines/{id}/comparison",
                "/api/v1/evaluation-baselines/" + baselineId + "/comparison?candidateRunId=" + evaluationId, null);

        // Versioned tools and capability packs. Search is summary-only; exact detail is an author operation.
        Map<String, Object> tool = Map.ofEntries(Map.entry("namespace", "core"), Map.entry("name", "search"),
                Map.entry("version", "1.0.0"), Map.entry("type", "MCP"), Map.entry("summary", "Search the web"),
                Map.entry("description", "Search public web pages"), Map.entry("inputSchema",
                        Map.of("type", "object", "properties", Map.of("query", Map.of("type", "string")))),
                Map.entry("effect", Map.of("resource", "public-web", "action", "read", "risk", "LOW")),
                Map.entry("tags", List.of("web")), Map.entry("compatibility", List.of("runtime-v1")),
                Map.entry("summaryTokens", 8), Map.entry("detailTokens", 32));
        assertThat(call(cli, "POST", "/api/v1/tools", "/api/v1/tools", tool).status()).isEqualTo(201);
        call(cli, "POST", "/api/v1/tools/{namespace}/{name}/versions/{version}/lifecycle",
                "/api/v1/tools/core/search/versions/1.0.0/lifecycle", Map.of("state", "ACTIVE"));
        call(cli, "GET", "/api/v1/tools", "/api/v1/tools?q=search&type=MCP&tag=web&limit=1", null);
        call(cli, "GET", "/api/v1/tools", "/api/v1/tools?cursor=bad", null);
        call(cli, "GET", "/api/v1/tools/{namespace}/{name}/versions/{version}",
                "/api/v1/tools/core/search/versions/1.0.0", null);
        call(cli, "GET", "/api/v1/mcp-connections", "/api/v1/mcp-connections", null);
        Map<String, Object> pack = Map.of("id", "research", "version", "1", "tools",
                List.of(Map.of("namespace", "core", "name", "search", "version", "1.0.0")));
        assertThat(call(cli, "POST", "/api/v1/capability-packs", "/api/v1/capability-packs", pack).status())
                .isEqualTo(201);
        call(cli, "POST", "/api/v1/capability-packs/{id}/versions/{version}/lifecycle",
                "/api/v1/capability-packs/research/versions/1/lifecycle", Map.of("state", "ACTIVE"));
        call(cli, "GET", "/api/v1/capability-packs/{id}/versions/{version}",
                "/api/v1/capability-packs/research/versions/1", null);

        // Releases and aliases.
        var release = call(cli, "POST", "/api/v1/agent-versions/{versionId}/releases",
                "/api/v1/agent-versions/" + versionId + "/releases", Map.of("name", "agent-1"));
        String releaseId = release.json().path("id").asString();
        String secondRelease = call(cli, "POST", "/api/v1/agent-versions/{versionId}/releases",
                "/api/v1/agent-versions/" + versionId + "/releases", Map.of("name", "agent-2")).json().path("id").asString();
        call(cli, "GET", "/api/v1/releases", "/api/v1/releases", null);
        call(cli, "GET", "/api/v1/releases/{id}", "/api/v1/releases/" + releaseId, null);
        call(cli, "GET", "/api/v1/releases/{id}/manifest", "/api/v1/releases/" + releaseId + "/manifest", null);
        String releaseTag = call(cli, "GET", "/api/v1/releases/{id}/lifecycle",
                "/api/v1/releases/" + releaseId + "/lifecycle", null).header("ETag");
        var releaseSuspended = call(cli, "POST", "/api/v1/releases/{id}/{action}",
                "/api/v1/releases/" + releaseId + "/suspend", Map.of("reason", "contract maintenance"),
                Map.of("If-Match", releaseTag, "Idempotency-Key", "release-suspend-1"));
        call(cli, "POST", "/api/v1/releases/{id}/{action}", "/api/v1/releases/" + releaseId + "/resume",
                Map.of("reason", "contract maintenance complete"), Map.of("If-Match", releaseSuspended.header("ETag")));
        call(cli, "GET", "/api/v1/release-revocations", "/api/v1/release-revocations", null);
        call(cli, "GET", "/api/v1/administrative-audit", "/api/v1/administrative-audit?limit=1", null);
        call(cli, "GET", "/api/v1/diagnostics/support-bundle", "/api/v1/diagnostics/support-bundle", null);
        // This contract fixture intentionally has no signing key or anchor. The management endpoints must still be
        // published and answer with their documented typed refusals; independent verification remains available.
        call(cli, "POST", "/api/v1/releases/{id}/bundle", "/api/v1/releases/" + releaseId + "/bundle", null);
        call(cli, "GET", "/api/v1/releases/{id}/bundle", "/api/v1/releases/" + releaseId + "/bundle", null);
        call(cli, "POST", "/api/v1/releases/{id}/bundle/anchor",
                "/api/v1/releases/" + releaseId + "/bundle/anchor", null);
        call(cli, "POST", "/api/v1/release-bundles/verify", "/api/v1/release-bundles/verify",
                Map.of("bundle", "{}"));
        call(cli, "POST", "/api/v1/release-bundles/import", "/api/v1/release-bundles/import",
                Map.of("bundle", "{}"));
        var alias = call(cli, "PUT", "/api/v1/release-aliases/{name}", "/api/v1/release-aliases/latest",
                Map.of("releaseId", releaseId));
        call(cli, "PUT", "/api/v1/release-aliases/{name}", "/api/v1/release-aliases/latest",
                Map.of("releaseId", secondRelease), Map.of("If-Match", "\"99\""));
        call(cli, "PUT", "/api/v1/release-aliases/{name}", "/api/v1/release-aliases/latest",
                Map.of("releaseId", secondRelease), Map.of("If-Match", alias.header("ETag")));
        call(cli, "GET", "/api/v1/release-aliases/{name}", "/api/v1/release-aliases/latest", null);

        // Environments and promotion policies.
        var environment = call(cli, "POST", "/api/v1/environments", "/api/v1/environments", Map.of("name", "prod"));
        String environmentId = environment.json().path("id").asString();
        call(cli, "GET", "/api/v1/environments", "/api/v1/environments", null);
        call(cli, "GET", "/api/v1/environments/{id}", "/api/v1/environments/" + environmentId, null);
        call(cli, "GET", "/api/v1/environments/{id}/promotion-policy", "/api/v1/environments/" + environmentId
                + "/promotion-policy", null);
        call(cli, "PUT", "/api/v1/environments/{id}/promotion-policy", "/api/v1/environments/" + environmentId
                + "/promotion-policy", Map.of("gates", List.of()));
        var policyUpdate = call(cli, "PUT", "/api/v1/environments/{id}/promotion-policy", "/api/v1/environments/"
                + environmentId + "/promotion-policy", Map.of("gates", List.of()),
                Map.of("If-Match", environment.header("ETag")));
        assertThat(policyUpdate.status()).withFailMessage(policyUpdate.raw()).isEqualTo(200);
        String policyDigest = call(cli, "GET", "/api/v1/environments/{id}",
                "/api/v1/environments/" + environmentId, null).json().path("policyDigest").asString();
        call(cli, "POST", "/api/v1/environments/{environmentId}/promotion-approvals",
                "/api/v1/environments/" + environmentId + "/promotion-approvals", Map.of());
        call(cli, "POST", "/api/v1/environments/{environmentId}/promotion-approvals",
                "/api/v1/environments/" + environmentId + "/promotion-approvals",
                Map.of("releaseId", UUID.randomUUID()));
        var approval = call(cli, "POST", "/api/v1/environments/{environmentId}/promotion-approvals",
                "/api/v1/environments/" + environmentId + "/promotion-approvals", Map.of("releaseId", releaseId));
        String approvalId = approval.json().path("id").asString();
        call(cli, "GET", "/api/v1/environments/{environmentId}/promotion-approvals/{approvalId}",
                "/api/v1/environments/" + environmentId + "/promotion-approvals/" + approvalId, null);
        String wrongEnvironment = call(cli, "POST", "/api/v1/environments", "/api/v1/environments",
                Map.of("name", "approval-mismatch")).json().path("id").asString();
        call(cli, "GET", "/api/v1/environments/{environmentId}/promotion-approvals/{approvalId}",
                "/api/v1/environments/" + wrongEnvironment + "/promotion-approvals/" + approvalId, null);
        call(cli, "POST", "/api/v1/environments/{environmentId}/promotion-approvals/{approvalId}/approve",
                "/api/v1/environments/" + wrongEnvironment + "/promotion-approvals/" + approvalId + "/approve",
                Map.of("releaseId", releaseId, "policyDigest", policyDigest));
        call(cli, "POST", "/api/v1/environments/{environmentId}/promotion-approvals/{approvalId}/approve",
                "/api/v1/environments/" + environmentId + "/promotion-approvals/" + approvalId + "/approve",
                Map.of("releaseId", UUID.randomUUID(), "policyDigest", policyDigest));
        Map<String, Object> approvalBinding = Map.of("releaseId", releaseId, "policyDigest", policyDigest);
        call(cli, "POST", "/api/v1/environments/{environmentId}/promotion-approvals/{approvalId}/approve",
                "/api/v1/environments/" + environmentId + "/promotion-approvals/" + approvalId + "/approve",
                approvalBinding);
        call(cli, "POST", "/api/v1/environments/{environmentId}/promotion-approvals/{approvalId}/approve",
                "/api/v1/environments/" + environmentId + "/promotion-approvals/" + approvalId + "/approve",
                approvalBinding);
        call(cli, "GET", "/api/v1/environments/{environmentId}/promotion-approvals/{approvalId}",
                "/api/v1/environments/" + environmentId + "/promotion-approvals/" + approvalId, null);
        UUID foreignWorkspace = workspace();
        IdentityHttp foreign = new IdentityHttp(port).bearer(
                identities.create(foreignWorkspace, WorkspaceRole.ADMIN, PASSWORD, "ADMIN").token());
        String foreignEnvironment = call(foreign, "POST", "/api/v1/environments", "/api/v1/environments",
                Map.of("name", "foreign-approval")).json().path("id").asString();
        call(foreign, "POST", "/api/v1/environments/{environmentId}/promotion-approvals",
                "/api/v1/environments/" + foreignEnvironment + "/promotion-approvals",
                Map.of("releaseId", releaseId));

        // Remembered tool permissions: policy identity only, with append-only revocation.
        Map<String, Object> permissionRequest = Map.ofEntries(Map.entry("id", UUID.randomUUID()),
                Map.entry("effect", "ALLOW"),
                Map.entry("scope", "WORKSPACE"), Map.entry("actorId", admin.userId()),
                Map.entry("capability", "core:search:1.0.0"), Map.entry("resource", "public-web"),
                Map.entry("action", "read"), Map.entry("environmentId", environmentId),
                Map.entry("maximumRisk", "LOW"), Map.entry("expiresAt", Instant.now().plus(Duration.ofDays(1))));
        var permission = call(cli, "POST", "/api/v1/remembered-permissions", "/api/v1/remembered-permissions",
                permissionRequest);
        call(cli, "POST", "/api/v1/remembered-permissions", "/api/v1/remembered-permissions", permissionRequest);
        String permissionId = permission.json().path("id").asString();
        call(cli, "GET", "/api/v1/remembered-permissions", "/api/v1/remembered-permissions", null);
        call(cli, "GET", "/api/v1/remembered-permissions/{id}",
                "/api/v1/remembered-permissions/" + permissionId, null);
        call(cli, "POST", "/api/v1/remembered-permissions/{id}/revoke",
                "/api/v1/remembered-permissions/" + permissionId + "/revoke", null);

        // Promotion, deployments, routing, decisions, and cohorts.
        var promotion = call(cli, "POST", "/api/v1/environments/{environmentId}/promotions", "/api/v1/environments/"
                + environmentId + "/promotions", Map.of("deployment", "api", "releaseId", releaseId),
                Map.of("Idempotency-Key", "promote-1"));
        call(cli, "POST", "/api/v1/environments/{environmentId}/promotions", "/api/v1/environments/" + environmentId
                + "/promotions", Map.of("deployment", "api", "releaseId", releaseId), Map.of("Idempotency-Key", "promote-1"));
        String deploymentId = promotion.json().path("deploymentId").asString();
        String firstCohort = promotion.json().path("cohortId").asString();
        call(cli, "GET", "/api/v1/deployments", "/api/v1/deployments?environmentId=" + environmentId, null);
        call(cli, "GET", "/api/v1/deployments/{id}", "/api/v1/deployments/" + deploymentId, null);
        call(cli, "GET", "/api/v1/routing-generations/{id}/targets", "/api/v1/routing-generations/"
                + promotion.json().path("routingGenerationId").asString() + "/targets", null);
        call(cli, "GET", "/api/v1/decisions/{id}", "/api/v1/decisions/" + promotion.json().path("decisionId").asString(),
                null);
        call(cli, "GET", "/api/v1/cohorts/{id}", "/api/v1/cohorts/" + firstCohort, null);

        // Runs and their events, steps, and streams.
        var run = call(cli, "POST", "/api/v1/deployments/{deploymentId}/runs", "/api/v1/deployments/" + deploymentId
                + "/runs", Map.of("input", Map.of("q", "hello")), Map.of("Idempotency-Key", "run-1"));
        assertThat(run.status()).isEqualTo(201);
        assertThat(call(cli, "POST", "/api/v1/deployments/{deploymentId}/runs", "/api/v1/deployments/" + deploymentId
                + "/runs", Map.of("input", Map.of("q", "hello")), Map.of("Idempotency-Key", "run-1")).status())
                .isEqualTo(200);
        call(cli, "POST", "/api/v1/deployments/{deploymentId}/runs", "/api/v1/deployments/" + deploymentId + "/runs",
                Map.of("input", Map.of("q", "other")), Map.of("Idempotency-Key", "run-1"));
        call(cli, "POST", "/api/v1/deployments/{deploymentId}/runs", "/api/v1/deployments/" + deploymentId + "/runs",
                Map.of("parentRunId", UUID.randomUUID().toString()));
        String runId = run.json().path("id").asString();
        call(cli, "GET", "/api/v1/runs", "/api/v1/runs?deploymentId=" + deploymentId + "&lifecycle=QUEUED&limit=5", null);
        call(cli, "GET", "/api/v1/runs", "/api/v1/runs?lifecycle=NOPE", null);
        var view = call(cli, "GET", "/api/v1/runs/{id}", "/api/v1/runs/" + runId, null);
        assertThat(call(cli, "GET", "/api/v1/runs/{id}", "/api/v1/runs/" + runId, null,
                Map.of("If-None-Match", view.header("ETag"))).status()).isEqualTo(304);
        call(cli, "GET", "/api/v1/runs/{id}/events", "/api/v1/runs/" + runId + "/events?limit=1", null);
        call(cli, "GET", "/api/v1/runs/{id}/events", "/api/v1/runs/" + runId + "/events?cursor=9", null);
        call(cli, "GET", "/api/v1/runs/{id}/context", "/api/v1/runs/" + runId + "/context", null);
        call(cli, "GET", "/api/v1/runs/{id}/steps", "/api/v1/runs/" + runId + "/steps", null);
        call(cli, "POST", "/api/v1/runs/{id}/resume", "/api/v1/runs/" + runId + "/resume", null);
        call(cli, "POST", "/api/v1/runs/{id}/cancel", "/api/v1/runs/" + runId + "/cancel", Map.of("reason", "done"));
        call(cli, "POST", "/api/v1/runs/{id}/resume", "/api/v1/runs/" + runId + "/resume", null);
        call(cli, "GET", "/api/v1/runs/{id}/replay/plan",
                "/api/v1/runs/" + runId + "/replay/plan?mode=EXACT", null);
        call(cli, "POST", "/api/v1/runs/{id}/replay", "/api/v1/runs/" + runId + "/replay",
                Map.of("mode", "EXACT"));
        stream(admin.token(), runId, null, 200);
        long last = engine.run(new WorkspaceId(workspace), UUID.fromString(runId)).summary().eventSequence();
        stream(admin.token(), runId, runId + ":" + last, 204);
        stream(admin.token(), runId, "garbage", 400);

        String paused = call(cli, "POST", "/api/v1/deployments/{deploymentId}/runs", "/api/v1/deployments/"
                + deploymentId + "/runs", Map.of()).json().path("id").asString();
        var scope = ClaimScope.any(ReleaseManifestReader.SUPPORTED_VERSIONS).in(new WorkspaceId(workspace));
        var claimed = engine.claimNext("contract-worker", scope, Duration.ofMinutes(1)).orElseThrow();
        assertThat(claimed.run().id().toString()).isEqualTo(paused);
        // #183: an active Spring AI run cannot be paused safely yet (409 PAUSE_UNSAFE), a paused run is returned
        // unchanged and a queued run pauses at once (200); a terminal run is a typed conflict.
        assertThat(call(cli, "POST", "/api/v1/runs/{id}/pause", "/api/v1/runs/" + paused + "/pause",
                Map.of("reason", "maintenance")).status()).isEqualTo(409);
        engine.pause(claimed.lease(), "operator");
        assertThat(call(cli, "POST", "/api/v1/runs/{id}/pause", "/api/v1/runs/" + paused + "/pause", null).status())
                .isEqualTo(200);
        assertThat(call(cli, "POST", "/api/v1/runs/{id}/resume", "/api/v1/runs/" + paused + "/resume", null).status())
                .isEqualTo(200);
        assertThat(call(cli, "POST", "/api/v1/runs/{id}/pause", "/api/v1/runs/" + paused + "/pause", null).status())
                .isEqualTo(200);
        assertThat(call(cli, "POST", "/api/v1/runs/{id}/resume", "/api/v1/runs/" + paused + "/resume", null).status())
                .isEqualTo(200);
        assertThat(call(cli, "POST", "/api/v1/runs/{id}/pause", "/api/v1/runs/" + runId + "/pause", null).status())
                .isEqualTo(409);

        // Logical session delivery creates a current-route root and exposes its durable inbox. Operation lifecycle
        // success is covered with a provider by SessionInboxDurableOperationPostgresTest; this no-provider API
        // journey verifies list visibility and the documented fail-closed mutation responses.
        var session = call(cli, "POST", "/api/v1/sessions", "/api/v1/sessions",
                Map.of("deploymentId", deploymentId));
        String sessionId = session.json().path("id").asString();
        call(cli, "GET", "/api/v1/sessions", "/api/v1/sessions?limit=1", null);
        call(cli, "GET", "/api/v1/sessions/{sessionId}", "/api/v1/sessions/" + sessionId, null);
        var message = call(cli, "POST", "/api/v1/sessions/{sessionId}/messages",
                "/api/v1/sessions/" + sessionId + "/messages",
                Map.of("type", "USER_MESSAGE", "correlationId", "contract-session", "payload", Map.of("text", "hi")),
                Map.of("Idempotency-Key", "contract-session-message"));
        String messageId = message.json().path("id").asString();
        call(cli, "GET", "/api/v1/sessions/{sessionId}/messages",
                "/api/v1/sessions/" + sessionId + "/messages?status=PENDING", null);
        call(cli, "GET", "/api/v1/sessions/{sessionId}/messages/{messageId}",
                "/api/v1/sessions/" + sessionId + "/messages/" + messageId, null);
        call(cli, "GET", "/api/v1/operations", "/api/v1/operations?status=RUNNING", null);
        String absentOperation = UUID.randomUUID().toString();
        call(cli, "GET", "/api/v1/operations/{operationId}", "/api/v1/operations/" + absentOperation, null);
        call(cli, "POST", "/api/v1/operations/{operationId}/cancel",
                "/api/v1/operations/" + absentOperation + "/cancel", null);
        call(anonymous, "POST", "/api/v1/operation-callbacks/{workspaceId}/{operationId}",
                "/api/v1/operation-callbacks/" + workspace + "/" + absentOperation, Map.of(),
                Map.of("Dvara-Operation-Token", "invalid"));

        // Durable human interaction read/respond, including the typed terminal-repeat conflict.
        var interaction = interaction(cli, workspace, deploymentId, admin.userId(),
                new HumanInteractionPrompt.Approval("Approve", "Proceed?", "Production"),
                Duration.ofMinutes(5), "response");
        call(cli, "GET", "/api/v1/interactions", "/api/v1/interactions?status=OPEN&runId=" + interaction.runId(), null);
        call(cli, "GET", "/api/v1/interactions/{interactionId}",
                "/api/v1/interactions/" + interaction.id(), null);
        assertThat(call(cli, "POST", "/api/v1/interactions/{interactionId}/response",
                "/api/v1/interactions/" + interaction.id() + "/response", Map.of("approved", true)).status())
                .isEqualTo(200);
        assertThat(call(cli, "POST", "/api/v1/interactions/{interactionId}/response",
                "/api/v1/interactions/" + interaction.id() + "/response", Map.of("approved", true)).status())
                .isEqualTo(409);
        var cancelled = interaction(cli, workspace, deploymentId, admin.userId(),
                new HumanInteractionPrompt.Confirmation("Cancel", "Continue?", "Production"),
                Duration.ofMinutes(5), "cancel");
        assertThat(call(cli, "POST", "/api/v1/interactions/{interactionId}/cancel",
                "/api/v1/interactions/" + cancelled.id() + "/cancel", null).status()).isEqualTo(200);
        var expiring = interaction(cli, workspace, deploymentId, admin.userId(),
                new HumanInteractionPrompt.Question("Timed", "Answer", "{\"type\":\"string\"}"),
                Duration.ofMillis(1), "expire");
        Thread.sleep(20);
        assertThat(call(cli, "POST", "/api/v1/interactions/{interactionId}/expire",
                "/api/v1/interactions/" + expiring.id() + "/expire", null).status()).isEqualTo(200);
        var revoked = interaction(cli, workspace, deploymentId, admin.userId(),
                new HumanInteractionPrompt.Authorization("Authorize", "Connect", new CredentialRef("env://calendar-token"),
                        Set.of("calendar.read")), Duration.ofMinutes(5), "revoke");
        assertThat(call(cli, "POST", "/api/v1/interactions/{interactionId}/revoke",
                "/api/v1/interactions/" + revoked.id() + "/revoke", null).status()).isEqualTo(200);

        // A second promotion drains the first cohort, whose queued run blocks its retirement.
        var promoted = call(cli, "POST", "/api/v1/environments/{environmentId}/promotions", "/api/v1/environments/"
                + environmentId + "/promotions", Map.of("deployment", "api", "releaseId", secondRelease));
        String secondCohort = promoted.json().path("cohortId").asString();
        String cohortTag = call(cli, "GET", "/api/v1/cohorts/{id}", "/api/v1/cohorts/" + secondCohort, null).header("ETag");
        var suspended = call(cli, "POST", "/api/v1/cohorts/{id}/{action}", "/api/v1/cohorts/" + secondCohort + "/suspend",
                Map.of("reason", "maintenance"), Map.of("If-Match", cohortTag, "Idempotency-Key", "suspend-1"));
        call(cli, "POST", "/api/v1/cohorts/{id}/{action}", "/api/v1/cohorts/" + secondCohort + "/resume",
                Map.of("reason", "done"), Map.of("If-Match", suspended.header("ETag")));
        call(cli, "POST", "/api/v1/cohorts/{id}/{action}", "/api/v1/cohorts/" + secondCohort + "/resume",
                Map.of("reason", "stale"), Map.of("If-Match", suspended.header("ETag")));
        call(cli, "POST", "/api/v1/cohorts/{id}/{action}", "/api/v1/cohorts/" + secondCohort + "/resume",
                Map.of("reason", "missing"));
        call(cli, "POST", "/api/v1/cohorts/{id}/quiescence", "/api/v1/cohorts/" + firstCohort + "/quiescence", null);
        UUID quarantinedOutbox = jdbc.queryForObject(
                "select id from control_plane_outbox where workspace_id = ? order by sequence_number limit 1",
                UUID.class, workspace);
        jdbc.update("insert into control_plane_outbox_attempt(consumer, workspace_id, outbox_id, attempts, "
                + "first_failed_at, last_failed_at, next_attempt_at, quarantined_at, last_error_code, "
                + "last_error_digest) values ('run-plane-deployments', ?, ?, 5, current_timestamp, "
                + "current_timestamp, null, current_timestamp, 'ContractFailure', ?)", workspace, quarantinedOutbox,
                "sha256:" + "0".repeat(64));
        call(cli, "GET", "/api/v1/outbox-quarantines", "/api/v1/outbox-quarantines", null);
        call(cli, "POST", "/api/v1/outbox-quarantines/{outboxId}/release",
                "/api/v1/outbox-quarantines/" + quarantinedOutbox + "/release", null);
        String drainingTag = call(cli, "GET", "/api/v1/cohorts/{id}", "/api/v1/cohorts/" + firstCohort, null).header("ETag");
        var retire = call(cli, "POST", "/api/v1/cohorts/{id}/retire", "/api/v1/cohorts/" + firstCohort + "/retire",
                Map.of("reason", "cleanup"), Map.of("If-Match", drainingTag));
        assertThat(retire.status()).isEqualTo(409);
        assertThat(retire.json().path("outcome").asString()).isEqualTo("BLOCKED");
        String generation = call(cli, "GET", "/api/v1/deployments/{id}", "/api/v1/deployments/" + deploymentId, null)
                .header("ETag");
        var rollback = call(cli, "POST", "/api/v1/deployments/{id}/rollback", "/api/v1/deployments/" + deploymentId
                + "/rollback", Map.of("releaseId", releaseId, "reason", "regression"), Map.of("If-Match", generation));
        assertThat(rollback.status()).isEqualTo(201);
        String rolledBack = rollback.json().path("cohortId").asString();
        String rolledBackTag = call(cli, "GET", "/api/v1/cohorts/{id}", "/api/v1/cohorts/" + rolledBack, null)
                .header("ETag");
        call(cli, "POST", "/api/v1/cohorts/{id}/{action}", "/api/v1/cohorts/" + rolledBack + "/revoke",
                Map.of("reason", "compromised"), Map.of("If-Match", rolledBackTag));
        // The second cohort never admitted a run: once drained it is quiescent and retires.
        String quiescent = call(cli, "POST", "/api/v1/cohorts/{id}/quiescence", "/api/v1/cohorts/" + secondCohort
                + "/quiescence", null).header("ETag");
        assertThat(call(cli, "POST", "/api/v1/cohorts/{id}/retire", "/api/v1/cohorts/" + secondCohort + "/retire",
                Map.of("reason", "cleanup"), Map.of("If-Match", quiescent)).status()).isEqualTo(200);

        // Immutable artifact upload, discovery, range transfer, validation, pinning, and expiry.
        var artifact = call(cli, "POST", "/api/v1/artifacts", "/api/v1/artifacts", "contract artifact",
                Map.of("Content-Type", "text/plain"));
        assertThat(artifact.status()).isEqualTo(201);
        String artifactId = artifact.json().path("id").asString();
        call(cli, "GET", "/api/v1/artifacts", "/api/v1/artifacts?limit=1", null);
        call(cli, "GET", "/api/v1/artifacts/{artifactId}", "/api/v1/artifacts/" + artifactId, null);
        call(cli, "GET", "/api/v1/artifacts/{artifactId}/content",
                "/api/v1/artifacts/" + artifactId + "/content", null, Map.of("Range", "bytes=0-7"));
        call(cli, "POST", "/api/v1/artifacts/{artifactId}/validation",
                "/api/v1/artifacts/" + artifactId + "/validation",
                Map.of("validation", "ACCEPTED", "reason", "CONTRACT_ACCEPTED"));
        var pin = call(cli, "POST", "/api/v1/artifacts/{artifactId}/pins",
                "/api/v1/artifacts/" + artifactId + "/pins",
                Map.of("kind", "RETENTION", "subjectId", UUID.randomUUID()));
        call(cli, "GET", "/api/v1/artifacts/{artifactId}/pins", "/api/v1/artifacts/" + artifactId + "/pins", null);
        call(cli, "DELETE", "/api/v1/artifacts/{artifactId}/pins/{pinId}",
                "/api/v1/artifacts/" + artifactId + "/pins/" + pin.json().path("id").asString(), null);
        call(cli, "POST", "/api/v1/artifacts/{artifactId}/expire",
                "/api/v1/artifacts/" + artifactId + "/expire", null);

        // Bootstrap is complete; password change and sign-out end the journey.
        call(anonymous, "POST", "/api/v1/auth/bootstrap", "/api/v1/auth/bootstrap", Map.of("token", "awb_" + "x".repeat(43),
                "username", "second-admin", "password", "a long enough password 1"));
        call(browser, "POST", "/api/v1/auth/password", "/api/v1/auth/password", Map.of("currentPassword", PASSWORD,
                "newPassword", "another correct horse battery 43"));
        assertThat(call(browser, "POST", "/api/v1/auth/logout", "/api/v1/auth/logout", null).status()).isEqualTo(204);

        Set<String> missing = new TreeSet<>();
        for (String operation : contract.operations().keySet()) {
            boolean success = contract.validated.stream().anyMatch(entry -> entry.startsWith(operation + " 2")
                    || entry.startsWith(operation + " 3"));
            boolean any = contract.validated.stream().anyMatch(entry -> entry.startsWith(operation + " "));
            if (REFUSAL_ONLY.contains(operation) ? !any : !success) missing.add(operation);
        }
        assertThat(missing).as("operations never answered as documented").isEmpty();
    }

    private IdentityHttp.Response call(IdentityHttp http, String method, String template, String path, Object body) {
        return call(http, method, template, path, body, Map.of());
    }

    private IdentityHttp.Response call(IdentityHttp http, String method, String template, String path, Object body,
            Map<String, String> headers) {
        IdentityHttp.Response response = http.send(method, path, body, headers);
        contract.validate(method, template, response.status(), response.header("Content-Type"), response.raw());
        return response;
    }

    private ContractInteraction interaction(IdentityHttp cli, UUID workspace, String deploymentId, UUID assigned,
            HumanInteractionPrompt prompt, Duration timeout, String key) {
        UUID runId = UUID.fromString(call(cli, "POST", "/api/v1/deployments/{deploymentId}/runs",
                "/api/v1/deployments/" + deploymentId + "/runs", Map.of()).json().path("id").asString());
        var scope = ClaimScope.any(ReleaseManifestReader.SUPPORTED_VERSIONS).in(new WorkspaceId(workspace));
        var otherRuns = Set.copyOf(jdbc.queryForList("select id from agent_run where workspace_id = ? and id <> ?",
                UUID.class, workspace, runId));
        var claimed = engine.claimNext("interaction-contract-worker-" + key, scope.excluding(otherRuns),
                Duration.ofMinutes(1)).orElseThrow();
        assertThat(claimed.run().id()).isEqualTo(runId);
        UUID id = UUID.randomUUID();
        var pins = claimed.run().pins();
        interactions.block(claimed.lease(), new HumanInteractionDraft(id, workspace, runId, pins.releaseId(),
                pins.deploymentId(), pins.revisionId(), pins.cohortId(), "contract-" + key, "contract-" + key,
                prompt, HumanInteractionAssignment.user(workspace, assigned), timeout));
        return new ContractInteraction(id, runId);
    }

    /** Reads a whole stream of a terminal run and validates every event's data against its documented schema. */
    private void stream(String token, String run, String lastEventId, int expected) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/runs/"
                + run + "/stream")).header("Accept", "text/event-stream").header("Authorization", "Bearer " + token)
                .timeout(Duration.ofSeconds(20));
        if (lastEventId != null) request.header("Last-Event-ID", lastEventId);
        HttpResponse<String> response = HttpClient.newHttpClient().send(request.build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(expected);
        contract.validate("GET", "/api/v1/runs/{id}/stream", response.statusCode(),
                response.headers().firstValue("Content-Type").orElse(null), response.body());
        if (expected != 200) return;
        List<Map<String, String>> events = new ArrayList<>();
        Map<String, String> current = new LinkedHashMap<>();
        for (String line : response.body().split("\n", -1)) {
            if (line.isEmpty()) {
                if (current.containsKey("data")) events.add(current);
                current = new LinkedHashMap<>();
            } else if (!line.startsWith(":") && line.contains(":")) {
                current.put(line.substring(0, line.indexOf(':')), line.substring(line.indexOf(':') + 1).strip());
            }
        }
        assertThat(events).isNotEmpty();
        for (Map<String, String> event : events) {
            String schema = switch (event.get("event")) {
                case "stream-end" -> "StreamEnd";
                case "reconnect" -> "StreamReconnect";
                case "stream-error" -> "StreamError";
                default -> "RunEvent";
            };
            contract.validateJson(contract.schema(schema), OpenApiContract.JSON.readTree(event.get("data")),
                    "stream event " + event.get("event"));
        }
        assertThat(events.getLast().get("event")).isEqualTo("stream-end");
    }

    private UUID workspace() {
        UUID organization = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        jdbc.update("insert into organization(id, name) values (?, ?)", organization, "org-" + organization);
        jdbc.update("insert into workspace(id, organization_id, name) values (?, ?, ?)", workspace, organization, "ws");
        return workspace;
    }

    private record ContractInteraction(UUID id, UUID runId) {}
}
