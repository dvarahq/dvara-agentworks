package com.dvarahq.agentworks.console.web;

import com.dvarahq.agentworks.domain.identity.WorkspaceRole;
import com.dvarahq.agentworks.domain.run.ClaimScope;
import com.dvarahq.agentworks.domain.run.RunLease;
import com.dvarahq.agentworks.domain.run.RunEventType;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import com.dvarahq.agentworks.DvaraAgentWorksApplication;
import com.dvarahq.agentworks.identity.TestIdentities;
import com.dvarahq.agentworks.identity.web.ConsoleAccess;
import com.dvarahq.agentworks.identity.web.IdentityHttp;
import com.dvarahq.agentworks.identity.web.IdentityTestConfiguration;
import com.dvarahq.agentworks.interaction.HumanInteractionAssignment;
import com.dvarahq.agentworks.interaction.HumanInteractionDraft;
import com.dvarahq.agentworks.interaction.HumanInteractionPrompt;
import com.dvarahq.agentworks.runplane.execution.DurableHumanInteractionCoordinator;
import com.dvarahq.agentworks.runplane.execution.DurableRunEngine;
import com.dvarahq.agentworks.runplane.execution.DurableRunEngine.EventDraft;
import com.dvarahq.agentworks.runplane.execution.ReleaseManifestReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.dvarahq.agentworks.identity.web.IdentityTestConfiguration.PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Web Console (issue #36) on the shipped application over real HTTP: sign-in and session handling, rendering of
 * API-created resources, CSRF and cross-origin refusal of every action, authorization equal to the API's, output
 * escaping of names, event payloads, and error details, workspace isolation and switching, the Content-Security-
 * Policy, vendored asset integrity, and accessibility basics.
 */
@SpringBootTest(classes = DvaraAgentWorksApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"agentworks.artifacts.enabled=true",
                "agentworks.artifacts.root=${java.io.tmpdir}/agentworks-console-test-artifacts"})
@Import(IdentityTestConfiguration.class)
@Tag("integration")
class ConsoleWebTest {
    private static final String XSS = "<script>alert('x')</script><img src=x onerror=alert(1)>";

    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TestIdentities identities;
    @Autowired
    DurableRunEngine engine;
    @Autowired
    DurableHumanInteractionCoordinator interactions;
    private UUID workspace;

    @BeforeEach
    void setUp() {
        workspace = createWorkspace("console-" + UUID.randomUUID());
    }

    @Test
    void signInRendersThePagesAndSignOutEndsTheSession() {
        var admin = identities.create(workspace, WorkspaceRole.ADMIN, PASSWORD, "MEMBER");
        ConsoleBrowser browser = new ConsoleBrowser(port);

        ConsoleBrowser.Page login = browser.get("/console/login");
        assertThat(login.status()).isEqualTo(200);
        assertThat(login.header("Content-Security-Policy")).isEqualTo(ConsoleAccess.CSP);
        assertThat(browser.signIn(admin.username(), "not the password").status()).isEqualTo(401);
        assertThat(browser.cookie()).isNull();

        ConsoleBrowser.Page signedIn = browser.signIn(admin.username(), PASSWORD);
        assertThat(signedIn.status()).isEqualTo(303);
        assertThat(signedIn.redirect()).isEqualTo("/console/dashboard");
        assertThat(browser.cookie()).isNotNull();
        for (String path : List.of("/console/dashboard", "/console/runs", "/console/agents", "/console/releases",
                "/console/deployments", "/console/artifacts", "/console/mcp", "/console/features",
                "/console/audit", "/console/settings", "/console/diagnostics")) {
            ConsoleBrowser.Page page = browser.open(path);
            assertThat(page.status()).as(path).isEqualTo(200);
            assertThat(page.header("Content-Type")).startsWith("text/html");
            assertThat(page.header("Content-Security-Policy")).isEqualTo(ConsoleAccess.CSP);
            assertThat(page.header("Cache-Control")).contains("no-store");
            assertThat(page.body()).contains(admin.username());
        }
        assertThat(browser.csrf()).isNotBlank();

        ConsoleBrowser.Page signedOut = browser.action("/console/logout", Map.of());
        assertThat(signedOut.redirect()).isEqualTo("/console/login");
        assertThat(browser.cookie()).isNull();
        assertThat(browser.get("/console/agents").redirect()).isEqualTo("/console/login");
    }

