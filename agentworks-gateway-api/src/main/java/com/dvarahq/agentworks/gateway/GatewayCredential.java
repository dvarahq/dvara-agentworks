package com.dvarahq.agentworks.gateway;

import java.util.function.Function;

/**
 * Just-in-time access to the gateway's credential for one invocation. Core authorizes and resolves the configured
 * {@code CredentialRef} only when the adapter first calls {@link #withMaterial}, after capability negotiation has
 * passed, and zeroizes the material when the invocation ends. Adapters must not retain the material, a derived value,
 * or this handle beyond the invocation, and must never put either into exceptions, logs, or events.
 */
public interface GatewayCredential {

    /** Whether the gateway is configured with a credential reference. */
    boolean present();

    /**
     * Applies {@code use} to the credential material.
     *
     * @throws GatewayException {@link GatewayErrorCode#CREDENTIAL_UNAVAILABLE} if none is configured or resolution fails
     */
    <T> T withMaterial(Function<char[], T> use);
}
