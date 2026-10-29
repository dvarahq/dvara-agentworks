package com.dvarahq.agentworks.identity;

import com.dvarahq.agentworks.storage.identity.JdbcIdentityRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The bootstrap token file follows the database: it is written under the bootstrap row's lock before the token
 * commits, a live token is never replaced by any node, the file is removed once users exist, and an unwritable location
 * is a typed configuration error.
 */
@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
@ResourceLock("output-capture")
class BootstrapTokenFileTest {
    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;
    private JdbcIdentityRepository repository;
    private MutableClock clock;

    @TempDir
    Path directory;

    @BeforeEach
    void setUp() {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration/common").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new JdbcTransactionManager(dataSource));
        repository = new JdbcIdentityRepository(jdbc);
        clock = new MutableClock();
    }

    @Test
    void anUnwritableLocationIsATypedErrorAndCommitsNoToken() throws Exception {
        Path blocker = Files.writeString(directory.resolve("not-a-directory"), "x");

        assertThatThrownBy(() -> service(blocker.resolve("token")).prepare())
                .isInstanceOf(InstallationBootstrapService.BootstrapConfigurationException.class)
                .hasMessageContaining("agentworks.security.bootstrap.token-file");
        // The file is written under the row lock, before the digest commits: a token nobody can read never commits.
        assertThat(jdbc.queryForObject("select count(*) from installation_bootstrap", Integer.class)).isZero();

        Path writable = directory.resolve("token");
        assertThat(service(writable).prepare()).isPresent();
        assertThat(Files.readString(writable)).startsWith("awb_");
        assertThat(service(writable).prepare()).as("a live token with its file is kept").isEmpty();
    }

    // #177 medium: nodes that share one database never replace each other's live token, whether or not they share
    // the token file's volume. A node without the file logs where the token lives instead of reissuing it.
    @Test
    void nodesSharingADatabaseNeverInvalidateEachOthersLiveToken() throws Exception {
        Path first = directory.resolve("node-a").resolve("token");
        Path second = directory.resolve("node-b").resolve("token");
        var nodeA = service(first);

        assertThat(nodeA.prepare()).isPresent();
        String token = Files.readString(first).strip();
        String digest = jdbc.queryForObject("select token_digest from installation_bootstrap", String.class);

        // Another node, with its own volume, starts (and restarts) while the token is live.
        assertThat(service(second).prepare()).isEmpty();
        assertThat(service(second).prepare()).isEmpty();
        assertThat(Files.exists(second)).isFalse();
        // A node on the same volume keeps the file as it is.
        assertThat(service(first).prepare()).isEmpty();
        assertThat(Files.readString(first).strip()).isEqualTo(token);
        assertThat(jdbc.queryForObject("select token_digest from installation_bootstrap", String.class))
                .isEqualTo(digest);
        // The database records where the live token was written, for the log line of a node that lacks it.
        assertThat(jdbc.queryForObject("select token_file from installation_bootstrap", String.class))
                .isEqualTo(first.toAbsolutePath().toString());
        assertThat(jdbc.queryForObject("select issued_by from installation_bootstrap", String.class)).isNotBlank();

        // The first node's token still works, presented to either node.
        assertThat(service(second).complete(token, "root", null, PASSPHRASE, "Acme", "Ops").username())
                .isEqualTo("root");
    }

    @Test
    void onlyAnExpiredTokenIsReissuedByWhicheverNodeStartsNext() throws Exception {
        Path first = directory.resolve("node-a").resolve("token");
        Path second = directory.resolve("node-b").resolve("token");
        assertThat(service(first).prepare()).isPresent();
        String expired = Files.readString(first).strip();

        clock.advance(Duration.ofHours(2));
        assertThat(service(second).prepare()).isPresent();
        String fresh = Files.readString(second).strip();

        assertThat(fresh).isNotEqualTo(expired);
        assertThat(jdbc.queryForObject("select token_file from installation_bootstrap", String.class))
                .isEqualTo(second.toAbsolutePath().toString());
        assertThat(service(first).prepare()).as("the new token is live").isEmpty();
        // Every node may use the same path: a file holding the expired token is not taken for the live one.
        var live = repository.findBootstrap().orElseThrow();
        assertThat(InstallationBootstrapService.holdsLiveToken(first, live)).isFalse();
        assertThat(InstallationBootstrapService.holdsLiveToken(second, live)).isTrue();
        assertThatThrownBy(() -> service(first).complete(expired, "root", null, PASSPHRASE, "Acme", "Ops"))
                .isInstanceOf(IdentityException.class);
        assertThat(service(first).complete(fresh, "root", null, PASSPHRASE, "Acme", "Ops").username()).isEqualTo("root");
    }