    @Test
    void completeConsoleSurfacesCanonicalLifecycleContextPolicyAndAudit() {
        var admin = identities.create(workspace, WorkspaceRole.ADMIN, PASSWORD, "MEMBER");
        var api = new IdentityHttp(port).bearer(admin.token());
        RunFixture fixture = new RunFixture(api);
        UUID release = jdbc.queryForObject("select id from agent_release where workspace_id = ?", UUID.class,
                workspace);
        UUID environment = jdbc.queryForObject("select id from agent_environment where workspace_id = ?", UUID.class,
                workspace);
        ConsoleBrowser browser = signedIn(admin.username());
        UUID run = fixture.launch(browser);

        String releasePage = browser.open("/console/releases/" + release).body();
        assertThat(releasePage).contains("Signed evidence and ABOM", "Manifest", release.toString());
        long lifecycleVersion = api.get("/api/v1/releases/" + release + "/lifecycle").json().path("version").asLong();
        ConsoleBrowser.Page suspended = browser.action("/console/releases/" + release + "/suspend", Map.of(
                "version", Long.toString(lifecycleVersion), "idempotencyKey", UUID.randomUUID().toString(),
                "reason", "operator verification"));
        assertThat(suspended.redirect()).isEqualTo("/console/releases/" + release);
        assertThat(browser.open("/console/releases/" + release).body()).contains("SUSPENDED");
        assertThat(browser.open("/console/deployments/" + fixture.deployment()).body())
                .contains("Placement and exposure", "current revision", "SUSPENDED", "Resume");

        String environmentPage = browser.open("/console/environments/" + environment).body();
        assertThat(environmentPage).contains("Promotion policy", "continuityAction");
        long policyVersion = api.get("/api/v1/environments/" + environment).json().path("policyVersion").asLong();
        ConsoleBrowser.Page policy = browser.action("/console/environments/" + environment + "/policy", Map.of(
                "version", Long.toString(policyVersion), "policy",
                "{\"gates\":[],\"drainDeadline\":\"PT1M\",\"continuityAction\":\"FAIL\",\"overridesAllowed\":false}"));
        assertThat(policy.redirect()).isEqualTo("/console/environments/" + environment);

        String runPage = browser.open("/console/runs/" + run).body();
        assertThat(runPage).contains("Context decisions", "Pause safely");
        ConsoleBrowser.Page paused = browser.action("/console/runs/" + run + "/pause",
                Map.of("reason", "inspect safely"));
        assertThat(paused.redirect()).isEqualTo("/console/runs/" + run);
        assertThat(browser.open("/console/runs/" + run).body()).contains("PAUSED", "Resume run");
        assertThat(browser.action("/console/runs/" + run + "/resume", Map.of()).redirect())
                .isEqualTo("/console/runs/" + run);

        assertThat(browser.open("/console/audit").body())
                .contains("RELEASE_SUSPENDED", "PROMOTION_POLICY_CHANGED", "RUN_PAUSE_REQUESTED");
        assertThat(browser.open("/console/features").body())
                .contains("Evaluation datasets", "Capabilities and tools", "Artifact compute", "Continuity");
        assertThat(browser.open("/console/settings").body()).contains(admin.username(), "Your API tokens");
        assertThat(browser.open("/console/diagnostics").body()).contains("Console connected.", run.toString());
    }

    /** Signing in again, for example as someone else, while the browser still holds a session (review of #36). */
    @Test
    void signingInWhileHoldingASessionReplacesItAndRevokesTheOldOne() {
        var viewer = identities.create(workspace, WorkspaceRole.VIEWER, PASSWORD, "MEMBER");
        var admin = identities.create(workspace, WorkspaceRole.ADMIN, PASSWORD, "MEMBER");
        ConsoleBrowser browser = signedIn(viewer.username());
        String old = browser.cookie();

        ConsoleBrowser.Page again = browser.signIn(admin.username(), PASSWORD);
        assertThat(again.status()).as(again.body()).isEqualTo(303);
        assertThat(browser.cookie()).isNotEqualTo(old);
        assertThat(browser.open("/console/agents").body()).contains(admin.username());
        ConsoleBrowser stale = new ConsoleBrowser(port);
        stale.cookie(old);
        assertThat(stale.get("/console/agents").redirect()).isEqualTo("/console/login");
    }

