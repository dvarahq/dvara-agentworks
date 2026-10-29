package com.dvarahq.agentworks.identity.web;

import com.dvarahq.agentworks.DvaraAgentWorksApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Sign-in and bootstrap attempts from one client address are rate limited before any password is checked. */
@SpringBootTest(classes = DvaraAgentWorksApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "agentworks.security.login.attempts-per-window=3")
@Import(IdentityTestConfiguration.class)
class LoginRateLimitTest {
    @LocalServerPort
    int port;

    @Test
    void aBurstOfAttemptsFromOneAddressIsThrottled() {
        for (int attempt = 0; attempt < 3; attempt++) {
            assertThat(new IdentityHttp(port).login("guess-" + attempt, "wrong password guess").status()).isEqualTo(401);
        }

        var throttled = new IdentityHttp(port).login("guess-4", "wrong password guess");
        var bootstrap = new IdentityHttp(port).send("POST", "/api/v1/auth/bootstrap",
                Map.of("token", "awb_" + "A".repeat(43), "username", "root", "password", "a long enough passphrase"));

        assertThat(throttled.status()).isEqualTo(429);
        assertThat(throttled.code()).isEqualTo("TOO_MANY_ATTEMPTS");
        // #177: one neutral text for every limit, which names neither the client nor the account.
        assertThat(throttled.json().path("detail").asString()).isEqualTo("Too many attempts; retry later.");
        assertThat(Long.parseLong(throttled.header("Retry-After"))).isBetween(1L, 61L);
        assertThat(bootstrap.status()).isEqualTo(429);
    }
}
