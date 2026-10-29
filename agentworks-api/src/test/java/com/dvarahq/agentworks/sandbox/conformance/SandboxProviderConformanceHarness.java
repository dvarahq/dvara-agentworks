package com.dvarahq.agentworks.sandbox.conformance;

import com.dvarahq.agentworks.sandbox.SandboxProvider;

import java.util.List;

/** Provider instrumentation and portable commands used by the sandbox lifecycle TCK. */
public interface SandboxProviderConformanceHarness extends AutoCloseable {
    SandboxProvider provider();

    int activeResourceCount();

    int liveExecutionCount();

    List<String> outputCommand(String value);

    List<String> longRunningCommand();

    @Override
    default void close() {
        provider().close();
    }
}