    /** The promotion form offers a release chosen elsewhere, and never defaults to one (review of #36). */
    @Test
    void thePromotionFormOffersTheChosenReleaseAndNeverDefaultsToAnother() {
        var admin = identities.create(workspace, WorkspaceRole.ADMIN, PASSWORD, "MEMBER");
        var api = new IdentityHttp(port).bearer(admin.token());
        new RunFixture(api);
        String chosen = jdbc.queryForObject("select id from agent_release where workspace_id = ?", UUID.class,
                workspace).toString();
        ConsoleBrowser browser = signedIn(admin.username());

        String unchosen = browser.open("/console/deployments").body();
        assertThat(unchosen).contains("<option value=\"\" disabled selected=\"selected\">Choose a release</option>");
        String preselected = browser.open("/console/deployments?release=" + chosen).body();
        assertThat(preselected).containsPattern("<option value=\"" + chosen + "\"\\s+selected=\"selected\">");
        ConsoleBrowser.Page blank = browser.action("/console/promotions", Map.of("environmentId",
                UUID.randomUUID().toString(), "deployment", "x", "releaseId", ""));
        assertThat(blank.status()).isEqualTo(422);
        assertThat(blank.body()).contains("Choose a release");
    }

    /**
     * A form submitted without its script (review of #36) is a plain POST without the CSRF header: it is refused and
     * changes nothing. A URL carrying passwords is refused and never echoed.
     */
    @Test
    void aFormSubmittedWithoutItsScriptIsRefusedAndPasswordsNeverTravelInAUrl() {
        var member = identities.create(workspace, WorkspaceRole.VIEWER, PASSWORD, "MEMBER");
        ConsoleBrowser browser = signedIn(member.username());
        browser.open("/console/password");
        ConsoleBrowser.Page nativePost = browser.form("POST", "/console/password", Map.of("currentPassword", PASSWORD,
                "newPassword", "another new passphrase 99"), false, Map.of("Origin", browser.origin));
        assertThat(nativePost.status()).isEqualTo(403);
        assertThat(nativePost.body()).contains("CSRF_TOKEN_INVALID").doesNotContain(PASSWORD);
        // The password is unchanged.
        assertThat(new ConsoleBrowser(port).signIn(member.username(), PASSWORD).status()).isEqualTo(303);

        ConsoleBrowser.Page inUrl = browser.get("/console/password?currentPassword=secret-value&newPassword=x");
        assertThat(inUrl.status()).isEqualTo(400);
        assertThat(inUrl.body()).contains("CREDENTIAL_IN_URL").doesNotContain("secret-value");
    }

    /** The run page's self-refreshing fragments render without the navigation or the run's immutable pins (review). */
    @Test
    void theRunFragmentsRenderOnlyWhatRefreshes() {
        var admin = identities.create(workspace, WorkspaceRole.ADMIN, PASSWORD, "MEMBER");
        var api = new IdentityHttp(port).bearer(admin.token());
        ConsoleBrowser browser = signedIn(admin.username());
        UUID run = new RunFixture(api).launch(browser);

        ConsoleBrowser.Page summary = browser.form("GET", "/console/runs/" + run + "/summary", Map.of(), true, Map.of());
        assertThat(summary.status()).isEqualTo(200);
        assertThat(summary.body()).contains("id=\"run-summary\"").contains("QUEUED").doesNotContain("Pinned identity")
                .doesNotContain("navbar");
        ConsoleBrowser.Page actions = browser.form("GET", "/console/runs/" + run + "/actions", Map.of(), true, Map.of());
        assertThat(actions.status()).isEqualTo(200);
        assertThat(actions.body()).contains("Cancel run").doesNotContain("navbar");
        String page = browser.open("/console/runs/" + run).body();
        assertThat(page).contains("data-complete=\"false\"").contains("data-terminal-lifecycles=\"SUCCEEDED,FAILED,CANCELLED\"");
    }

