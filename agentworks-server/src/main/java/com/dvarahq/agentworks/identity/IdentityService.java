package com.dvarahq.agentworks.identity;

import com.dvarahq.agentworks.domain.identity.AuthenticatedPrincipal;
import com.dvarahq.agentworks.domain.identity.AuthenticationMethod;
import com.dvarahq.agentworks.domain.identity.WorkspacePermission;
import com.dvarahq.agentworks.domain.identity.WorkspaceRole;
import com.dvarahq.agentworks.storage.TenantTransactionTemplate;
import com.dvarahq.agentworks.storage.identity.IdentityRows.CredentialGrant;
import com.dvarahq.agentworks.storage.identity.IdentityRows.UserRow;
import com.dvarahq.agentworks.storage.identity.JdbcIdentityRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Local users and passwords: verification with lockout, administrator-issued temporary passwords, self-service
 * change, and enabling or disabling accounts. Passwords are hashed with the configured adaptive
 * {@link PasswordEncoder} and never logged, audited, or returned except for a temporary password shown once to the
 * administrator who issued it.
 */
public final class IdentityService {
    public static final String ACTIVE = "ACTIVE";
    public static final String DISABLED = "DISABLED";
    public static final String ORGANIZATION_ADMIN = "ADMIN";
    public static final String ORGANIZATION_MEMBER = "MEMBER";
    /** Longer presented passwords are rejected without hashing them in full; the policy maximum is lower. */
    static final int MAX_PRESENTED_LENGTH = 1024;

    /** A created or reset user and the temporary password, which the caller shows exactly once. */
    public record IssuedPassword(UserRow user, String temporaryPassword) {
        @Override
        public String toString() {
            return "IssuedPassword[user=" + user.id() + "]";
        }
    }

    private final TransactionTemplate transactions;
    private final TenantTransactionTemplate tenants;
    private final JdbcIdentityRepository repository;
    private final PasswordEncoder encoder;
    private final PasswordPolicy passwords;
    private final SecurityPolicy.Login login;
    private final CredentialCodec codec;
    private final AuthAuditLog audit;
    private final Clock clock;
    private final String unknownUserHash;
    private final HashingBulkhead hashing;

    public IdentityService(TransactionTemplate transactions, JdbcIdentityRepository repository, PasswordEncoder encoder,
            PasswordPolicy passwords, SecurityPolicy.Login login, CredentialCodec codec, AuthAuditLog audit,
            Clock clock) {
        this(transactions, repository, encoder, passwords, login, codec, audit, clock,
                new HashingBulkhead(login.maxConcurrentVerifications()));
    }

