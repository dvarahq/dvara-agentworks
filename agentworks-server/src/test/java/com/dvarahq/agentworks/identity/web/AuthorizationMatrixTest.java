package com.dvarahq.agentworks.identity.web;

import com.dvarahq.agentworks.domain.identity.WorkspacePermission;
import com.dvarahq.agentworks.domain.identity.WorkspaceRole;
import com.dvarahq.agentworks.DvaraAgentWorksApplication;
import com.dvarahq.agentworks.identity.TestIdentities;
import com.dvarahq.agentworks.identity.TestIdentities.Member;
import com.dvarahq.agentworks.storage.DefaultTenantBootstrap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.dvarahq.agentworks.domain.identity.WorkspacePermission.AGENT_WRITE;
import static com.dvarahq.agentworks.domain.identity.WorkspacePermission.ARTIFACT_ADMIN;
import static com.dvarahq.agentworks.domain.identity.WorkspacePermission.ARTIFACT_WRITE;
import static com.dvarahq.agentworks.domain.identity.WorkspacePermission.DEPLOYMENT_OPERATE;
import static com.dvarahq.agentworks.domain.identity.WorkspacePermission.ENVIRONMENT_ADMIN;
import static com.dvarahq.agentworks.domain.identity.WorkspacePermission.INTERACTION_RESPOND;
import static com.dvarahq.agentworks.domain.identity.WorkspacePermission.MEMBER_ADMIN;
import static com.dvarahq.agentworks.domain.identity.WorkspacePermission.RELEASE_WRITE;
import static com.dvarahq.agentworks.domain.identity.WorkspacePermission.RELEASE_ADMIN;
import static com.dvarahq.agentworks.domain.identity.WorkspacePermission.RUN_ADMIT;
import static com.dvarahq.agentworks.domain.identity.WorkspacePermission.TOKEN_SELF;
import static com.dvarahq.agentworks.domain.identity.WorkspacePermission.TOOL_PERMISSION_ADMIN;
import static com.dvarahq.agentworks.domain.identity.WorkspacePermission.WORKSPACE_READ;
import static com.dvarahq.agentworks.identity.web.IdentityTestConfiguration.PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The authorization matrix for every REST operation: each endpoint is exercised by an anonymous client and by an
 * API token and a browser session of every role, and must be allowed exactly as specified here. The completeness
 * check fails when an endpoint exists without an entry, so every new operation needs an explicit decision; the
 * runtime rules deny it until one exists.
 */
@SpringBootTest(classes = DvaraAgentWorksApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // Authorization, not sign-in throttling, is under test: every sign-in here comes from one loopback address.
        properties = {"agentworks.security.login.attempts-per-window=1000", "agentworks.artifacts.enabled=true",
                "agentworks.artifacts.root=${java.io.tmpdir}/agentworks-authorization-matrix-artifacts"})
@Import(IdentityTestConfiguration.class)
public class AuthorizationMatrixTest {
    private static final UUID WORKSPACE = DefaultTenantBootstrap.DEFAULT_WORKSPACE_ID.value();
    private static final String ID = UUID.randomUUID().toString();
    /** Malformed JSON: an allowed request fails in the handler, so the matrix never changes state. */
    private static final String UNREADABLE = "{";

    enum Need { PUBLIC, AUTHENTICATED, SESSION, ACTIVE_SESSION, ORGANIZATION_ADMIN, SESSION_AND_PERMISSION, PERMISSION }

    record Rule(String method, String mapping, List<String> examples, String body, Need need,
            WorkspacePermission permission) {
        String key() {
            return method + " " + mapping;
        }
    }

    record Client(String name, IdentityHttp http, WorkspaceRole role, boolean session, boolean organizationAdmin) {}