    /** A run an operator paused through the API (#183) shows its pause, and is still followed live (not terminal). */
    @Test
    void aPausedRunShowsItsPauseInTheLifecycleAndTheTimeline() {
        var admin = identities.create(workspace, WorkspaceRole.ADMIN, PASSWORD, "MEMBER");
        var api = new IdentityHttp(port).bearer(admin.token());
        ConsoleBrowser browser = signedIn(admin.username());
        UUID run = new RunFixture(api).launch(browser);
        var paused = api.send("POST", "/api/v1/runs/" + run + "/pause", Map.of("reason", "maintenance"));
        assertThat(paused.status()).as(paused.raw()).isEqualTo(200);

        String page = browser.open("/console/runs/" + run).body();
        assertThat(page).contains("PAUSED").contains("RUN_PAUSED").contains("data-terminal=\"false\"")
                .contains("data-complete=\"false\"").contains("Cancel run");
    }

    @Test
    void anAssignedMemberListsReadsAndRespondsWithoutExposingTheAnswer() {
        var admin = identities.create(workspace, WorkspaceRole.ADMIN, PASSWORD, "MEMBER");
        var api = new IdentityHttp(port).bearer(admin.token());
        ConsoleBrowser browser = signedIn(admin.username());
        UUID runId = new RunFixture(api).launch(browser);
        UUID interactionId = block(runId, admin.userId());

        String listed = browser.open("/console/interactions?status=OPEN").body();
        assertThat(listed).contains("Private question", interactionId.toString(), "OPEN");
        String detail = browser.open("/console/interactions/" + interactionId).body();
        assertThat(detail).contains("Supply a private value", "QUESTION", "Submit response")
                .doesNotContain("console-secret", "interaction-idempotency");

        ConsoleBrowser.Page responded = browser.action("/console/interactions/" + interactionId + "/response",
                Map.of("answer", "console-secret"));
        assertThat(responded.status()).as(responded.body()).isEqualTo(200);
        assertThat(responded.redirect()).isEqualTo("/console/interactions/" + interactionId);
        assertThat(browser.open(responded.redirect()).body()).contains("RESPONDED", "Answer recorded (redacted)")
                .doesNotContain("console-secret");
        assertThat(engine.run(new WorkspaceId(workspace), runId).lifecycle().name()).isEqualTo("QUEUED");
    }

    @Test
    void anAnonymousRequestIsSentToSignInAndNeverShownData() {
        ConsoleBrowser anonymous = new ConsoleBrowser(port);
        ConsoleBrowser.Page page = anonymous.get("/console/agents");
        assertThat(page.status()).isEqualTo(302);
        assertThat(page.redirect()).isEqualTo("/console/login");
        ConsoleBrowser.Page action = anonymous.action("/console/agents", Map.of("name", "x"));
        assertThat(action.status()).isEqualTo(401);
        assertThat(action.header("HX-Redirect")).isEqualTo("/console/login");
        // Assets load without a session, so the sign-in page is styled.
        assertThat(anonymous.get("/console/assets/console.js").status()).isEqualTo(200);
    }

    @Test
    void everyActionNeedsTheSessionsCsrfTokenAndASameOriginRequest() {
        var author = identities.create(workspace, WorkspaceRole.AUTHOR, PASSWORD, "MEMBER");
        ConsoleBrowser browser = signedIn(author.username());
        browser.open("/console/agents");
        String token = browser.csrf();

        browser.csrf(null);
        assertThat(browser.action("/console/agents", Map.of("name", "no-token")).status()).isEqualTo(403);
        browser.csrf("forged-" + token);
        assertThat(browser.action("/console/agents", Map.of("name", "wrong-token")).status()).isEqualTo(403);
        browser.csrf(token);
        ConsoleBrowser.Page crossSite = browser.form("POST", "/console/agents", Map.of("name", "cross-site"), true,
                Map.of("Origin", "https://attacker.example"));
        assertThat(crossSite.status()).isEqualTo(403);
        assertThat(crossSite.body()).contains("CROSS_ORIGIN_REQUEST");
        ConsoleBrowser.Page fetchSite = browser.form("POST", "/console/agents", Map.of("name", "cross-site-2"), true,
                Map.of("Sec-Fetch-Site", "cross-site"));
        assertThat(fetchSite.status()).isEqualTo(403);
        assertThat(agentNames()).doesNotContain("no-token", "wrong-token", "cross-site", "cross-site-2");

        ConsoleBrowser.Page created = browser.action("/console/agents", Map.of("name", "with-token"));
        assertThat(created.status()).isEqualTo(200);
        assertThat(created.redirect()).startsWith("/console/agents/");
        assertThat(agentNames()).contains("with-token");

        // Sign-in has no token yet: a foreign Origin is refused.
        ConsoleBrowser foreign = new ConsoleBrowser(port);
        ConsoleBrowser.Page refused = foreign.form("POST", "/console/login", Map.of("username", author.username(),
                "password", PASSWORD), false, Map.of("Origin", "https://attacker.example"));
        assertThat(refused.status()).isEqualTo(403);
        assertThat(foreign.cookie()).isNull();
    }

