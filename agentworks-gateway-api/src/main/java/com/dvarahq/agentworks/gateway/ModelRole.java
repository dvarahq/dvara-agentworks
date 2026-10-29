package com.dvarahq.agentworks.gateway;

import java.util.Arrays;

/**
 * Logical model roles (master specification section 17.1). A role never names a provider model; a configured gateway
 * resolves each role's alias to a concrete model identity when a release is built.
 */
public enum ModelRole {
    REASONING("reasoning"),
    COMPACTION("compaction"),
    SUMMARIZATION("summarization"),
    MEMORY("memory"),
    CAPABILITY_ROUTING("capabilityRouting"),
    GUARDRAIL("guardrail"),
    EVALUATION("evaluation");

    private final String manifestKey;

    ModelRole(String manifestKey) {
        this.manifestKey = manifestKey;
    }

    /** The AgentSpec and release-manifest key for this role, for example {@code capabilityRouting}. */
    public String manifestKey() {
        return manifestKey;
    }

    public static ModelRole fromManifestKey(String key) {
        return Arrays.stream(values()).filter(role -> role.manifestKey.equals(key)).findFirst()
                .orElseThrow(() -> new GatewayException(GatewayErrorCode.MANIFEST_UNSUPPORTED,
                        "unknown model role in release manifest"));
    }
}
