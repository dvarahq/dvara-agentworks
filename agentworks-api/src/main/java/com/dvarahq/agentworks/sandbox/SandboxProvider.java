package com.dvarahq.agentworks.sandbox;

import com.dvarahq.agentworks.domain.sandbox.SandboxCapability;
import com.dvarahq.agentworks.domain.sandbox.SandboxOwner;
import com.dvarahq.agentworks.domain.sandbox.SandboxProvisionRequest;
import com.dvarahq.agentworks.domain.sandbox.SandboxSnapshot;

import java.util.Set;

public interface SandboxProvider extends AutoCloseable {
    Set<SandboxCapability> capabilities();
    SandboxInstance provision(SandboxOwner owner);

    /**
     * Negotiates required assurances before provisioning. Implementations that need request-specific configuration may
     * override this method, but must preserve the fail-before-allocation rule.
     */
    default SandboxInstance provision(SandboxProvisionRequest request) {
        Set<SandboxCapability> available = Set.copyOf(capabilities());
        if (!available.containsAll(request.requiredCapabilities())) {
            throw new SandboxCapabilityUnavailableException(request.requiredCapabilities(), available);
        }
        if (request.reservedSandboxId().isPresent()) {
            throw new IllegalArgumentException("Provider does not support a reserved sandbox identity");
        }
        return provision(request.owner());
    }

    SandboxInstance restore(SandboxSnapshot snapshot);
    @Override void close();
}
