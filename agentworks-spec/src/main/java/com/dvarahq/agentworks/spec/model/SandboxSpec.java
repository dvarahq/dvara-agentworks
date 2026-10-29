package com.dvarahq.agentworks.spec.model;

import java.math.BigDecimal;

/** Demand-provisioned sandbox requirements. Runtime placement is a deployment concern and is not expressed here. */
public record SandboxSpec(String provider, SandboxProvisioning provisioning, String image, BigDecimal cpu,
        String memory) {
    public SandboxSpec {
        cpu = Decimals.normalize(cpu);
    }
}
