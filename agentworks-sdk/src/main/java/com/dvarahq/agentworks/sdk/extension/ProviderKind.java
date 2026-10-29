package com.dvarahq.agentworks.sdk.extension;

/** Closed 1.0 provider families. Evidence anchoring is intentionally deferred by ADR-030. */
public enum ProviderKind {
    GATEWAY, EXECUTOR, TOOL, SANDBOX, MEMORY, KNOWLEDGE, ARTIFACT, CREDENTIAL,
    POLICY, INTERACTION_CHANNEL, TELEMETRY
}
