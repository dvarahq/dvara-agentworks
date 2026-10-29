package com.dvarahq.agentworks.identity.web;

import com.dvarahq.agentworks.domain.identity.WorkspaceRole;
import com.dvarahq.agentworks.DvaraAgentWorksApplication;
import com.dvarahq.agentworks.identity.MutableClock;
import com.dvarahq.agentworks.identity.TestIdentities;
import com.dvarahq.agentworks.identity.TestIdentities.Member;
import com.dvarahq.agentworks.storage.DefaultTenantBootstrap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.dvarahq.agentworks.identity.web.IdentityTestConfiguration.PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Browser sign-in, sessions, CSRF, password change and reset, lockout, and account state through the production
 * security chain (issue #74). Time-based policies advance a test clock instead of sleeping.
 */
@SpringBootTest(classes = DvaraAgentWorksApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"agentworks.security.login.max-failures=3", "agentworks.security.session.max-concurrent=2"})
@Import(IdentityTestConfiguration.class)
@Tag("integration")
class AuthenticationApiTest {
    private static final UUID DEFAULT_WORKSPACE = DefaultTenantBootstrap.DEFAULT_WORKSPACE_ID.value();

    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TestIdentities identities;
    @Autowired
    MutableClock clock;
    private Member admin;

    @BeforeEach
    void setUp() {
        admin = identities.create(DEFAULT_WORKSPACE, WorkspaceRole.ADMIN, PASSWORD, "ADMIN");
    }

    @Test
    void signInIssuesAHardenedSessionCookieAndACsrfToken() {
        Member author = member(WorkspaceRole.AUTHOR);
        var browser = new IdentityHttp(port);

        var login = browser.login(author.username(), PASSWORD);
        var session = browser.get("/api/v1/auth/session");

        assertThat(login.status()).isEqualTo(200);
        assertThat(login.setCookie()).startsWith("__Host-agentworks-session=aws_")
                .contains("Path=/", "Secure", "HttpOnly", "SameSite=Strict", "Max-Age=43200")
                .doesNotContain("Domain");
        assertThat(login.json().path("csrfToken").asString()).isNotBlank().isEqualTo(browser.csrf());
        assertThat(login.json().path("role").asString()).isEqualTo("AUTHOR");
        assertThat(login.json().path("workspaceId").asString()).isEqualTo(DEFAULT_WORKSPACE.toString());
        assertThat(login.raw()).doesNotContain(browser.cookie(), PASSWORD);
        assertThat(session.json().path("userId").asString()).isEqualTo(author.userId().toString());
        assertThat(session.json().path("authenticationMethod").asString()).isEqualTo("session");
        assertThat(session.header("Cache-Control")).contains("no-store");
        // Only a digest of the cookie value is stored.
        assertThat(jdbc.queryForObject("select count(*) from auth_session where session_digest = ?", Integer.class,
                browser.cookie())).isZero();
    }

    @Test
    void unknownUsersAndWrongPasswordsFailIdentically() {
        Member author = member(WorkspaceRole.AUTHOR);

        var unknown = new IdentityHttp(port).login("nobody-" + UUID.randomUUID(), PASSWORD);
        var wrong = new IdentityHttp(port).login(author.username(), PASSWORD + "!");
        var malformed = new IdentityHttp(port).login("x", "");
        // An oversized password is refused without hashing it in full, and still counts as a failure.
        var oversized = new IdentityHttp(port).login(author.username(), "x".repeat(200_000));

        for (var response : List.of(unknown, wrong, malformed, oversized)) {
            assertThat(response.status()).isEqualTo(401);
            assertThat(response.code()).isEqualTo("AUTHENTICATION_FAILED");
            assertThat(response.setCookie()).isNull();
        }
        assertThat(unknown.raw()).isEqualTo(wrong.raw()).isEqualTo(malformed.raw()).isEqualTo(oversized.raw());
        assertThat(jdbc.queryForObject("select count(*) from auth_audit_event where event_type = 'LOGIN_FAILED' "
                + "and target_id = ? and detail = 'BAD_CREDENTIALS'", Integer.class, author.userId())).isEqualTo(2);
    }

