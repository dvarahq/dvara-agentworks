package com.dvarahq.agentworks.tck;

import com.dvarahq.agentworks.sdk.extension.ProviderInvocationContext;

import java.util.Set;

/** Small adapter implemented by a provider TCK fixture; it must not expose application content in observations. */
public interface ProviderProbe extends AutoCloseable {
    Set<String> capabilities();
    boolean ready();
    /** True only after all provider-owned resources have been released. */
    boolean closed();
    ProviderObservation invoke(ProviderInvocationContext context, byte[] boundedInput);
    @Override void close();
}
