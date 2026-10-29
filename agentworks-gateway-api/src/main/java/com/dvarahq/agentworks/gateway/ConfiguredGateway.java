package com.dvarahq.agentworks.gateway;

import com.dvarahq.agentworks.domain.security.CredentialRef;

import java.util.Objects;

/**
 * An adapter plus its operator configuration.
 *
 * @param policy the capability policy new releases capture for roles bound to this gateway
 * @param credentialRef the credential resolved just in time for invocations, or {@code null} for a gateway that needs
 *        none (for example a loopback vLLM server)
 */
public record ConfiguredGateway(AiGatewayAdapter adapter, CapabilityPolicy policy, CredentialRef credentialRef) {
    public ConfiguredGateway {
        Objects.requireNonNull(adapter, "adapter");
        Objects.requireNonNull(policy, "policy");
    }

    public String gatewayId() {
        return adapter.descriptor().gatewayId();
    }

    @Override
    public String toString() {
        return "ConfiguredGateway[id=" + gatewayId() + ", type=" + adapter.type() + ", credentialConfigured="
                + (credentialRef != null) + "]";
    }
}
