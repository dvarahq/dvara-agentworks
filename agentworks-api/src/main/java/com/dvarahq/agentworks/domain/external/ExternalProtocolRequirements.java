package com.dvarahq.agentworks.domain.external;

import java.util.Objects;
import java.util.Set;

public record ExternalProtocolRequirements(
        String protocolVersion,
        String schemaVersion,
        ExternalAssurance assurance,
        ExternalAccessMode accessMode,
        Set<ExternalCapability> capabilities
) {
    public ExternalProtocolRequirements {
        protocolVersion = required(protocolVersion, "protocolVersion");
        schemaVersion = required(schemaVersion, "schemaVersion");
        Objects.requireNonNull(assurance, "assurance");
        Objects.requireNonNull(accessMode, "accessMode");
        capabilities = Set.copyOf(Objects.requireNonNull(capabilities, "capabilities"));
    }

    private static String required(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
