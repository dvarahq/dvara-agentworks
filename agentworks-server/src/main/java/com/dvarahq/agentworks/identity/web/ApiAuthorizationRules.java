package com.dvarahq.agentworks.identity.web;

import com.dvarahq.agentworks.domain.identity.WorkspacePermission;
import jakarta.servlet.DispatcherType;
import org.springframework.security.authorization.AuthorityAuthorizationManager;
import org.springframework.security.authorization.AuthorizationManagers;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;

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
import static org.springframework.http.HttpMethod.DELETE;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpMethod.PUT;

/**
 * Deny-by-default request authorization for every Core endpoint. Each operation names the one workspace
 * permission (or session capability) it needs; anything not listed, including every endpoint added later without
 * a rule, is denied. Resource scope is separately bound to the principal's single workspace by the
 * {@code WorkspaceScopeResolver}, so a permission never reaches another workspace's resources.
 */
public final class ApiAuthorizationRules {
    private static final String API = "/api/v1";

    private ApiAuthorizationRules() {}

    public static void apply(
            AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry rules) {
        rules.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll();
        rules.requestMatchers(GET, "/actuator/health", "/actuator/health/**").permitAll();
        // Metrics contain no tenant identity labels, but scraping still requires an authenticated operator token.
        rules.requestMatchers(GET, "/actuator/prometheus").hasAuthority(AgentWorksAuthentication.AUTHENTICATED);
        // The published OpenAPI description of this contract (#35); it grants nothing.
        rules.requestMatchers(GET, API + "/openapi.yaml").permitAll();
        // Provider callbacks authenticate with a per-operation HMAC-derived token at the controller boundary.
        rules.requestMatchers(POST, API + "/operation-callbacks/*/*").permitAll();

        // Identity: public sign-in and bootstrap; session self-service; organization user administration.
        rules.requestMatchers(POST, API + "/auth/login", API + "/auth/bootstrap").permitAll();
        rules.requestMatchers(GET, API + "/auth/session").hasAuthority(AgentWorksAuthentication.AUTHENTICATED);
        rules.requestMatchers(POST, API + "/auth/logout", API + "/auth/password")
                .hasAuthority(AgentWorksAuthentication.SESSION);
        rules.requestMatchers(GET, API + "/auth/workspaces", API + "/auth/sessions")
                .hasAuthority(AgentWorksAuthentication.ACTIVE_SESSION);
        rules.requestMatchers(PUT, API + "/auth/session/workspace").hasAuthority(AgentWorksAuthentication.ACTIVE_SESSION);
        rules.requestMatchers(DELETE, API + "/auth/sessions/*").hasAuthority(AgentWorksAuthentication.ACTIVE_SESSION);
        rules.requestMatchers(GET, API + "/users").hasAuthority(AgentWorksAuthentication.ORGANIZATION_ADMIN);
        rules.requestMatchers(POST, API + "/users", API + "/users/*/password-reset", API + "/users/*/disable",
                API + "/users/*/enable").hasAuthority(AgentWorksAuthentication.ORGANIZATION_ADMIN);

        // Workspace membership and API tokens. Only an interactive session may create a token.
        rules.requestMatchers(GET, API + "/members").hasAuthority(permission(MEMBER_ADMIN));
        // Granting roles needs an interactive session, so a leaked token cannot enrol a lasting account.
        rules.requestMatchers(PUT, API + "/members/*").access(AuthorizationManagers.allOf(
                AuthorityAuthorizationManager.hasAuthority(AgentWorksAuthentication.ACTIVE_SESSION),
                AuthorityAuthorizationManager.hasAuthority(permission(MEMBER_ADMIN))));
        rules.requestMatchers(DELETE, API + "/members/*").access(AuthorizationManagers.allOf(
                AuthorityAuthorizationManager.hasAuthority(AgentWorksAuthentication.ACTIVE_SESSION),
                AuthorityAuthorizationManager.hasAuthority(permission(MEMBER_ADMIN))));
        rules.requestMatchers(POST, API + "/tokens").access(AuthorizationManagers.allOf(
                AuthorityAuthorizationManager.hasAuthority(AgentWorksAuthentication.ACTIVE_SESSION),
                AuthorityAuthorizationManager.hasAuthority(permission(TOKEN_SELF))));
        rules.requestMatchers(GET, API + "/tokens").hasAuthority(permission(TOKEN_SELF));
        rules.requestMatchers(DELETE, API + "/tokens/*").hasAuthority(permission(TOKEN_SELF));
        // Redacted administrative audit is readable by every workspace reader; credential ids and addresses stay hidden.
        rules.requestMatchers(GET, API + "/administrative-audit").hasAuthority(permission(WORKSPACE_READ));
        rules.requestMatchers(GET, API + "/diagnostics/support-bundle").hasAuthority(permission(WORKSPACE_READ));

        // Agent definitions and versions.
        rules.requestMatchers(GET, API + "/agents", API + "/agents/*", API + "/agents/*/versions",
                API + "/agents/*/versions/*").hasAuthority(permission(WORKSPACE_READ));
        rules.requestMatchers(POST, API + "/agents", API + "/agents/*/versions").hasAuthority(permission(AGENT_WRITE));

        // Deterministic engineering evaluations: authors define and execute; readers inspect immutable evidence.
        rules.requestMatchers(GET, API + "/evaluation-datasets", API + "/evaluation-datasets/*",
                API + "/evaluators", API + "/evaluators/*", API + "/evaluation-runs/*",
                API + "/evaluation-runs/*/report", API + "/evaluation-baselines",
                API + "/evaluation-baselines/*/comparison").hasAuthority(permission(WORKSPACE_READ));
        rules.requestMatchers(POST, API + "/evaluation-datasets", API + "/evaluation-datasets/purge-expired",
                API + "/evaluators", API + "/evaluation-runs",
                API + "/evaluation-runs/*/cancel", API + "/evaluation-runs/*/resume",
                API + "/evaluation-baselines").hasAuthority(permission(AGENT_WRITE));

        // Capability catalog. Discovery is schema-free; exact detail and every mutation are author operations.
        rules.requestMatchers(GET, API + "/tools", API + "/capability-packs/*/versions/*")
                .hasAuthority(permission(WORKSPACE_READ));
        rules.requestMatchers(GET, API + "/mcp-connections").hasAuthority(permission(WORKSPACE_READ));
        rules.requestMatchers(GET, API + "/tools/*/*/versions/*").hasAuthority(permission(AGENT_WRITE));
        rules.requestMatchers(POST, API + "/tools", API + "/tools/*/*/versions/*/lifecycle",
                API + "/capability-packs", API + "/capability-packs/*/versions/*/lifecycle")
                .hasAuthority(permission(AGENT_WRITE));

        // Remembered tool permissions expose policy identity and are managed only by security administrators.
        rules.requestMatchers(GET, API + "/remembered-permissions", API + "/remembered-permissions/*")
                .hasAuthority(permission(TOOL_PERMISSION_ADMIN));
        rules.requestMatchers(POST, API + "/remembered-permissions", API + "/remembered-permissions/*/revoke")
                .hasAuthority(permission(TOOL_PERMISSION_ADMIN));

        // Releases and aliases.
        rules.requestMatchers(POST, API + "/agent-versions/*/releases").hasAuthority(permission(RELEASE_WRITE));
        rules.requestMatchers(POST, API + "/releases/*/bundle", API + "/releases/*/bundle/anchor",
                API + "/release-bundles/import").hasAuthority(permission(RELEASE_WRITE));
        rules.requestMatchers(POST, API + "/release-bundles/verify").hasAuthority(permission(WORKSPACE_READ));
        rules.requestMatchers(PUT, API + "/release-aliases/*").hasAuthority(permission(RELEASE_WRITE));
        rules.requestMatchers(POST, API + "/releases/*/suspend", API + "/releases/*/resume",
                API + "/releases/*/revoke", API + "/releases/*/retire").hasAuthority(permission(RELEASE_ADMIN));
        rules.requestMatchers(GET, API + "/releases", API + "/releases/*", API + "/releases/*/manifest",
                API + "/releases/*/bundle", API + "/releases/*/lifecycle", API + "/release-revocations",
                API + "/release-aliases/*").hasAuthority(permission(WORKSPACE_READ));

        // Environments and promotion policies.
        rules.requestMatchers(POST, API + "/environments").hasAuthority(permission(ENVIRONMENT_ADMIN));
        rules.requestMatchers(PUT, API + "/environments/*/promotion-policy").hasAuthority(permission(ENVIRONMENT_ADMIN));
        rules.requestMatchers(GET, API + "/environments", API + "/environments/*",
                API + "/environments/*/promotion-policy").hasAuthority(permission(WORKSPACE_READ));

        // Promotion, rollback, deployments, routing, decisions, and cohorts.
        rules.requestMatchers(POST, API + "/environments/*/promotions", API + "/deployments/*/rollback",
                API + "/cohorts/*/suspend", API + "/cohorts/*/resume", API + "/cohorts/*/revoke",
                API + "/cohorts/*/retire", API + "/cohorts/*/quiescence",
                API + "/outbox-quarantines/*/release").hasAuthority(permission(DEPLOYMENT_OPERATE));
        rules.requestMatchers(POST, API + "/environments/*/promotion-approvals")
                .hasAuthority(permission(DEPLOYMENT_OPERATE));
        rules.requestMatchers(POST, API + "/environments/*/promotion-approvals/*/approve")
                .hasAuthority(permission(ENVIRONMENT_ADMIN));
        rules.requestMatchers(GET, API + "/environments/*/promotion-approvals/*")
                .hasAuthority(permission(WORKSPACE_READ));
        rules.requestMatchers(GET, API + "/deployments", API + "/deployments/*", API + "/routing-generations/*/targets",
                API + "/decisions/*", API + "/cohorts/*", API + "/outbox-quarantines")
                .hasAuthority(permission(WORKSPACE_READ));

        // Runs.
        rules.requestMatchers(POST, API + "/deployments/*/runs", API + "/runs/*/cancel", API + "/runs/*/pause",
                        API + "/runs/*/resume", API + "/runs/*/replay", API + "/interactions/*/expire",
                        API + "/interactions/*/cancel", API + "/interactions/*/revoke", API + "/sessions",
                        API + "/sessions/*/messages", API + "/operations/*/cancel")
                .hasAuthority(permission(RUN_ADMIT));
        // Run reads, including the reconnectable event stream (#35), which is re-authorized while it is open.
        rules.requestMatchers(GET, API + "/runs", API + "/runs/*", API + "/runs/*/events", API + "/runs/*/context",
                API + "/runs/*/steps", API + "/runs/*/replay/plan",
                API + "/runs/*/stream", API + "/sessions", API + "/sessions/*",
                API + "/sessions/*/messages", API + "/sessions/*/messages/*", API + "/operations",
                API + "/operations/*").hasAuthority(permission(WORKSPACE_READ));

        // Human interactions: workspace permission is the outer gate; the durable assignment is the exact gate.
        rules.requestMatchers(GET, API + "/interactions", API + "/interactions/*")
                .hasAuthority(permission(WORKSPACE_READ));
        rules.requestMatchers(POST, API + "/interactions/*/response")
                .hasAuthority(permission(INTERACTION_RESPOND));

        // Artifact content is always tenant-resolved; lifecycle mutation is limited to artifact administrators.
        rules.requestMatchers(GET, API + "/artifacts", API + "/artifacts/*", API + "/artifacts/*/content",
                API + "/artifacts/*/pins").hasAuthority(permission(WORKSPACE_READ));
        rules.requestMatchers(POST, API + "/artifacts").hasAuthority(permission(ARTIFACT_WRITE));
        rules.requestMatchers(POST, API + "/artifacts/*/expire", API + "/artifacts/*/validation",
                API + "/artifacts/*/pins").hasAuthority(permission(ARTIFACT_ADMIN));
        rules.requestMatchers(DELETE, API + "/artifacts/*/pins/*").hasAuthority(permission(ARTIFACT_ADMIN));

        console(rules);
        rules.anyRequest().denyAll();
    }

