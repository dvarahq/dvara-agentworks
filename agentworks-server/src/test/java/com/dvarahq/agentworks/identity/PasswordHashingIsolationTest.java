package com.dvarahq.agentworks.identity;

import com.dvarahq.agentworks.storage.identity.JdbcIdentityRepository;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The adaptive password hash never runs inside a transaction or while holding a pooled connection, so a burst of
 * sign-in attempts cannot exhaust the connection pool, and every sign-in path performs exactly one hash.
 */
class PasswordHashingIsolationTest {
    private static final String PASSWORD = "a correct and long passphrase";
    private static final UUID ORGANIZATION = UUID.randomUUID();

    private HikariDataSource dataSource;
    private JdbcIdentityRepository repository;
    private SlowEncoder encoder;
    private IdentityService identities;

    /** A deliberately slow encoder that records whether a transaction or connection was held while it hashed. */
    static final class SlowEncoder implements PasswordEncoder {
        final AtomicInteger verifications = new AtomicInteger();
        final List<String> heldResources = new CopyOnWriteArrayList<>();
        volatile long delayMillis;

        @Override
        public String encode(CharSequence raw) {
            observe();
            return "{test}" + raw;
        }

        @Override
        public boolean matches(CharSequence raw, String encoded) {
            verifications.incrementAndGet();
            observe();
            return encoded != null && encoded.equals("{test}" + raw);
        }

        private void observe() {
            if (TransactionSynchronizationManager.isActualTransactionActive()
                    || !TransactionSynchronizationManager.getResourceMap().isEmpty()) {
                heldResources.add(Thread.currentThread().getName());
            }
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @BeforeEach
    void setUp() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        config.setUsername("sa");
        config.setMaximumPoolSize(2);
        config.setConnectionTimeout(750);
        dataSource = new HikariDataSource(config);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration/common").load().migrate();
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.update("insert into organization(id, name) values (?, ?)", ORGANIZATION, "org");
        repository = new JdbcIdentityRepository(jdbc);
        encoder = new SlowEncoder();
        var clock = new MutableClock();
        var policy = SecurityPolicy.defaults();
        var codec = new CredentialCodec();
        identities = new IdentityService(new TransactionTemplate(new JdbcTransactionManager(dataSource)), repository,
                encoder, new PasswordPolicy(policy.passwords()), policy.login(), codec,
                new AuthAuditLog(repository, clock), clock);
        encoder.heldResources.clear();
        encoder.verifications.set(0);
    }

    @AfterEach
    void tearDown() {
        dataSource.close();
    }

    @Test
    void aBurstOfSignInsLeavesPooledConnectionsForOtherRequests() throws Exception {
        String username = user("{test}" + PASSWORD);
        encoder.delayMillis = 1_500;
        var started = new CountDownLatch(6);
        List<Future<Boolean>> attempts = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(6)) {
            for (int index = 0; index < 6; index++) {
                attempts.add(executor.submit(() -> {
                    started.countDown();
                    return identities.verifyPassword(username, PASSWORD).isPresent();
                }));
            }
            started.await();
            Thread.sleep(300);
            // While six verifications hash, a pool of two still serves other requests within its timeout.
            var jdbc = new JdbcTemplate(dataSource);
            for (int query = 0; query < 3; query++) {
                assertThat(jdbc.queryForObject("select count(*) from user_account", Integer.class)).isOne();
            }
            for (var attempt : attempts) assertThat(attempt.get(30, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(encoder.heldResources).as("threads that hashed while holding a transaction or connection").isEmpty();
    }

    @Test
    void passwordChangeHashesOutsideTheTransaction() {
        String username = user("{test}" + PASSWORD);
        var session = identities.verifyPassword(username, PASSWORD).orElseThrow();
        var principal = identities.principalFor(session.id(), null,
                com.dvarahq.agentworks.domain.identity.AuthenticationMethod.SESSION, UUID.randomUUID()).orElseThrow();
        encoder.heldResources.clear();

        identities.changePassword(principal, PASSWORD, "an entirely different passphrase");

        assertThat(encoder.heldResources).isEmpty();
        assertThat(identities.verifyPassword(username, "an entirely different passphrase")).isPresent();
    }

    @Test
    void unknownUsersAndUsersWithoutALocalPasswordHashExactlyOnce() {
        String external = "external-" + UUID.randomUUID().toString().substring(0, 8);
        new JdbcTemplate(dataSource).update("insert into user_account(id, organization_id, username, display_name, "
                        + "organization_role, identity_provider, must_change_password, status, failed_login_count, "
                        + "created_at, updated_at) values (?, ?, ?, 'External', 'MEMBER', 'oidc', false, 'ACTIVE', 0, "
                        + "current_timestamp, current_timestamp)", UUID.randomUUID(), ORGANIZATION, external);
        String local = user("{test}" + PASSWORD);

        for (String username : List.of("nobody-" + UUID.randomUUID().toString().substring(0, 8), external, local)) {
            encoder.verifications.set(0);
            assertThat(identities.verifyPassword(username, "wrong password guess")).isEmpty();
            assertThat(encoder.verifications.get()).as(username).isOne();
        }
    }

    @Test
    void aPasswordChangedDuringVerificationFailsWithoutCountingAFailure() throws Exception {
        String username = user("{test}" + PASSWORD);
        UUID id = repository.findUserByUsername(username).orElseThrow().id();
        encoder.delayMillis = 800;
        try (var executor = Executors.newSingleThreadExecutor()) {
            var attempt = executor.submit(() -> identities.verifyPassword(username, PASSWORD));
            Thread.sleep(300);
            new JdbcTemplate(dataSource).update("update user_account set password_hash = '{test}replaced' where id = ?", id);
            assertThat(attempt.get(10, TimeUnit.SECONDS)).isEmpty();
        }
        assertThat(repository.findUser(id).orElseThrow().failedLoginCount()).isZero();
    }

    private String user(String hash) {
        String username = "user-" + UUID.randomUUID().toString().substring(0, 8);
        repository.insertUser(UUID.randomUUID(), ORGANIZATION, username, "User", "MEMBER", hash, false,
                java.time.Instant.now());
        return username;
    }
}