    private static final List<Rule> MATRIX = List.of(
            rule("GET", "/api/v1/openapi.yaml", null, Need.PUBLIC, null),
            rule("POST", "/api/v1/auth/login", UNREADABLE, Need.PUBLIC, null),
            rule("POST", "/api/v1/auth/bootstrap", UNREADABLE, Need.PUBLIC, null),
            rule("GET", "/api/v1/auth/session", null, Need.AUTHENTICATED, null),
            rule("POST", "/api/v1/auth/password", UNREADABLE, Need.SESSION, null),
            rule("GET", "/api/v1/auth/workspaces", null, Need.ACTIVE_SESSION, null),
            rule("PUT", "/api/v1/auth/session/workspace", UNREADABLE, Need.ACTIVE_SESSION, null),
            rule("GET", "/api/v1/auth/sessions", null, Need.ACTIVE_SESSION, null),
            rule("DELETE", "/api/v1/auth/sessions/{id}", null, Need.ACTIVE_SESSION, null),
            rule("GET", "/api/v1/users", null, Need.ORGANIZATION_ADMIN, null),
            rule("POST", "/api/v1/users", UNREADABLE, Need.ORGANIZATION_ADMIN, null),
            rule("POST", "/api/v1/users/{id}/password-reset", null, Need.ORGANIZATION_ADMIN, null),
            rule("POST", "/api/v1/users/{id}/disable", null, Need.ORGANIZATION_ADMIN, null),
            rule("POST", "/api/v1/users/{id}/enable", null, Need.ORGANIZATION_ADMIN, null),
            rule("GET", "/api/v1/members", null, Need.PERMISSION, MEMBER_ADMIN),
            rule("PUT", "/api/v1/members/{userId}", UNREADABLE, Need.SESSION_AND_PERMISSION, MEMBER_ADMIN),
            rule("DELETE", "/api/v1/members/{userId}", null, Need.SESSION_AND_PERMISSION, MEMBER_ADMIN),
            rule("POST", "/api/v1/tokens", UNREADABLE, Need.SESSION_AND_PERMISSION, TOKEN_SELF),
            rule("GET", "/api/v1/tokens", null, Need.PERMISSION, TOKEN_SELF),
            rule("DELETE", "/api/v1/tokens/{id}", null, Need.PERMISSION, TOKEN_SELF),
            rule("GET", "/api/v1/administrative-audit", null, Need.PERMISSION, WORKSPACE_READ),
            rule("GET", "/api/v1/diagnostics/support-bundle", null, Need.PERMISSION, WORKSPACE_READ),
            rule("POST", "/api/v1/agents", UNREADABLE, Need.PERMISSION, AGENT_WRITE),
            rule("GET", "/api/v1/agents", null, Need.PERMISSION, WORKSPACE_READ),
            rule("GET", "/api/v1/agents/{definitionId}", null, Need.PERMISSION, WORKSPACE_READ),
            rule("POST", "/api/v1/agents/{definitionId}/versions", UNREADABLE, Need.PERMISSION, AGENT_WRITE),
            rule("GET", "/api/v1/agents/{definitionId}/versions", null, Need.PERMISSION, WORKSPACE_READ),
            rule("GET", "/api/v1/agents/{definitionId}/versions/{version}", null, Need.PERMISSION, WORKSPACE_READ),
            rule("POST", "/api/v1/evaluation-datasets", UNREADABLE, Need.PERMISSION, AGENT_WRITE),
            rule("GET", "/api/v1/evaluation-datasets", null, Need.PERMISSION, WORKSPACE_READ),
            rule("GET", "/api/v1/evaluation-datasets/{id}", null, Need.PERMISSION, WORKSPACE_READ),
            rule("POST", "/api/v1/evaluation-datasets/purge-expired", null, Need.PERMISSION, AGENT_WRITE),
            rule("POST", "/api/v1/evaluators", UNREADABLE, Need.PERMISSION, AGENT_WRITE),
            rule("GET", "/api/v1/evaluators", null, Need.PERMISSION, WORKSPACE_READ),
            rule("GET", "/api/v1/evaluators/{id}", null, Need.PERMISSION, WORKSPACE_READ),
            rule("POST", "/api/v1/evaluation-runs", UNREADABLE, Need.PERMISSION, AGENT_WRITE),
            rule("GET", "/api/v1/evaluation-runs/{id}", null, Need.PERMISSION, WORKSPACE_READ),
            rule("GET", "/api/v1/evaluation-runs/{id}/report", null, Need.PERMISSION, WORKSPACE_READ),
            rule("POST", "/api/v1/evaluation-runs/{id}/cancel", null, Need.PERMISSION, AGENT_WRITE),
            rule("POST", "/api/v1/evaluation-runs/{id}/resume", null, Need.PERMISSION, AGENT_WRITE),
            rule("POST", "/api/v1/evaluation-baselines", UNREADABLE, Need.PERMISSION, AGENT_WRITE),
            rule("GET", "/api/v1/evaluation-baselines", null, Need.PERMISSION, WORKSPACE_READ),
            rule("GET", "/api/v1/evaluation-baselines/{id}/comparison", null, Need.PERMISSION, WORKSPACE_READ),
            rule("POST", "/api/v1/tools", UNREADABLE, Need.PERMISSION, AGENT_WRITE),
            rule("GET", "/api/v1/tools", null, Need.PERMISSION, WORKSPACE_READ),
            rule("GET", "/api/v1/mcp-connections", null, Need.PERMISSION, WORKSPACE_READ),
            rule("GET", "/api/v1/tools/{namespace}/{name}/versions/{version}", null, Need.PERMISSION, AGENT_WRITE),
            rule("POST", "/api/v1/tools/{namespace}/{name}/versions/{version}/lifecycle", UNREADABLE,
                    Need.PERMISSION, AGENT_WRITE),
            rule("POST", "/api/v1/capability-packs", UNREADABLE, Need.PERMISSION, AGENT_WRITE),
            rule("GET", "/api/v1/capability-packs/{id}/versions/{version}", null, Need.PERMISSION, WORKSPACE_READ),
            rule("POST", "/api/v1/capability-packs/{id}/versions/{version}/lifecycle", UNREADABLE,
                    Need.PERMISSION, AGENT_WRITE),
            rule("POST", "/api/v1/remembered-permissions", UNREADABLE, Need.PERMISSION, TOOL_PERMISSION_ADMIN),
            rule("GET", "/api/v1/remembered-permissions", null, Need.PERMISSION, TOOL_PERMISSION_ADMIN),
            rule("GET", "/api/v1/remembered-permissions/{id}", null, Need.PERMISSION, TOOL_PERMISSION_ADMIN),
            rule("POST", "/api/v1/remembered-permissions/{id}/revoke", null, Need.PERMISSION,
                    TOOL_PERMISSION_ADMIN),
            rule("POST", "/api/v1/agent-versions/{versionId}/releases", UNREADABLE, Need.PERMISSION, RELEASE_WRITE),
            rule("GET", "/api/v1/releases", null, Need.PERMISSION, WORKSPACE_READ),
            rule("GET", "/api/v1/releases/{id}", null, Need.PERMISSION, WORKSPACE_READ),
            rule("GET", "/api/v1/releases/{id}/manifest", null, Need.PERMISSION, WORKSPACE_READ),
            rule("GET", "/api/v1/releases/{id}/lifecycle", null, Need.PERMISSION, WORKSPACE_READ),
            new Rule("POST", "/api/v1/releases/{id}/{action:suspend|resume|revoke|retire}", List.of(
                    "/api/v1/releases/" + ID + "/suspend", "/api/v1/releases/" + ID + "/resume",
                    "/api/v1/releases/" + ID + "/revoke", "/api/v1/releases/" + ID + "/retire"),
                    UNREADABLE, Need.PERMISSION, RELEASE_ADMIN),
            rule("GET", "/api/v1/release-revocations", null, Need.PERMISSION, WORKSPACE_READ),
            rule("POST", "/api/v1/releases/{id}/bundle", null, Need.PERMISSION, RELEASE_WRITE),
            rule("GET", "/api/v1/releases/{id}/bundle", null, Need.PERMISSION, WORKSPACE_READ),
            rule("POST", "/api/v1/releases/{id}/bundle/anchor", null, Need.PERMISSION, RELEASE_WRITE),
            rule("POST", "/api/v1/release-bundles/verify", UNREADABLE, Need.PERMISSION, WORKSPACE_READ),
            rule("POST", "/api/v1/release-bundles/import", UNREADABLE, Need.PERMISSION, RELEASE_WRITE),
            rule("PUT", "/api/v1/release-aliases/{name}", UNREADABLE, Need.PERMISSION, RELEASE_WRITE),
            rule("GET", "/api/v1/release-aliases/{name}", null, Need.PERMISSION, WORKSPACE_READ),
            rule("POST", "/api/v1/environments", UNREADABLE, Need.PERMISSION, ENVIRONMENT_ADMIN),
            rule("GET", "/api/v1/environments", null, Need.PERMISSION, WORKSPACE_READ),
            rule("GET", "/api/v1/environments/{id}", null, Need.PERMISSION, WORKSPACE_READ),
            rule("GET", "/api/v1/environments/{id}/promotion-policy", null, Need.PERMISSION, WORKSPACE_READ),
            rule("PUT", "/api/v1/environments/{id}/promotion-policy", UNREADABLE, Need.PERMISSION, ENVIRONMENT_ADMIN),
            rule("POST", "/api/v1/environments/{environmentId}/promotion-approvals", UNREADABLE,
                    Need.PERMISSION, DEPLOYMENT_OPERATE),
            rule("GET", "/api/v1/environments/{environmentId}/promotion-approvals/{approvalId}", null,
                    Need.PERMISSION, WORKSPACE_READ),
            rule("POST", "/api/v1/environments/{environmentId}/promotion-approvals/{approvalId}/approve", UNREADABLE,
                    Need.PERMISSION, ENVIRONMENT_ADMIN),
            rule("POST", "/api/v1/environments/{environmentId}/promotions", UNREADABLE, Need.PERMISSION,
                    DEPLOYMENT_OPERATE),
            rule("POST", "/api/v1/deployments/{id}/rollback", UNREADABLE, Need.PERMISSION, DEPLOYMENT_OPERATE),
            rule("GET", "/api/v1/deployments", null, Need.PERMISSION, WORKSPACE_READ),
            rule("GET", "/api/v1/deployments/{id}", null, Need.PERMISSION, WORKSPACE_READ),
            rule("GET", "/api/v1/routing-generations/{id}/targets", null, Need.PERMISSION, WORKSPACE_READ),
            rule("GET", "/api/v1/decisions/{id}", null, Need.PERMISSION, WORKSPACE_READ),
            rule("GET", "/api/v1/cohorts/{id}", null, Need.PERMISSION, WORKSPACE_READ),
            new Rule("POST", "/api/v1/cohorts/{id}/{action:suspend|resume|revoke}", List.of(
                    "/api/v1/cohorts/" + ID + "/suspend", "/api/v1/cohorts/" + ID + "/resume",
                    "/api/v1/cohorts/" + ID + "/revoke"), UNREADABLE, Need.PERMISSION, DEPLOYMENT_OPERATE),
            rule("POST", "/api/v1/cohorts/{id}/retire", UNREADABLE, Need.PERMISSION, DEPLOYMENT_OPERATE),
            rule("POST", "/api/v1/cohorts/{id}/quiescence", null, Need.PERMISSION, DEPLOYMENT_OPERATE),
            rule("GET", "/api/v1/outbox-quarantines", null, Need.PERMISSION, WORKSPACE_READ),
            rule("POST", "/api/v1/outbox-quarantines/{outboxId}/release", null, Need.PERMISSION,
                    DEPLOYMENT_OPERATE),
            rule("POST", "/api/v1/deployments/{deploymentId}/runs", UNREADABLE, Need.PERMISSION, RUN_ADMIT),
            rule("GET", "/api/v1/runs", null, Need.PERMISSION, WORKSPACE_READ),
            rule("GET", "/api/v1/runs/{id}", null, Need.PERMISSION, WORKSPACE_READ),
            rule("GET", "/api/v1/runs/{id}/stream", null, Need.PERMISSION, WORKSPACE_READ),
            rule("GET", "/api/v1/runs/{id}/events", null, Need.PERMISSION, WORKSPACE_READ),
            rule("GET", "/api/v1/runs/{id}/context", null, Need.PERMISSION, WORKSPACE_READ),
            rule("GET", "/api/v1/runs/{id}/steps", null, Need.PERMISSION, WORKSPACE_READ),
            rule("GET", "/api/v1/runs/{id}/replay/plan", null, Need.PERMISSION, WORKSPACE_READ),
            rule("POST", "/api/v1/runs/{id}/replay", UNREADABLE, Need.PERMISSION, RUN_ADMIT),
            rule("POST", "/api/v1/runs/{id}/cancel", null, Need.PERMISSION, RUN_ADMIT),
            rule("POST", "/api/v1/runs/{id}/pause", null, Need.PERMISSION, RUN_ADMIT),
            rule("POST", "/api/v1/runs/{id}/resume", null, Need.PERMISSION, RUN_ADMIT),
            rule("POST", "/api/v1/sessions", UNREADABLE, Need.PERMISSION, RUN_ADMIT),
            rule("GET", "/api/v1/sessions", null, Need.PERMISSION, WORKSPACE_READ),
            rule("GET", "/api/v1/sessions/{sessionId}", null, Need.PERMISSION, WORKSPACE_READ),
            rule("POST", "/api/v1/sessions/{sessionId}/messages", UNREADABLE, Need.PERMISSION, RUN_ADMIT),
            rule("GET", "/api/v1/sessions/{sessionId}/messages", null, Need.PERMISSION, WORKSPACE_READ),
            rule("GET", "/api/v1/sessions/{sessionId}/messages/{messageId}", null, Need.PERMISSION, WORKSPACE_READ),
            rule("GET", "/api/v1/operations", null, Need.PERMISSION, WORKSPACE_READ),
            rule("GET", "/api/v1/operations/{operationId}", null, Need.PERMISSION, WORKSPACE_READ),
            rule("POST", "/api/v1/operations/{operationId}/cancel", null, Need.PERMISSION, RUN_ADMIT),
            rule("POST", "/api/v1/operation-callbacks/{workspaceId}/{operationId}", UNREADABLE,
                    Need.PUBLIC, null),
            rule("GET", "/api/v1/interactions", null, Need.PERMISSION, WORKSPACE_READ),
            rule("GET", "/api/v1/interactions/{interactionId}", null, Need.PERMISSION, WORKSPACE_READ),
            rule("POST", "/api/v1/interactions/{interactionId}/response", UNREADABLE, Need.PERMISSION,
                    INTERACTION_RESPOND),
            rule("POST", "/api/v1/interactions/{interactionId}/expire", null, Need.PERMISSION, RUN_ADMIT),
            rule("POST", "/api/v1/interactions/{interactionId}/cancel", null, Need.PERMISSION, RUN_ADMIT),
            rule("POST", "/api/v1/interactions/{interactionId}/revoke", null, Need.PERMISSION, RUN_ADMIT),
            rule("POST", "/api/v1/artifacts", "safe", Need.PERMISSION, ARTIFACT_WRITE),
            rule("GET", "/api/v1/artifacts", null, Need.PERMISSION, WORKSPACE_READ),
            rule("GET", "/api/v1/artifacts/{artifactId}", null, Need.PERMISSION, WORKSPACE_READ),
            rule("GET", "/api/v1/artifacts/{artifactId}/content", null, Need.PERMISSION, WORKSPACE_READ),
            rule("GET", "/api/v1/artifacts/{artifactId}/pins", null, Need.PERMISSION, WORKSPACE_READ),
            rule("POST", "/api/v1/artifacts/{artifactId}/expire", null, Need.PERMISSION, ARTIFACT_ADMIN),
            rule("POST", "/api/v1/artifacts/{artifactId}/validation", UNREADABLE, Need.PERMISSION, ARTIFACT_ADMIN),
            rule("POST", "/api/v1/artifacts/{artifactId}/pins", UNREADABLE, Need.PERMISSION, ARTIFACT_ADMIN),
            rule("DELETE", "/api/v1/artifacts/{artifactId}/pins/{pinId}", null, Need.PERMISSION, ARTIFACT_ADMIN),
            // The Web Console (#36): browser sessions only, each with the permission of the API operation it calls.
            rule("GET", "/console/login", null, Need.PUBLIC, null),
            rule("POST", "/console/login", UNREADABLE, Need.PUBLIC, null),
            rule("GET", "/console/password", null, Need.SESSION, null),
            rule("POST", "/console/password", UNREADABLE, Need.SESSION, null),
            rule("POST", "/console/workspace", UNREADABLE, Need.ACTIVE_SESSION, null),
            rule("GET", "/console", null, Need.SESSION_AND_PERMISSION, WORKSPACE_READ),
            rule("GET", "/console/dashboard", null, Need.SESSION_AND_PERMISSION, WORKSPACE_READ),
            rule("GET", "/console/agents", null, Need.SESSION_AND_PERMISSION, WORKSPACE_READ),
            rule("GET", "/console/agents/{id}", null, Need.SESSION_AND_PERMISSION, WORKSPACE_READ),
            rule("GET", "/console/releases", null, Need.SESSION_AND_PERMISSION, WORKSPACE_READ),
            rule("GET", "/console/releases/{id}", null, Need.SESSION_AND_PERMISSION, WORKSPACE_READ),
            rule("GET", "/console/environments/{id}", null, Need.SESSION_AND_PERMISSION, WORKSPACE_READ),
            rule("GET", "/console/features", null, Need.SESSION_AND_PERMISSION, WORKSPACE_READ),
            rule("GET", "/console/audit", null, Need.SESSION_AND_PERMISSION, WORKSPACE_READ),
            rule("GET", "/console/settings", null, Need.SESSION_AND_PERMISSION, WORKSPACE_READ),
            rule("GET", "/console/diagnostics", null, Need.SESSION_AND_PERMISSION, WORKSPACE_READ),
            rule("GET", "/console/deployments", null, Need.SESSION_AND_PERMISSION, WORKSPACE_READ),
            rule("GET", "/console/mcp", null, Need.SESSION_AND_PERMISSION, WORKSPACE_READ),
            rule("GET", "/console/deployments/{id}", null, Need.SESSION_AND_PERMISSION, WORKSPACE_READ),
            rule("GET", "/console/runs", null, Need.SESSION_AND_PERMISSION, WORKSPACE_READ),
            rule("GET", "/console/runs/{id}", null, Need.SESSION_AND_PERMISSION, WORKSPACE_READ),
            rule("GET", "/console/runs/{id}/summary", null, Need.SESSION_AND_PERMISSION, WORKSPACE_READ),
            rule("GET", "/console/runs/{id}/actions", null, Need.SESSION_AND_PERMISSION, WORKSPACE_READ),
            rule("GET", "/console/interactions", null, Need.SESSION_AND_PERMISSION, WORKSPACE_READ),
            rule("GET", "/console/interactions/{id}", null, Need.SESSION_AND_PERMISSION, WORKSPACE_READ),
            rule("GET", "/console/sessions", null, Need.SESSION_AND_PERMISSION, WORKSPACE_READ),
            rule("GET", "/console/sessions/{id}", null, Need.SESSION_AND_PERMISSION, WORKSPACE_READ),
            rule("GET", "/console/operations", null, Need.SESSION_AND_PERMISSION, WORKSPACE_READ),
            rule("GET", "/console/operations/{id}", null, Need.SESSION_AND_PERMISSION, WORKSPACE_READ),
            rule("POST", "/console/agents", UNREADABLE, Need.SESSION_AND_PERMISSION, AGENT_WRITE),
            rule("POST", "/console/agents/{id}/versions", UNREADABLE, Need.SESSION_AND_PERMISSION, AGENT_WRITE),
            rule("POST", "/console/agent-versions/{versionId}/releases", UNREADABLE, Need.SESSION_AND_PERMISSION,
                    RELEASE_WRITE),
            rule("POST", "/console/releases/{id}/bundle", null, Need.SESSION_AND_PERMISSION, RELEASE_WRITE),
            new Rule("POST", "/console/releases/{id}/{action:suspend|resume|revoke|retire}", List.of(
                    "/console/releases/" + ID + "/suspend", "/console/releases/" + ID + "/resume",
                    "/console/releases/" + ID + "/revoke", "/console/releases/" + ID + "/retire"),
                    null, Need.SESSION_AND_PERMISSION, RELEASE_ADMIN),
            rule("POST", "/console/environments", UNREADABLE, Need.SESSION_AND_PERMISSION, ENVIRONMENT_ADMIN),
            rule("POST", "/console/environments/{id}/policy", UNREADABLE, Need.SESSION_AND_PERMISSION,
                    ENVIRONMENT_ADMIN),
            rule("POST", "/console/promotions", UNREADABLE, Need.SESSION_AND_PERMISSION, DEPLOYMENT_OPERATE),
            new Rule("POST", "/console/deployments/{deploymentId}/cohorts/{cohortId}/{action:suspend|resume|revoke}",
                    List.of("/console/deployments/" + ID + "/cohorts/" + ID + "/suspend",
                            "/console/deployments/" + ID + "/cohorts/" + ID + "/resume",
                            "/console/deployments/" + ID + "/cohorts/" + ID + "/revoke"),
                    null, Need.SESSION_AND_PERMISSION, DEPLOYMENT_OPERATE),
            new Rule("POST", "/console/deployments/{deploymentId}/cohorts/{cohortId}/retire",
                    List.of("/console/deployments/" + ID + "/cohorts/" + ID + "/retire"), null,
                    Need.SESSION_AND_PERMISSION, DEPLOYMENT_OPERATE),
            new Rule("POST", "/console/deployments/{deploymentId}/cohorts/{cohortId}/quiescence",
                    List.of("/console/deployments/" + ID + "/cohorts/" + ID + "/quiescence"), null,
                    Need.SESSION_AND_PERMISSION, DEPLOYMENT_OPERATE),
            rule("POST", "/console/deployments/{deploymentId}/runs", UNREADABLE, Need.SESSION_AND_PERMISSION,
                    RUN_ADMIT),
            rule("POST", "/console/runs/{id}/cancel", UNREADABLE, Need.SESSION_AND_PERMISSION, RUN_ADMIT),
            rule("POST", "/console/runs/{id}/pause", UNREADABLE, Need.SESSION_AND_PERMISSION, RUN_ADMIT),
            rule("POST", "/console/runs/{id}/resume", null, Need.SESSION_AND_PERMISSION, RUN_ADMIT),
            rule("POST", "/console/interactions/{id}/response", UNREADABLE, Need.SESSION_AND_PERMISSION,
                    INTERACTION_RESPOND),
            rule("POST", "/console/operations/{id}/cancel", null, Need.SESSION_AND_PERMISSION, RUN_ADMIT),
            rule("GET", "/console/artifacts", null, Need.SESSION_AND_PERMISSION, WORKSPACE_READ),
            rule("GET", "/console/artifacts/{id}", null, Need.SESSION_AND_PERMISSION, WORKSPACE_READ),
            rule("POST", "/console/artifacts", UNREADABLE, Need.SESSION_AND_PERMISSION, ARTIFACT_WRITE),
            rule("POST", "/console/artifacts/{id}/expire", null, Need.SESSION_AND_PERMISSION, ARTIFACT_ADMIN),
            rule("POST", "/console/artifacts/{id}/validation", UNREADABLE, Need.SESSION_AND_PERMISSION,
                    ARTIFACT_ADMIN),
            rule("POST", "/console/artifacts/{id}/pins", UNREADABLE, Need.SESSION_AND_PERMISSION, ARTIFACT_ADMIN),
            rule("POST", "/console/artifacts/{id}/pins/{pinId}/remove", null, Need.SESSION_AND_PERMISSION,
                    ARTIFACT_ADMIN),
            // Sign-out ends the session clients, so both are exercised last; the session clients sign in again after
            // the Console's.
            rule("POST", "/console/logout", null, Need.SESSION, null),
            rule("POST", "/api/v1/auth/logout", null, Need.SESSION, null));

