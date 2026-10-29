package com.dvarahq.agentworks.identity;

import com.dvarahq.agentworks.domain.identity.AuthenticatedPrincipal;
import com.dvarahq.agentworks.domain.identity.AuthenticationMethod;
import com.dvarahq.agentworks.domain.identity.WorkspacePermission;
import com.dvarahq.agentworks.domain.identity.WorkspaceRole;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import com.dvarahq.agentworks.storage.TenantTransactionTemplate;
import com.dvarahq.agentworks.storage.identity.IdentityRows.TokenRow;
import com.dvarahq.agentworks.storage.identity.JdbcIdentityRepository;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Workspace-bound API tokens for the CLI and automation. A token value is shown once at creation and stored only
 * as a digest. It carries a role no broader than its owner's membership, always expires, can be revoked, and is
 * re-validated against its row, its owner, and the owner's current membership on every request, so revocation,
 * disabling, and membership changes take effect immediately.
 */
public final class ApiTokenService {
    private static final int MAX_NAME_LENGTH = 100;

    /** A created token; {@code token} is the only copy of the value and is returned to the caller once. */
    public record IssuedToken(TokenRow row, String token) {
        @Override
        public String toString() {
            return "IssuedToken[id=" + row.id() + "]";
        }
    }

    private final TenantTransactionTemplate tenants;
    private final JdbcIdentityRepository repository;
    private final CredentialCodec codec;
    private final SecurityPolicy.Tokens policy;
    private final AuthAuditLog audit;
    private final Clock clock;

