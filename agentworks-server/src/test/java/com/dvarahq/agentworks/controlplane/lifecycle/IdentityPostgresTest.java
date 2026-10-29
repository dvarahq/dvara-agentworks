package com.dvarahq.agentworks.controlplane.lifecycle;

import com.dvarahq.agentworks.domain.identity.AuthenticatedPrincipal;
import com.dvarahq.agentworks.domain.identity.WorkspaceRole;
import com.dvarahq.agentworks.identity.ApiTokenService;
import com.dvarahq.agentworks.identity.AuthAuditLog;
import com.dvarahq.agentworks.identity.CredentialCodec;
import com.dvarahq.agentworks.identity.IdentityException;
import com.dvarahq.agentworks.identity.IdentityService;
import com.dvarahq.agentworks.identity.InstallationBootstrapService;
import com.dvarahq.agentworks.identity.MembershipService;
import com.dvarahq.agentworks.identity.MutableClock;
import com.dvarahq.agentworks.identity.PasswordPolicy;
import com.dvarahq.agentworks.identity.SecurityPolicy;
import com.dvarahq.agentworks.identity.SessionService;
import com.dvarahq.agentworks.storage.identity.JdbcIdentityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.dvarahq.agentworks.controlplane.lifecycle.PromotionLifecyclePostgresTest.race;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Issue #74 identity authority on PostgreSQL: plane-role privileges, database-enforced credential and membership
 * invariants, append-only audit, and concurrency of lockout, bootstrap, and administrator removal.
 */
class IdentityPostgresTest {
    private static final String PASSWORD = "a correct and long passphrase";
    private static final PasswordEncoder ENCODER = new DelegatingPasswordEncoder("argon2",
            Map.of("argon2", new Argon2PasswordEncoder(16, 32, 1, 19_456, 2)));

    private LifecycleFixture fixture;
    private Services services;
    private UUID organization;
    private UUID workspace;

    record Services(JdbcTemplate jdbc, JdbcIdentityRepository repository, IdentityService identities,
            SessionService sessions, ApiTokenService tokens, MembershipService memberships,
            InstallationBootstrapService bootstrap, MutableClock clock) {
        static Services on(DataSource dataSource, Path tokenFile) {
            return on(dataSource, tokenFile, null);
        }

        static Services on(DataSource dataSource, Path tokenFile, String reissue) {
            return on(dataSource, tokenFile, reissue, null);
        }

        static Services on(DataSource dataSource, Path tokenFile, String reissue, String confirm) {
            var transactions = new TransactionTemplate(new JdbcTransactionManager(dataSource));
            var jdbc = new JdbcTemplate(dataSource);
            var repository = new JdbcIdentityRepository(jdbc);
            var clock = new MutableClock();
            var policy = SecurityPolicy.defaults();
            var audit = new AuthAuditLog(repository, clock);
            var codec = new CredentialCodec();
            var passwords = new PasswordPolicy(policy.passwords());
            return new Services(jdbc, repository,
                    new IdentityService(transactions, repository, ENCODER, passwords, policy.login(), codec, audit, clock),
                    new SessionService(transactions, repository, codec, policy.session(), audit, clock),
                    new ApiTokenService(transactions, repository, codec, policy.tokens(), audit, clock),
                    new MembershipService(transactions, repository, audit, clock),
                    new InstallationBootstrapService(transactions, repository, ENCODER, passwords, codec,
                            new SecurityPolicy.Bootstrap(true, Duration.ofHours(1), tokenFile, reissue, confirm), audit,
                            clock),
                    clock);
        }
    }

    @BeforeEach
    void setUp(@TempDir Path directory) {
        fixture = LifecycleFixture.postgres();
        services = Services.on(fixture.dataSource, directory.resolve("bootstrap"));
        organization = UUID.randomUUID();
        workspace = organization(organization);
    }

