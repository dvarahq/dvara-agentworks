package com.dvarahq.agentworks.storage.identity;

import com.dvarahq.agentworks.storage.TenantContext;
import com.dvarahq.agentworks.storage.identity.IdentityRows.AuditEvent;
import com.dvarahq.agentworks.storage.identity.IdentityRows.BootstrapRow;
import com.dvarahq.agentworks.storage.identity.IdentityRows.CredentialGrant;
import com.dvarahq.agentworks.storage.identity.IdentityRows.MemberRow;
import com.dvarahq.agentworks.storage.identity.IdentityRows.MembershipRow;
import com.dvarahq.agentworks.storage.identity.IdentityRows.SessionRow;
import com.dvarahq.agentworks.storage.identity.IdentityRows.TokenRow;
import com.dvarahq.agentworks.storage.identity.IdentityRows.UserRow;
import com.dvarahq.agentworks.storage.identity.IdentityRows.WorkspaceMembershipRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for identities, memberships, sessions, API tokens, the installation bootstrap, and the
 * authentication audit trail.
 *
 * <p>Authentication necessarily runs before a workspace is known, so credential and user lookups are
 * installation-scoped and keyed by an unguessable digest or a normalized username. Every membership and token
 * administration read or write that serves a workspace request is bound to the transaction's
 * {@link TenantContext} workspace and fails closed without it.
 */
public final class JdbcIdentityRepository {
    private static final String USER_COLUMNS = "id, organization_id, username, display_name, organization_role, "
            + "identity_provider, password_hash, must_change_password, status, failed_login_count, locked_until, "
            + "created_at";
    private static final String SESSION_COLUMNS = "id, organization_id, user_id, workspace_id, created_at, "
            + "last_seen_at, expires_at, revoked_at, revoked_reason";
    private static final String TOKEN_COLUMNS = "id, token_prefix, name, organization_id, workspace_id, user_id, "
            + "token_role, created_at, expires_at, last_used_at, revoked_at, revoked_by";
    private static final RowMapper<UserRow> USERS = (rs, row) -> new UserRow(rs.getObject("id", UUID.class),
            rs.getObject("organization_id", UUID.class), rs.getString("username"), rs.getString("display_name"),
            rs.getString("organization_role"), rs.getString("identity_provider"), rs.getString("password_hash"),
            rs.getBoolean("must_change_password"), rs.getString("status"), rs.getInt("failed_login_count"),
            instant(rs, "locked_until"), instant(rs, "created_at"));
    private static final RowMapper<SessionRow> SESSIONS = (rs, row) -> new SessionRow(rs.getObject("id", UUID.class),
            rs.getObject("organization_id", UUID.class), rs.getObject("user_id", UUID.class),
            rs.getObject("workspace_id", UUID.class), instant(rs, "created_at"), instant(rs, "last_seen_at"),
            instant(rs, "expires_at"), instant(rs, "revoked_at"), rs.getString("revoked_reason"));
    private static final RowMapper<TokenRow> TOKENS = (rs, row) -> new TokenRow(rs.getObject("id", UUID.class),
            rs.getString("token_prefix"), rs.getString("name"), rs.getObject("organization_id", UUID.class),
            rs.getObject("workspace_id", UUID.class), rs.getObject("user_id", UUID.class), rs.getString("token_role"),
            instant(rs, "created_at"), instant(rs, "expires_at"), instant(rs, "last_used_at"),
            instant(rs, "revoked_at"), rs.getObject("revoked_by", UUID.class));

    private final JdbcTemplate jdbc;

    public JdbcIdentityRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    // ---- Organizations and workspaces (bootstrap only) ----

    public boolean workspaceExists(UUID workspaceId) {
        return count("select count(*) from workspace where id = ?", workspaceId) > 0;
    }

