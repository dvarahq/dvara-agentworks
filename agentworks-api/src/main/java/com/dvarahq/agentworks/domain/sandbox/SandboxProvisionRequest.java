package com.dvarahq.agentworks.domain.sandbox;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Run-owned sandbox request with the minimum assurances that the caller needs.
 * Providers must reject an unsupported assurance before acquiring any resource.
 */
public record SandboxProvisionRequest(SandboxOwner owner, Optional<SandboxConfiguration> configuration,
        Set<SandboxCapability> requiredCapabilities, Optional<UUID> reservedSandboxId) {
    public SandboxProvisionRequest {
        Objects.requireNonNull(owner, "owner");
        configuration = Objects.requireNonNull(configuration, "configuration");
        requiredCapabilities = Set.copyOf(Objects.requireNonNull(requiredCapabilities, "requiredCapabilities"));
        reservedSandboxId = Objects.requireNonNull(reservedSandboxId, "reservedSandboxId");
    }

    public SandboxProvisionRequest(SandboxOwner owner, Optional<SandboxConfiguration> configuration,
            Set<SandboxCapability> requiredCapabilities) {
        this(owner, configuration, requiredCapabilities, Optional.empty());
    }

    public SandboxProvisionRequest(SandboxOwner owner, Set<SandboxCapability> requiredCapabilities) {
        this(owner, Optional.empty(), requiredCapabilities, Optional.empty());
    }

    /**
     * Binds provisioning to an identity already reserved in durable state. Providers must either create exactly this
     * identity or fail; substituting a fresh identity would make crash reconciliation ambiguous.
     */
    public SandboxProvisionRequest withReservedSandboxId(UUID sandboxId) {
        return new SandboxProvisionRequest(owner, configuration, requiredCapabilities,
                Optional.of(Objects.requireNonNull(sandboxId, "sandboxId")));
    }

    public static SandboxProvisionRequest requiring(SandboxOwner owner, SandboxCapability... capabilities) {
        return new SandboxProvisionRequest(owner, Set.of(capabilities));
    }
}
