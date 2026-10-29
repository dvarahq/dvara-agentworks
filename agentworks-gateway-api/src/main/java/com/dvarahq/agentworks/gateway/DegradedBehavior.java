package com.dvarahq.agentworks.gateway;

import java.util.Arrays;

/** The single, documented behavior Core applies when policy permits degrading a missing capability. */
public enum DegradedBehavior {
    /** Streaming: the provider is called without streaming and the whole content is delivered as chunk 0. */
    SINGLE_CHUNK(GatewayCapability.STREAMING),
    /** Tool calls: the request is sent without tool definitions; the model cannot call tools. */
    TOOLS_OMITTED(GatewayCapability.TOOL_CALLS),
    /** Structured output: the schema is not sent; the caller must validate the content itself. */
    SCHEMA_NOT_ENFORCED(GatewayCapability.STRUCTURED_OUTPUT),
    /** Usage: the provider omitted usage and Core supplies a clearly labelled conservative estimate. */
    USAGE_UNREPORTED(GatewayCapability.USAGE),
    /** Cancellation: Core stops delivering and discards the result, but the provider request may run to completion. */
    LOCAL_CANCELLATION_ONLY(GatewayCapability.CANCELLATION),
    /** Model metadata: the served identity is the pinned model with no provider fingerprint. */
    SERVED_IDENTITY_UNREPORTED(GatewayCapability.MODEL_METADATA);

    private final GatewayCapability capability;

    DegradedBehavior(GatewayCapability capability) {
        this.capability = capability;
    }

    public GatewayCapability capability() {
        return capability;
    }

    public static DegradedBehavior forCapability(GatewayCapability capability) {
        return Arrays.stream(values()).filter(value -> value.capability == capability).findFirst()
                .orElseThrow(() -> new IllegalArgumentException(capability + " is not a negotiated capability"));
    }
}