    @Test
    void planeRolesHaveLeastPrivilegeOnIdentityTables() {
        for (String table : List.of("user_account", "auth_session", "api_token", "installation_bootstrap",
                "auth_audit_event", "workspace_membership")) {
            for (String privilege : List.of("SELECT", "INSERT", "UPDATE", "DELETE")) {
                assertThat(privilege("agentworks_run_plane", table, privilege)).as("run plane %s %s", privilege, table)
                        .isFalse();
            }
            assertThat(privilege("agentworks_control_plane", table, "SELECT")).as(table).isTrue();
            assertThat(privilege("agentworks_control_plane", table, "INSERT")).as(table).isTrue();
        }
        assertThat(privilege("agentworks_control_plane", "auth_audit_event", "UPDATE")).isFalse();
        assertThat(privilege("agentworks_control_plane", "auth_audit_event", "DELETE")).isFalse();
        assertThat(privilege("agentworks_control_plane", "user_account", "DELETE")).isFalse();
        assertThat(privilege("agentworks_control_plane", "api_token", "DELETE")).isFalse();
        UUID user = user(organization, WorkspaceRole.VIEWER);
        assertThatThrownBy(() -> fixture.asRole("agentworks_run_plane",
                "update user_account set display_name = 'x' where id = ?", user)).isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("permission denied");
        fixture.asRole("agentworks_control_plane", "update user_account set display_name = 'renamed' where id = ?", user);
    }

    @Test
    void theAuditTrailIsAppendOnlyAndUserIdentityIsImmutable() {
        UUID user = user(organization, WorkspaceRole.VIEWER);
        UUID other = organizationOnly();
        services.identities().verifyPassword(username(user), "wrong password entirely");
        UUID event = services.jdbc().queryForObject("select id from auth_audit_event where target_id = ?", UUID.class, user);

        for (String sql : List.of("update auth_audit_event set detail = 'edited' where id = ?",
                "delete from auth_audit_event where id = ?")) {
            assertThatThrownBy(() -> services.jdbc().update(sql, event)).hasMessageContaining("agentworks:IMMUTABLE_HISTORY");
        }
        assertThatThrownBy(() -> services.jdbc().execute("truncate auth_audit_event"))
                .hasMessageContaining("agentworks:IMMUTABLE_HISTORY");
        assertThatThrownBy(() -> services.jdbc().update("update user_account set organization_id = ? where id = ?",
                other, user)).hasMessageContaining("agentworks:IMMUTABLE_IDENTITY");
        assertThatThrownBy(() -> services.jdbc().update("update user_account set username = 'renamed' where id = ?", user))
                .hasMessageContaining("agentworks:IMMUTABLE_IDENTITY");
        assertThatThrownBy(() -> services.jdbc().update("delete from user_account where id = ?", user))
                .hasMessageContaining("agentworks:IMMUTABLE_IDENTITY");
    }