    @Test
    void repeatedFailuresLockTheAccountUntilTheLockoutExpires() {
        Member operator = member(WorkspaceRole.OPERATOR);
        for (int attempt = 0; attempt < 3; attempt++) {
            assertThat(new IdentityHttp(port).login(operator.username(), "wrong password " + attempt).status())
                    .isEqualTo(401);
        }

        var locked = new IdentityHttp(port).login(operator.username(), PASSWORD);
        clock.advance(Duration.ofMinutes(16));
        // A typo after the lock expires starts a new count rather than locking again.
        assertThat(new IdentityHttp(port).login(operator.username(), "one more typo").status()).isEqualTo(401);
        var unlocked = new IdentityHttp(port).login(operator.username(), PASSWORD);

        // The correct password is refused while locked, indistinguishably from a wrong one.
        assertThat(locked.status()).isEqualTo(401);
        assertThat(locked.code()).isEqualTo("AUTHENTICATION_FAILED");
        assertThat(unlocked.status()).isEqualTo(200);
        assertThat(jdbc.queryForObject("select failed_login_count from user_account where id = ?", Integer.class,
                operator.userId())).isZero();
    }

    @Test
    void aLockedAccountRefusesPasswordChangesIdenticallyWhateverThePasswords() {
        Member author = member(WorkspaceRole.AUTHOR);
        var browser = new IdentityHttp(port);
        browser.login(author.username(), PASSWORD);
        for (int attempt = 0; attempt < 3; attempt++) {
            new IdentityHttp(port).login(author.username(), "wrong password " + attempt);
        }

        var rightCurrentWeakNew = browser.send("POST", "/api/v1/auth/password",
                Map.of("currentPassword", PASSWORD, "newPassword", "short"));
        var wrongCurrent = browser.send("POST", "/api/v1/auth/password",
                Map.of("currentPassword", "not the password", "newPassword", "a perfectly valid new passphrase"));

        for (var refused : List.of(rightCurrentWeakNew, wrongCurrent)) {
            assertThat(refused.status()).isEqualTo(401);
            assertThat(refused.code()).isEqualTo("AUTHENTICATION_FAILED");
        }
        assertThat(rightCurrentWeakNew.raw()).isEqualTo(wrongCurrent.raw());
    }

    @Test
    void signInRotatesThePresentedSessionSoAPlantedSessionIsNeverPromoted() {
        Member victim = member(WorkspaceRole.ADMIN);
        Member attacker = member(WorkspaceRole.VIEWER);
        var attackerBrowser = new IdentityHttp(port);
        attackerBrowser.login(attacker.username(), PASSWORD);
        String planted = attackerBrowser.cookie();

        // The victim's browser carries a session value the attacker planted and signs in.
        var victimBrowser = new IdentityHttp(port).cookie(attackerBrowser.cookieName(), planted);
        var login = victimBrowser.login(victim.username(), PASSWORD);

        assertThat(login.status()).isEqualTo(200);
        assertThat(victimBrowser.cookie()).isNotEqualTo(planted);
        assertThat(victimBrowser.get("/api/v1/auth/session").json().path("userId").asString())
                .isEqualTo(victim.userId().toString());
        // The planted value was revoked, not promoted to the victim's identity.
        var reused = new IdentityHttp(port).cookie(attackerBrowser.cookieName(), planted).get("/api/v1/auth/session");
        assertThat(reused.status()).isEqualTo(401);
        // An invented value is simply ignored.
        var invented = new IdentityHttp(port).cookie(attackerBrowser.cookieName(), "aws_" + "A".repeat(43));
        assertThat(invented.login(victim.username(), PASSWORD).status()).isEqualTo(200);
        assertThat(invented.cookie()).isNotEqualTo("aws_" + "A".repeat(43));
    }

    @Test
    void logoutRevokesTheSessionEverywhere() {
        Member viewer = member(WorkspaceRole.VIEWER);
        var browser = new IdentityHttp(port);
        browser.login(viewer.username(), PASSWORD);
        String name = browser.cookieName();
        String value = browser.cookie();

        var logout = browser.send("POST", "/api/v1/auth/logout", null);
        var replay = new IdentityHttp(port).cookie(name, value).get("/api/v1/agents");

        assertThat(logout.status()).isEqualTo(204);
        assertThat(logout.setCookie()).startsWith(name + "=;").contains("Max-Age=0");
        assertThat(replay.status()).isEqualTo(401);
        assertThat(replay.code()).isEqualTo("AUTHENTICATION_REQUIRED");
        assertThat(replay.setCookie()).as("a stale cookie is cleared").contains("Max-Age=0");
    }

