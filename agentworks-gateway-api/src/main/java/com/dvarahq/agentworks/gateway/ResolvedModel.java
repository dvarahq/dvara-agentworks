package com.dvarahq.agentworks.gateway;

import java.util.Objects;

/**
 * An adapter's answer to {@link ModelResolutionRequest}: the concrete provider model the alias denotes.
 *
 * @param providerModel the exact model identifier sent to the provider
 * @param source how the adapter resolved it
 * @param fingerprint a provider or gateway fingerprint of the model, or {@code null} when none is available
 */
public record ResolvedModel(String providerModel, ResolutionSource source, String fingerprint) {

    public enum ResolutionSource {
        /** The operator-configured alias catalog of the gateway. */
        GATEWAY_ALIAS_CATALOG,
        /** The gateway resolved and pinned the alias itself (advertised as {@code MODEL_PIN_RESOLUTION}). */
        GATEWAY_PIN_RESOLUTION
    }

    public ResolvedModel {
        GatewayDescriptor.requireText(providerModel, "providerModel");
        Objects.requireNonNull(source, "source");
        if (fingerprint != null) {
            GatewayDescriptor.requireText(fingerprint, "fingerprint");
        }
    }
}