    /**
     * The Web Console (issue #36). It serves browser sessions only, so every page and action needs an interactive
     * session, plus exactly the permission of the API operations it calls. These rules are the permission gate for
     * those in-process calls (the REST controllers re-apply the workspace scope, not the permission), so a Console
     * handler must never call an operation that needs more than its rule grants. Sign-in and the vendored assets are public; the password page needs any session, since it is where a
     * pending password change leads.
     */
    private static void console(
            AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry rules) {
        rules.requestMatchers(GET, "/console/login", "/console/assets/**").permitAll();
        rules.requestMatchers(POST, "/console/login").permitAll();
        rules.requestMatchers(GET, "/console/password").hasAuthority(AgentWorksAuthentication.SESSION);
        rules.requestMatchers(POST, "/console/password", "/console/logout").hasAuthority(AgentWorksAuthentication.SESSION);
        rules.requestMatchers(POST, "/console/workspace").hasAuthority(AgentWorksAuthentication.ACTIVE_SESSION);
        rules.requestMatchers(GET, "/console", "/console/dashboard", "/console/agents", "/console/agents/*", "/console/releases",
                "/console/releases/*", "/console/environments/*", "/console/features", "/console/audit",
                "/console/settings", "/console/diagnostics",
                "/console/deployments", "/console/deployments/*", "/console/runs", "/console/runs/*",
                "/console/runs/*/summary", "/console/runs/*/actions", "/console/interactions",
                "/console/interactions/*", "/console/sessions", "/console/sessions/*", "/console/operations",
                "/console/operations/*", "/console/artifacts", "/console/artifacts/*", "/console/mcp")
                .access(consoleSession(WORKSPACE_READ));
        rules.requestMatchers(POST, "/console/agents", "/console/agents/*/versions").access(consoleSession(AGENT_WRITE));
        rules.requestMatchers(POST, "/console/agent-versions/*/releases").access(consoleSession(RELEASE_WRITE));
        rules.requestMatchers(POST, "/console/releases/*/bundle").access(consoleSession(RELEASE_WRITE));
        rules.requestMatchers(POST, "/console/releases/*/suspend", "/console/releases/*/resume",
                "/console/releases/*/revoke", "/console/releases/*/retire")
                .access(consoleSession(RELEASE_ADMIN));
        rules.requestMatchers(POST, "/console/environments").access(consoleSession(ENVIRONMENT_ADMIN));
        rules.requestMatchers(POST, "/console/environments/*/policy").access(consoleSession(ENVIRONMENT_ADMIN));
        rules.requestMatchers(POST, "/console/promotions").access(consoleSession(DEPLOYMENT_OPERATE));
        rules.requestMatchers(POST, "/console/deployments/*/cohorts/*/suspend",
                "/console/deployments/*/cohorts/*/resume", "/console/deployments/*/cohorts/*/revoke",
                "/console/deployments/*/cohorts/*/retire", "/console/deployments/*/cohorts/*/quiescence")
                .access(consoleSession(DEPLOYMENT_OPERATE));
        rules.requestMatchers(POST, "/console/deployments/*/runs", "/console/runs/*/cancel",
                "/console/runs/*/pause", "/console/runs/*/resume")
                .access(consoleSession(RUN_ADMIT));
        rules.requestMatchers(POST, "/console/interactions/*/response").access(consoleSession(INTERACTION_RESPOND));
        rules.requestMatchers(POST, "/console/operations/*/cancel").access(consoleSession(RUN_ADMIT));
        rules.requestMatchers(POST, "/console/artifacts").access(consoleSession(ARTIFACT_WRITE));
        rules.requestMatchers(POST, "/console/artifacts/*/expire", "/console/artifacts/*/validation",
                "/console/artifacts/*/pins", "/console/artifacts/*/pins/*/remove")
                .access(consoleSession(ARTIFACT_ADMIN));
    }

    private static org.springframework.security.authorization.AuthorizationManager<
            org.springframework.security.web.access.intercept.RequestAuthorizationContext> consoleSession(
            WorkspacePermission permission) {
        return AuthorizationManagers.allOf(
                AuthorityAuthorizationManager.hasAuthority(AgentWorksAuthentication.ACTIVE_SESSION),
                AuthorityAuthorizationManager.hasAuthority(permission(permission)));
    }

    static String permission(WorkspacePermission permission) {
        return AgentWorksAuthentication.permission(permission);
    }
}
