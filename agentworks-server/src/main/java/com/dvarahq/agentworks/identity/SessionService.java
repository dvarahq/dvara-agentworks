package com.dvarahq.agentworks.identity;

import com.dvarahq.agentworks.domain.identity.AuthenticatedPrincipal;
import com.dvarahq.agentworks.domain.identity.AuthenticationMethod;
import com.dvarahq.agentworks.storage.identity.IdentityRows.CredentialGrant;
import com.dvarahq.agentworks.storage.identity.IdentityRows.SessionRow;
import com.dvarahq.agentworks.storage.identity.IdentityRows.UserRow;
import com.dvarahq.agentworks.storage.identity.IdentityRows.WorkspaceMembershipRow;
import com.dvarahq.agentworks.storage.identity.JdbcIdentityRepository;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Server-side browser sessions. Each sign-in, workspace switch, and password change issues a new random session
 * value (the previous one is revoked, which defeats session fixation); only its digest is stored. Sessions expire
 * after an idle period and an absolute lifetime, a user holds at most a configured number of live sessions, and
 * every request re-reads the session, its user, and the current membership, so revocation, disabling, and
 * membership changes take effect on the next request on every node.
 */
public final class SessionService {

    /** A newly issued session. The cookie value and CSRF token are returned once to set on the response. */
    public record EstablishedSession(String cookieValue, String csrfToken, UUID sessionId,
            AuthenticatedPrincipal principal, Instant expiresAt) {
        @Override
        public String toString() {
            return "EstablishedSession[sessionId=" + sessionId + ", expiresAt=" + expiresAt + "]";
        }
    }

    private final TransactionTemplate transactions;
    private final JdbcIdentityRepository repository;
    private final CredentialCodec codec;
    private final SecurityPolicy.Session policy;
    private final AuthAuditLog audit;
    private final Clock clock;

    public SessionService(TransactionTemplate transactions, JdbcIdentityRepository repository, CredentialCodec codec,
            SecurityPolicy.Session policy, AuthAuditLog audit, Clock clock) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Issues a session for an authenticated, active user.
     *
     * @param requestedWorkspace a workspace the user must be a member of, or {@code null} for the oldest membership
     * @param presentedCookie the session value the client already held, revoked so it can never be promoted
     * @param event the audit event: {@code LOGIN_SUCCEEDED}, {@code SESSION_ROTATED}, or an external provider's
     * @param loginMethod how the user proved identity for this session, recorded in the audit trail
     */
    public EstablishedSession establish(UUID userId, UUID requestedWorkspace, String presentedCookie, String event,
            String loginMethod) {
        return establish(userId, requestedWorkspace, presentedCookie, event, loginMethod, false);
    }

    /**
     * @param keepLifetime when true, the new session expires no later than the presented session of the same user,
     *                     so rotating without re-authentication can never extend the absolute lifetime
     */
    private EstablishedSession establish(UUID userId, UUID requestedWorkspace, String presentedCookie, String event,
            String loginMethod, boolean keepLifetime) {
        return transactions.execute(status -> {
            // Stored timestamps keep microseconds; truncating here makes the reported expiry equal the stored one.
            Instant now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            UserRow user = repository.lockUser(userId).filter(row -> IdentityService.ACTIVE.equals(row.status()))
                    .orElseThrow(IdentityException::authenticationFailed);
            Instant expires = now.plus(policy.absoluteTimeout());
            Optional<CredentialGrant> previous = CredentialCodec.wellFormed(presentedCookie, CredentialCodec.SESSION_PREFIX)
                    ? repository.sessionGrant(CredentialCodec.digest(presentedCookie)) : Optional.empty();
            if (keepLifetime) {
                CredentialGrant current = previous.filter(grant -> grant.userId().equals(user.id())
                        && grant.revokedAt() == null && grant.expiresAt().isAfter(now))
                        .orElseThrow(IdentityException::authenticationFailed);
                if (current.expiresAt().isBefore(expires)) expires = current.expiresAt();
            }
            previous.ifPresent(grant -> repository.revokeSession(grant.credentialId(), "ROTATED", now));
            List<WorkspaceMembershipRow> memberships = repository.membershipsOf(user.id());
            WorkspaceMembershipRow selected = requestedWorkspace == null ? memberships.stream().findFirst().orElse(null)
                    : memberships.stream().filter(row -> row.workspaceId().equals(requestedWorkspace)).findFirst()
                    .orElseThrow(() -> new IdentityException(IdentityException.WORKSPACE_SCOPE_UNAVAILABLE,
                            "The workspace is not available to this user."));
            List<SessionRow> active = repository.activeSessions(user.id(), now, now.minus(policy.idleTimeout()));
            for (int index = 0; index <= active.size() - policy.maxConcurrent(); index++) {
                SessionRow oldest = active.get(index);
                if (repository.revokeSession(oldest.id(), "CONCURRENT_LIMIT", now)) {
                    audit.record("SESSION_REVOKED", AuthAuditLog.SUCCEEDED, user.organizationId(), oldest.workspaceId(),
                            user.id(), AuthenticationMethod.SESSION.value(), oldest.id(), "SESSION", oldest.id(),
                            "CONCURRENT_LIMIT");
                }
            }
            String value = codec.newValue(CredentialCodec.SESSION_PREFIX);
            UUID id = UUID.randomUUID();
            UUID workspace = selected == null ? null : selected.workspaceId();
            Instant expiresAt = expires;
            repository.insertSession(id, CredentialCodec.digest(value), user.organizationId(), user.id(), workspace,
                    now, expiresAt);
            audit.record(event, AuthAuditLog.SUCCEEDED, user.organizationId(), workspace, user.id(), loginMethod, id,
                    "SESSION", id, null);
            CredentialGrant grant = new CredentialGrant(id, user.organizationId(), user.id(), workspace,
                    user.username(), user.organizationRole(), user.status(), user.mustChangePassword(),
                    selected == null ? null : selected.role(), null, now, now, expiresAt, null, null);
            AuthenticatedPrincipal principal = Principals.of(grant, AuthenticationMethod.SESSION, null)
                    .orElseThrow(IdentityException::authenticationFailed);
            return new EstablishedSession(value, CredentialCodec.csrfToken(value), id, principal, expiresAt);
        });
    }