    @Test
    void sessionsExpireWhenIdleAndAtTheirAbsoluteLifetime() {
        Member viewer = member(WorkspaceRole.VIEWER);
        var idle = new IdentityHttp(port);
        idle.login(viewer.username(), PASSWORD);
        var active = new IdentityHttp(port);
        active.login(viewer.username(), PASSWORD);

        clock.advance(Duration.ofMinutes(20));
        assertThat(active.get("/api/v1/agents").status()).isEqualTo(200);
        clock.advance(Duration.ofMinutes(15));
        // 35 minutes without use exceeds the 30-minute idle timeout; the used session is still live.
        assertThat(idle.get("/api/v1/agents").status()).isEqualTo(401);
        assertThat(active.get("/api/v1/agents").status()).isEqualTo(200);

        for (int step = 0; step < 40 && active.get("/api/v1/agents").status() == 200; step++) {
            clock.advance(Duration.ofMinutes(25));
        }
        // However active, a session ends at its 12-hour absolute lifetime.
        assertThat(active.get("/api/v1/agents").status()).isEqualTo(401);
        assertThat(jdbc.queryForObject("select count(*) from auth_session where user_id = ? and revoked_at is null "
                + "and expires_at > ?", Integer.class, viewer.userId(),
                java.sql.Timestamp.from(clock.instant()))).isZero();
    }

    @Test
    void theOldestSessionIsRevokedBeyondTheConcurrentSessionLimit() {
        Member viewer = member(WorkspaceRole.VIEWER);
        var first = new IdentityHttp(port);
        first.login(viewer.username(), PASSWORD);
        clock.advance(Duration.ofSeconds(1));
        var second = new IdentityHttp(port);
        second.login(viewer.username(), PASSWORD);
        clock.advance(Duration.ofSeconds(1));
        var third = new IdentityHttp(port);
        third.login(viewer.username(), PASSWORD);

        assertThat(first.get("/api/v1/agents").status()).isEqualTo(401);
        assertThat(second.get("/api/v1/agents").status()).isEqualTo(200);
        assertThat(third.get("/api/v1/agents").status()).isEqualTo(200);
        assertThat(jdbc.queryForObject("select count(*) from auth_session where user_id = ? and revoked_reason = "
                + "'CONCURRENT_LIMIT'", Integer.class, viewer.userId())).isOne();
    }

    @Test
    void cookieAuthenticatedMutationsRequireTheSessionsCsrfToken() {
        var browser = new IdentityHttp(port);
        browser.login(admin.username(), PASSWORD);
        String csrf = browser.csrf();
        var other = new IdentityHttp(port);
        other.login(member(WorkspaceRole.ADMIN).username(), PASSWORD);

        var missing = browser.csrf(null).send("POST", "/api/v1/environments", Map.of("name", "no-csrf"));
        var foreign = browser.csrf(other.csrf()).send("POST", "/api/v1/environments", Map.of("name", "foreign-csrf"));
        var accepted = browser.csrf(csrf).send("POST", "/api/v1/environments", Map.of("name", "with-csrf-" + UUID.randomUUID()));
        // A bearer token is not an ambient credential, so it needs no CSRF token.
        var bearer = new IdentityHttp(port).bearer(admin.token()).send("POST", "/api/v1/environments",
                Map.of("name", "bearer-" + UUID.randomUUID()));

        for (var rejected : List.of(missing, foreign)) {
            assertThat(rejected.status()).isEqualTo(403);
            assertThat(rejected.code()).isEqualTo("CSRF_TOKEN_INVALID");
        }
        assertThat(accepted.status()).isEqualTo(201);
        assertThat(bearer.status()).isEqualTo(201);
        assertThat(jdbc.queryForObject("select count(*) from agent_environment where name in ('no-csrf', 'foreign-csrf')",
                Integer.class)).isZero();
    }

    @Test
    void passwordChangeVerifiesTheCurrentPasswordAndRevokesEveryOtherSession() {
        Member author = member(WorkspaceRole.AUTHOR);
        var browser = new IdentityHttp(port);
        browser.login(author.username(), PASSWORD);
        var elsewhere = new IdentityHttp(port);
        elsewhere.login(author.username(), PASSWORD);
        String before = browser.cookie();
        String changed = "an entirely new passphrase 7";

        var wrongCurrent = browser.send("POST", "/api/v1/auth/password",
                Map.of("currentPassword", "not it at all", "newPassword", changed));
        var weak = browser.send("POST", "/api/v1/auth/password",
                Map.of("currentPassword", PASSWORD, "newPassword", "short"));
        var success = browser.send("POST", "/api/v1/auth/password",
                Map.of("currentPassword", PASSWORD, "newPassword", changed));

        assertThat(wrongCurrent.status()).isEqualTo(401);
        assertThat(weak.status()).isEqualTo(422);
        assertThat(weak.code()).isEqualTo("PASSWORD_POLICY");
        assertThat(weak.raw()).doesNotContain("short");
        assertThat(success.status()).isEqualTo(200);
        assertThat(browser.cookie()).isNotEqualTo(before);
        assertThat(browser.get("/api/v1/agents").status()).isEqualTo(200);
        assertThat(elsewhere.get("/api/v1/agents").status()).isEqualTo(401);
        assertThat(new IdentityHttp(port).cookie(browser.cookieName(), before).get("/api/v1/agents").status())
                .isEqualTo(401);
        assertThat(new IdentityHttp(port).login(author.username(), PASSWORD).status()).isEqualTo(401);
        assertThat(new IdentityHttp(port).login(author.username(), changed).status()).isEqualTo(200);
    }

