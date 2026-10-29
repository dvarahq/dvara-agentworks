package com.dvarahq.agentworks.identity.web;

import com.dvarahq.agentworks.DvaraAgentWorksApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Behind a trusted proxy with a forwarded-headers strategy, sign-in limits apply per client, so one client exhausting
 * its limit does not lock out others; an IPv6 client cannot escape by rotating within its /64; and one account is
 * limited however many clients target it.
 */
@SpringBootTest(classes = DvaraAgentWorksApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"server.forward-headers-strategy=native", "agentworks.security.login.attempts-per-window=3",
                "agentworks.security.login.attempts-per-account-window=4"})
@Import(IdentityTestConfiguration.class)
class ProxiedLoginRateLimitTest {
    @LocalServerPort
    int port;

    @Test
    void oneClientExhaustingItsLimitDoesNotDenyOtherClients() {
        for (int attempt = 0; attempt < 3; attempt++) {
            assertThat(login("guess-a" + attempt, "198.51.100.1").status()).isEqualTo(401);
        }

        assertThat(login("guess-a3", "198.51.100.1").status()).isEqualTo(429);
        assertThat(login("guess-b0", "198.51.100.2").status()).as("another client behind the same proxy")
                .isEqualTo(401);
    }

    @Test
    void anIpv6ClientRotatingWithinItsSlash64SharesOneLimit() {
        for (int host = 1; host <= 3; host++) {
            assertThat(login("guess-v6-" + host, "2001:db8:5:6::" + host).status()).isEqualTo(401);
        }
        assertThat(login("guess-v6-4", "2001:db8:5:6::4").status()).isEqualTo(429);
        assertThat(login("guess-v6-5", "2001:db8:5:7::1").status()).as("another /64").isEqualTo(401);
    }

    @Test
    void oneAccountIsLimitedAcrossManyClients() {
        for (int client = 1; client <= 4; client++) {
            assertThat(login("targeted-account", "203.0.113." + client).status()).isEqualTo(401);
        }
        var limited = login("targeted-account", "203.0.113.5");

        assertThat(limited.status()).isEqualTo(429);
        assertThat(limited.code()).isEqualTo("TOO_MANY_ATTEMPTS");
    }

    private IdentityHttp.Response login(String username, String client) {
        return new IdentityHttp(port).send("POST", "/api/v1/auth/login",
                Map.of("username", username, "password", "a wrong password guess"), Map.of("X-Forwarded-For", client));
    }
}