    @Test
    void theDatabaseRejectsCrossOrganizationMembershipsAndCredentialsWithoutMembership() {
        UUID otherWorkspace = organization(UUID.randomUUID());
        UUID member = user(organization, WorkspaceRole.AUTHOR);
        UUID outsider = user(organization, null);
        var now = java.sql.Timestamp.from(services.clock().instant());

        // A user of this organization cannot be a member of another organization's workspace.
        assertThatThrownBy(() -> services.jdbc().update("insert into workspace_membership(organization_id, workspace_id, "
                        + "subject_id, membership_role, created_at) values (?, ?, ?, 'VIEWER', ?)", organization,
                otherWorkspace, member, now)).isInstanceOf(DataIntegrityViolationException.class);
        UUID otherOrganization = services.repository().workspaceOrganization(otherWorkspace).orElseThrow();
        assertThatThrownBy(() -> services.jdbc().update("insert into workspace_membership(organization_id, workspace_id, "
                        + "subject_id, membership_role, created_at) values (?, ?, ?, 'VIEWER', ?)", otherOrganization,
                otherWorkspace, member, now)).isInstanceOf(DataIntegrityViolationException.class);
        // Tokens and sessions must be bound to an existing membership.
        assertThatThrownBy(() -> services.repository().insertToken(UUID.randomUUID(), "a".repeat(64), "awt_abcdef",
                "t", organization, workspace, outsider, "VIEWER", services.clock().instant(),
                services.clock().instant().plusSeconds(60))).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> services.repository().insertSession(UUID.randomUUID(), "b".repeat(64), organization,
                outsider, workspace, services.clock().instant(), services.clock().instant().plusSeconds(60)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> services.repository().insertToken(UUID.randomUUID(), "c".repeat(64), "awt_abcdef",
                "t", organization, workspace, member, "OWNER", services.clock().instant(),
                services.clock().instant().plusSeconds(60))).isInstanceOf(DataIntegrityViolationException.class);

        // Removing a membership removes the credentials bound to it.
        var session = services.sessions().establish(member, workspace, null, "LOGIN_SUCCEEDED", "password");
        services.tokens().issue(session.principal(), "cli", null, null);
        services.jdbc().update("delete from workspace_membership where workspace_id = ? and subject_id = ?", workspace,
                member);
        assertThat(services.jdbc().queryForObject("select count(*) from api_token where user_id = ?", Integer.class,
                member)).isZero();
        assertThat(services.jdbc().queryForObject("select count(*) from auth_session where user_id = ? and workspace_id "
                + "is not null", Integer.class, member)).isZero();
    }

    @Test
    void credentialBindingsAreImmutableAndRevocationIsFinal() {
        UUID member = user(organization, WorkspaceRole.OPERATOR);
        var session = services.sessions().establish(member, workspace, null, "LOGIN_SUCCEEDED", "password");
        var token = services.tokens().issue(session.principal(), "cli", null, null);
        UUID tokenId = token.row().id();
        UUID otherWorkspace = UUID.randomUUID();
        services.jdbc().update("insert into workspace(id, organization_id, name) values (?, ?, ?)", otherWorkspace,
                organization, "second");

        services.jdbc().update("update api_token set last_used_at = current_timestamp where id = ?", tokenId);
        assertThatThrownBy(() -> services.jdbc().update("update api_token set token_role = 'ADMIN' where id = ?", tokenId))
                .hasMessageContaining("agentworks:IMMUTABLE_CREDENTIAL");
        assertThatThrownBy(() -> services.jdbc().update("update api_token set expires_at = expires_at + interval '1 year' "
                + "where id = ?", tokenId)).hasMessageContaining("agentworks:IMMUTABLE_CREDENTIAL");
        services.tokens().revoke(session.principal(), tokenId);
        assertThat(services.tokens().authenticate(token.token())).isEmpty();
        assertThatThrownBy(() -> services.jdbc().update("update api_token set revoked_at = null, revoked_by = null "
                + "where id = ?", tokenId)).hasMessageContaining("agentworks:IMMUTABLE_CREDENTIAL");
        assertThatThrownBy(() -> services.jdbc().update("update auth_session set workspace_id = ? where id = ?",
                otherWorkspace, session.sessionId())).hasMessageContaining("agentworks:IMMUTABLE_CREDENTIAL");
        services.sessions().logout(session.principal());
        assertThat(services.sessions().authenticate(session.cookieValue())).isEmpty();
        assertThatThrownBy(() -> services.jdbc().update("update auth_session set revoked_at = null, revoked_reason = null "
                + "where id = ?", session.sessionId())).hasMessageContaining("agentworks:IMMUTABLE_CREDENTIAL");
    }

