package com.dvarahq.agentworks.gateway;

/** What happens when an invocation needs a capability the gateway does not advertise. */
public enum CapabilityMode {
    /** Fail with {@link GatewayErrorCode#CAPABILITY_UNSUPPORTED} before provider contact. */
    REQUIRED,
    /** Proceed with the capability's documented {@link DegradedBehavior} and emit a degradation event. */
    DEGRADE
}