    /**
     * Each endpoint ("METHOD /pattern") and its authorization need: the need name, and for permission needs the
     * permission after a colon. The OpenAPI contract test (#35) checks the published description against it.
     */
    public static Map<String, String> expectedAuthorization() {
        Map<String, String> needs = new java.util.TreeMap<>();
        for (Rule rule : MATRIX) {
            // The published contract is /api/v1; the Console's pages are not API operations.
            if (!rule.mapping().startsWith("/api/")) continue;
            needs.put(rule.key(), rule.need().name() + (rule.permission() == null ? "" : ":" + rule.permission().name()));
        }
        return needs;
    }

    @LocalServerPort
    int port;
    @Autowired
    TestIdentities identities;
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mappings;

    @Test
    void everyEndpointHasAnExplicitAuthorizationDecision() {
        Set<String> endpoints = mappings.getHandlerMethods().keySet().stream()
                .flatMap(info -> info.getPatternValues().stream().filter(pattern -> !pattern.startsWith("/error"))
                        .flatMap(pattern -> info.getMethodsCondition().getMethods().stream()
                                .map(method -> method.name() + " " + pattern)))
                .collect(Collectors.toCollection(TreeSet::new));

        assertThat(endpoints).isNotEmpty()
                .isEqualTo(MATRIX.stream().map(Rule::key).collect(Collectors.toCollection(TreeSet::new)));
    }

