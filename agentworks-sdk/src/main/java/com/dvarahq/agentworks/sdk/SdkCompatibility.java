package com.dvarahq.agentworks.sdk;

/** Public compatibility identity shared by the SDK, starter, TCK, and extension registrations. */
public final class SdkCompatibility {
    public static final int CONTRACT_VERSION = 1;
    public static final String SERVER_API_PREFIX = "/api/v1";
    private SdkCompatibility() { }

    public static void requireContractVersion(String component, int actual) {
        if (actual != CONTRACT_VERSION) {
            throw new IllegalStateException("Incompatible AgentWorks component " + component + ": contract " + actual
                    + " cannot run with SDK contract " + CONTRACT_VERSION
                    + "; import com.dvarahq.agentworks:agentworks-bom and remove explicit mixed versions");
        }
    }
}