    // #177 medium: on a shared volume the file is written under the row lock, so the last writer always holds the
    // stored digest. Many nodes starting at once leave exactly one token, and the file holds it.
    @Test
    void concurrentStartsOnASharedVolumeLeaveTheFileHoldingTheStoredToken() throws Exception {
        Path shared = directory.resolve("shared").resolve("token");
        int nodes = 6;
        var start = new java.util.concurrent.CountDownLatch(1);
        var issued = new java.util.concurrent.atomic.AtomicInteger();
        try (var threads = java.util.concurrent.Executors.newFixedThreadPool(nodes)) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int node = 0; node < nodes; node++) {
                futures.add(threads.submit(() -> {
                    start.await();
                    if (service(shared).prepare().isPresent()) issued.incrementAndGet();
                    return null;
                }));
            }
            start.countDown();
            for (var future : futures) future.get(60, java.util.concurrent.TimeUnit.SECONDS);
        }

        assertThat(issued).hasValue(1);
        String token = Files.readString(shared).strip();
        assertThat(jdbc.queryForObject("select token_digest from installation_bootstrap", String.class))
                .isEqualTo(CredentialCodec.digest(token));
    }

    @Test
    void aLeftoverTokenFileIsDeletedOnceUsersExist() throws Exception {
        Path file = directory.resolve("token");
        assertThat(service(file).prepare()).isPresent();
        UUID organization = UUID.randomUUID();
        jdbc.update("insert into organization(id, name) values (?, ?)", organization, "org");
        // Another node completed the bootstrap; this node's file is stale.
        repository.insertUser(UUID.randomUUID(), organization, "someone", "Someone", "ADMIN", "{noop}x", false,
                clock.instant());

        assertThat(service(file).prepare()).isEmpty();
        assertThat(Files.exists(file)).isFalse();
    }

    @Test
    void unauthenticatedBootstrapAttemptsNeverHashAPassword() throws Exception {
        Path file = directory.resolve("token");
        var counting = new CountingEncoder();
        var bootstrap = service(file, counting);
        bootstrap.prepare();
        String token = Files.readString(file).strip();

        assertThatThrownBy(() -> bootstrap.complete("awb_" + "A".repeat(43), "root", null, PASSPHRASE, "Acme", "Ops"))
                .isInstanceOfSatisfying(IdentityException.class,
                        failure -> assertThat(failure.code()).isEqualTo(IdentityException.BOOTSTRAP_UNAVAILABLE));
        assertThat(counting.encodings).as("a bogus token").isZero();
        bootstrap.complete(token, "root", null, PASSPHRASE, "Acme", "Ops");
        assertThat(counting.encodings).isOne();
        assertThatThrownBy(() -> bootstrap.complete(token, "late", null, PASSPHRASE, "Acme", "Ops"))
                .isInstanceOf(IdentityException.class);
        assertThat(counting.encodings).as("after the bootstrap completed").isOne();
    }

    private static final String PASSPHRASE = "the first administrator's passphrase";

    /** Counts password encodings; verification is not needed here. */
    static final class CountingEncoder implements org.springframework.security.crypto.password.PasswordEncoder {
        private final Argon2PasswordEncoder delegate = new Argon2PasswordEncoder(16, 32, 1, 1024, 1);
        int encodings;

        @Override
        public String encode(CharSequence raw) {
            encodings++;
            return delegate.encode(raw);
        }

        @Override
        public boolean matches(CharSequence raw, String encoded) {
            return delegate.matches(raw, encoded);
        }
    }

    // #177 round 2: a node whose token file was lost with its container recovers only through the explicit override,
    // which invalidates the live token and is audited; without it, no node ever replaces a live token.
    @Test
    void theReissueOverrideReplacesALiveTokenAndIsAudited() throws Exception {
        Path lost = directory.resolve("node-a").resolve("token");
        assertThat(service(lost).prepare()).isPresent();
        String old = Files.readString(lost).strip();
        Files.delete(lost);
        assertThat(service(lost).prepare()).as("without the override").isEmpty();
        assertThat(Files.exists(lost)).isFalse();

        // #201 review: a value seen for the first time keeps the live token; #206: a start that the operator
        // confirmed with the logged code replaces it.
        assertThat(service(lost, "2026-09-26T10:00Z", () -> "node-a").prepare()).isEmpty();
        assertThat(service(lost, "2026-09-26T10:00Z", confirmation("2026-09-26T10:00Z"), () -> "node-a").prepare())
                .isPresent();
        String fresh = Files.readString(lost).strip();

        assertThat(fresh).isNotEqualTo(old);
        assertThat(jdbc.queryForObject("select count(*) from auth_audit_event where event_type = 'BOOTSTRAP_REISSUED'",
                Integer.class)).isOne();
        assertThat(jdbc.queryForObject("select issued_by from installation_bootstrap", String.class)).isEqualTo("node-a");
        assertThatThrownBy(() -> service(lost).complete(old, "root", null, PASSPHRASE, "Acme", "Ops"))
                .isInstanceOf(IdentityException.class);
        assertThat(service(lost).complete(fresh, "root", null, PASSPHRASE, "Acme", "Ops").username()).isEqualTo("root");
    }

    // #177 round 2: the node identity is the configured agentworks.node-id, read only when a token is issued.
    @Test
    void theNodeIdentityIsResolvedOnlyWhenATokenIsIssued() throws Exception {
        var lookups = new java.util.concurrent.atomic.AtomicInteger();
        java.util.function.Supplier<String> nodeId = () -> {
            lookups.incrementAndGet();
            return "stable-node-1";
        };
        Path file = directory.resolve("token");
        assertThat(service(file, null, nodeId).prepare()).isPresent();
        assertThat(lookups).hasValue(1);
        assertThat(jdbc.queryForObject("select issued_by from installation_bootstrap", String.class))
                .isEqualTo("stable-node-1");

        assertThat(service(file, null, nodeId).prepare()).isEmpty();
        assertThat(lookups).as("no token issued, so no lookup").hasValue(1);
    }

    // #185 medium 3: the reissue override replaced the live token on every start of every node that had it, so nodes
    // invalidated each other's files. Each value now replaces the token at most once, across restarts and nodes.
    @Test
    void aReissueValueReplacesTheLiveTokenOnlyOnceAcrossRestartsAndNodes() throws Exception {
        Path lost = directory.resolve("node-a").resolve("token");
        Path other = directory.resolve("node-b").resolve("token");
        assertThat(service(lost).prepare()).isPresent();
        Files.delete(lost);

        String once = "2026-09-26T10:00Z";
        // #201 review: a value seen for the first time keeps the live token; #206: a confirmed start replaces it.
        assertThat(service(lost, once, () -> "node-a").prepare()).isEmpty();
        String confirmed = confirmation(once);
        assertThat(service(lost, once, confirmed, () -> "node-a").prepare()).isPresent();
        String recovered = Files.readString(lost).strip();
        // The same settings, left in place on this node and applied to another, replace nothing again.
        assertThat(service(lost, once, confirmed, () -> "node-a").prepare()).isEmpty();
        assertThat(service(other, once, confirmed, () -> "node-b").prepare()).isEmpty();
        assertThat(service(other, once, () -> "node-b").prepare()).isEmpty();
        assertThat(Files.exists(other)).isFalse();
        assertThat(Files.readString(lost).strip()).isEqualTo(recovered);
        assertThat(reissues()).isOne();
        // The value's digest is recorded, never the value.
        assertThat(jdbc.queryForObject("select digest from installation_bootstrap_reissue", String.class))
                .isEqualTo(InstallationBootstrapService.reissueDigest(once)).doesNotContain(once);

        // A new value is a new, explicit decision: it replaces the token once more, once confirmed.
        assertThat(service(other, "2026-09-26T11:00Z", () -> "node-b").prepare()).isEmpty();
        assertThat(service(other, "2026-09-26T11:00Z", confirmation("2026-09-26T11:00Z"), () -> "node-b").prepare())
                .isPresent();
        assertThat(reissues()).isEqualTo(2);
        assertThat(service(other).complete(Files.readString(other).strip(), "root", null, PASSPHRASE, "Acme", "Ops")
                .username()).isEqualTo("root");
    }

    // #185 medium 3, review round 1: every consumed value is kept, not only the latest, so two nodes left with two
    // different values cannot take turns replacing each other's token on every start.
    @Test
    void twoNodesLeftWithDifferentValuesNeverTakeTurnsReplacingTheToken() throws Exception {
        Path first = directory.resolve("node-a").resolve("token");
        Path second = directory.resolve("node-b").resolve("token");
        assertThat(service(first).prepare()).isPresent();
        Files.delete(first);

        // #201 review: a value seen for the first time keeps the live token; #206: a confirmed start replaces it.
        assertThat(service(first, "value-1", () -> "node-a").prepare()).isEmpty();
        String confirmed1 = confirmation("value-1");
        assertThat(service(first, "value-1", confirmed1, () -> "node-a").prepare()).isPresent();
        Files.delete(first);
        assertThat(service(second, "value-2", () -> "node-b").prepare()).isEmpty();
        String confirmed2 = confirmation("value-2");
        assertThat(service(second, "value-2", confirmed2, () -> "node-b").prepare()).isPresent();
        String live = Files.readString(second).strip();
        for (int restart = 0; restart < 3; restart++) {
            assertThat(service(first, "value-1", confirmed1, () -> "node-a").prepare()).isEmpty();
            assertThat(service(second, "value-2", confirmed2, () -> "node-b").prepare()).isEmpty();
        }
        assertThat(reissues()).isEqualTo(2);
        assertThat(Files.readString(second).strip()).isEqualTo(live);
        assertThat(service(second).complete(live, "root", null, PASSPHRASE, "Acme", "Ops").username()).isEqualTo("root");
    }

    // #185 medium 3: a node whose own file already holds the live token never replaces it, and the value it was started
    // with is consumed, so no other node started with it replaces the token that file holds.
    @Test
    void aNodeWhoseFileHoldsTheLiveTokenNeverReplacesIt() throws Exception {
        Path holder = directory.resolve("node-a").resolve("token");
        Path other = directory.resolve("node-b").resolve("token");
        assertThat(service(holder).prepare()).isPresent();
        String token = Files.readString(holder).strip();

        String once = "operator-reissue-1";
        assertThat(service(holder, once, () -> "node-a").prepare()).isEmpty();
        assertThat(service(other, once, () -> "node-b").prepare()).isEmpty();
        assertThat(Files.readString(holder).strip()).isEqualTo(token);
        assertThat(Files.exists(other)).isFalse();
        assertThat(reissues()).isZero();
        // #185 review round 3: consuming a value without replacing the token is audited too.
        assertThat(jdbc.queryForObject("select count(*) from auth_audit_event where event_type = "
                + "'BOOTSTRAP_REISSUE_CONSUMED'", Integer.class)).isOne();
        assertThat(service(other).complete(token, "root", null, PASSPHRASE, "Acme", "Ops").username()).isEqualTo("root");
    }

    // #185 medium 3: a value in force when no token is live is consumed by the issue, so it cannot later replace the
    // token a node issued meanwhile.
    @Test
    void aReissueValueIsConsumedByAnOrdinaryIssueToo() throws Exception {
        Path first = directory.resolve("node-a").resolve("token");
        Path second = directory.resolve("node-b").resolve("token");
        String once = "2026-09-26T12:00Z";
        assertThat(service(first, once, () -> "node-a").prepare()).isPresent();
        assertThat(service(second, once, () -> "node-b").prepare()).isEmpty();
        assertThat(reissues()).isZero();
        // After the token expires, the next start issues a new one and keeps the value consumed.
        clock.advance(Duration.ofHours(2));
        assertThat(service(second).prepare()).isPresent();
        assertThat(service(first, once, () -> "node-a").prepare()).isEmpty();
        assertThat(reissues()).isZero();
    }

    // #185 review round 3: a node that loses the race to issue the first token rolls back; its value is still
    // recorded as consumed (and audited), so it cannot replace the winner's token at a later start.
    @Test
    void aNodeThatLosesTheFirstIssueRaceStillConsumesItsValue() throws Exception {
        Path winner = directory.resolve("node-a").resolve("token");
        Path loser = directory.resolve("node-b").resolve("token");
        var raced = new java.util.concurrent.atomic.AtomicBoolean();

        assertThat(service(racingRepository(winner, raced), transactions, loser, "loser-value").prepare()).isEmpty();

        assertThat(raced).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from installation_bootstrap_reissue where digest = ?",
                Integer.class, InstallationBootstrapService.reissueDigest("loser-value"))).isOne();
        assertThat(audits("BOOTSTRAP_REISSUE_CONSUMED", "SUCCEEDED")).isOne();
        // Restarted with the same value, it keeps the winner's token.
        assertThat(service(loser, "loser-value", () -> "node-b").prepare()).isEmpty();
        assertThat(reissues()).isZero();
        assertThat(jdbc.queryForObject("select issued_by from installation_bootstrap", String.class)).isEqualTo("node-a");
    }

    // #201 medium 1: a race loser that could not record its value as consumed used to fail its startup, and the
    // restart that followed replaced the winner's live token, unaudited. The value now replaces only the token that was
    // live when it was first seen, which is recorded before the value is acted on: here none. The failure no longer
    // stops the start and is audited, and a restart with the same value keeps the winner's token and consumes it.
    @Test
    void aRaceLoserThatCannotConsumeItsValueNeverReplacesTheWinnersTokenOnRestart() throws Exception {
        Path winner = directory.resolve("node-a").resolve("token");
        Path loser = directory.resolve("node-b").resolve("token");
        var raced = new java.util.concurrent.atomic.AtomicBoolean();
        // After the race, the consumption runs in its own transaction; the database is briefly unavailable for it.
        var failingAfterTheRace = new TransactionTemplate(transactions.getTransactionManager()) {
            private final java.util.concurrent.atomic.AtomicBoolean failed = new java.util.concurrent.atomic.AtomicBoolean();

            @Override
            public void executeWithoutResult(
                    java.util.function.Consumer<org.springframework.transaction.TransactionStatus> action) {
                if (raced.get() && failed.compareAndSet(false, true)) {
                    throw new org.springframework.dao.TransientDataAccessResourceException("connection reset");
                }
                super.executeWithoutResult(action);
            }
        };

        Throwable firstStart = org.assertj.core.api.Assertions.catchThrowable(() -> service(
                racingRepository(winner, raced), failingAfterTheRace, loser, "loser-value").prepare());
        assertThat(raced).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from installation_bootstrap_reissue where digest = ?",
                Integer.class, InstallationBootstrapService.reissueDigest("loser-value"))).isZero();

        // The operator restarts the node with the setting still in place: the winner's token stands.
        assertThat(service(loser, "loser-value", () -> "node-b").prepare()).isEmpty();
        assertThat(reissues()).isZero();
        assertThat(jdbc.queryForObject("select issued_by from installation_bootstrap", String.class)).isEqualTo("node-a");
        assertThat(jdbc.queryForObject("select token_digest from installation_bootstrap", String.class))
                .isEqualTo(CredentialCodec.digest("awb_winner"));
        assertThat(Files.exists(loser)).isFalse();
        // The first start did not fail, its failure is on record, and the restart consumed the value.
        assertThat(firstStart).isNull();
        assertThat(audits("BOOTSTRAP_REISSUE_CONSUMED", "FAILED")).isOne();
        assertThat(audits("BOOTSTRAP_REISSUE_CONSUMED", "SUCCEEDED")).isOne();
        assertThat(jdbc.queryForObject("select count(*) from installation_bootstrap_reissue where digest = ?",
                Integer.class, InstallationBootstrapService.reissueDigest("loser-value"))).isOne();
        assertThat(service(loser, "loser-value", () -> "node-b").prepare()).isEmpty();
        assertThat(reissues()).isZero();
    }

    // #201 medium 1: a value set to replace one token never replaces a later one. Here the node started with it fails
    // before acting on it; meanwhile that token expires and another node issues the next one, which the restart keeps.
    @Test
    void aReissueValueReplacesOnlyTheTokenThatWasLiveWhenItWasFirstSeen() throws Exception {
        Path lost = directory.resolve("node-a").resolve("token");
        Path other = directory.resolve("node-b").resolve("token");
        assertThat(service(lost).prepare()).isPresent();
        Files.delete(lost);
        // The value is first seen while that token is live; the confirming start cannot write its token file, so it
        // replaces nothing and consumes nothing.
        assertThat(service(lost, "once", () -> "node-a").prepare()).isEmpty();
        Path blocker = Files.writeString(directory.resolve("not-a-directory"), "x");
        String confirmed = confirmation("once");
        assertThatThrownBy(() -> service(blocker.resolve("token"), "once", confirmed, () -> "node-a").prepare())
                .isInstanceOf(InstallationBootstrapService.BootstrapConfigurationException.class);
        assertThat(reissues()).isZero();

        clock.advance(Duration.ofHours(2));
        assertThat(service(other).prepare()).isPresent();
        String next = Files.readString(other).strip();

        assertThat(service(lost, "once", confirmed, () -> "node-a").prepare()).isEmpty();
        assertThat(reissues()).isZero();
        assertThat(Files.exists(lost)).isFalse();
        assertThat(service(other).complete(next, "root", null, PASSPHRASE, "Acme", "Ops").username()).isEqualTo("root");
    }

    // #201 review, medium 1: a value set before the first sighting was recorded (before V20, or on a node of an earlier
    // build during a rolling upgrade) may predate the live token, for example the token of the node that won the race
    // to issue the first token. The start that first sees such a value never replaces the live token; it records the
    // sighting, keeps the token, and consumes nothing, so the operator decides. Only a later start replaces it.
    @Test
    void aValueSeenForTheFirstTimeNeverReplacesTheLiveTokenOnThatStart() throws Exception {
        Path winner = directory.resolve("node-a").resolve("token");
        Path loser = directory.resolve("node-b").resolve("token");
        // As before V20: another node's live token, and no sighting of the loser's value.
        assertThat(service(winner, null, () -> "node-a").prepare()).isPresent();
        String token = Files.readString(winner).strip();

        assertThat(service(loser, "left-from-before", () -> "node-b").prepare()).isEmpty();

        assertThat(reissues()).isZero();
        assertThat(Files.exists(loser)).isFalse();
        assertThat(jdbc.queryForObject("select issued_by from installation_bootstrap", String.class)).isEqualTo("node-a");
        assertThat(jdbc.queryForObject("select count(*) from installation_bootstrap_reissue", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select target_digest from installation_bootstrap_reissue_seen", String.class))
                .isEqualTo(CredentialCodec.digest(token));
        assertThat(service(winner).complete(token, "root", null, PASSPHRASE, "Acme", "Ops").username()).isEqualTo("root");
        // #201 review, low 9: once the bootstrap completes, no sighting names a token any more.
        assertThat(jdbc.queryForObject("select count(*) from installation_bootstrap_reissue_seen where target_digest "
                + "is not null", Integer.class)).isZero();
    }

    // #206 medium: the "second start" that confirmed a reissue value could be any start: another replica rolled out
    // with the same value replaced the token without an operator. A replacement now needs the operator's confirmation
    // code, which a start logs and no configuration knows in advance.
    @Test
    void aSecondReplicaWithTheSameValueNeverReplacesTheTokenUnconfirmed() throws Exception {
        Path holder = directory.resolve("node-a").resolve("token");
        assertThat(service(holder).prepare()).isPresent();
        String token = Files.readString(holder).strip();
        String value = "2026-09-28T09:00Z";

        // The value is rolled out to several replicas, none of which has the file.
        for (String node : java.util.List.of("node-b", "node-c", "node-d")) {
            assertThat(service(directory.resolve(node).resolve("token"), value, () -> node).prepare()).as(node)
                    .isEmpty();
        }

        assertThat(reissues()).isZero();
        assertThat(jdbc.queryForObject("select token_digest from installation_bootstrap", String.class))
                .isEqualTo(CredentialCodec.digest(token));
        assertThat(jdbc.queryForObject("select count(*) from installation_bootstrap_reissue", Integer.class)).isZero();
        assertThat(service(holder).complete(token, "root", null, PASSPHRASE, "Acme", "Ops").username()).isEqualTo("root");
    }

    // #206 medium: an orchestrator that restarts a crashed node with the same settings never replaces the token.
    @Test
    void anAutomaticRestartNeverReplacesTheTokenUnconfirmed() throws Exception {
        Path lost = directory.resolve("node-a").resolve("token");
        assertThat(service(lost).prepare()).isPresent();
        String token = Files.readString(lost).strip();
        Files.delete(lost);
        String value = "2026-09-28T09:00Z";

        for (int restart = 0; restart < 4; restart++) {
            assertThat(service(lost, value, () -> "node-a").prepare()).as("start %d", restart).isEmpty();
        }
        // A wrong code, such as one for another value, confirms nothing either.
        assertThat(service(lost, value, confirmation("another-value"), () -> "node-a").prepare()).isEmpty();

        assertThat(reissues()).isZero();
        assertThat(Files.exists(lost)).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from installation_bootstrap_reissue", Integer.class)).isZero();
        assertThat(service(lost).complete(token, "root", null, PASSPHRASE, "Acme", "Ops").username()).isEqualTo("root");
    }

    // #206 medium: the operator confirms with the code that a start logged, which replaces the token once; the
    // settings left in place, on this node or any other, replace nothing again.
    @Test
    void anOperatorsConfirmationReplacesTheToken(org.springframework.boot.test.system.CapturedOutput output)
            throws Exception {
        Path lost = directory.resolve("node-a").resolve("token");
        Path other = directory.resolve("node-b").resolve("token");
        assertThat(service(lost).prepare()).isPresent();
        String old = Files.readString(lost).strip();
        Files.delete(lost);
        String value = "2026-09-28T09:00Z";

        assertThat(service(lost, value, () -> "node-a").prepare()).isEmpty();
        String code = loggedConfirmation(output);
        // The log names the code, never the token or the value's digest.
        assertThat(output.getAll()).doesNotContain(old).doesNotContain(CredentialCodec.digest(old))
                .doesNotContain(InstallationBootstrapService.reissueDigest(value));
        // Codes are compared ignoring case.
        assertThat(service(lost, value, code.toUpperCase(java.util.Locale.ROOT), () -> "node-a").prepare())
                .isPresent();
        String fresh = Files.readString(lost).strip();

        assertThat(fresh).isNotEqualTo(old);
        assertThat(reissues()).isOne();
        assertThat(service(lost, value, code, () -> "node-a").prepare()).isEmpty();
        assertThat(service(other, value, code, () -> "node-b").prepare()).isEmpty();
        assertThat(reissues()).isOne();
        assertThatThrownBy(() -> service(lost).complete(old, "root", null, PASSPHRASE, "Acme", "Ops"))
                .isInstanceOf(IdentityException.class);
        assertThat(service(lost).complete(fresh, "root", null, PASSPHRASE, "Acme", "Ops").username()).isEqualTo("root");
    }

    @Test
    void theReissueConfirmationIsASixteenDigitCodeOrNothing() {
        Path file = directory.resolve("token");
        assertThat(new SecurityPolicy.Bootstrap(true, Duration.ofHours(1), file, "v", " 0123456789ABCDEF ")
                .reissueConfirm()).isEqualTo("0123456789abcdef");
        assertThat(new SecurityPolicy.Bootstrap(true, Duration.ofHours(1), file, "v", " ").reissueConfirm()).isNull();
        assertThat(new SecurityPolicy.Bootstrap(true, Duration.ofHours(1), file, "v").reissueConfirm()).isNull();
        for (String malformed : java.util.List.of("true", "0123456789abcde", "0123456789abcdefa", "0123456789abcdeg")) {
            assertThatThrownBy(() -> new SecurityPolicy.Bootstrap(true, Duration.ofHours(1), file, "v", malformed))
                    .as(malformed).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("reissue-confirm");
        }
        // A code names the value and the token: another value or another token has another code.
        String code = InstallationBootstrapService.reissueConfirmation("v", "a".repeat(64));
        assertThat(code).matches(SecurityPolicy.Bootstrap.REISSUE_CONFIRM_PATTERN)
                .isNotEqualTo(InstallationBootstrapService.reissueConfirmation("w", "a".repeat(64)))
                .isNotEqualTo(InstallationBootstrapService.reissueConfirmation("v", "b".repeat(64)));
    }

    /**
     * A repository whose first bootstrap insert loses a race: another node, on its own thread, connection, and
     * transaction, commits the first token right before it.
     */
    private JdbcIdentityRepository racingRepository(Path winner, java.util.concurrent.atomic.AtomicBoolean raced) {
        return new JdbcIdentityRepository(new JdbcTemplate(jdbc.getDataSource()) {
            @Override
            public int update(String sql, Object... arguments) {
                if (sql.startsWith("insert into installation_bootstrap(") && raced.compareAndSet(false, true)) {
                    var other = Thread.ofPlatform().start(() -> repository.insertBootstrap(
                            CredentialCodec.digest("awb_winner"), clock.instant(),
                            clock.instant().plus(Duration.ofHours(1)), "node-a", winner.toString()));
                    try {
                        other.join();
                    } catch (InterruptedException interrupted) {
                        throw new IllegalStateException(interrupted);
                    }
                }
                return super.update(sql, arguments);
            }
        });
    }

    private InstallationBootstrapService service(JdbcIdentityRepository repository, TransactionTemplate transactions,
            Path file, String reissue) {
        var policy = SecurityPolicy.defaults();
        return new InstallationBootstrapService(transactions, repository, new Argon2PasswordEncoder(16, 32, 1, 1024, 1),
                new PasswordPolicy(policy.passwords()), new CredentialCodec(),
                new SecurityPolicy.Bootstrap(true, Duration.ofHours(1), file, reissue),
                new AuthAuditLog(repository, clock), clock, new HashingBulkhead(4), () -> "node-b");
    }

    private int audits(String type, String outcome) {
        return jdbc.queryForObject("select count(*) from auth_audit_event where event_type = ? and outcome = ?",
                Integer.class, type, outcome);
    }

    @Test
    void theReissueSettingIsAOneTimeValueOrNothing() {
        Path file = directory.resolve("token");
        // #185 review round 3: every value Spring's boolean conversion reads as false still disables the override.
        for (String disabled : java.util.List.of("false", "FALSE", "False", "off", "OFF", "no", "No", "n", "N", "0",
                " off ")) {
            assertThat(new SecurityPolicy.Bootstrap(true, Duration.ofHours(1), file, disabled).reissue())
                    .as(disabled).isNull();
        }
        assertThat(new SecurityPolicy.Bootstrap(true, Duration.ofHours(1), file, "yes").reissue()).isEqualTo("yes");
        assertThat(new SecurityPolicy.Bootstrap(true, Duration.ofHours(1), file, " ").reissue()).isNull();
        assertThat(new SecurityPolicy.Bootstrap(true, Duration.ofHours(1), file, " 2026-09-26T10:00Z ").reissue())
                .isEqualTo("2026-09-26T10:00Z");
        assertThatThrownBy(() -> new SecurityPolicy.Bootstrap(true, Duration.ofHours(1), file, "two words"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("one-time value");
        assertThatThrownBy(() -> new SecurityPolicy.Bootstrap(true, Duration.ofHours(1), file, "x".repeat(129)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private int reissues() {
        return jdbc.queryForObject("select count(*) from auth_audit_event where event_type = 'BOOTSTRAP_REISSUED'",
                Integer.class);
    }

    private InstallationBootstrapService service(Path file, String reissue, java.util.function.Supplier<String> nodeId) {
        return service(file, reissue, null, nodeId);
    }

    private InstallationBootstrapService service(Path file, String reissue, String confirm,
            java.util.function.Supplier<String> nodeId) {
        var policy = SecurityPolicy.defaults();
        return new InstallationBootstrapService(transactions, repository, new Argon2PasswordEncoder(16, 32, 1, 1024, 1),
                new PasswordPolicy(policy.passwords()), new CredentialCodec(),
                new SecurityPolicy.Bootstrap(true, Duration.ofHours(1), file, reissue, confirm),
                new AuthAuditLog(repository, clock), clock, new HashingBulkhead(4), nodeId);
    }

    /** The operator's confirmation of {@code value} for the live token, as a start with the value logs it (#206). */
    private String confirmation(String value) {
        return InstallationBootstrapService.reissueConfirmation(value,
                jdbc.queryForObject("select token_digest from installation_bootstrap", String.class));
    }

    /** The confirmation code in the last log line that names one. */
    private static String loggedConfirmation(org.springframework.boot.test.system.CapturedOutput output) {
        var codes = java.util.regex.Pattern.compile("reissue-confirm=([0-9a-f]{16})").matcher(output.getAll())
                .results().map(match -> match.group(1)).toList();
        assertThat(codes).as("a logged confirmation code").isNotEmpty();
        return codes.getLast();
    }

    private InstallationBootstrapService service(Path file) {
        return service(file, new Argon2PasswordEncoder(16, 32, 1, 1024, 1));
    }

    private InstallationBootstrapService service(Path file,
            org.springframework.security.crypto.password.PasswordEncoder encoder) {
        var policy = SecurityPolicy.defaults();
        return new InstallationBootstrapService(transactions, repository,
                encoder, new PasswordPolicy(policy.passwords()),
                new CredentialCodec(), new SecurityPolicy.Bootstrap(true, Duration.ofHours(1), file),
                new AuthAuditLog(repository, clock), clock);
    }
}