    @Test
    void temporaryPasswordsMustBeChangedBeforeAnyOtherOperation() {
        var adminBrowser = new IdentityHttp(port);
        adminBrowser.login(admin.username(), PASSWORD);
        String username = "new-" + UUID.randomUUID().toString().substring(0, 8);
        var created = adminBrowser.send("POST", "/api/v1/users", Map.of("username", username, "displayName", "New User",
                "workspaceRole", "AUTHOR"));
        String temporary = created.json().path("temporaryPassword").asString();

        var browser = new IdentityHttp(port);
        var login = browser.login(username, temporary);
        var blockedRead = browser.get("/api/v1/agents");
        var blockedToken = browser.send("POST", "/api/v1/tokens", Map.of("name", "cli"));
        var session = browser.get("/api/v1/auth/session");
        var changed = browser.send("POST", "/api/v1/auth/password",
                Map.of("currentPassword", temporary, "newPassword", "my own chosen passphrase"));
        var allowed = browser.get("/api/v1/agents");

        assertThat(created.status()).isEqualTo(201);
        assertThat(created.json().at("/user/mustChangePassword").asBoolean()).isTrue();
        assertThat(login.json().path("mustChangePassword").asBoolean()).isTrue();
        assertThat(login.json().path("permissions")).isEmpty();
        for (var blocked : List.of(blockedRead, blockedToken)) {
            assertThat(blocked.status()).isEqualTo(403);
            assertThat(blocked.code()).isEqualTo("PASSWORD_CHANGE_REQUIRED");
        }
        assertThat(session.status()).isEqualTo(200);
        assertThat(changed.status()).isEqualTo(200);
        assertThat(changed.json().path("mustChangePassword").asBoolean()).isFalse();
        assertThat(allowed.status()).isEqualTo(200);
        assertThat(adminBrowser.get("/api/v1/users").raw()).doesNotContain(temporary);
    }

    @Test
    void switchingWorkspacesRotatesTheSessionAndRequiresMembership() {
        UUID second = UUID.randomUUID();
        jdbc.update("insert into workspace(id, organization_id, name) values (?, ?, ?)", second,
                DefaultTenantBootstrap.DEFAULT_ORGANIZATION_ID.value(), "second-" + second);
        Member author = member(WorkspaceRole.AUTHOR);
        jdbc.update("insert into workspace_membership(organization_id, workspace_id, subject_id, membership_role, "
                        + "created_at, updated_at) values (?, ?, ?, 'VIEWER', current_timestamp, current_timestamp)",
                DefaultTenantBootstrap.DEFAULT_ORGANIZATION_ID.value(), second, author.userId());
        UUID foreign = UUID.randomUUID();
        var browser = new IdentityHttp(port);
        var login = browser.login(author.username(), PASSWORD);
        String before = browser.cookie();
        clock.advance(Duration.ofMinutes(5));

        var workspaces = browser.get("/api/v1/auth/workspaces");
        var refused = browser.send("PUT", "/api/v1/auth/session/workspace", Map.of("workspaceId", foreign.toString()));
        var switched = browser.send("PUT", "/api/v1/auth/session/workspace", Map.of("workspaceId", second.toString()));
        var write = browser.send("POST", "/api/v1/agents", Map.of("name", "viewer-cannot-write"));

        assertThat(workspaces.json().path("items")).hasSize(2);
        assertThat(refused.status()).isEqualTo(403);
        assertThat(refused.code()).isEqualTo("WORKSPACE_SCOPE_UNAVAILABLE");
        assertThat(switched.status()).isEqualTo(200);
        assertThat(switched.json().path("workspaceId").asString()).isEqualTo(second.toString());
        assertThat(switched.json().path("role").asString()).isEqualTo("VIEWER");
        // Rotation without a password keeps the absolute lifetime of the session it replaces, and so does its cookie.
        assertThat(switched.json().path("expiresAt").asString()).isEqualTo(login.json().path("expiresAt").asString());
        assertThat(switched.setCookie()).contains("Max-Age=" + (43_200 - 300) + ";");
        assertThat(browser.cookie()).isNotEqualTo(before);
        assertThat(new IdentityHttp(port).cookie(browser.cookieName(), before).get("/api/v1/agents").status())
                .isEqualTo(401);
        assertThat(write.status()).isEqualTo(403);
        assertThat(write.code()).isEqualTo("ACCESS_DENIED");
    }

