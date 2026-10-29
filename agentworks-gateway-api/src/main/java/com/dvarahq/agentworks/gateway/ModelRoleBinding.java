package com.dvarahq.agentworks.gateway;

import java.util.Objects;

/** One authored AgentSpec model binding: a role, the gateway identifier, and the logical alias. */
public record ModelRoleBinding(ModelRole role, String gatewayId, String alias) {
    public ModelRoleBinding {
        Objects.requireNonNull(role, "role");
        GatewayDescriptor.requireIdentifier(gatewayId, "gatewayId");
        ModelResolutionRequest.requireAlias(alias);
    }
}
