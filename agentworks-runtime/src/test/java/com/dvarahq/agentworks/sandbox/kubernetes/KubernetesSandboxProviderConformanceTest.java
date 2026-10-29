package com.dvarahq.agentworks.sandbox.kubernetes;

import com.dvarahq.agentworks.sandbox.SandboxProvider;
import com.dvarahq.agentworks.sandbox.conformance.SandboxProviderConformanceHarness;
import com.dvarahq.agentworks.sandbox.conformance.SandboxProviderConformanceSuite;

import java.util.List;

class KubernetesSandboxProviderConformanceTest extends SandboxProviderConformanceSuite {
    @Override
    protected SandboxProviderConformanceHarness createHarness() {
        var control = new KubernetesSandboxTestSupport.FakeControl();
        var provider = new KubernetesSandboxProvider(KubernetesSandboxTestSupport.configuration(), ignored -> true,
                control);
        return new SandboxProviderConformanceHarness() {
            @Override public SandboxProvider provider() { return provider; }
            @Override public int activeResourceCount() { return control.resources.size(); }
            @Override public int liveExecutionCount() { return provider.liveExecutionCount(); }
            @Override public List<String> outputCommand(String value) { return List.of("printf", value); }
            @Override public List<String> longRunningCommand() { return List.of("long"); }
            @Override public void close() { provider.close(); }
        };
    }
}
