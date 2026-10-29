package com.dvarahq.agentworks.identity;

import com.dvarahq.agentworks.domain.identity.AuthenticatedPrincipal;
import com.dvarahq.agentworks.domain.identity.AuthenticationMethod;

import java.util.Objects;
import java.util.Optional;

/**
 * Re-checks the principal of a long-lived request, such as a run event stream, against current identity state
 * (issue #35). Authentication happens once per request, so a stream that outlives its credential would otherwise
 * keep reading after sign-out, revocation, expiry, a disabled account, or a membership change. The check uses the
 * credential's record id, never the credential itself, and records no use or activity.
 */
@FunctionalInterface
public interface PrincipalRevalidation {
    /** Refuses every principal; used when identity storage is unavailable. */
    PrincipalRevalidation REFUSE = principal -> Optional.empty();

    /** The principal as its credential authorizes it now, or empty when the credential no longer authenticates. */
    Optional<AuthenticatedPrincipal> revalidate(AuthenticatedPrincipal principal);

    /**
     * Sessions and API tokens are re-checked by their record; a principal of any other authentication provider is
     * re-derived from its user's current status and membership, as {@link IdentityService#principalFor} does.
     */
    static PrincipalRevalidation of(SessionService sessions, ApiTokenService tokens, IdentityService identities) {
        Objects.requireNonNull(sessions, "sessions");
        Objects.requireNonNull(tokens, "tokens");
        Objects.requireNonNull(identities, "identities");
        return principal -> {
            if (AuthenticationMethod.SESSION.equals(principal.method())) return sessions.revalidate(principal);
            if (AuthenticationMethod.API_TOKEN.equals(principal.method())) return tokens.revalidate(principal);
            return identities.principalFor(principal.subjectId(), principal.workspaceId() == null ? null
                    : principal.workspaceId().value(), principal.method(), principal.credentialId());
        };
    }
}
