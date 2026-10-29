package com.dvarahq.agentworks.sdk.extension;

import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import com.dvarahq.agentworks.sdk.SdkCompatibility;

/** One named provider and its truthfully advertised capabilities. */
public record ProviderRegistration<T>(ProviderKind kind, String id, Class<T> contract,
                                      T provider, Set<String> capabilities, int contractVersion) {
    private static final Pattern ID = Pattern.compile("[a-z0-9](?:[-a-z0-9.]{0,62}[a-z0-9])?");

    public ProviderRegistration {
        Objects.requireNonNull(kind, "kind");
        if (id == null || !ID.matcher(id).matches()) throw new IllegalArgumentException("provider id");
        Objects.requireNonNull(contract, "contract");
        Objects.requireNonNull(provider, "provider");
        if (!contract.isInstance(provider)) throw new IllegalArgumentException("provider does not implement contract");
        capabilities = Set.copyOf(capabilities == null ? Set.of() : capabilities);
        if (capabilities.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("capabilities must be nonblank");
        }
        SdkCompatibility.requireContractVersion(kind + "/" + id, contractVersion);
    }

    public ProviderRegistration(ProviderKind kind, String id, Class<T> contract,
                                T provider, Set<String> capabilities) {
        this(kind, id, contract, provider, capabilities, SdkCompatibility.CONTRACT_VERSION);
    }
}
