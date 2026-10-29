package com.dvarahq.agentworks.identity.web;

import com.dvarahq.agentworks.DvaraAgentWorksApplication;
import com.dvarahq.agentworks.identity.InstallationBootstrapService;
import com.dvarahq.agentworks.identity.MutableClock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A clean installation has exactly one time-bounded bootstrap path; it creates one administrator with a personal
 * password and then closes forever (issue #74).
 */
@SpringBootTest(classes = DvaraAgentWorksApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // A dedicated context: the bootstrap is one-shot and needs an installation without users.
        properties = "agentworks.security.bootstrap.lifetime=PT1H")
@Import(IdentityTestConfiguration.class)
class BootstrapApiTest {
    private static final String PASSPHRASE = "the first administrator's passphrase";

    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    InstallationBootstrapService bootstrap;
    @Autowired
    MutableClock clock;

    @Test
    void theBootstrapTokenCreatesExactlyOneAdministratorOnce() throws Exception {
        var file = bootstrap.tokenFile();
        String issued = Files.readString(file).strip();
        assertThat(Files.getPosixFilePermissions(file))
                .containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
        assertThat(jdbc.queryForObject("select token_digest from installation_bootstrap", String.class))
                .hasSize(64).isNotEqualTo(issued);

        // Wrong, malformed, and (below) expired tokens fail identically and change nothing.
        var wrong = complete("awb_" + "A".repeat(43), "root");
        var malformed = complete("not-a-token", "root");
        clock.advance(Duration.ofMinutes(61));
        var expired = complete(issued, "root");
        for (var refused : List.of(wrong, malformed, expired)) {
            assertThat(refused.status()).isEqualTo(403);
            assertThat(refused.code()).isEqualTo("BOOTSTRAP_UNAVAILABLE");
            assertThat(refused.raw()).isEqualTo(wrong.raw());
        }
        assertThat(jdbc.queryForObject("select count(*) from user_account", Integer.class)).isZero();

        // An expired token is replaced at the next start, never while it is live.
        assertThat(bootstrap.prepare()).isPresent();
        assertThat(bootstrap.prepare()).isEmpty();
        String reissued = Files.readString(file).strip();
        assertThat(reissued).isNotEqualTo(issued);
        assertThat(complete(issued, "root").status()).isEqualTo(403);

        // A weak password is refused without consuming the token.
        var weak = new IdentityHttp(port).send("POST", "/api/v1/auth/bootstrap",
                Map.of("token", reissued, "username", "weak-root", "password", "password123"));
        assertThat(weak.status()).isEqualTo(422);
        assertThat(weak.code()).isEqualTo("PASSWORD_POLICY");
        assertThat(jdbc.queryForObject("select state from installation_bootstrap", String.class)).isEqualTo("PENDING");

        // Concurrent presentations of the valid token create exactly one administrator.
        var ready = new CountDownLatch(1);
        List<Callable<IdentityHttp.Response>> attempts = new ArrayList<>();
        for (int index = 0; index < 6; index++) {
            String username = "root-" + index;
            attempts.add(() -> {
                ready.await();
                return complete(reissued, username);
            });
        }
        List<IdentityHttp.Response> results = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(6)) {
            List<Future<IdentityHttp.Response>> futures = attempts.stream().map(executor::submit).toList();
            ready.countDown();
            for (var future : futures) results.add(future.get());
        }
        assertThat(results.stream().filter(response -> response.status() == 201)).hasSize(1);
        assertThat(results.stream().filter(response -> response.status() == 403)).hasSize(5);
        String administrator = results.stream().filter(response -> response.status() == 201).findFirst().orElseThrow()
                .json().path("username").asString();

        assertThat(jdbc.queryForObject("select count(*) from user_account", Integer.class)).isOne();
        assertThat(jdbc.queryForMap("select organization_role, must_change_password from user_account"))
                .containsEntry("ORGANIZATION_ROLE", "ADMIN").containsEntry("MUST_CHANGE_PASSWORD", false);
        assertThat(jdbc.queryForObject("select membership_role from workspace_membership", String.class))
                .isEqualTo("ADMIN");
        assertThat(jdbc.queryForMap("select state, token_digest from installation_bootstrap"))
                .containsEntry("STATE", "COMPLETED").containsEntry("TOKEN_DIGEST", null);
        assertThat(Files.exists(file)).isFalse();
        assertThat(complete(reissued, "late").status()).isEqualTo(403);
        assertThat(bootstrap.prepare()).as("the path never reopens once users exist").isEmpty();

        // The administrator signs in with the personal password; the token never authenticated anything.
        var browser = new IdentityHttp(port);
        assertThat(browser.login(administrator, PASSPHRASE).json().path("organizationAdmin").asBoolean()).isTrue();
        assertThat(new IdentityHttp(port).bearer(reissued).get("/api/v1/agents").status()).isEqualTo(401);
        assertThat(jdbc.queryForList("select event_type from auth_audit_event where event_type like 'BOOTSTRAP%'",
                String.class)).contains("BOOTSTRAP_ISSUED", "BOOTSTRAP_REJECTED", "BOOTSTRAP_COMPLETED");
    }

    private IdentityHttp.Response complete(String token, String username) {
        return new IdentityHttp(port).send("POST", "/api/v1/auth/bootstrap",
                Map.of("token", token, "username", username, "password", PASSPHRASE));
    }
}
