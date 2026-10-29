package com.dvarahq.agentworks.credential;

import com.dvarahq.agentworks.domain.security.CredentialRef;
import com.dvarahq.agentworks.domain.security.InvocationIdentity;

import java.util.Objects;
import java.util.UUID;

/** Secret-free, provider-issued authorization for one scoped JIT resolution. */
public record CredentialAuthorization(
        UUID grantId,
        CredentialRef credentialRef,
        InvocationIdentity identity
) {
    public CredentialAuthorization {
        Objects.requireNonNull(grantId, "grantId");
        Objects.requireNonNull(credentialRef, "credentialRef");
        Objects.requireNonNull(identity, "identity");
    }
}