    @Test
    void usersListAndRevokeOnlyTheirOwnSessions() {
        Member viewer = member(WorkspaceRole.VIEWER);
        var first = new IdentityHttp(port);
        first.login(viewer.username(), PASSWORD);
        var second = new IdentityHttp(port);
        second.login(viewer.username(), PASSWORD);
        var stranger = new IdentityHttp(port);
        stranger.login(member(WorkspaceRole.VIEWER).username(), PASSWORD);

        var listed = first.get("/api/v1/auth/sessions");
        String secondId = listed.json().path("items").valueStream()
                .filter(item -> !item.path("current").asBoolean()).findFirst().orElseThrow().path("id").asString();
        var foreign = stranger.send("DELETE", "/api/v1/auth/sessions/" + secondId, null);
        var revoked = first.send("DELETE", "/api/v1/auth/sessions/" + secondId, null);

        assertThat(listed.json().path("items").valueStream().filter(item -> item.path("current").asBoolean())).hasSize(1);
        assertThat(foreign.status()).isEqualTo(404);
        assertThat(revoked.status()).isEqualTo(204);
        assertThat(second.get("/api/v1/agents").status()).isEqualTo(401);
        assertThat(first.get("/api/v1/agents").status()).isEqualTo(200);
    }

    @Test
    void disablingAUserEndsItsSessionsAndTokensImmediately() {
        Member operator = member(WorkspaceRole.OPERATOR);
        var browser = new IdentityHttp(port);
        browser.login(operator.username(), PASSWORD);
        var adminBrowser = new IdentityHttp(port);
        adminBrowser.login(admin.username(), PASSWORD);

        var disabled = adminBrowser.send("POST", "/api/v1/users/" + operator.userId() + "/disable", null);
        var self = adminBrowser.send("POST", "/api/v1/users/" + admin.userId() + "/disable", null);

        assertThat(disabled.status()).isEqualTo(200);
        assertThat(disabled.json().path("status").asString()).isEqualTo("DISABLED");
        assertThat(self.status()).isEqualTo(422);
        assertThat(browser.get("/api/v1/agents").status()).isEqualTo(401);
        assertThat(new IdentityHttp(port).bearer(operator.token()).get("/api/v1/agents").status()).isEqualTo(401);
        var login = new IdentityHttp(port).login(operator.username(), PASSWORD);
        assertThat(login.status()).isEqualTo(401);
        assertThat(login.code()).isEqualTo("AUTHENTICATION_FAILED");

        assertThat(adminBrowser.send("POST", "/api/v1/users/" + operator.userId() + "/enable", null).status())
                .isEqualTo(200);
        assertThat(new IdentityHttp(port).login(operator.username(), PASSWORD).status()).isEqualTo(200);
    }

    @Test
    void administratorPasswordResetRevokesEveryCredential() {
        Member author = member(WorkspaceRole.AUTHOR);
        var browser = new IdentityHttp(port);
        browser.login(author.username(), PASSWORD);
        var adminBrowser = new IdentityHttp(port);
        adminBrowser.login(admin.username(), PASSWORD);
        var nonAdmin = new IdentityHttp(port);
        nonAdmin.login(member(WorkspaceRole.ADMIN).username(), PASSWORD);

        var denied = nonAdmin.send("POST", "/api/v1/users/" + author.userId() + "/password-reset", null);
        var reset = adminBrowser.send("POST", "/api/v1/users/" + author.userId() + "/password-reset", null);
        String temporary = reset.json().path("temporaryPassword").asString();

        // A workspace administrator is not an organization administrator.
        assertThat(denied.status()).isEqualTo(403);
        assertThat(denied.code()).isEqualTo("ACCESS_DENIED");
        assertThat(reset.status()).isEqualTo(200);
        assertThat(browser.get("/api/v1/agents").status()).isEqualTo(401);
        assertThat(new IdentityHttp(port).bearer(author.token()).get("/api/v1/agents").status()).isEqualTo(401);
        assertThat(new IdentityHttp(port).login(author.username(), PASSWORD).status()).isEqualTo(401);
        var fresh = new IdentityHttp(port);
        assertThat(fresh.login(author.username(), temporary).json().path("mustChangePassword").asBoolean()).isTrue();
    }

    private Member member(WorkspaceRole role) {
        return identities.create(DEFAULT_WORKSPACE, role, PASSWORD, "MEMBER");
    }
}