    public Optional<UUID> workspaceOrganization(UUID workspaceId) {
        return jdbc.query("select organization_id from workspace where id = ?",
                (rs, row) -> rs.getObject(1, UUID.class), workspaceId).stream().findFirst();
    }

    public void insertOrganization(UUID id, String name) {
        jdbc.update("insert into organization(id, name) values (?, ?)", id, name);
    }

    public void insertWorkspace(UUID id, UUID organizationId, String name) {
        jdbc.update("insert into workspace(id, organization_id, name) values (?, ?, ?)", id, organizationId, name);
    }

    // ---- Users ----

    public long countUsers() {
        return count("select count(*) from user_account");
    }

    public void insertUser(UUID id, UUID organizationId, String username, String displayName, String organizationRole,
            String passwordHash, boolean mustChangePassword, Instant now) {
        jdbc.update("insert into user_account(id, organization_id, username, display_name, organization_role, "
                        + "password_hash, password_changed_at, must_change_password, status, failed_login_count, "
                        + "created_at, updated_at) values (?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', 0, ?, ?)",
                id, organizationId, username, displayName, organizationRole, passwordHash, timestamp(now),
                mustChangePassword, timestamp(now), timestamp(now));
    }

    public Optional<UserRow> findUserByUsername(String username) {
        return jdbc.query("select " + USER_COLUMNS + " from user_account where username = ?", USERS, username)
                .stream().findFirst();
    }

    public Optional<UserRow> findUser(UUID id) {
        return jdbc.query("select " + USER_COLUMNS + " from user_account where id = ?", USERS, id).stream().findFirst();
    }

    /** Row-locks a user so that logins, lockout, and session limits for one user serialize. */
    public Optional<UserRow> lockUser(UUID id) {
        return jdbc.query("select " + USER_COLUMNS + " from user_account where id = ? for update", USERS, id)
                .stream().findFirst();
    }

    public List<UserRow> listUsers(UUID organizationId) {
        return jdbc.query("select " + USER_COLUMNS + " from user_account where organization_id = ? order by username",
                USERS, organizationId);
    }

    /**
     * Counts one failed attempt atomically and locks the account once {@code threshold} consecutive failures are
     * reached. The expressions read the pre-update row, so concurrent failures are each counted exactly once. After
     * a lock expires the count restarts, and while a lock is active nothing changes, so failures cannot extend it.
     */
    public void recordFailedLogin(UUID id, int threshold, Instant lockUntil, Instant now) {
        jdbc.update("update user_account set "
                        + "failed_login_count = case when locked_until is not null then 1 else failed_login_count + 1 end, "
                        + "locked_until = case when (case when locked_until is not null then 1 else failed_login_count + 1 end)"
                        + " >= ? then ? else null end, updated_at = ? "
                        + "where id = ? and (locked_until is null or locked_until <= ?)",
                threshold, timestamp(lockUntil), timestamp(now), id, timestamp(now));
    }

    public void recordSuccessfulLogin(UUID id, Instant now) {
        jdbc.update("update user_account set failed_login_count = 0, locked_until = null, updated_at = ? "
                + "where id = ? and (failed_login_count <> 0 or locked_until is not null)", timestamp(now), id);
    }

    public void updatePassword(UUID id, String passwordHash, boolean mustChangePassword, Instant now) {
        jdbc.update("update user_account set password_hash = ?, password_changed_at = ?, must_change_password = ?, "
                        + "failed_login_count = 0, locked_until = null, updated_at = ? where id = ?",
                passwordHash, timestamp(now), mustChangePassword, timestamp(now), id);
    }

    /**
     * Replaces a hash produced with outdated parameters after a successful verification, only while the stored hash
     * is still the one that was verified.
     */
    public void rehashPassword(UUID id, String verifiedHash, String passwordHash, Instant now) {
        jdbc.update("update user_account set password_hash = ?, updated_at = ? where id = ? and password_hash = ?",
                passwordHash, timestamp(now), id, verifiedHash);
    }

