package com.dvarahq.agentworks.credential.conformance;

import com.dvarahq.agentworks.credential.CredentialProvider;
import com.dvarahq.agentworks.domain.security.CredentialRef;
import com.dvarahq.agentworks.domain.security.InvocationIdentity;

import java.time.Instant;

/** Control surface used by the provider TCK to arrange deterministic provider states. */
public interface CredentialProviderConformanceHarness {

    CredentialProvider provider();

    void put(CredentialRef reference, InvocationIdentity identity, char[] material, Instant expiresAt);

    void revoke(CredentialRef reference, InvocationIdentity identity);

    void available(boolean available);

    int resolveCalls();
}
