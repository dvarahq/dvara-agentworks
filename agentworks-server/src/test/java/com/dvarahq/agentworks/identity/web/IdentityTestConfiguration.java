package com.dvarahq.agentworks.identity.web;

import com.dvarahq.agentworks.identity.ApiTokenService;
import com.dvarahq.agentworks.identity.MutableClock;
import com.dvarahq.agentworks.identity.SessionService;
import com.dvarahq.agentworks.identity.TestIdentities;
import com.dvarahq.agentworks.storage.identity.JdbcIdentityRepository;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/** A controllable clock and real-principal provisioning for identity API tests. */
@TestConfiguration(proxyBeanMethods = false)
public class IdentityTestConfiguration {
    public static final String PASSWORD = "correct horse battery staple 42";

    @Bean
    MutableClock testClock() {
        return new MutableClock();
    }

    @Bean
    TestIdentities testIdentities(JdbcIdentityRepository repository, SessionService sessions, ApiTokenService tokens,
            PasswordEncoder encoder, MutableClock clock) {
        return new TestIdentities(repository, sessions, tokens, encoder, clock);
    }

    @Bean
    com.dvarahq.agentworks.storage.lifecycle.IdempotencyDigests testIdempotencyDigests() {
        return new com.dvarahq.agentworks.storage.lifecycle.IdempotencyDigests(
                Map.of(1, "test-only-idempotency-hmac-key-32-bytes!".getBytes(StandardCharsets.UTF_8)), 1);
    }
}
