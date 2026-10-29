package com.dvarahq.agentworks.gateway;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Secret-free identity of one configured gateway.
 *
 * @param gatewayId the neutral identifier an AgentSpec model binding names in {@code gateway}, for example {@code direct}
 * @param type the adapter type, for example {@code openai-compatible} or {@code deterministic-fake}
 * @param adapterVersion the adapter implementation version, recorded as provenance
 * @param protocol the wire protocol and version the adapter speaks; a change breaks existing pins
 */
public record GatewayDescriptor(String gatewayId, String type, String adapterVersion, String protocol) {

    /** Same pattern as AgentSpec identifiers, so every {@code models.<role>.gateway} value can name a gateway. */
    public static final Pattern IDENTIFIER = Pattern.compile("^[a-z0-9]([a-z0-9._-]*[a-z0-9])?$");

    public GatewayDescriptor {
        requireIdentifier(gatewayId, "gatewayId");
        requireIdentifier(type, "type");
        requireText(adapterVersion, "adapterVersion");
        requireText(protocol, "protocol");
    }

    static void requireIdentifier(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.length() > 128 || !IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " must match " + IDENTIFIER.pattern());
        }
    }

    static void requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > 256) {
            throw new IllegalArgumentException(name + " must be 1 to 256 non-blank characters");
        }
    }
}
