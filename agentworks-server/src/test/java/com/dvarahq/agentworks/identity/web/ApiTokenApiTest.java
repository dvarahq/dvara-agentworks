package com.dvarahq.agentworks.identity.web;

import com.dvarahq.agentworks.domain.identity.WorkspaceRole;
import com.dvarahq.agentworks.DvaraAgentWorksApplication;
import com.dvarahq.agentworks.identity.MutableClock;
import com.dvarahq.agentworks.identity.TestIdentities;
import com.dvarahq.agentworks.identity.TestIdentities.Member;
import com.dvarahq.agentworks.storage.DefaultTenantBootstrap;
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
 * API token lifecycle: shown once, stored as a digest, workspace-bound, role-narrowed, expiring, and rejected
 * immediately after revocation or membership change (issue #74).
 */
@SpringBootTest(classes = DvaraAgentWorksApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(IdentityTestConfiguration.class)
class ApiTokenApiTest {
    private static final UUID DEFAULT_WORKSPACE = DefaultTenantBootstrap.DEFAULT_WORKSPACE_ID.value();

    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TestIdentities identities;
    @Autowired
    MutableClock clock;

    @Test
    void aTokenIsShownOnceStoredAsADigestAndAuthenticatesTheCli() {
        Member author = member(WorkspaceRole.AUTHOR);
        var browser = signedIn(author);

        var created = browser.send("POST", "/api/v1/tokens", Map.of("name", "ci pipeline", "expiresInDays", 7));
        String token = created.json().path("token").asString();
        var cli = new IdentityHttp(port).bearer(token);
        var whoami = cli.get("/api/v1/auth/session");
        var listed = browser.get("/api/v1/tokens");
        var agent = cli.send("POST", "/api/v1/agents", Map.of("name", "cli-agent-" + UUID.randomUUID()));

        assertThat(created.status()).isEqualTo(201);
        assertThat(token).startsWith("awt_").hasSize(47);
        assertThat(created.json().at("/metadata/role").asString()).isEqualTo("AUTHOR");
        assertThat(created.json().at("/metadata/expiresAt").asString()).isNotBlank();
        assertThat(created.header("Cache-Control")).contains("no-store");
        assertThat(whoami.json().path("authenticationMethod").asString()).isEqualTo("api-token");
        assertThat(whoami.json().path("csrfToken").isNull()).isTrue();
        assertThat(listed.raw()).doesNotContain(token).contains(created.json().at("/metadata/prefix").asString());
        assertThat(agent.status()).isEqualTo(201);
        // No column holds the token; the stored digest is not the value and the prefix reveals 6 of 43 characters.
        UUID id = UUID.fromString(created.json().at("/metadata/id").asString());
        var row = jdbc.queryForMap("select * from api_token where id = ?", id);
        assertThat(row.values()).noneMatch(value -> value != null && value.toString().contains(token.substring(10)));
        assertThat(row.get("token_digest").toString()).hasSize(64);
        assertThat(jdbc.queryForObject("select last_used_at from api_token where id = ?", java.sql.Timestamp.class, id))
                .isNotNull();
        // Decisions and audit name the token as the actor's credential.
        assertThat(jdbc.queryForObject("select count(*) from auth_audit_event where event_type = 'TOKEN_CREATED' "
                + "and target_id = ? and actor_id = ?", Integer.class, id, author.userId())).isOne();
    }

    @Test
    void tokensCannotMintTokensOrExceedTheirOwnersRole() {
        Member author = member(WorkspaceRole.AUTHOR);
        var browser = signedIn(author);

        var byToken = new IdentityHttp(port).bearer(author.token()).send("POST", "/api/v1/tokens", Map.of("name", "child"));
        var escalated = browser.send("POST", "/api/v1/tokens", Map.of("name", "ops", "role", "OPERATOR"));
        var admin = browser.send("POST", "/api/v1/tokens", Map.of("name", "admin", "role", "ADMIN"));
        var viewer = browser.send("POST", "/api/v1/tokens", Map.of("name", "read-only", "role", "VIEWER"));
        var readOnly = new IdentityHttp(port).bearer(viewer.json().path("token").asString());
        var tooLong = browser.send("POST", "/api/v1/tokens", Map.of("name", "forever", "expiresInDays", 366));
        var unnamed = browser.send("POST", "/api/v1/tokens", Map.of("name", " "));

        assertThat(byToken.status()).isEqualTo(403);
        assertThat(byToken.code()).isEqualTo("ACCESS_DENIED");
        for (var refused : List.of(escalated, admin)) {
            assertThat(refused.status()).isEqualTo(403);
            assertThat(refused.code()).isEqualTo("ROLE_NOT_GRANTABLE");
        }
        assertThat(viewer.status()).isEqualTo(201);
        assertThat(readOnly.get("/api/v1/agents").status()).isEqualTo(200);
        assertThat(readOnly.get("/api/v1/auth/session").json().path("role").asString()).isEqualTo("VIEWER");
        assertThat(readOnly.send("POST", "/api/v1/agents", Map.of("name", "nope")).code()).isEqualTo("ACCESS_DENIED");
        assertThat(tooLong.status()).isEqualTo(422);
        assertThat(unnamed.status()).isEqualTo(422);
    }

    @Test
    void expiredRevokedTamperedAndMalformedTokensFailIdentically() {
        Member operator = member(WorkspaceRole.OPERATOR);
        var browser = signedIn(operator);
        String shortLived = browser.send("POST", "/api/v1/tokens", Map.of("name", "short", "expiresInDays", 1))
                .json().path("token").asString();
        var revocable = browser.send("POST", "/api/v1/tokens", Map.of("name", "revocable"));
        String revoked = revocable.json().path("token").asString();

        assertThat(new IdentityHttp(port).bearer(revoked).get("/api/v1/agents").status()).isEqualTo(200);
        var revocation = browser.send("DELETE", "/api/v1/tokens/" + revocable.json().at("/metadata/id").asString(), null);
        String tampered = operator.token().substring(0, 20) + (operator.token().charAt(20) == 'A' ? 'B' : 'A')
                + operator.token().substring(21);
        var afterRevocation = new IdentityHttp(port).bearer(revoked).get("/api/v1/agents");
        clock.advance(Duration.ofDays(1).plusSeconds(1));
        var failures = List.of(afterRevocation,
                new IdentityHttp(port).bearer(shortLived).get("/api/v1/agents"),
                new IdentityHttp(port).bearer(tampered).get("/api/v1/agents"),
                new IdentityHttp(port).bearer("awt_" + "0".repeat(43)).get("/api/v1/agents"),
                new IdentityHttp(port).bearer("not-a-token").get("/api/v1/agents"),
                new IdentityHttp(port).send("GET", "/api/v1/agents", null, Map.of("Authorization", "Basic YTpi")));

        assertThat(revocation.status()).isEqualTo(204);
        assertThat(failures).allSatisfy(failure -> {
            assertThat(failure.status()).isEqualTo(401);
            assertThat(failure.code()).isEqualTo("AUTHENTICATION_FAILED");
            assertThat(failure.raw()).isEqualTo(failures.getFirst().raw());
        });
        assertThat(new IdentityHttp(port).bearer(operator.token()).get("/api/v1/agents").status()).isEqualTo(200);
    }

    @Test
    void onlyTheOwnerOrAWorkspaceAdministratorSeesAndRevokesAToken() {
        Member owner = member(WorkspaceRole.AUTHOR);
        Member peer = member(WorkspaceRole.AUTHOR);
        Member admin = member(WorkspaceRole.ADMIN);
        String tokenId = signedIn(owner).get("/api/v1/tokens").json().path("items").get(0).path("id").asString();

        var peerList = new IdentityHttp(port).bearer(peer.token()).get("/api/v1/tokens");
        var peerAll = new IdentityHttp(port).bearer(peer.token()).get("/api/v1/tokens?all=true");
        var peerRevoke = new IdentityHttp(port).bearer(peer.token()).send("DELETE", "/api/v1/tokens/" + tokenId, null);
        var adminAll = new IdentityHttp(port).bearer(admin.token()).get("/api/v1/tokens?all=true");
        var adminRevoke = new IdentityHttp(port).bearer(admin.token()).send("DELETE", "/api/v1/tokens/" + tokenId, null);

        assertThat(peerList.raw()).doesNotContain(tokenId);
        assertThat(peerAll.status()).isEqualTo(403);
        assertThat(peerRevoke.status()).isEqualTo(404);
        assertThat(adminAll.raw()).contains(tokenId);
        assertThat(adminRevoke.status()).isEqualTo(204);
        assertThat(new IdentityHttp(port).bearer(owner.token()).get("/api/v1/agents").status()).isEqualTo(401);
        assertThat(jdbc.queryForObject("select revoked_by from api_token where id = ?", UUID.class,
                UUID.fromString(tokenId))).isEqualTo(admin.userId());
    }

    @Test
    void membershipChangesNarrowOrEndTokensImmediately() {
        Member admin = member(WorkspaceRole.ADMIN);
        Member member = member(WorkspaceRole.AUTHOR);
        var adminBrowser = signedIn(admin);
        var memberCli = new IdentityHttp(port).bearer(member.token());

        assertThat(memberCli.send("POST", "/api/v1/agents", Map.of("name", "before-" + UUID.randomUUID())).status())
                .isEqualTo(201);
        adminBrowser.send("PUT", "/api/v1/members/" + member.userId(), Map.of("role", "VIEWER"));
        var narrowed = memberCli.send("POST", "/api/v1/agents", Map.of("name", "after-" + UUID.randomUUID()));
        var stillReads = memberCli.get("/api/v1/agents");
        adminBrowser.send("DELETE", "/api/v1/members/" + member.userId(), null);
        var removed = memberCli.get("/api/v1/agents");

        assertThat(narrowed.code()).isEqualTo("ACCESS_DENIED");
        assertThat(stillReads.status()).isEqualTo(200);
        assertThat(removed.status()).isEqualTo(401);
        assertThat(jdbc.queryForObject("select count(*) from api_token where user_id = ?", Integer.class,
                member.userId())).isZero();
    }

    @Test
    void aTokenIsConfinedToItsWorkspace() {
        UUID organization = UUID.randomUUID();
        UUID otherWorkspace = UUID.randomUUID();
        jdbc.update("insert into organization(id, name) values (?, ?)", organization, "org-" + organization);
        jdbc.update("insert into workspace(id, organization_id, name) values (?, ?, ?)", otherWorkspace, organization, "ws");
        Member outsider = identities.create(otherWorkspace, WorkspaceRole.ADMIN);
        Member insider = member(WorkspaceRole.ADMIN);
        String environment = new IdentityHttp(port).bearer(insider.token())
                .send("POST", "/api/v1/environments", Map.of("name", "private-" + UUID.randomUUID())).json()
                .path("id").asString();
        var outsiderCli = new IdentityHttp(port).bearer(outsider.token());

        var read = outsiderCli.get("/api/v1/environments/" + environment);
        var claimed = outsiderCli.send("GET", "/api/v1/environments/" + environment, null,
                Map.of("X-Workspace-Id", DEFAULT_WORKSPACE.toString(), "X-Test-Workspace", DEFAULT_WORKSPACE.toString()));
        var membership = outsiderCli.send("PUT", "/api/v1/members/" + insider.userId(), Map.of("role", "ADMIN"));
        var revoke = outsiderCli.send("DELETE", "/api/v1/tokens/" + jdbc.queryForObject(
                "select id from api_token where user_id = ?", UUID.class, insider.userId()), null);
        var list = outsiderCli.get("/api/v1/environments");

        for (var hidden : List.of(read, claimed, revoke)) {
            assertThat(hidden.status()).isEqualTo(404);
        }
        assertThat(membership.code()).as("role grants need a session").isEqualTo("ACCESS_DENIED");
        assertThat(list.raw()).doesNotContain(environment);
        assertThat(new IdentityHttp(port).bearer(insider.token()).get("/api/v1/environments/" + environment).status())
                .isEqualTo(200);
    }

    @Test
    void credentialsAreNeverAcceptedInTheUrl() {
        Member viewer = member(WorkspaceRole.VIEWER);
        var anonymous = new IdentityHttp(port);

        var query = anonymous.get("/api/v1/agents?access_token=" + viewer.token());
        var encoded = anonymous.get("/api/v1/agents?t=" + viewer.token().replace("_", "%5F"));
        var path = anonymous.get("/api/v1/tokens/" + viewer.token());

        for (var refused : List.of(query, encoded, path)) {
            assertThat(refused.status()).isEqualTo(400);
            assertThat(refused.code()).isEqualTo("CREDENTIAL_IN_URL");
            assertThat(refused.raw()).doesNotContain(viewer.token());
        }
    }

    private Member member(WorkspaceRole role) {
        return identities.create(DEFAULT_WORKSPACE, role, PASSWORD, "MEMBER");
    }

    private IdentityHttp signedIn(Member member) {
        var browser = new IdentityHttp(port);
        assertThat(browser.login(member.username(), PASSWORD).status()).isEqualTo(200);
        return browser;
    }
}
