package com.dvarahq.agentworks.gateway.fixture;

import com.dvarahq.agentworks.domain.security.CredentialRef;

/** Allowed: gateway configuration holding only a credential reference. */
public record GatewayConfigWithCredentialRef(String baseUrl, CredentialRef credentialRef) {
}
