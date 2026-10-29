package com.dvarahq.agentworks.gateway;

import java.util.EnumSet;
import java.util.Set;

/**
 * Capabilities a gateway adapter may advertise (ADR-009). The first six are negotiated for every invocation; the rest
 * are advertised extension capabilities (for example from the optional DvaraHQ adapter, #76) that Core records but does
 * not yet negotiate.
 */
public enum GatewayCapability {
    /** Incremental, ordered content delivery. */
    STREAMING,
    /** Tool (function) definitions in the request and tool calls in the response. */
    TOOL_CALLS,
    /** Provider-enforced JSON Schema output. */
    STRUCTURED_OUTPUT,
    /** Provider-reported token usage. */
    USAGE,
    /** Aborting an in-flight provider request. */
    CANCELLATION,
    /** Provider-reported served model identity and fingerprint. */
    MODEL_METADATA,
    MULTIMODAL_INPUT,
    MODEL_ROUTING,
    MODEL_PIN_RESOLUTION,
    MODEL_FINGERPRINT,
    CONTINUITY_EVENTS,
    MCP_PROXY,
    A2A_PROXY,
    INLINE_POLICY,
    DELEGATED_IDENTITY,
    COST_ACCOUNTING,
    EVIDENCE_ANCHOR;

    private static final Set<GatewayCapability> NEGOTIATED = EnumSet.of(
            STREAMING, TOOL_CALLS, STRUCTURED_OUTPUT, USAGE, CANCELLATION, MODEL_METADATA);

    /** Whether Core negotiates this capability before every invocation. */
    public boolean negotiated() {
        return NEGOTIATED.contains(this);
    }

    public static Set<GatewayCapability> negotiatedCapabilities() {
        return EnumSet.copyOf(NEGOTIATED);
    }
}
