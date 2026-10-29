package com.dvarahq.agentworks.testing;

/** Closed effect policy. Live effects need both isolated mode and a named opt-in. */
public record EffectPolicy(boolean isolated, boolean liveNetwork, boolean liveCredentials) {
    public EffectPolicy {
        if ((liveNetwork || liveCredentials) && !isolated) throw new IllegalArgumentException("live effects require isolated mode");
    }
    public static EffectPolicy denyAll() { return new EffectPolicy(false, false, false); }
    public void requireNetwork() { if (!liveNetwork) throw new ForbiddenEffectException("LIVE_NETWORK_FORBIDDEN"); }
    public void requireCredentials() { if (!liveCredentials) throw new ForbiddenEffectException("LIVE_CREDENTIAL_FORBIDDEN"); }
}
