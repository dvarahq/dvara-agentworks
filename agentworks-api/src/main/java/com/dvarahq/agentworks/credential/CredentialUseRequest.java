package com.dvarahq.agentworks.credential;

import com.dvarahq.agentworks.domain.security.CredentialRef;
import com.dvarahq.agentworks.domain.security.InvocationIdentity;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Secret-free identity and resource facts available when one credential use is authorized. */
public record CredentialUseRequest(CredentialRef credentialRef, InvocationIdentity identity,
        Optional<UUID> agentId) {
    public CredentialUseRequest {
        Objects.requireNonNull(credentialRef, "credentialRef");
        Objects.requireNonNull(identity, "identity");
        agentId = Objects.requireNonNull(agentId, "agentId");
    }

    public static CredentialUseRequest withoutAgent(CredentialRef reference, InvocationIdentity identity) {
        return new CredentialUseRequest(reference, identity, Optional.empty());
    }

    public static CredentialUseRequest forAgent(CredentialRef reference, InvocationIdentity identity, UUID agentId) {
        return new CredentialUseRequest(reference, identity, Optional.of(Objects.requireNonNull(agentId, "agentId")));
    }
}
