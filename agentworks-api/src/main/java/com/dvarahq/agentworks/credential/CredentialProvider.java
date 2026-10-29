package com.dvarahq.agentworks.credential;

import com.dvarahq.agentworks.domain.security.CredentialRef;
import com.dvarahq.agentworks.domain.security.InvocationIdentity;

import java.time.Instant;

/**
 * Provider-neutral authorization and just-in-time credential resolution SPI. A provider reports a denial or refusal
 * as a {@link CredentialResolutionException}; it is audited as {@code DENIED_OR_FAILED} even when the invocation is
 * cancelled at the same moment, whereas any other exception thrown after a cancellation is taken as the cancellation's
 * own effect (for example an interrupted call).
 */
public interface CredentialProvider {
    /**
     * Authorizes the complete use context. Existing providers remain source-compatible through the identity-only
     * method; providers that support AGENT scope override this method.
     */
    default CredentialAuthorization authorize(CredentialUseRequest request, Instant now) {
        return authorize(request.credentialRef(), request.identity(), now);
    }

    CredentialAuthorization authorize(CredentialRef credentialRef, InvocationIdentity identity, Instant now);
    ResolvedCredential resolve(CredentialAuthorization authorization, Instant now);
}