    /** Resolves a presented session value; every invalid, expired, idle, or revoked value is simply absent. */
    public Optional<AuthenticatedPrincipal> authenticate(String cookieValue) {
        if (!CredentialCodec.wellFormed(cookieValue, CredentialCodec.SESSION_PREFIX)) return Optional.empty();
        String digest = CredentialCodec.digest(cookieValue);
        Instant now = clock.instant();
        return repository.sessionGrant(digest)
                .filter(grant -> CredentialCodec.sameDigest(grant.digest(), digest))
                .filter(grant -> grant.revokedAt() == null && grant.expiresAt().isAfter(now)
                        && grant.lastSeenAt().plus(policy.idleTimeout()).isAfter(now))
                .flatMap(grant -> {
                    Optional<AuthenticatedPrincipal> principal = Principals.of(grant, AuthenticationMethod.SESSION, null);
                    if (principal.isPresent() && !grant.lastSeenAt().plus(policy.touchInterval()).isAfter(now)) {
                        repository.touchSession(grant.credentialId(), now);
                    }
                    return principal;
                });
    }

    /**
     * Re-checks the session that authenticated {@code principal} by its record id, with the same rules as
     * {@link #authenticate(String)}, without touching its activity time (issue #35). A long-lived request such as an
     * event stream calls this periodically, so sign-out, revocation, expiry, idle timeout, a disabled account, or a
     * membership change ends it. Empty when the session is no longer valid.
     */
    public Optional<AuthenticatedPrincipal> revalidate(AuthenticatedPrincipal principal) {
        if (!AuthenticationMethod.SESSION.equals(principal.method())) return Optional.empty();
        Instant now = clock.instant();
        return repository.sessionGrantById(principal.credentialId())
                .filter(grant -> grant.userId().equals(principal.subjectId()))
                .filter(grant -> grant.revokedAt() == null && grant.expiresAt().isAfter(now)
                        && grant.lastSeenAt().plus(policy.idleTimeout()).isAfter(now))
                .flatMap(grant -> Principals.of(grant, AuthenticationMethod.SESSION, null));
    }

    /**
     * Moves the principal's browser into another of its workspaces under a new session value that keeps the absolute
     * lifetime of the session it replaces. Only a password verification starts a new lifetime.
     */
    public EstablishedSession switchWorkspace(AuthenticatedPrincipal principal, String presentedCookie, UUID workspace) {
        requireSession(principal);
        return establish(principal.subjectId(), workspace, presentedCookie, "SESSION_ROTATED",
                AuthenticationMethod.SESSION.value(), true);
    }

    public void logout(AuthenticatedPrincipal principal) {
        requireSession(principal);
        transactions.executeWithoutResult(status -> {
            if (repository.revokeSession(principal.credentialId(), "LOGOUT", clock.instant())) {
                audit.record("LOGOUT", principal, "SESSION", principal.credentialId(), null);
            }
        });
    }

    public List<SessionRow> list(AuthenticatedPrincipal principal) {
        Instant now = clock.instant();
        return transactions.execute(status -> repository.activeSessions(principal.subjectId(), now,
                now.minus(policy.idleTimeout())));
    }

    /** Revokes one of the principal's own sessions; another user's session is indistinguishable from none. */
    public void revoke(AuthenticatedPrincipal principal, UUID sessionId) {
        transactions.executeWithoutResult(status -> {
            if (!repository.revokeSessionOf(principal.subjectId(), sessionId, "REVOKED", clock.instant())) {
                throw IdentityException.notFound();
            }
            audit.record("SESSION_REVOKED", principal, "SESSION", sessionId, "REVOKED");
        });
    }

    public SecurityPolicy.Session policy() {
        return policy;
    }

    private static void requireSession(AuthenticatedPrincipal principal) {
        if (!AuthenticationMethod.SESSION.equals(principal.method())) throw IdentityException.authenticationFailed();
    }
}