    @Test
    void everyRoleAndCredentialTypeIsAllowedExactlyWhatTheMatrixGrants() {
        List<Client> clients = new ArrayList<>();
        Map<String, String> usernames = new java.util.HashMap<>();
        clients.add(new Client("anonymous", new IdentityHttp(port), null, false, false));
        for (WorkspaceRole role : WorkspaceRole.values()) {
            boolean organizationAdmin = role == WorkspaceRole.ADMIN;
            Member member = identities.create(WORKSPACE, role, PASSWORD, organizationAdmin ? "ADMIN" : "MEMBER");
            clients.add(new Client(role + " token", new IdentityHttp(port).bearer(member.token()), role, false, false));
            var browser = new IdentityHttp(port);
            assertThat(browser.login(member.username(), PASSWORD).status()).isEqualTo(200);
            clients.add(new Client(role + " session", browser, role, true, organizationAdmin));
            usernames.put(role + " session", member.username());
        }

        Map<String, String> mismatches = new LinkedHashMap<>();
        for (Rule rule : MATRIX) {
            for (String path : rule.examples()) {
                for (Client client : clients) {
                    var response = client.http().send(rule.method(), path, rule.body());
                    boolean expected = allowed(rule, client);
                    boolean console = path.startsWith("/console");
                    // The Console answers a browser: sign-in for the anonymous, an HTML page for a refused role.
                    boolean denied = console
                            ? client.role() == null
                                    ? response.status() == 302 && String.valueOf(response.header("Location"))
                                            .endsWith("/console/login")
                                    : response.status() == 403 && response.raw().contains("Access denied")
                            : client.role() == null
                                    ? response.status() == 401 && "AUTHENTICATION_REQUIRED".equals(response.code())
                                    : response.status() == 403 && "ACCESS_DENIED".equals(response.code());
                    boolean unauthenticated = client.role() != null && response.status() == 401
                            && !"/api/v1/auth/login".equals(path) && !"/console/login".equals(path);
                    // An allowed Console request is served by its handler: never a security refusal or a failure.
                    if (console && expected && !denied && (response.status() >= 500 || response.raw().contains(
                            "CSRF_TOKEN_INVALID") || (response.status() == 403 && !response.raw().contains("<")))) {
                        mismatches.put(client.name() + " " + rule.method() + " " + path, "allowed but not served: "
                                + response.status() + " " + response.code());
                    }
                    if (denied == expected || unauthenticated) {
                        mismatches.put(client.name() + " " + rule.method() + " " + path,
                                "expected " + (expected ? "allowed" : "denied") + " but got " + response.status() + " "
                                        + response.code());
                    }
                }
            }
            if ("/console/logout".equals(rule.mapping())) {
                for (Client client : clients) {
                    if (client.session()) assertThat(client.http().login(usernames.get(client.name()), PASSWORD)
                            .status()).isEqualTo(200);
                }
            }
        }
        assertThat(mismatches).isEmpty();
    }