    @Test
    void eachRoleMayDoExactlyWhatTheApiAllowsIt() {
        var viewer = identities.create(workspace, WorkspaceRole.VIEWER, PASSWORD, "MEMBER");
        ConsoleBrowser browser = signedIn(viewer.username());
        ConsoleBrowser.Page agents = browser.open("/console/agents");
        assertThat(agents.status()).isEqualTo(200);
        assertThat(agents.body()).doesNotContain("hx-post=\"/console/agents\"").contains("Your role cannot create agents");
        ConsoleBrowser.Page denied = browser.action("/console/agents", Map.of("name", "by-viewer"));
        assertThat(denied.status()).isEqualTo(403);
        assertThat(denied.body()).contains("ACCESS_DENIED");
        assertThat(browser.action("/console/promotions", Map.of()).status()).isEqualTo(403);
        assertThat(browser.action("/console/deployments/" + UUID.randomUUID() + "/runs", Map.of()).status())
                .isEqualTo(403);
        assertThat(agentNames()).doesNotContain("by-viewer");

        // An API token is not a Console session.
        var token = identities.create(workspace, WorkspaceRole.ADMIN);
        var cli = new IdentityHttp(port).bearer(token.token());
        assertThat(cli.send("GET", "/console/agents", null, Map.of("Accept", "text/html")).status()).isEqualTo(403);
    }

    @Test
    void namesEventTextAndErrorDetailsAreAlwaysEscaped() {
        var admin = identities.create(workspace, WorkspaceRole.ADMIN, PASSWORD, "MEMBER");
        var api = new IdentityHttp(port).bearer(admin.token());
        // Agent names are restricted to a safe pattern by the API; descriptions are free text.
        String definition = api.send("POST", "/api/v1/agents", Map.of("name", "escaped", "description", XSS)).json()
                .path("id").asString();
        assertThat(definition).isNotBlank();
        ConsoleBrowser browser = signedIn(admin.username());

        for (String path : List.of("/console/agents", "/console/agents/" + definition)) {
            String body = browser.open(path).body();
            assertThat(body).as(path).doesNotContain("<script>alert").doesNotContain("<img src=x")
                    .contains("&lt;script&gt;alert(");
        }
        // An AgentSpec refused with diagnostics that echo the document is shown escaped.
        ConsoleBrowser.Page refused = browser.action("/console/agents/" + definition + "/versions",
                Map.of("document", "apiVersion: dvarahq.com/v1alpha1\nkind: Agent\nmetadata: {name: a, version: 1.0.0}\n"
                        + "spec:\n  executor: {type: spring-ai}\n  \"" + XSS + "\": 1\n"));
        assertThat(refused.status()).isIn(400, 422);
        assertThat(refused.body()).contains("AGENT_SPEC_INVALID").doesNotContain("<script>alert")
                .doesNotContain("<img src=x");

        // Event text, as a model could produce it, is escaped in the server-rendered timeline.
        var scenario = new RunFixture(api);
        UUID run = scenario.launch(browser);
        var claimed = engine.claimNext("console-test", ClaimScope.any(ReleaseManifestReader.SUPPORTED_VERSIONS)
                .in(new WorkspaceId(workspace)), Duration.ofMinutes(1)).orElseThrow();
        assertThat(claimed.run().id()).isEqualTo(run);
        engine.append(claimed.lease(), List.of(EventDraft.of(RunEventType.MODEL_STREAM_CHUNK, Map.of("text", XSS))));
        String page = browser.open("/console/runs/" + run).body();
        assertThat(page).contains("MODEL_STREAM_CHUNK").doesNotContain("<script>alert").doesNotContain("<img src=x")
                .contains("&lt;script&gt;");
        // A cancellation reason typed by a user becomes event text as well.
        ConsoleBrowser.Page cancelled = browser.action("/console/runs/" + run + "/cancel", Map.of("reason", XSS));
        assertThat(cancelled.redirect()).isEqualTo("/console/runs/" + run);
        String after = browser.open("/console/runs/" + run).body();
        assertThat(after).contains("RUN_CANCELLED").doesNotContain("<script>alert").doesNotContain("<img src=x");
    }