    /** @param hashing the bulkhead shared with the installation bootstrap */
    public IdentityService(TransactionTemplate transactions, JdbcIdentityRepository repository, PasswordEncoder encoder,
            PasswordPolicy passwords, SecurityPolicy.Login login, CredentialCodec codec, AuthAuditLog audit,
            Clock clock, HashingBulkhead hashing) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.tenants = new TenantTransactionTemplate(transactions);
        this.repository = Objects.requireNonNull(repository, "repository");
        this.encoder = Objects.requireNonNull(encoder, "encoder");
        this.passwords = Objects.requireNonNull(passwords, "passwords");
        this.login = Objects.requireNonNull(login, "login");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.clock = Objects.requireNonNull(clock, "clock");
        // Verifying against a real hash for unknown usernames keeps their timing indistinguishable.
        this.unknownUserHash = encoder.encode(codec.newTemporaryPassword());
        this.hashing = Objects.requireNonNull(hashing, "hashing");
    }

    public String hash(String password) {
        return encoder.encode(password);
    }

    /**
     * Verifies a username and password. Every failure (unknown user, wrong password, locked or disabled account)
     * returns empty after one hash verification, so callers answer uniformly.
     *
     * <p>The adaptive hash never runs inside a transaction or while holding a connection: the credential is read
     * first, verified with no connection held, and the outcome is then recorded in a short transaction that locks
     * the user and re-checks its state. A password changed in between fails without counting, and wrong passwords
     * are counted under the row lock, so concurrent guesses are each counted.
     */
    public Optional<UserRow> verifyPassword(String username, String password) {
        return hashing.run(() -> verify(username, password));
    }

    private Optional<UserRow> verify(String username, String password) {
        Optional<String> normalized = PasswordPolicy.normalizeUsername(username);
        // An oversized value can never be a valid password; it is hashed as empty so it costs no more than any other.
        boolean oversized = password != null && password.length() > MAX_PRESENTED_LENGTH;
        String presented = password == null || oversized ? "" : password;
        Optional<UserRow> snapshot = normalized.flatMap(repository::findUserByUsername);
        String storedHash = snapshot.map(UserRow::passwordHash).orElse(null);
        // Unknown users and users without a local password verify against a real hash, so every path hashes once.
        boolean hashMatches = encoder.matches(presented, storedHash == null ? unknownUserHash : storedHash);
        boolean matches = hashMatches && storedHash != null && !oversized;
        Optional<UserRow> verified = transactions.execute(status -> {
            Optional<UserRow> found = snapshot.flatMap(user -> repository.lockUser(user.id()));
            if (found.isEmpty()) {
                audit.record("LOGIN_FAILED", AuthAuditLog.FAILED, null, null, null, "password", null, null, null,
                        "UNKNOWN_SUBJECT");
                return Optional.<UserRow>empty();
            }
            UserRow user = found.get();
            Instant now = clock.instant();
            boolean changed = !Objects.equals(user.passwordHash(), storedHash);
            boolean locked = user.lockedUntil() != null && user.lockedUntil().isAfter(now);
            String failure = changed ? "CREDENTIAL_CHANGED" : !matches ? "BAD_CREDENTIALS" : locked ? "LOCKED"
                    : !ACTIVE.equals(user.status()) ? "DISABLED" : null;
            if (failure != null) {
                if (!changed && !matches) {
                    repository.recordFailedLogin(user.id(), login.maxFailures(), now.plus(login.lockout()), now);
                }
                audit.record("LOGIN_FAILED", AuthAuditLog.FAILED, user.organizationId(), null, user.id(), "password",
                        null, "USER", user.id(), failure);
                return Optional.<UserRow>empty();
            }
            repository.recordSuccessfulLogin(user.id(), now);
            return Optional.of(user);
        });
        verified.filter(user -> encoder.upgradeEncoding(user.passwordHash())).ifPresent(user -> {
            String upgraded = encoder.encode(presented);
            transactions.executeWithoutResult(status ->
                    repository.rehashPassword(user.id(), user.passwordHash(), upgraded, clock.instant()));
        });
        return verified;
    }

    /**
     * The principal of a local user acting in one of its workspaces, for an authentication provider that verified
     * the user's identity itself, such as a later external identity provider. Authority still comes only from the
     * user's status and current membership here, so such a provider cannot widen tenant or resource access.
     *
     * @param credentialId the provider's own revocable credential or assertion identifier, for audit
     */
    public Optional<AuthenticatedPrincipal> principalFor(UUID userId, UUID workspaceId, AuthenticationMethod method,
            UUID credentialId) {
        return transactions.execute(status -> repository.findUser(userId).flatMap(user -> {
            String role = workspaceId == null ? null : repository.findMembership(workspaceId, userId)
                    .filter(membership -> membership.organizationId().equals(user.organizationId()))
                    .map(membership -> membership.role()).orElse(null);
            if (workspaceId != null && role == null) return Optional.empty();
            Instant now = clock.instant();
            return Principals.of(new CredentialGrant(credentialId, user.organizationId(), user.id(), workspaceId,
                    user.username(), user.organizationRole(), user.status(), user.mustChangePassword(), role, null,
                    now, now, now, null, null), method, null);
        }));
    }

    /** Creates a user in the administrator's organization with a temporary password that must be changed. */
    public IssuedPassword createUser(AuthenticatedPrincipal admin, String username, String displayName,
            String organizationRole, WorkspaceRole workspaceRole) {
        String normalized = PasswordPolicy.normalizeUsername(username).orElseThrow(() -> new IdentityException(
                IdentityException.INVALID_REQUEST, "The username must be 3 to 64 lower-case letters, digits, or ._@+-."));
        String name = displayName(displayName, normalized);
        String role = organizationRole == null ? ORGANIZATION_MEMBER : organizationRole;
        if (!ORGANIZATION_ADMIN.equals(role) && !ORGANIZATION_MEMBER.equals(role)) {
            throw new IdentityException(IdentityException.INVALID_REQUEST, "The organization role must be ADMIN or MEMBER.");
        }
        if (workspaceRole != null && admin.workspaceId() == null) {
            throw new IdentityException(IdentityException.WORKSPACE_SCOPE_UNAVAILABLE,
                    "A workspace role requires a selected workspace.");
        }
        if (workspaceRole != null && !admin.can(WorkspacePermission.MEMBER_ADMIN)) {
            throw new IdentityException(IdentityException.ROLE_NOT_GRANTABLE,
                    "Granting a workspace role requires administering the selected workspace.");
        }
        String temporary = codec.newTemporaryPassword();
        String hash = encoder.encode(temporary);
        UUID id = UUID.randomUUID();
        try {
            return transactions.execute(status -> {
                Instant now = clock.instant();
                repository.insertUser(id, admin.organizationId().value(), normalized, name, role, hash, true, now);
                audit.record("USER_CREATED", admin, "USER", id, role);
                if (workspaceRole != null) {
                    tenants.executeWithoutResult(admin.workspaceId(), () -> {
                        repository.putMembership(admin.organizationId().value(), id, workspaceRole.name(), now);
                        audit.record("MEMBERSHIP_SET", admin, "USER", id, workspaceRole.name());
                    });
                }
                return new IssuedPassword(repository.findUser(id).orElseThrow(), temporary);
            });
        } catch (DuplicateKeyException taken) {
            throw new IdentityException(IdentityException.USERNAME_TAKEN, "The username is already in use.");
        }
    }

    public List<UserRow> listUsers(AuthenticatedPrincipal admin) {
        return transactions.execute(status -> repository.listUsers(admin.organizationId().value()));
    }

    /**
     * Issues a new temporary password. The user must change it at the next sign-in, and every session and API
     * token the user holds is revoked because a reset implies the old credential may be compromised.
     */
    public IssuedPassword resetPassword(AuthenticatedPrincipal admin, UUID userId) {
        String temporary = codec.newTemporaryPassword();
        String hash = encoder.encode(temporary);
        return transactions.execute(status -> {
            UserRow user = organizationUser(admin, userId);
            Instant now = clock.instant();
            repository.updatePassword(user.id(), hash, true, now);
            repository.revokeSessionsOf(user.id(), null, "PASSWORD_RESET", now);
            repository.revokeTokensOf(user.id(), admin.subjectId(), now);
            audit.record("PASSWORD_RESET", admin, "USER", user.id(), null);
            return new IssuedPassword(repository.findUser(user.id()).orElseThrow(), temporary);
        });
    }

    /**
     * Changes the principal's own password after verifying the current one, which counts toward lockout when
     * wrong. Every session of the user is revoked in the same transaction; the caller issues a fresh one.
     */
    public void changePassword(AuthenticatedPrincipal principal, String currentPassword, String newPassword) {
        hashing.run(() -> {
            change(principal, currentPassword, newPassword);
            return null;
        });
    }

    private void change(AuthenticatedPrincipal principal, String currentPassword, String newPassword) {
        String presented = currentPassword == null || currentPassword.length() > MAX_PRESENTED_LENGTH ? ""
                : currentPassword;
        // As for sign-in, every hash runs before the short transaction and with no connection held.
        UserRow snapshot = repository.findUser(principal.subjectId()).orElseThrow(IdentityException::authenticationFailed);
        String storedHash = snapshot.passwordHash();
        // Lockout takes precedence: a locked account is refused below with the same 401 whatever either password
        // is, so the new-password policy is never evaluated or revealed for it.
        boolean lockedNow = snapshot.lockedUntil() != null && snapshot.lockedUntil().isAfter(clock.instant());
        boolean matches = !lockedNow && encoder.matches(presented, storedHash == null ? unknownUserHash : storedHash)
                && storedHash != null;
        String replacement = null;
        if (matches) {
            passwords.validate(snapshot.username(), newPassword);
            if (encoder.matches(newPassword, storedHash)) {
                throw new IdentityException(IdentityException.PASSWORD_POLICY,
                        "Password rejected: the new password must differ from the current password.");
            }
            replacement = encoder.encode(newPassword);
        }
        String newHash = replacement;
        IdentityException rejected = transactions.execute(status -> {
            UserRow user = repository.lockUser(principal.subjectId()).orElseThrow(IdentityException::authenticationFailed);
            Instant now = clock.instant();
            boolean changed = !Objects.equals(user.passwordHash(), storedHash);
            boolean locked = user.lockedUntil() != null && user.lockedUntil().isAfter(now);
            if (changed || !matches || locked) {
                if (!changed) repository.recordFailedLogin(user.id(), login.maxFailures(), now.plus(login.lockout()), now);
                audit.record("PASSWORD_CHANGE_FAILED", AuthAuditLog.FAILED, user.organizationId(), null, user.id(),
                        principal.method().value(), principal.credentialId(), "USER", user.id(),
                        changed ? "CREDENTIAL_CHANGED" : "BAD_CREDENTIALS");
                return IdentityException.authenticationFailed();
            }
            repository.updatePassword(user.id(), newHash, false, now);
            repository.revokeSessionsOf(user.id(), null, "PASSWORD_CHANGED", now);
            audit.record("PASSWORD_CHANGED", principal, "USER", user.id(), null);
            return null;
        });
        // The failed attempt is committed before the failure is reported.
        if (rejected != null) throw rejected;
    }

    public UserRow disable(AuthenticatedPrincipal admin, UUID userId) {
        if (admin.subjectId().equals(userId)) {
            throw new IdentityException(IdentityException.INVALID_REQUEST, "Administrators cannot disable themselves.");
        }
        return transactions.execute(status -> {
            UserRow user = organizationUser(admin, userId);
            Instant now = clock.instant();
            repository.setStatus(user.id(), DISABLED, now);
            repository.revokeSessionsOf(user.id(), null, "USER_DISABLED", now);
            repository.revokeTokensOf(user.id(), admin.subjectId(), now);
            audit.record("USER_DISABLED", admin, "USER", user.id(), null);
            return repository.findUser(user.id()).orElseThrow();
        });
    }

    /** Re-enables an account and clears any lockout. */
    public UserRow enable(AuthenticatedPrincipal admin, UUID userId) {
        return transactions.execute(status -> {
            UserRow user = organizationUser(admin, userId);
            repository.setStatus(user.id(), ACTIVE, clock.instant());
            audit.record("USER_ENABLED", admin, "USER", user.id(), null);
            return repository.findUser(user.id()).orElseThrow();
        });
    }

    /** Locks a user of the administrator's organization; any other user is indistinguishable from none. */
    private UserRow organizationUser(AuthenticatedPrincipal admin, UUID userId) {
        return repository.lockUser(userId).filter(user -> user.organizationId().equals(admin.organizationId().value()))
                .orElseThrow(IdentityException::notFound);
    }

    static String displayName(String displayName, String fallback) {
        String name = displayName == null || displayName.isBlank() ? fallback : displayName.strip();
        if (name.length() > 200 || name.chars().anyMatch(Character::isISOControl)) {
            throw new IdentityException(IdentityException.INVALID_REQUEST,
                    "The display name must be at most 200 printable characters.");
        }
        return name;
    }
}
