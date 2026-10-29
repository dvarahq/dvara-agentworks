package com.dvarahq.agentworks.gateway;

import java.util.Collection;
import java.util.SortedMap;

/**
 * Resolves authored model-role bindings to concrete identities at the release boundary. Resolution is deterministic
 * and offline: it reads gateway configuration only, never contacts a provider, and never resolves a credential.
 */
@FunctionalInterface
public interface ModelRoleResolver {

    /**
     * @throws GatewayException {@link GatewayErrorCode#GATEWAY_NOT_CONFIGURED} or
     *         {@link GatewayErrorCode#MODEL_ALIAS_UNKNOWN}; nothing is partially resolved
     */
    SortedMap<ModelRole, ResolvedModelIdentity> resolve(Collection<ModelRoleBinding> bindings);
}
