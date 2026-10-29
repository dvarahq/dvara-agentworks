package com.dvarahq.agentworks.runplane.execution;

import com.dvarahq.agentworks.capability.CapabilityDisclosure;
import com.dvarahq.agentworks.capability.CapabilityResolution;
import com.dvarahq.agentworks.capability.CapabilityResolutionRequest;

import java.util.Objects;

/** Captured capability decision and its model-facing, summary-first projection for one run continuation. */
public record ResolvedRunCapabilities(CapabilityResolutionRequest request, CapabilityResolution resolution,
        CapabilityDisclosure disclosure) {
    public ResolvedRunCapabilities {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(resolution, "resolution");
        Objects.requireNonNull(disclosure, "disclosure");
    }
}