    @Test
    void unlistedMethodsAndPathsAreDeniedEvenToAdministrators() {
        Member admin = identities.member(WORKSPACE, WorkspaceRole.ADMIN);
        var cli = new IdentityHttp(port).bearer(admin.token());

        for (var response : List.of(cli.send("PATCH", "/api/v1/agents", UNREADABLE),
                cli.send("DELETE", "/api/v1/agents/" + ID, null), cli.get("/api/v1/unknown"),
                cli.get("/actuator/env"), cli.send("HEAD", "/api/v1/agents", null))) {
            assertThat(response.status()).isEqualTo(403);
        }
        assertThat(new IdentityHttp(port).get("/api/v1/unknown").status()).isEqualTo(401);
    }

    @Test
    void crossOriginRequestsReceiveNoCorsGrantAndResponsesCarrySecurityHeaders() {
        Member admin = identities.member(WORKSPACE, WorkspaceRole.ADMIN);
        var preflight = new IdentityHttp(port).send("OPTIONS", "/api/v1/agents", null, Map.of(
                "Origin", "https://attacker.example", "Access-Control-Request-Method", "POST",
                "Access-Control-Request-Headers", "authorization,content-type"));
        var simple = new IdentityHttp(port).bearer(admin.token()).send("GET", "/api/v1/agents", null,
                Map.of("Origin", "https://attacker.example"));
        var health = new IdentityHttp(port).get("/actuator/health");

        assertThat(preflight.status()).isIn(401, 403);
        assertThat(preflight.header("Access-Control-Allow-Origin")).isNull();
        assertThat(simple.header("Access-Control-Allow-Origin")).isNull();
        assertThat(simple.header("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(simple.header("X-Frame-Options")).isEqualTo("DENY");
        assertThat(simple.header("Content-Security-Policy")).contains("default-src 'none'", "frame-ancestors 'none'");
        assertThat(simple.header("Referrer-Policy")).isEqualTo("no-referrer");
        assertThat(simple.header("Cache-Control")).contains("no-store");
        assertThat(health.status()).isEqualTo(200);
        assertThat(health.raw()).doesNotContain("components", "details");
    }

    private static boolean allowed(Rule rule, Client client) {
        if (rule.need() == Need.PUBLIC) return true;
        if (client.role() == null) return false;
        return switch (rule.need()) {
            case AUTHENTICATED -> true;
            case SESSION, ACTIVE_SESSION -> client.session();
            case ORGANIZATION_ADMIN -> client.session() && client.organizationAdmin();
            case SESSION_AND_PERMISSION -> client.session() && client.role().permissions().contains(rule.permission());
            case PERMISSION -> client.role().permissions().contains(rule.permission());
            case PUBLIC -> true;
        };
    }

    private static Rule rule(String method, String mapping, String body, Need need, WorkspacePermission permission) {
        String example = mapping.replace("{definitionId}", ID).replace("{versionId}", ID)
                .replace("{namespace}", "core").replace("{version}", "1.0.0")
                .replace("{name}", "latest").replace("{userId}", ID).replace("{environmentId}", ID)
                .replace("{deploymentId}", ID).replace("{interactionId}", ID).replace("{id}", ID);
        example = example.replace("{artifactId}", ID).replace("{pinId}", ID).replace("{approvalId}", ID)
                .replace("{sessionId}", ID).replace("{messageId}", ID).replace("{operationId}", ID)
                .replace("{workspaceId}", WORKSPACE.toString()).replace("{outboxId}", ID);
        return new Rule(method, mapping, List.of(example), body, need, permission);
    }
}