    @Test
    void anotherWorkspacesResourcesAreNotFoundAndNeverListed() {
        var admin = identities.create(workspace, WorkspaceRole.ADMIN, PASSWORD, "MEMBER");
        var api = new IdentityHttp(port).bearer(admin.token());
        String definition = api.send("POST", "/api/v1/agents", Map.of("name", "private-agent")).json().path("id")
                .asString();
        UUID run = new RunFixture(api).launch(signedIn(admin.username()));

        UUID other = createWorkspace("other-" + UUID.randomUUID());
        var outsider = identities.create(other, WorkspaceRole.ADMIN, PASSWORD, "MEMBER");
        ConsoleBrowser browser = signedIn(outsider.username());
        assertThat(browser.open("/console/agents").body()).doesNotContain("private-agent");
        assertThat(browser.open("/console/runs").body()).doesNotContain(run.toString());
        for (String path : List.of("/console/agents/" + definition, "/console/runs/" + run)) {
            ConsoleBrowser.Page page = browser.open(path);
            assertThat(page.status()).as(path).isEqualTo(404);
            assertThat(page.body()).contains("NOT_FOUND").doesNotContain("private-agent");
        }
        ConsoleBrowser.Page cancel = browser.action("/console/runs/" + run + "/cancel", Map.of());
        assertThat(cancel.status()).isEqualTo(404);
        assertThat(api.get("/api/v1/runs/" + run).json().path("lifecycle").asString()).isEqualTo("QUEUED");
    }

    @Test
    void aUserSwitchesBetweenItsWorkspacesUnderANewSession() {
        var admin = identities.create(workspace, WorkspaceRole.ADMIN, PASSWORD, "MEMBER");
        UUID second = createWorkspaceInOrganization(workspace, "second-" + UUID.randomUUID());
        jdbc.update("insert into workspace_membership(organization_id, workspace_id, subject_id, membership_role, "
                + "created_at, updated_at) select organization_id, ?, ?, 'VIEWER', current_timestamp, current_timestamp "
                + "from workspace where id = ?", second, admin.userId(), second);
        var api = new IdentityHttp(port).bearer(admin.token());
        api.send("POST", "/api/v1/agents", Map.of("name", "first-workspace-agent"));
        ConsoleBrowser browser = signedIn(admin.username());
        // Which membership a sign-in selects first is the API's rule; start from the first workspace explicitly.
        browser.open("/console/agents");
        assertThat(browser.action("/console/workspace", Map.of("workspaceId", workspace.toString())).redirect())
                .isEqualTo("/console/dashboard");
        ConsoleBrowser.Page first = browser.open("/console/agents");
        assertThat(first.body()).contains("first-workspace-agent").contains(second.toString());
        String before = browser.cookie();

        ConsoleBrowser.Page switched = browser.action("/console/workspace", Map.of("workspaceId", second.toString()));
        assertThat(switched.redirect()).isEqualTo("/console/dashboard");
        assertThat(browser.cookie()).isNotEqualTo(before);
        ConsoleBrowser.Page after = browser.open("/console/agents");
        assertThat(after.body()).doesNotContain("first-workspace-agent").contains("VIEWER");
        // A workspace the user is no member of is refused.
        assertThat(browser.action("/console/workspace", Map.of("workspaceId", UUID.randomUUID().toString())).status())
                .isGreaterThanOrEqualTo(400);
    }

    @Test
    void aPendingPasswordChangeLeadsToThePasswordPageFirst() {
        var member = identities.create(workspace, WorkspaceRole.VIEWER, PASSWORD, "MEMBER");
        jdbc.update("update user_account set must_change_password = true where id = ?", member.userId());
        ConsoleBrowser browser = new ConsoleBrowser(port);
        assertThat(browser.signIn(member.username(), PASSWORD).redirect()).isEqualTo("/console/password");
        assertThat(browser.get("/console/runs").redirect()).isEqualTo("/console/password");
        browser.open("/console/password");
        ConsoleBrowser.Page changed = browser.action("/console/password", Map.of("currentPassword", PASSWORD,
                "newPassword", "a brand new passphrase 77"));
        assertThat(changed.redirect()).isEqualTo("/console/dashboard");
        assertThat(browser.open("/console/runs").status()).isEqualTo(200);
    }