    public void setStatus(UUID id, String status, Instant now) {
        jdbc.update("update user_account set status = ?, failed_login_count = 0, locked_until = null, updated_at = ? "
                + "where id = ?", status, timestamp(now), id);
    }

    // ---- Memberships ----

    public void insertMembership(UUID workspaceId, UUID organizationId, UUID subjectId, String role, Instant now) {
        jdbc.update("insert into workspace_membership(organization_id, workspace_id, subject_id, membership_role, "
                        + "created_at, updated_at) values (?, ?, ?, ?, ?, ?)", organizationId, workspaceId, subjectId,
                role, timestamp(now), timestamp(now));
    }

    public Optional<MembershipRow> findMembership(UUID workspaceId, UUID subjectId) {
        return jdbc.query("select workspace_id, organization_id, subject_id, membership_role, created_at from "
                        + "workspace_membership where workspace_id = ? and subject_id = ?",
                (rs, row) -> new MembershipRow(rs.getObject("workspace_id", UUID.class),
                        rs.getObject("organization_id", UUID.class), rs.getObject("subject_id", UUID.class),
                        rs.getString("membership_role"), instant(rs, "created_at")),
                workspaceId, subjectId).stream().findFirst();
    }

    /** Workspaces the subject is a member of, oldest membership first. */
    public List<WorkspaceMembershipRow> membershipsOf(UUID subjectId) {
        return jdbc.query("select m.workspace_id, m.organization_id, w.name, m.membership_role from "
                        + "workspace_membership m join workspace w on w.id = m.workspace_id where m.subject_id = ? "
                        + "order by m.created_at, m.workspace_id",
                (rs, row) -> new WorkspaceMembershipRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getString(3), rs.getString(4)), subjectId);
    }

    /** Members of the transaction's workspace. */
    public List<MemberRow> listMembers() {
        return jdbc.query("select m.subject_id, u.username, u.display_name, m.membership_role, m.created_at from "
                        + "workspace_membership m join user_account u on u.id = m.subject_id "
                        + "where m.workspace_id = ? order by u.username",
                (rs, row) -> new MemberRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getString(4), instant(rs, "created_at")), workspace());
    }

    /**
     * Row-locks the administrators of the transaction's workspace, serializing changes that could remove the
     * last one. Returns their subject identifiers.
     */
    public List<UUID> lockAdministrators() {
        return jdbc.query("select subject_id from workspace_membership where workspace_id = ? "
                        + "and membership_role = 'ADMIN' order by subject_id for update",
                (rs, row) -> rs.getObject(1, UUID.class), workspace());
    }

    /** Inserts or changes a membership in the transaction's workspace; returns true when it was created. */
    public boolean putMembership(UUID organizationId, UUID subjectId, String role, Instant now) {
        UUID workspaceId = workspace();
        int changed = jdbc.update("update workspace_membership set membership_role = ?, updated_at = ? "
                + "where workspace_id = ? and subject_id = ?", role, timestamp(now), workspaceId, subjectId);
        if (changed == 1) return false;
        insertMembership(workspaceId, organizationId, subjectId, role, now);
        return true;
    }

    /** Removes a membership from the transaction's workspace; its sessions and tokens cascade. */
    public boolean deleteMembership(UUID subjectId) {
        return jdbc.update("delete from workspace_membership where workspace_id = ? and subject_id = ?", workspace(),
                subjectId) == 1;
    }

    // ---- Sessions ----

    public void insertSession(UUID id, String digest, UUID organizationId, UUID userId, UUID workspaceId,
            Instant createdAt, Instant expiresAt) {
        jdbc.update("insert into auth_session(id, session_digest, organization_id, user_id, workspace_id, created_at, "
                        + "last_seen_at, expires_at) values (?, ?, ?, ?, ?, ?, ?, ?)", id, digest, organizationId, userId,
                workspaceId, timestamp(createdAt), timestamp(createdAt), timestamp(expiresAt));
    }

    public Optional<CredentialGrant> sessionGrant(String digest) {
        return sessionGrant("s.session_digest = ?", digest);
    }

    /**
     * The grant of the session record {@code id}, for re-checking a long-lived request such as an event stream
     * without holding its credential (issue #35). Callers apply the same validity rules as sign-in authentication.
     */
    public Optional<CredentialGrant> sessionGrantById(UUID id) {
        return sessionGrant("s.id = ?", id);
    }

    private Optional<CredentialGrant> sessionGrant(String predicate, Object argument) {
        return jdbc.query("select s.id, s.organization_id, s.user_id, s.workspace_id, u.username, u.organization_role, "
                        + "u.status, u.must_change_password, m.membership_role, s.created_at, s.last_seen_at, "
                        + "s.expires_at, s.revoked_at, s.session_digest from auth_session s "
                        + "join user_account u on u.id = s.user_id and u.organization_id = s.organization_id "
                        + "left join workspace_membership m on m.workspace_id = s.workspace_id and m.subject_id = s.user_id "
                        + "where " + predicate,
                (rs, row) -> new CredentialGrant(rs.getObject("id", UUID.class),
                        rs.getObject("organization_id", UUID.class), rs.getObject("user_id", UUID.class),
                        rs.getObject("workspace_id", UUID.class), rs.getString("username"),
                        rs.getString("organization_role"), rs.getString("status"),
                        rs.getBoolean("must_change_password"), rs.getString("membership_role"), null,
                        instant(rs, "created_at"), instant(rs, "last_seen_at"), instant(rs, "expires_at"),
                        instant(rs, "revoked_at"), rs.getString("session_digest")), argument).stream().findFirst();
    }

    public void touchSession(UUID id, Instant now) {
        jdbc.update("update auth_session set last_seen_at = ? where id = ? and revoked_at is null", timestamp(now), id);
    }

    public boolean revokeSession(UUID id, String reason, Instant now) {
        return jdbc.update("update auth_session set revoked_at = ?, revoked_reason = ? where id = ? "
                + "and revoked_at is null", timestamp(now), reason, id) == 1;
    }

    /** Revokes a session only when it belongs to {@code userId}. */
    public boolean revokeSessionOf(UUID userId, UUID id, String reason, Instant now) {
        return jdbc.update("update auth_session set revoked_at = ?, revoked_reason = ? where id = ? and user_id = ? "
                + "and revoked_at is null", timestamp(now), reason, id, userId) == 1;
    }

    /** Revokes every unrevoked session of a user except {@code keep}, which may be {@code null}. */
    public int revokeSessionsOf(UUID userId, UUID keep, String reason, Instant now) {
        if (keep == null) {
            return jdbc.update("update auth_session set revoked_at = ?, revoked_reason = ? where user_id = ? "
                    + "and revoked_at is null", timestamp(now), reason, userId);
        }
        return jdbc.update("update auth_session set revoked_at = ?, revoked_reason = ? where user_id = ? and id <> ? "
                + "and revoked_at is null", timestamp(now), reason, userId, keep);
    }

    /** Unrevoked, unexpired sessions of a user that were used after {@code idleCutoff}, oldest first. */
    public List<SessionRow> activeSessions(UUID userId, Instant now, Instant idleCutoff) {
        return jdbc.query("select " + SESSION_COLUMNS + " from auth_session where user_id = ? and revoked_at is null "
                        + "and expires_at > ? and last_seen_at > ? order by created_at, id", SESSIONS, userId,
                timestamp(now), timestamp(idleCutoff));
    }

    // ---- API tokens ----

    public void insertToken(UUID id, String digest, String prefix, String name, UUID organizationId, UUID workspaceId,
            UUID userId, String role, Instant createdAt, Instant expiresAt) {
        jdbc.update("insert into api_token(id, token_digest, token_prefix, name, organization_id, workspace_id, user_id, "
                        + "token_role, created_at, expires_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", id, digest, prefix,
                name, organizationId, workspaceId, userId, role, timestamp(createdAt), timestamp(expiresAt));
    }

    public Optional<CredentialGrant> tokenGrant(String digest) {
        return tokenGrant("t.token_digest = ?", digest);
    }

    /** The grant of the API token record {@code id}; see {@link #sessionGrantById(UUID)} (issue #35). */
    public Optional<CredentialGrant> tokenGrantById(UUID id) {
        return tokenGrant("t.id = ?", id);
    }

    private Optional<CredentialGrant> tokenGrant(String predicate, Object argument) {
        return jdbc.query("select t.id, t.organization_id, t.user_id, t.workspace_id, u.username, u.organization_role, "
                        + "u.status, u.must_change_password, m.membership_role, t.token_role, t.created_at, "
                        + "t.last_used_at, t.expires_at, t.revoked_at, t.token_digest from api_token t "
                        + "join user_account u on u.id = t.user_id and u.organization_id = t.organization_id "
                        + "left join workspace_membership m on m.workspace_id = t.workspace_id and m.subject_id = t.user_id "
                        + "where " + predicate,
                (rs, row) -> new CredentialGrant(rs.getObject("id", UUID.class),
                        rs.getObject("organization_id", UUID.class), rs.getObject("user_id", UUID.class),
                        rs.getObject("workspace_id", UUID.class), rs.getString("username"),
                        rs.getString("organization_role"), rs.getString("status"),
                        rs.getBoolean("must_change_password"), rs.getString("membership_role"),
                        rs.getString("token_role"), instant(rs, "created_at"), instant(rs, "last_used_at"),
                        instant(rs, "expires_at"), instant(rs, "revoked_at"), rs.getString("token_digest")),
                argument).stream().findFirst();
    }

    public void touchToken(UUID id, Instant now) {
        jdbc.update("update api_token set last_used_at = ? where id = ? and revoked_at is null", timestamp(now), id);
    }

    /** Tokens of the transaction's workspace, optionally only those owned by {@code userId}. */
    public List<TokenRow> listTokens(UUID userId) {
        if (userId == null) {
            return jdbc.query("select " + TOKEN_COLUMNS + " from api_token where workspace_id = ? order by created_at, id",
                    TOKENS, workspace());
        }
        return jdbc.query("select " + TOKEN_COLUMNS + " from api_token where workspace_id = ? and user_id = ? "
                + "order by created_at, id", TOKENS, workspace(), userId);
    }

    /** A token of the transaction's workspace. */
    public Optional<TokenRow> findToken(UUID id) {
        return jdbc.query("select " + TOKEN_COLUMNS + " from api_token where workspace_id = ? and id = ?", TOKENS,
                workspace(), id).stream().findFirst();
    }

    /** Revokes a token of the transaction's workspace; false when absent or already revoked. */
    public boolean revokeToken(UUID id, UUID revokedBy, Instant now) {
        return jdbc.update("update api_token set revoked_at = ?, revoked_by = ? where workspace_id = ? and id = ? "
                + "and revoked_at is null", timestamp(now), revokedBy, workspace(), id) == 1;
    }

    /** Revokes every unrevoked token a user owns in any workspace. */
    public int revokeTokensOf(UUID userId, UUID revokedBy, Instant now) {
        return jdbc.update("update api_token set revoked_at = ?, revoked_by = ? where user_id = ? and revoked_at is null",
                timestamp(now), revokedBy, userId);
    }

    // ---- Installation bootstrap ----

    /** The bootstrap row without a lock, for a pre-check that is repeated under lock. */
    public Optional<BootstrapRow> findBootstrap() {
        return jdbc.query("select " + BOOTSTRAP + " from installation_bootstrap where id = 1", JdbcIdentityRepository::bootstrap)
                .stream().findFirst();
    }

    public Optional<BootstrapRow> lockBootstrap() {
        return jdbc.query("select " + BOOTSTRAP + " from installation_bootstrap where id = 1 for update",
                JdbcIdentityRepository::bootstrap).stream().findFirst();
    }

    private static final String BOOTSTRAP = "state, token_digest, issued_at, expires_at, completed_at, issued_by, token_file";

    private static BootstrapRow bootstrap(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new BootstrapRow(rs.getString("state"), rs.getString("token_digest"), instant(rs, "issued_at"),
                instant(rs, "expires_at"), instant(rs, "completed_at"), rs.getString("issued_by"),
                rs.getString("token_file"));
    }

    /**
     * Creates the single bootstrap row; a concurrent creator fails on the primary key. {@code issuedBy} and
     * {@code tokenFile} record where the token's file is written.
     */
    public void insertBootstrap(String digest, Instant issuedAt, Instant expiresAt, String issuedBy, String tokenFile) {
        jdbc.update("insert into installation_bootstrap(id, state, token_digest, issued_at, expires_at, issued_by, "
                + "token_file) values (1, 'PENDING', ?, ?, ?, ?, ?)", digest, timestamp(issuedAt), timestamp(expiresAt),
                issuedBy, tokenFile);
    }

    public void reissueBootstrap(String digest, Instant issuedAt, Instant expiresAt, String issuedBy, String tokenFile) {
        jdbc.update("update installation_bootstrap set token_digest = ?, issued_at = ?, expires_at = ?, issued_by = ?, "
                + "token_file = ? where id = 1 and state = 'PENDING'", digest, timestamp(issuedAt), timestamp(expiresAt),
                issuedBy, tokenFile);
    }

    /**
     * Whether the bootstrap reissue override with this digest has been consumed (#185). Read while holding the
     * bootstrap row's lock, so concurrent starts with one value act on it once.
     */
    public boolean bootstrapReissueConsumed(String digest) {
        Integer found = jdbc.queryForObject("select count(*) from installation_bootstrap_reissue where digest = ?",
                Integer.class, digest);
        return found != null && found > 0;
    }

    /**
     * Records the bootstrap reissue override with this digest as consumed (#185); every consumed value is kept. A
     * concurrent recorder of the same digest fails on the primary key.
     */
    public void consumeBootstrapReissue(String digest, Instant consumedAt, String consumedBy) {
        jdbc.update("insert into installation_bootstrap_reissue(digest, consumed_at, consumed_by) values (?, ?, ?)",
                Objects.requireNonNull(digest, "digest"), timestamp(consumedAt), consumedBy);
    }

    /** The first sight of the bootstrap reissue value with this digest (#201), if any node has seen it. */
    public Optional<IdentityRows.ReissueSighting> bootstrapReissueSighting(String digest) {
        return jdbc.query("select seen_at, seen_by, target_digest from installation_bootstrap_reissue_seen "
                        + "where digest = ?", (rs, row) -> new IdentityRows.ReissueSighting(instant(rs, "seen_at"),
                        rs.getString("seen_by"), rs.getString("target_digest")), digest).stream().findFirst();
    }

    /**
     * Records the first sight of the bootstrap reissue value with this digest (#201) and the digest of the token live
     * then ({@code targetDigest}, null when none was). A concurrent recorder of the same digest fails on the primary
     * key. A sighting is never changed except to clear its target when the bootstrap completes; on PostgreSQL a guard
     * enforces that (V22_1, #206).
     */
    public void recordBootstrapReissueSighting(String digest, Instant seenAt, String seenBy, String targetDigest) {
        jdbc.update("insert into installation_bootstrap_reissue_seen(digest, seen_at, seen_by, target_digest) "
                + "values (?, ?, ?, ?)", Objects.requireNonNull(digest, "digest"), timestamp(seenAt), seenBy,
                targetDigest);
    }

    /**
     * Forgets which token each reissue value was first seen with (#201), once the bootstrap completes: like the
     * token's own digest, it serves no purpose after that.
     */
    public void forgetBootstrapReissueTargets() {
        jdbc.update("update installation_bootstrap_reissue_seen set target_digest = null where target_digest is not null");
    }

    /** Completes the bootstrap once; false when it was not pending. */
    public boolean completeBootstrap(UUID userId, Instant now) {
        return jdbc.update("update installation_bootstrap set state = 'COMPLETED', token_digest = null, "
                + "completed_at = ?, completed_by = ? where id = 1 and state = 'PENDING'", timestamp(now), userId) == 1;
    }

    // ---- Audit ----

    public void insertAudit(AuditEvent event) {
        jdbc.update("insert into auth_audit_event(id, occurred_at, event_type, outcome, organization_id, workspace_id, "
                        + "actor_id, authentication_method, credential_id, target_type, target_id, detail, "
                        + "remote_address) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", event.id(),
                timestamp(event.occurredAt()), event.eventType(), event.outcome(), event.organizationId(),
                event.workspaceId(), event.actorId(), event.authenticationMethod(), event.credentialId(),
                event.targetType(), event.targetId(), event.detail(), event.remoteAddress());
    }

    /**
     * One newest-first workspace page of the append-only administrative audit trail. Filters are exact and optional;
     * the cursor is the exclusive {@code (occurred_at,id)} position of the last row already returned.
     */
    public List<AuditEvent> listAudit(String eventType, String outcome, String targetType, UUID targetId,
            Instant beforeOccurredAt, UUID beforeId, int limit) {
        if ((beforeOccurredAt == null) != (beforeId == null)) {
            throw new IllegalArgumentException("an audit position names both keys");
        }
        StringBuilder sql = new StringBuilder("select id, occurred_at, event_type, outcome, organization_id, "
                + "workspace_id, actor_id, authentication_method, credential_id, target_type, target_id, detail, "
                + "remote_address from auth_audit_event where workspace_id = ?");
        List<Object> arguments = new java.util.ArrayList<>();
        arguments.add(workspace());
        if (eventType != null) { sql.append(" and event_type = ?"); arguments.add(eventType); }
        if (outcome != null) { sql.append(" and outcome = ?"); arguments.add(outcome); }
        if (targetType != null) { sql.append(" and target_type = ?"); arguments.add(targetType); }
        if (targetId != null) { sql.append(" and target_id = ?"); arguments.add(targetId); }
        if (beforeOccurredAt != null) {
            sql.append(" and (occurred_at < ? or (occurred_at = ? and id < ?))");
            arguments.add(timestamp(beforeOccurredAt));
            arguments.add(timestamp(beforeOccurredAt));
            arguments.add(beforeId);
        }
        sql.append(" order by occurred_at desc, id desc fetch first ").append(limit).append(" rows only");
        return jdbc.query(sql.toString(), (rs, row) -> new AuditEvent(rs.getObject("id", UUID.class),
                instant(rs, "occurred_at"), rs.getString("event_type"), rs.getString("outcome"),
                rs.getObject("organization_id", UUID.class), rs.getObject("workspace_id", UUID.class),
                rs.getObject("actor_id", UUID.class), rs.getString("authentication_method"),
                rs.getObject("credential_id", UUID.class), rs.getString("target_type"),
                rs.getObject("target_id", UUID.class),
                rs.getString("detail"), rs.getString("remote_address")), arguments.toArray());
    }

    // ---- Helpers ----

    private long count(String sql, Object... arguments) {
        Long value = jdbc.queryForObject(sql, Long.class, arguments);
        return value == null ? 0 : value;
    }

    private static UUID workspace() {
        return TenantContext.requireWorkspace().value();
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime timestamp(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant.truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
    }
}