    @Test
    void concurrentWrongPasswordsAreEachCountedAndLockTheAccount() throws Exception {
        UUID member = user(organization, WorkspaceRole.VIEWER);

        List<Object> outcomes = race(8, index -> services.identities().verifyPassword(username(member),
                "wrong guess number " + index));

        assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome).isEqualTo(java.util.Optional.empty()));
        // Five failures lock the account; failures while locked neither count nor extend the lock.
        assertThat(services.jdbc().queryForObject("select failed_login_count from user_account where id = ?",
                Integer.class, member)).isEqualTo(5);
        var lockedUntil = services.jdbc().queryForObject("select locked_until from user_account where id = ?",
                java.sql.Timestamp.class, member);
        services.clock().advance(Duration.ofMinutes(10));
        assertThat(services.identities().verifyPassword(username(member), "still guessing")).isEmpty();
        assertThat(services.identities().verifyPassword(username(member), PASSWORD)).isEmpty();
        assertThat(services.jdbc().queryForObject("select locked_until from user_account where id = ?",
                java.sql.Timestamp.class, member)).isEqualTo(lockedUntil);
        services.clock().advance(Duration.ofMinutes(6));
        // After the lock expires, one more typo starts a new count instead of locking again.
        assertThat(services.identities().verifyPassword(username(member), "one typo")).isEmpty();
        assertThat(services.jdbc().queryForObject("select failed_login_count from user_account where id = ?",
                Integer.class, member)).isOne();
        assertThat(services.identities().verifyPassword(username(member), PASSWORD)).isPresent();
    }

    @Test
    void concurrentAdministratorsCannotRemoveEachOtherIntoAnEmptyWorkspace() throws Exception {
        UUID first = user(organization, WorkspaceRole.ADMIN);
        UUID second = user(organization, WorkspaceRole.ADMIN);
        AuthenticatedPrincipal firstAdmin = services.sessions().establish(first, workspace, null, "LOGIN_SUCCEEDED",
                "password").principal();
        AuthenticatedPrincipal secondAdmin = services.sessions().establish(second, workspace, null, "LOGIN_SUCCEEDED",
                "password").principal();

        List<Object> outcomes = race(2, index -> {
            if (index == 0) services.memberships().remove(firstAdmin, second);
            else services.memberships().remove(secondAdmin, first);
            return "removed";
        });

        assertThat(outcomes).filteredOn("removed"::equals).hasSize(1);
        assertThat(outcomes).filteredOn(IdentityException.class::isInstance).hasSize(1);
        assertThat(services.jdbc().queryForObject("select count(*) from workspace_membership where workspace_id = ? "
                + "and membership_role = 'ADMIN'", Integer.class, workspace)).isOne();
    }

    @Test
    void servicesConfineEveryOperationToThePrincipalsWorkspace() {
        UUID otherWorkspace = organization(UUID.randomUUID());
        UUID insider = user(organization, WorkspaceRole.ADMIN);
        UUID outsider = user(services.repository().workspaceOrganization(otherWorkspace).orElseThrow(), null);
        services.jdbc().update("insert into workspace_membership(organization_id, workspace_id, subject_id, "
                        + "membership_role, created_at) values (?, ?, ?, 'ADMIN', current_timestamp)",
                services.repository().workspaceOrganization(otherWorkspace).orElseThrow(), otherWorkspace, outsider);
        var insiderSession = services.sessions().establish(insider, workspace, null, "LOGIN_SUCCEEDED", "password");
        var insiderToken = services.tokens().issue(insiderSession.principal(), "cli", null, null);
        var foreignSession = services.sessions().establish(outsider, otherWorkspace, null, "LOGIN_SUCCEEDED",
                "password");
        AuthenticatedPrincipal foreignAdmin = foreignSession.principal();

        assertThatThrownBy(() -> services.tokens().revoke(foreignAdmin, insiderToken.row().id()))
                .isInstanceOfSatisfying(IdentityException.class, failure -> assertThat(failure.code()).isEqualTo("NOT_FOUND"));
        assertThatThrownBy(() -> services.memberships().put(foreignAdmin, insider, WorkspaceRole.VIEWER))
                .isInstanceOfSatisfying(IdentityException.class, failure -> assertThat(failure.code()).isEqualTo("NOT_FOUND"));
        assertThatThrownBy(() -> services.memberships().remove(foreignAdmin, insider))
                .isInstanceOfSatisfying(IdentityException.class, failure -> assertThat(failure.code()).isEqualTo("NOT_FOUND"));
        assertThatThrownBy(() -> services.identities().resetPassword(foreignAdmin, insider))
                .isInstanceOfSatisfying(IdentityException.class, failure -> assertThat(failure.code()).isEqualTo("NOT_FOUND"));
        assertThatThrownBy(() -> services.sessions().switchWorkspace(foreignAdmin, foreignSession.cookieValue(),
                workspace))
                .isInstanceOfSatisfying(IdentityException.class,
                        failure -> assertThat(failure.code()).isEqualTo("WORKSPACE_SCOPE_UNAVAILABLE"));
        assertThat(services.tokens().list(foreignAdmin, true)).isEmpty();
        assertThat(services.memberships().list(foreignAdmin)).extracting(row -> row.subjectId()).containsExactly(outsider);
        assertThat(services.tokens().authenticate(insiderToken.token())).get()
                .satisfies(principal -> assertThat(principal.workspaceId().value()).isEqualTo(workspace));
    }

    @Test
    void theBootstrapCompletesExactlyOnceAndNeverReopens(@TempDir Path directory) throws Exception {
        // The bootstrap is installation-wide, so it runs against a fresh database without a default workspace.
        var container = LifecycleFixture.POSTGRES;
        String database = "bootstrap_" + UUID.randomUUID().toString().replace("-", "");
        fixture.jdbc.execute("create database " + database);
        DataSource fresh = new DriverManagerDataSource(container.getJdbcUrl().replace("/" + container.getDatabaseName(),
                "/" + database), container.getUsername(), container.getPassword());
        LifecycleFixture.migrate(fresh, true);
        Services clean = Services.on(fresh, directory.resolve("token"));

        assertThat(clean.bootstrap().prepare()).isPresent();
        String token = Files.readString(directory.resolve("token")).strip();
        assertThatThrownBy(() -> clean.bootstrap().complete(token, "root", null, PASSWORD, null, null))
                .isInstanceOfSatisfying(IdentityException.class,
                        failure -> assertThat(failure.code()).isEqualTo("INVALID_REQUEST"));
        List<Object> outcomes = race(6, index -> clean.bootstrap().complete(token, "root-" + index, null, PASSWORD,
                "Acme", "Platform"));

        assertThat(outcomes).filteredOn(InstallationBootstrapService.Completed.class::isInstance).hasSize(1);
        assertThat(outcomes).filteredOn(IdentityException.class::isInstance).hasSize(5).allSatisfy(failure ->
                assertThat(((IdentityException) failure).code()).isEqualTo("BOOTSTRAP_UNAVAILABLE"));
        assertThat(clean.jdbc().queryForObject("select count(*) from user_account", Integer.class)).isOne();
        assertThat(clean.jdbc().queryForObject("select name from organization", String.class)).isEqualTo("Acme");
        assertThat(clean.jdbc().queryForObject("select name from workspace", String.class)).isEqualTo("Platform");
        assertThatThrownBy(() -> clean.jdbc().update("update installation_bootstrap set state = 'PENDING', "
                + "token_digest = 'x'")).hasMessageContaining("agentworks:BOOTSTRAP_COMPLETED");
        assertThatThrownBy(() -> clean.jdbc().update("delete from installation_bootstrap"))
                .hasMessageContaining("agentworks:BOOTSTRAP_COMPLETED");
        assertThat(clean.bootstrap().prepare()).isEmpty();
    }

    // #177 medium on PostgreSQL: nodes starting at once, some sharing a volume and some not, issue exactly one token,
    // never replace it, and the shared file holds the stored token.
    @Test
    void nodesStartingTogetherIssueOneTokenThatNoNodeReplaces(@TempDir Path directory) throws Exception {
        var container = LifecycleFixture.POSTGRES;
        String database = "bootstrap_" + UUID.randomUUID().toString().replace("-", "");
        fixture.jdbc.execute("create database " + database);
        DataSource fresh = new DriverManagerDataSource(container.getJdbcUrl().replace("/" + container.getDatabaseName(),
                "/" + database), container.getUsername(), container.getPassword());
        LifecycleFixture.migrate(fresh, true);
        Path shared = directory.resolve("shared").resolve("token");

        List<Object> outcomes = race(6, index -> Services.on(fresh, index % 2 == 0 ? shared
                : directory.resolve("node-" + index).resolve("token")).bootstrap().prepare());

        assertThat(outcomes).doesNotHaveAnyElementsOfTypes(Throwable.class);
        assertThat(outcomes).filteredOn(outcome -> outcome instanceof java.util.Optional<?> issued && issued.isPresent())
                .hasSize(1);
        String digest = new JdbcTemplate(fresh).queryForObject("select token_digest from installation_bootstrap",
                String.class);
        String stored = new JdbcTemplate(fresh).queryForObject("select token_file from installation_bootstrap",
                String.class);
        String token = Files.readString(Path.of(stored)).strip();
        assertThat(CredentialCodec.digest(token)).isEqualTo(digest);
        if (Files.exists(shared)) assertThat(Files.readString(shared).strip()).isEqualTo(token);
        // Later starts of any node keep it.
        assertThat(Services.on(fresh, directory.resolve("late").resolve("token")).bootstrap().prepare()).isEmpty();
        assertThat(new JdbcTemplate(fresh).queryForObject("select token_digest from installation_bootstrap",
                String.class)).isEqualTo(digest);
    }

    // #185 medium 3 on PostgreSQL: nodes starting at once with the same new reissue value replace the live token
    // exactly once, and a consumed value can be recorded but never changed or removed by the Control Plane.
    @Test
    void nodesStartingTogetherWithOneReissueValueReplaceTheTokenOnce(@TempDir Path directory) throws Exception {
        var container = LifecycleFixture.POSTGRES;
        String database = "reissue_" + UUID.randomUUID().toString().replace("-", "");
        fixture.jdbc.execute("create database " + database);
        DataSource fresh = new DriverManagerDataSource(container.getJdbcUrl().replace("/" + container.getDatabaseName(),
                "/" + database), container.getUsername(), container.getPassword());
        LifecycleFixture.migrate(fresh, true);
        String digest = UUID.randomUUID().toString().replace("-", "").repeat(2);
        try {
            reissueOnce(fresh, directory, digest);
        } finally {
            fixture.jdbc.update("delete from installation_bootstrap_reissue where digest = ?", digest);
            fixture.jdbc.update("delete from installation_bootstrap_reissue_seen where digest = ?", digest);
            fixture.jdbc.execute("drop database if exists " + database + " with (force)");
        }
    }

    private void reissueOnce(DataSource fresh, Path directory, String digest) throws Exception {
        Path lost = directory.resolve("lost").resolve("token");
        assertThat(Services.on(fresh, lost).bootstrap().prepare()).isPresent();
        Files.delete(lost);
        var jdbc = new JdbcTemplate(fresh);
        String value = "2026-09-26T10:00Z";

        // #206: replicas started together with the value, unconfirmed, replace nothing.
        List<Object> unconfirmed = race(6, index -> Services.on(fresh,
                directory.resolve("node-" + index).resolve("token"), value).bootstrap().prepare());
        assertThat(unconfirmed).doesNotHaveAnyElementsOfTypes(Throwable.class)
                .allSatisfy(outcome -> assertThat(outcome).isEqualTo(java.util.Optional.empty()));
        assertThat(jdbc.queryForObject("select count(*) from auth_audit_event where event_type = 'BOOTSTRAP_REISSUED'",
                Integer.class)).isZero();

        // Confirmed by the operator, the replicas replace it exactly once.
        String confirm = InstallationBootstrapService.reissueConfirmation(value,
                jdbc.queryForObject("select token_digest from installation_bootstrap", String.class));
        List<Object> outcomes = race(6, index -> Services.on(fresh, directory.resolve("node-" + index).resolve("token"),
                value, confirm).bootstrap().prepare());

        assertThat(outcomes).doesNotHaveAnyElementsOfTypes(Throwable.class);
        assertThat(outcomes).filteredOn(outcome -> outcome instanceof java.util.Optional<?> issued && issued.isPresent())
                .hasSize(1);
        assertThat(jdbc.queryForObject("select count(*) from auth_audit_event where event_type = 'BOOTSTRAP_REISSUED'",
                Integer.class)).isOne();
        assertThat(jdbc.queryForObject("select count(*) from installation_bootstrap_reissue", Integer.class)).isOne();

        // The Control Plane records consumed values and can neither change nor remove one (V13_1).
        fixture.asRole("agentworks_control_plane", "insert into installation_bootstrap_reissue(digest, consumed_at, "
                + "consumed_by) values (?, clock_timestamp(), 'node')", digest);
        assertThatThrownBy(() -> fixture.asRole("agentworks_control_plane", "update installation_bootstrap_reissue "
                + "set consumed_by = 'x'")).hasStackTraceContaining("permission denied");
        assertThatThrownBy(() -> fixture.asRole("agentworks_control_plane", "delete from installation_bootstrap_reissue"))
                .hasStackTraceContaining("permission denied");
        assertThatThrownBy(() -> fixture.asRole("agentworks_run_plane", "select count(*) from "
                + "installation_bootstrap_reissue")).hasStackTraceContaining("permission denied");

        // #201: the six nodes recorded one first sighting, against the token live then, and the Control Plane can
        // record and read sightings but never change or remove one (V20_1).
        assertThat(jdbc.queryForObject("select count(*) from installation_bootstrap_reissue_seen where target_digest "
                + "is not null", Integer.class)).isOne();
        fixture.asRole("agentworks_control_plane", "insert into installation_bootstrap_reissue_seen(digest, seen_at, "
                + "seen_by, target_digest) values (?, clock_timestamp(), 'node', null)", digest);
        assertThat(fixture.countAsRole("agentworks_control_plane", "select count(*) from "
                + "installation_bootstrap_reissue_seen where digest = ?", digest)).isOne();
        assertThatThrownBy(() -> fixture.asRole("agentworks_control_plane", "update installation_bootstrap_reissue_seen "
                + "set seen_by = 'x'")).hasStackTraceContaining("permission denied");
        // It may only forget the token a sighting named, which the completed bootstrap does. #206: a guard (V22_1)
        // refuses pointing a sighting at another token, for any role.
        assertThatThrownBy(() -> fixture.asRole("agentworks_control_plane", "update installation_bootstrap_reissue_seen "
                + "set target_digest = ? where digest = ?", "c".repeat(64), digest))
                .hasStackTraceContaining("REISSUE_SIGHTING_IMMUTABLE");
        assertThatThrownBy(() -> jdbc.update("update installation_bootstrap_reissue_seen set target_digest = ? "
                + "where target_digest is not null", "c".repeat(64)))
                .hasStackTraceContaining("REISSUE_SIGHTING_IMMUTABLE");
        assertThatThrownBy(() -> jdbc.update("update installation_bootstrap_reissue_seen set seen_by = 'x'"))
                .hasStackTraceContaining("REISSUE_SIGHTING_IMMUTABLE");
        // Nor can a new sighting name a token that is not the stored one (#206 review).
        assertThatThrownBy(() -> fixture.asRole("agentworks_control_plane", "insert into "
                + "installation_bootstrap_reissue_seen(digest, seen_at, seen_by, target_digest) values (?, "
                + "clock_timestamp(), 'node', ?)", "e".repeat(64), "d".repeat(64)))
                .hasStackTraceContaining("REISSUE_SIGHTING_IMMUTABLE");
        fixture.asRole("agentworks_control_plane", "update installation_bootstrap_reissue_seen set target_digest = null "
                + "where digest = ?", digest);
        assertThatThrownBy(() -> fixture.asRole("agentworks_control_plane",
                "delete from installation_bootstrap_reissue_seen")).hasStackTraceContaining("permission denied");
        assertThatThrownBy(() -> fixture.asRole("agentworks_run_plane", "select count(*) from "
                + "installation_bootstrap_reissue_seen")).hasStackTraceContaining("permission denied");
    }

    @Test
    void lifecycleCommandsWithoutAnActorFailClosed() {
        var lifecycleWorkspace = fixture.workspace();
        var environment = fixture.environment(lifecycleWorkspace, "prod");
        var release = fixture.release(lifecycleWorkspace, "agent", "1.0.0");

        // #177: refused up front as a typed validation error (422), before any record, lock, gate, or insert.
        for (String missing : new String[]{null, " "}) {
            assertThatThrownBy(() -> fixture.deployments.promote(lifecycleWorkspace,
                    new LifecycleModel.PromotionCommand(environment.id(), "api", release.id(),
                            LifecycleModel.DeploymentConfiguration.shared(), null, "no-actor-key", missing, "no actor")))
                    .isInstanceOf(com.dvarahq.agentworks.spec.AgentSpecValidationException.class);
            assertThatThrownBy(() -> fixture.deployments.suspend(lifecycleWorkspace,
                    new LifecycleModel.CohortCommand(UUID.randomUUID(), 1, null, missing, "no actor")))
                    .isInstanceOf(com.dvarahq.agentworks.spec.AgentSpecValidationException.class);
            // #203: one typed check for every command that records an actor.
            assertThatThrownBy(() -> fixture.deployments.rollback(lifecycleWorkspace,
                    new LifecycleModel.RollbackCommand(UUID.randomUUID(), release.id(), 1, "no-actor-rollback",
                            missing, "no actor")))
                    .isInstanceOf(com.dvarahq.agentworks.spec.AgentSpecValidationException.class);
            assertThatThrownBy(() -> fixture.deployments.retire(lifecycleWorkspace,
                    new LifecycleModel.CohortCommand(UUID.randomUUID(), 1, null, missing, "no actor")))
                    .isInstanceOf(com.dvarahq.agentworks.spec.AgentSpecValidationException.class);
            assertThatThrownBy(() -> fixture.deployments.observeQuiescence(lifecycleWorkspace, UUID.randomUUID(),
                    missing)).isInstanceOf(com.dvarahq.agentworks.spec.AgentSpecValidationException.class);
        }
        assertThat(fixture.jdbc.queryForObject("select count(*) from lifecycle_decision where workspace_id = ?",
                Integer.class, lifecycleWorkspace.value())).isZero();
        assertThat(fixture.jdbc.queryForObject("select count(*) from lifecycle_idempotency where workspace_id = ?",
                Integer.class, lifecycleWorkspace.value())).isZero();
    }

    private boolean privilege(String role, String table, String privilege) {
        return Boolean.TRUE.equals(fixture.jdbc.queryForObject("select has_table_privilege(?, ?, ?)", Boolean.class,
                role, table, privilege));
    }

    private UUID organization(UUID id) {
        UUID created = UUID.randomUUID();
        fixture.jdbc.update("insert into organization(id, name) values (?, ?)", id, "org-" + id);
        fixture.jdbc.update("insert into workspace(id, organization_id, name) values (?, ?, ?)", created, id, "ws");
        return created;
    }

    private UUID organizationOnly() {
        UUID id = UUID.randomUUID();
        fixture.jdbc.update("insert into organization(id, name) values (?, ?)", id, "org-" + id);
        return id;
    }

    private UUID user(UUID userOrganization, WorkspaceRole role) {
        UUID id = UUID.randomUUID();
        var now = services.clock().instant();
        services.repository().insertUser(id, userOrganization, "user-" + id, "User", "MEMBER", ENCODER.encode(PASSWORD),
                false, now);
        if (role != null) services.repository().insertMembership(workspace, userOrganization, id, role.name(), now);
        return id;
    }

    private static String username(UUID user) {
        return "user-" + user;
    }
}