    @Test
    void theVendoredAssetsMatchTheirPinnedIntegrityAndThePagesUseNoInlineScriptOrUnescapedOutput() throws Exception {
        String layout = resource("templates/console/layout.html");
        Matcher pinned = Pattern.compile("(?:href|src)=\"/console/assets/(vendor/[^\"]+)\"\\s+integrity=\"(sha384-[^\"]+)\"")
                .matcher(layout);
        int count = 0;
        while (pinned.find()) {
            count++;
            byte[] asset = new ClassPathResource("static/console/assets/" + pinned.group(1)).getContentAsByteArray();
            String integrity = "sha384-" + Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-384")
                    .digest(asset));
            assertThat(integrity).as(pinned.group(1)).isEqualTo(pinned.group(2));
            ConsoleBrowser.Page served = new ConsoleBrowser(port).get("/console/assets/" + pinned.group(1));
            assertThat(served.status()).isEqualTo(200);
        }
        assertThat(count).isEqualTo(2);

        for (String template : List.of("layout", "fragments", "login", "password", "error", "agents", "agent",
                "releases", "release", "deployments", "deployment", "environment", "runs", "run", "dashboard",
                "audit", "features", "settings", "diagnostics")) {
            String source = resource("templates/console/" + template + ".html");
            assertThat(source).as(template).doesNotContain("th:utext").doesNotContain("style=")
                    .doesNotContainPattern("<script(?![^>]*\\bsrc=)").doesNotContainPattern("\\son[a-z]+=")
                    .doesNotContain("javascript:");
            // A form that htmx posts must post without it too: never a GET that puts its fields in the URL (review).
            Matcher form = Pattern.compile("<form\\b[^>]*>", Pattern.DOTALL).matcher(source);
            while (form.find()) {
                String tag = form.group();
                if (!tag.contains("hx-post")) {
                    assertThat(tag).as(template + ": a form without hx-post is a GET filter or sign-in")
                            .containsPattern("method=\"(get|post)\"");
                    continue;
                }
                assertThat(tag).as(template + ": " + tag).contains("method=\"post\"").containsPattern("\\baction=");
            }
        }
        String script = resource("static/console/assets/console.js");
        assertThat(script).doesNotContain("innerHTML").doesNotContain("outerHTML").doesNotContain("insertAdjacentHTML")
                .doesNotContain("eval(").doesNotContain("new Function").doesNotContain("localStorage")
                .doesNotContain("sessionStorage");
    }

    @Test
    void everyFormControlHasALabelAndEveryPageHasItsLandmarks() {
        var admin = identities.create(workspace, WorkspaceRole.ADMIN, PASSWORD, "MEMBER");
        var api = new IdentityHttp(port).bearer(admin.token());
        String definition = api.send("POST", "/api/v1/agents", Map.of("name", "labelled")).json().path("id").asString();
        ConsoleBrowser browser = signedIn(admin.username());
        UUID run = new RunFixture(api).launch(browser);
        String deployment = api.get("/api/v1/runs/" + run).json().path("deploymentId").asString();

        for (String path : List.of("/console/login", "/console/runs", "/console/agents", "/console/agents/" + definition,
                "/console/releases", "/console/deployments", "/console/deployments/" + deployment,
                "/console/runs/" + run)) {
            String body = browser.open(path).body();
            assertThat(body).as(path).contains("<html lang=\"en\">").contains("<main id=\"main\"")
                    .containsPattern("<title>[^<]+</title>").containsPattern("<h1[ >]");
            if (!path.equals("/console/login")) assertThat(body).as(path).contains("href=\"#main\"");
            Matcher control = Pattern.compile("<(input|select|textarea)\\b([^>]*)>").matcher(body);
            while (control.find()) {
                String attributes = control.group(2);
                if (attributes.contains("type=\"hidden\"")) continue;
                Matcher id = Pattern.compile("\\bid=\"([^\"]+)\"").matcher(attributes);
                assertThat(id.find()).as(path + ": a form control without an id: " + control.group()).isTrue();
                assertThat(body).as(path + ": no label for " + id.group(1)).contains("for=\"" + id.group(1) + "\"");
            }
        }
    }

    private ConsoleBrowser signedIn(String username) {
        ConsoleBrowser browser = new ConsoleBrowser(port);
        assertThat(browser.signIn(username, PASSWORD).status()).isEqualTo(303);
        return browser;
    }

    private Set<String> agentNames() {
        return Set.copyOf(jdbc.queryForList("select name from agent_definition where workspace_id = ?", String.class,
                workspace));
    }

    private UUID createWorkspace(String name) {
        UUID organization = UUID.randomUUID();
        UUID created = UUID.randomUUID();
        jdbc.update("insert into organization(id, name) values (?, ?)", organization, "org-" + name);
        jdbc.update("insert into workspace(id, organization_id, name) values (?, ?, ?)", created, organization, name);
        return created;
    }

    private UUID createWorkspaceInOrganization(UUID sibling, String name) {
        UUID created = UUID.randomUUID();
        jdbc.update("insert into workspace(id, organization_id, name) select ?, organization_id, ? from workspace "
                + "where id = ?", created, name, sibling);
        return created;
    }

    private UUID block(UUID runId, UUID assignedUser) {
        var scope = ClaimScope.any(ReleaseManifestReader.SUPPORTED_VERSIONS).in(new WorkspaceId(workspace));
        var others = Set.copyOf(jdbc.queryForList("select id from agent_run where workspace_id = ? and id <> ?",
                UUID.class, workspace, runId));
        RunLease lease = engine.claimNext("console-interaction-test", scope.excluding(others), Duration.ofMinutes(1))
                .orElseThrow().lease();
        var run = engine.run(new WorkspaceId(workspace), runId);
        UUID id = UUID.randomUUID();
        interactions.block(lease, new HumanInteractionDraft(id, workspace, runId, run.pins().releaseId(),
                run.pins().deploymentId(), run.pins().revisionId(), run.pins().cohortId(), "console-correlation",
                "interaction-idempotency", new HumanInteractionPrompt.Question("Private question",
                "Supply a private value", "{\"type\":\"string\"}"),
                HumanInteractionAssignment.user(workspace, assignedUser), Duration.ofMinutes(5)));
        return id;
    }

    private static String resource(String path) throws IOException {
        return new String(new ClassPathResource(path).getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    /** A deployment of a release of a minimal agent, set up through the API; runs are launched through the Console. */
    private static final class RunFixture {
        private final IdentityHttp api;
        private final String deployment;

        RunFixture(IdentityHttp api) {
            this.api = api;
            String environment = api.send("POST", "/api/v1/environments", Map.of("name", "env-" + UUID.randomUUID()))
                    .json().path("id").asString();
            String name = "runner-" + UUID.randomUUID().toString().substring(0, 8);
            String definition = api.send("POST", "/api/v1/agents", Map.of("name", name)).json().path("id").asString();
            String version = api.send("POST", "/api/v1/agents/" + definition + "/versions",
                    "apiVersion: dvarahq.com/v1alpha1\nkind: Agent\nmetadata: {name: " + name + ", version: 1.0.0}\n"
                            + "spec:\n  executor: {type: spring-ai}\n", Map.of("Content-Type", "application/yaml"))
                    .json().path("id").asString();
            assertThat(version).as("version").isNotBlank();
            String release = api.send("POST", "/api/v1/agent-versions/" + version + "/releases",
                    Map.of("name", "release-" + UUID.randomUUID())).json().path("id").asString();
            this.deployment = api.send("POST", "/api/v1/environments/" + environment + "/promotions",
                    Map.of("deployment", "web", "releaseId", release)).json().path("deploymentId").asString();
            assertThat(deployment).as("deployment").isNotBlank();
        }

        UUID launch(ConsoleBrowser browser) {
            browser.open("/console/deployments/" + deployment);
            ConsoleBrowser.Page launched = browser.action("/console/deployments/" + deployment + "/runs",
                    Map.of("input", "{\"q\": 1}"));
            assertThat(launched.status()).as(launched.body()).isEqualTo(200);
            return UUID.fromString(launched.redirect().substring("/console/runs/".length()));
        }

        String deployment() {
            return deployment;
        }
    }
}
