package com.dvarahq.agentworks.identity.web;

import com.dvarahq.agentworks.domain.identity.WorkspaceRole;
import com.dvarahq.agentworks.DvaraAgentWorksApplication;
import com.dvarahq.agentworks.identity.TestIdentities;
import com.dvarahq.agentworks.identity.TestIdentities.Member;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.UUID;

import static com.dvarahq.agentworks.identity.web.IdentityTestConfiguration.PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Workspace membership administration: roles authorize immediately, organizations never mix, the last
 * administrator is kept, and actions are attributed to the authenticated actor (issue #74).
 */
@SpringBootTest(classes = DvaraAgentWorksApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(IdentityTestConfiguration.class)
@Isolated("runs a mutable full web application context")
class MembershipApiTest {
    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TestIdentities identities;
    private UUID workspace;

    @BeforeEach
    void setUp() {
        UUID organization = UUID.randomUUID();
        workspace = UUID.randomUUID();
        jdbc.update("insert into organization(id, name) values (?, ?)", organization, "org-" + organization);
        jdbc.update("insert into workspace(id, organization_id, name) values (?, ?, ?)", workspace, organization, "ws");
    }

    @Test
    void administratorsGrantChangeAndRemoveRolesWithImmediateEffect() {
        Member admin = identities.create(workspace, WorkspaceRole.ADMIN, PASSWORD, "ADMIN");
        var adminBrowser = new IdentityHttp(port);
        adminBrowser.login(admin.username(), PASSWORD);
        var created = adminBrowser.send("POST", "/api/v1/users", Map.of("username", "user-" + UUID.randomUUID()
                .toString().substring(0, 8)));
        UUID user = UUID.fromString(created.json().at("/user/id").asString());
        String temporary = created.json().path("temporaryPassword").asString();
        var browser = new IdentityHttp(port);
        browser.login(created.json().at("/user/username").asString(), temporary);
        browser.send("POST", "/api/v1/auth/password", Map.of("currentPassword", temporary, "newPassword", PASSWORD));

        var withoutMembership = browser.get("/api/v1/agents");
        var granted = adminBrowser.send("PUT", "/api/v1/members/" + user, Map.of("role", "OPERATOR"));
        var reselected = browser.send("PUT", "/api/v1/auth/session/workspace", Map.of("workspaceId", workspace.toString()));
        var operatorWrite = browser.send("POST", "/api/v1/agents", Map.of("name", "not-an-author"));
        var changed = adminBrowser.send("PUT", "/api/v1/members/" + user, Map.of("role", "AUTHOR"));
        var authorWrite = browser.send("POST", "/api/v1/agents", Map.of("name", "an-author"));
        var listed = adminBrowser.get("/api/v1/members");
        var invalid = adminBrowser.send("PUT", "/api/v1/members/" + user, Map.of("role", "OWNER"));
        var removed = adminBrowser.send("DELETE", "/api/v1/members/" + user, null);
        var afterRemoval = browser.get("/api/v1/agents");

        assertThat(withoutMembership.status()).isEqualTo(403);
        assertThat(withoutMembership.code()).isEqualTo("WORKSPACE_SCOPE_UNAVAILABLE");
        assertThat(granted.status()).isEqualTo(201);
        assertThat(reselected.json().path("role").asString()).isEqualTo("OPERATOR");
        assertThat(operatorWrite.code()).isEqualTo("ACCESS_DENIED");
        assertThat(changed.status()).isEqualTo(200);
        assertThat(authorWrite.status()).isEqualTo(201);
        assertThat(listed.json().path("items").valueStream().map(item -> item.path("role").asString()))
                .containsExactlyInAnyOrder("ADMIN", "AUTHOR");
        assertThat(invalid.status()).isEqualTo(422);
        assertThat(removed.status()).isEqualTo(204);
        // The session selected into the removed membership is deleted with it.
        assertThat(afterRemoval.status()).isEqualTo(401);
        assertThat(jdbc.queryForObject("select count(*) from auth_audit_event where event_type = 'MEMBERSHIP_SET' "
                + "and actor_id = ? and target_id = ?", Integer.class, admin.userId(), user)).isEqualTo(2);
    }

    @Test
    void theLastAdministratorCannotBeRemovedOrDemoted() {
        Member admin = identities.create(workspace, WorkspaceRole.ADMIN, PASSWORD, "MEMBER");
        var cli = signedIn(admin);

        var demoted = cli.send("PUT", "/api/v1/members/" + admin.userId(), Map.of("role", "VIEWER"));
        var removed = cli.send("DELETE", "/api/v1/members/" + admin.userId(), null);
        Member second = identities.create(workspace, WorkspaceRole.ADMIN, PASSWORD, "MEMBER");
        var nowAllowed = cli.send("PUT", "/api/v1/members/" + admin.userId(), Map.of("role", "VIEWER"));
        var finalAdmin = signedIn(second)
                .send("DELETE", "/api/v1/members/" + second.userId(), null);

        for (var refused : new IdentityHttp.Response[]{demoted, removed, finalAdmin}) {
            assertThat(refused.status()).isEqualTo(409);
            assertThat(refused.code()).isEqualTo("LAST_ADMINISTRATOR");
        }
        assertThat(nowAllowed.status()).isEqualTo(200);
    }

    @Test
    void usersOfAnotherOrganizationCannotBeAddedAndNonAdministratorsCannotManageMembers() {
        Member admin = identities.create(workspace, WorkspaceRole.ADMIN, PASSWORD, "MEMBER");
        Member author = identities.create(workspace, WorkspaceRole.AUTHOR, PASSWORD, "MEMBER");
        UUID otherOrganization = UUID.randomUUID();
        UUID otherWorkspace = UUID.randomUUID();
        jdbc.update("insert into organization(id, name) values (?, ?)", otherOrganization, "org-" + otherOrganization);
        jdbc.update("insert into workspace(id, organization_id, name) values (?, ?, ?)", otherWorkspace,
                otherOrganization, "ws");
        Member foreigner = identities.create(otherWorkspace, WorkspaceRole.VIEWER);

        var foreign = signedIn(admin).send("PUT", "/api/v1/members/" + foreigner.userId(), Map.of("role", "VIEWER"));
        var byAuthor = signedIn(author).send("PUT", "/api/v1/members/" + author.userId(), Map.of("role", "ADMIN"));
        // Even an administrator's API token cannot grant roles: a leaked token must not enrol a lasting account.
        var byAdminToken = new IdentityHttp(port).bearer(admin.token())
                .send("PUT", "/api/v1/members/" + author.userId(), Map.of("role", "ADMIN"));

        assertThat(foreign.status()).isEqualTo(404);
        assertThat(byAdminToken.status()).isEqualTo(403);
        assertThat(byAdminToken.code()).isEqualTo("ACCESS_DENIED");
        assertThat(byAuthor.status()).isEqualTo(403);
        assertThat(byAuthor.code()).isEqualTo("ACCESS_DENIED");
        assertThat(jdbc.queryForObject("select count(*) from workspace_membership where workspace_id = ? "
                + "and subject_id = ?", Integer.class, workspace, foreigner.userId())).isZero();
        assertThat(jdbc.queryForObject("select membership_role from workspace_membership where workspace_id = ? "
                + "and subject_id = ?", String.class, workspace, author.userId())).isEqualTo("AUTHOR");
    }

    @Test
    void anOrganizationAdministratorGrantsWorkspaceRolesOnlyWhereItAdministers() {
        Member organizationAdmin = identities.create(workspace, WorkspaceRole.VIEWER, PASSWORD, "ADMIN");
        var browser = new IdentityHttp(port);
        browser.login(organizationAdmin.username(), PASSWORD);

        var withRole = browser.send("POST", "/api/v1/users", Map.of("username", "granted-" + UUID.randomUUID()
                .toString().substring(0, 8), "workspaceRole", "ADMIN"));
        var withoutRole = browser.send("POST", "/api/v1/users", Map.of("username", "plain-" + UUID.randomUUID()
                .toString().substring(0, 8)));

        assertThat(withRole.status()).isEqualTo(403);
        assertThat(withRole.code()).isEqualTo("ROLE_NOT_GRANTABLE");
        assertThat(withoutRole.status()).isEqualTo(201);
        assertThat(jdbc.queryForObject("select count(*) from workspace_membership where workspace_id = ?",
                Integer.class, workspace)).isOne();
    }

    @Test
    void controlActionsRecordTheAuthenticatedActorAndMethod() {
        Member admin = identities.create(workspace, WorkspaceRole.ADMIN, PASSWORD, "MEMBER");
        var browser = new IdentityHttp(port);
        browser.login(admin.username(), PASSWORD);
        var cli = new IdentityHttp(port).bearer(admin.token());
        String tokenId = jdbc.queryForObject("select id from api_token where user_id = ?", UUID.class, admin.userId())
                .toString();

        String environment = browser.send("POST", "/api/v1/environments", Map.of("name", "prod")).json().path("id")
                .asString();
        String definition = cli.send("POST", "/api/v1/agents", Map.of("name", "agent")).json().path("id").asString();
        String version = cli.send("POST", "/api/v1/agents/" + definition + "/versions",
                "apiVersion: dvarahq.com/v1alpha1\nkind: Agent\nmetadata: {name: agent, version: 1.0.0}\n"
                        + "spec:\n  executor: {type: spring-ai}\n", Map.of("Content-Type", "application/yaml"))
                .json().path("id").asString();
        String release = cli.send("POST", "/api/v1/agent-versions/" + version + "/releases", Map.of("name", "r1"))
                .json().path("id").asString();
        var bySession = browser.send("POST", "/api/v1/environments/" + environment + "/promotions",
                Map.of("deployment", "api", "releaseId", release));
        var byToken = cli.send("POST", "/api/v1/cohorts/" + bySession.json().path("cohortId").asString() + "/suspend",
                Map.of("reason", "incident"), Map.of("If-Match", "\"2\""));

        assertThat(bySession.status()).isEqualTo(201);
        assertThat(byToken.status()).isEqualTo(200);
        assertThat(jdbc.queryForObject("select actor from lifecycle_decision where id = ?", String.class,
                UUID.fromString(bySession.json().path("decisionId").asString())))
                .isEqualTo("user:" + admin.userId() + "/session");
        assertThat(jdbc.queryForList("select actor from cohort_transition where workspace_id = ? and to_state = 'SUSPENDED'",
                String.class, workspace)).containsExactly("user:" + admin.userId() + "/api-token:" + tokenId);
    }

    @Test
    void runActionsRecordTheAuthenticatedActorAndMethod() {
        Member admin = identities.create(workspace, WorkspaceRole.ADMIN, PASSWORD, "MEMBER");
        var browser = signedIn(admin);
        var cli = new IdentityHttp(port).bearer(admin.token());
        String tokenId = jdbc.queryForObject("select id from api_token where user_id = ?", UUID.class, admin.userId())
                .toString();
        String environment = browser.send("POST", "/api/v1/environments", Map.of("name", "runs")).json().path("id")
                .asString();
        String definition = cli.send("POST", "/api/v1/agents", Map.of("name", "runner")).json().path("id").asString();
        String version = cli.send("POST", "/api/v1/agents/" + definition + "/versions",
                "apiVersion: dvarahq.com/v1alpha1\nkind: Agent\nmetadata: {name: runner, version: 1.0.0}\n"
                        + "spec:\n  executor: {type: spring-ai}\n", Map.of("Content-Type", "application/yaml"))
                .json().path("id").asString();
        String release = cli.send("POST", "/api/v1/agent-versions/" + version + "/releases", Map.of("name", "runner-1"))
                .json().path("id").asString();
        String deployment = browser.send("POST", "/api/v1/environments/" + environment + "/promotions",
                Map.of("deployment", "api", "releaseId", release)).json().path("deploymentId").asString();

        var admitted = cli.send("POST", "/api/v1/deployments/" + deployment + "/runs", Map.of("input", "hi"));
        String run = admitted.json().path("id").asString();
        var cancelled = browser.send("POST", "/api/v1/runs/" + run + "/cancel", Map.of("reason", "done"));

        assertThat(admitted.status()).isEqualTo(201);
        assertThat(cancelled.status()).isEqualTo(200);
        String byToken = "user:" + admin.userId() + "/api-token:" + tokenId;
        assertThat(jdbc.queryForList("select event_type || ' ' || actor from agent_run_event where run_id = ? "
                + "order by sequence", String.class, UUID.fromString(run))).containsExactly(
                "RUN_CREATED " + byToken, "RUN_QUEUED " + byToken,
                "RUN_CANCELLED user:" + admin.userId() + "/session");

        // An idempotency key is the caller's own: another principal reusing key and body gets no one else's run.
        Member operator = identities.create(workspace, WorkspaceRole.OPERATOR);
        var body = Map.of("input", "same request");
        var first = cli.send("POST", "/api/v1/deployments/" + deployment + "/runs", body, Map.of("Idempotency-Key", "k1"));
        var replay = cli.send("POST", "/api/v1/deployments/" + deployment + "/runs", body, Map.of("Idempotency-Key", "k1"));
        var other = new IdentityHttp(port).bearer(operator.token()).send("POST", "/api/v1/deployments/" + deployment
                + "/runs", body, Map.of("Idempotency-Key", "k1"));
        assertThat(first.status()).isEqualTo(201);
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.json().path("id")).isEqualTo(first.json().path("id"));
        assertThat(other.status()).isEqualTo(409);
        assertThat(other.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(other.raw()).doesNotContain(first.json().path("id").asString());

        // Actions whose tables have no actor column are attributed in the audit trail.
        String policy = "{\"gates\":[]}";
        String etag = browser.get("/api/v1/environments/" + environment).header("ETag");
        browser.send("PUT", "/api/v1/environments/" + environment + "/promotion-policy", policy, Map.of("If-Match", etag));
        cli.send("PUT", "/api/v1/release-aliases/stable", Map.of("releaseId", release));
        assertThat(jdbc.queryForList("select event_type || ' ' || authentication_method || ' ' || target_id "
                + "from auth_audit_event where actor_id = ? and workspace_id = ? and target_type <> 'API_TOKEN' "
                + "and event_type not like 'LOGIN%' and event_type not like 'SESSION%'", String.class,
                admin.userId(), workspace)).contains(
                "ENVIRONMENT_CREATED session " + environment,
                "AGENT_DEFINITION_CREATED api-token " + definition,
                "AGENT_VERSION_PUBLISHED api-token " + version,
                "RELEASE_CREATED api-token " + release,
                "PROMOTION_POLICY_CHANGED session " + environment,
                "RELEASE_ALIAS_SET api-token " + release);
    }

    private IdentityHttp signedIn(Member member) {
        var browser = new IdentityHttp(port);
        assertThat(browser.login(member.username(), PASSWORD).status()).isEqualTo(200);
        return browser;
    }
}
