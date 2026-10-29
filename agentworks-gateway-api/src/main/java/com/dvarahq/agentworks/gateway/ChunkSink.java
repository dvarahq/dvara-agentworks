package com.dvarahq.agentworks.gateway;

/**
 * Core's side of one adapter invocation. A streaming adapter emits content deltas here in provider order. Once the
 * invocation is cancelled, or the listener failed, {@link #emit} throws a {@link GatewayException} so the adapter stops
 * reading.
 */
public interface ChunkSink {

    /**
     * Every adapter calls this immediately before the request can leave for the provider, after the credential is
     * resolved and the request is built. Failure evidence reports {@code providerContacted=false} only for failures
     * raised before this call, which is what lets a retry policy (#85) know that no model turn could have started.
     * The conformance suite checks that provider failures are reported as contacted.
     */
    void requestSent();

    void emit(String delta);
}
