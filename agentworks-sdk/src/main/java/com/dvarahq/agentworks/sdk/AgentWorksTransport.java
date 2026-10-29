package com.dvarahq.agentworks.sdk;

/** Injectable transport boundary used by the SDK and deterministic fake servers. */
@FunctionalInterface
public interface AgentWorksTransport extends AutoCloseable {
    ApiDocument execute(ApiCall call);
    @Override default void close() { }
}