    public ApiTokenService(TransactionTemplate transactions, JdbcIdentityRepository repository, CredentialCodec codec,
            SecurityPolicy.Tokens policy, AuthAuditLog audit, Clock clock) {
        this.tenants = new TenantTransactionTemplate(Objects.requireNonNull(transactions, "transactions"));
        this.repository = Objects.requireNonNull(repository, "repository");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Creates a token in the principal's workspace. Only an interactive session may create tokens, so a leaked
     * token cannot mint longer-lived successors.
     *
     * @param role the token role, which the principal's membership role must cover; its own role when {@code null}
     * @param lifetime the validity period; the configured default when {@code null}, never beyond the maximum
     */
    public IssuedToken issue(AuthenticatedPrincipal principal, String name, String role, Duration lifetime) {
        if (!AuthenticationMethod.SESSION.equals(principal.method()) || principal.workspaceId() == null) {
            throw new IdentityException(IdentityException.ROLE_NOT_GRANTABLE,
                    "API tokens are created from a signed-in session with a selected workspace.");
        }
        String label = name == null ? "" : name.strip();
        if (label.isEmpty() || label.length() > MAX_NAME_LENGTH || label.chars().anyMatch(Character::isISOControl)) {
            throw new IdentityException(IdentityException.INVALID_REQUEST,
                    "The token name must be 1 to 100 printable characters.");
        }
        WorkspaceRole tokenRole = role == null ? principal.role() : WorkspaceRole.parse(role).orElseThrow(() ->
                new IdentityException(IdentityException.INVALID_REQUEST, "The token role is not a workspace role."));
        if (!principal.role().covers(tokenRole)) {
            throw new IdentityException(IdentityException.ROLE_NOT_GRANTABLE,
                    "A token cannot carry permissions its owner does not hold.");
        }
        Duration validity = lifetime == null ? policy.defaultLifetime() : lifetime;
        if (validity.isNegative() || validity.isZero() || validity.compareTo(policy.maxLifetime()) > 0) {
            throw new IdentityException(IdentityException.INVALID_REQUEST,
                    "The token lifetime must be positive and at most " + policy.maxLifetime().toDays() + " days.");
        }
        String value = codec.newValue(CredentialCodec.TOKEN_PREFIX);
        UUID id = UUID.randomUUID();
        return tenants.execute(principal.workspaceId(), () -> {
            Instant now = clock.instant();
            // The owner's membership is re-read in the transaction; the foreign key also requires it.
            repository.findMembership(principal.workspaceId().value(), principal.subjectId())
                    .flatMap(membership -> WorkspaceRole.parse(membership.role()))
                    .filter(membership -> membership.covers(tokenRole))
                    .orElseThrow(() -> new IdentityException(IdentityException.ROLE_NOT_GRANTABLE,
                            "A token cannot carry permissions its owner does not hold."));
            repository.insertToken(id, CredentialCodec.digest(value), value.substring(0, 10), label,
                    principal.organizationId().value(), principal.workspaceId().value(), principal.subjectId(),
                    tokenRole.name(), now, now.plus(validity));
            audit.record("TOKEN_CREATED", principal, "API_TOKEN", id, tokenRole.name());
            return new IssuedToken(repository.findToken(id).orElseThrow(), value);
        });
    }

    /** Resolves a presented token; every malformed, unknown, tampered, expired, or revoked value is absent. */
    public Optional<AuthenticatedPrincipal> authenticate(String token) {
        if (!CredentialCodec.wellFormed(token, CredentialCodec.TOKEN_PREFIX)) return Optional.empty();
        String digest = CredentialCodec.digest(token);
        Instant now = clock.instant();
        return repository.tokenGrant(digest)
                .filter(grant -> CredentialCodec.sameDigest(grant.digest(), digest))
                .filter(grant -> grant.revokedAt() == null && grant.expiresAt().isAfter(now))
                .flatMap(grant -> {
                    Optional<AuthenticatedPrincipal> principal = Principals.of(grant, AuthenticationMethod.API_TOKEN,
                            grant.tokenRole());
                    if (principal.isPresent() && (grant.lastSeenAt() == null
                            || !grant.lastSeenAt().plus(policy.touchInterval()).isAfter(now))) {
                        repository.touchToken(grant.credentialId(), now);
                    }
                    return principal;
                });
    }

    /**
     * Re-checks the API token that authenticated {@code principal} by its record id, with the same rules as
     * {@link #authenticate(String)}, without recording a use (issue #35); see {@link SessionService#revalidate}.
     */
    public Optional<AuthenticatedPrincipal> revalidate(AuthenticatedPrincipal principal) {
        if (!AuthenticationMethod.API_TOKEN.equals(principal.method())) return Optional.empty();
        Instant now = clock.instant();
        return repository.tokenGrantById(principal.credentialId())
                .filter(grant -> grant.userId().equals(principal.subjectId()))
                .filter(grant -> grant.revokedAt() == null && grant.expiresAt().isAfter(now))
                .flatMap(grant -> Principals.of(grant, AuthenticationMethod.API_TOKEN, grant.tokenRole()));
    }

    /** The principal's tokens in its workspace, or every member's with {@code all} and token administration. */
    public List<TokenRow> list(AuthenticatedPrincipal principal, boolean all) {
        if (all && !principal.can(WorkspacePermission.TOKEN_ADMIN)) {
            throw new IdentityException(IdentityException.ROLE_NOT_GRANTABLE, "Listing every token requires administration.");
        }
        return tenants.execute(workspace(principal), () -> repository.listTokens(all ? null : principal.subjectId()));
    }

    /** Revokes a token of the principal's workspace that it owns, or any with token administration. */
    public void revoke(AuthenticatedPrincipal principal, UUID tokenId) {
        tenants.executeWithoutResult(workspace(principal), () -> {
            TokenRow token = repository.findToken(tokenId)
                    .filter(row -> row.userId().equals(principal.subjectId())
                            || principal.can(WorkspacePermission.TOKEN_ADMIN))
                    .orElseThrow(IdentityException::notFound);
            if (repository.revokeToken(token.id(), principal.subjectId(), clock.instant())) {
                audit.record("TOKEN_REVOKED", principal, "API_TOKEN", token.id(), null);
            }
        });
    }

    private static WorkspaceId workspace(AuthenticatedPrincipal principal) {
        return principal.workspace().orElseThrow(() -> new IdentityException(
                IdentityException.WORKSPACE_SCOPE_UNAVAILABLE, "No workspace is selected."));
    }
}
