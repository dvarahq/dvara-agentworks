package com.dvarahq.agentworks.controlplane.agent.web;

import com.dvarahq.agentworks.DvaraAgentWorksApplication;
import com.dvarahq.agentworks.identity.InstallationBootstrapService;
import com.dvarahq.agentworks.identity.web.IdentityHttp;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shipped configuration authenticates every request: the bootstrapped default workspace is reachable only by an
 * authenticated member, including under the loopback-only {@code local} development profile (issue #74).
 */
class ShippedConfigurationAccessTest {

    @Nested
    @SpringBootTest(classes = DvaraAgentWorksApplication.class,
            webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    class DefaultProfile {
        @LocalServerPort
        int port;
        @Autowired
        JdbcTemplate jdbc;
        @Autowired
        ApplicationContext context;
        @Autowired
        InstallationBootstrapService bootstrap;

        @Test
        void bootstrappedDefaultWorkspaceIsNotServedWithoutAuthentication() throws Exception {
            var api = new AgentApiClient(port);

            var responses = List.of(
                    api.post("/api/v1/agents", "application/json", "{\"name\":\"agent\"}", null),
                    api.get("/api/v1/agents", null),
                    api.post("/api/v1/environments", "application/json", "{\"name\":\"prod\"}", null),
                    api.get("/api/v1/deployments", null));

            assertThat(responses).allSatisfy(response -> {
                assertThat(response.status()).isEqualTo(401);
                assertThat(response.code()).isEqualTo("AUTHENTICATION_REQUIRED");
                assertThat(response.header("WWW-Authenticate")).startsWith("Bearer");
            });
            assertThat(jdbc.queryForObject("select count(*) from workspace", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from agent_definition", Integer.class)).isZero();
            // A clean installation has no user, no membership, and exactly one pending, time-bounded bootstrap path.
            assertThat(jdbc.queryForObject("select count(*) from user_account", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from workspace_membership", Integer.class)).isZero();
            assertThat(jdbc.queryForList("select state from installation_bootstrap", String.class))
                    .containsExactly("PENDING");
            assertThat(Files.readString(bootstrap.tokenFile())).startsWith("awb_");
            // Spring Boot's generated in-memory user (and its logged password) never exists.
            assertThat(context.getBeanNamesForType(UserDetailsService.class)).isEmpty();
        }
    }

    @Nested
    @SpringBootTest(classes = DvaraAgentWorksApplication.class,
            webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @ActiveProfiles("local")
    class LocalProfile {
        @LocalServerPort
        int port;
        @Autowired
        Environment environment;
        @Autowired
        InstallationBootstrapService bootstrap;

        @Test
        void loopbackDevelopmentStillAuthenticatesThroughTheBootstrapAdministrator() throws Exception {
            var anonymous = new AgentApiClient(port).post("/api/v1/environments", "application/json",
                    "{\"name\":\"local\"}", null);
            assertThat(anonymous.status()).isEqualTo(401);

            var browser = new IdentityHttp(port);
            var admin = browser.send("POST", "/api/v1/auth/bootstrap", Map.of("token",
                    Files.readString(bootstrap.tokenFile()).strip(), "username", "local-admin", "password",
                    "a sufficiently long local passphrase"));
            var login = browser.login("local-admin", "a sufficiently long local passphrase");
            var created = browser.send("POST", "/api/v1/environments", Map.of("name", "local"));
            var promoted = browser.send("POST", "/api/v1/environments/" + created.json().path("id").asString()
                            + "/promotions", Map.of("deployment", "api", "releaseId", "00000000-0000-0000-0000-000000000000"),
                    Map.of("Idempotency-Key", "local-key"));

            assertThat(admin.status()).isEqualTo(201);
            assertThat(login.status()).isEqualTo(200);
            // Plain-HTTP loopback: the cookie is HttpOnly and SameSite=Strict but not Secure, so no __Host- prefix.
            assertThat(login.setCookie()).startsWith("agentworks-session=").contains("HttpOnly", "SameSite=Strict")
                    .doesNotContain("Secure");
            assertThat(created.status()).isEqualTo(201);
            // Bound to loopback only, and a proxy's forwarded headers never rewrite the remote address.
            assertThat(environment.getProperty("server.address")).isEqualTo("127.0.0.1");
            assertThat(environment.getProperty("server.forward-headers-strategy")).isEqualTo("none");
            // Reaching release lookup proves the scope resolved and the configured HMAC keys were accepted.
            assertThat(promoted.status()).isEqualTo(404);
        }
    }
}
