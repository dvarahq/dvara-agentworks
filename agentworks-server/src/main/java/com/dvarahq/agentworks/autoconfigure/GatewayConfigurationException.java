package com.dvarahq.agentworks.autoconfigure;

import java.util.Objects;

/**
 * Invalid model-gateway configuration, raised at startup. The message names the owning property or bean and never
 * echoes configured values, so no endpoint credential or secret-bearing value can reach logs through it.
 */
public final class GatewayConfigurationException extends IllegalStateException {

    private final String source;

    public GatewayConfigurationException(String source, String message) {
        super(Objects.requireNonNull(source, "source") + ": " + Objects.requireNonNull(message, "message"));
        this.source = source;
    }

    /** The property prefix ({@code agentworks.gateway.gateways.<id>}) or bean that is misconfigured. */
    public String source() {
        return source;
    }
}
