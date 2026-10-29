package com.dvarahq.agentworks.gateway;

import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;

/**
 * A {@link ModelRoleResolver} that refuses to pin any role to a gateway of a forbidden adapter type, for example the
 * deterministic fake outside local development. It is defense in depth behind startup validation: even a gateway
 * registry supplied by the application cannot get a forbidden type into a release.
 */
public final class RestrictedGatewayTypes implements ModelRoleResolver {

    private final ModelRoleResolver delegate;
    private final Set<String> forbiddenTypes;
    private final String reason;

    private RestrictedGatewayTypes(ModelRoleResolver delegate, Set<String> forbiddenTypes, String reason) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.forbiddenTypes = Set.copyOf(forbiddenTypes);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    /**
     * @param reason why the types are refused, appended to the {@link GatewayErrorCode#GATEWAY_NOT_PERMITTED} message;
     *        it must not contain configured values
     */
    public static ModelRoleResolver excluding(ModelRoleResolver delegate, Set<String> forbiddenTypes, String reason) {
        return new RestrictedGatewayTypes(delegate, forbiddenTypes, reason);
    }

    @Override
    public SortedMap<ModelRole, ResolvedModelIdentity> resolve(Collection<ModelRoleBinding> bindings) {
        SortedMap<ModelRole, ResolvedModelIdentity> resolved = delegate.resolve(bindings);
        for (ResolvedModelIdentity identity : resolved.values()) {
            if (forbiddenTypes.contains(identity.gatewayType())) {
                throw new GatewayException(GatewayErrorCode.GATEWAY_NOT_PERMITTED, "model role "
                        + identity.role().manifestKey() + " names gateway '" + identity.gatewayId() + "' of type "
                        + identity.gatewayType() + ", which " + reason);
            }
        }
        return resolved;
    }
}
