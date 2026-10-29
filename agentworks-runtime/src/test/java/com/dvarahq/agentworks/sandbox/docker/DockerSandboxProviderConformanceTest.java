package com.dvarahq.agentworks.sandbox.docker;

import com.dvarahq.agentworks.sandbox.SandboxProvider;
import com.dvarahq.agentworks.sandbox.conformance.SandboxProviderConformanceHarness;
import com.dvarahq.agentworks.sandbox.conformance.SandboxProviderConformanceSuite;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

class DockerSandboxProviderConformanceTest extends SandboxProviderConformanceSuite {
    @TempDir
    Path temporaryDirectory;

    @Override
    protected SandboxProviderConformanceHarness createHarness() {
        DockerSandboxProvider provider = new DockerSandboxProvider(temporaryDirectory.resolve("provider"),
                DockerSandboxTestSupport.configuration());
        return new SandboxProviderConformanceHarness() {
            @Override
            public SandboxProvider provider() {
                return provider;
            }

            @Override
            public int activeResourceCount() {
                return provider.activeSandboxCount();
            }

            @Override
            public int liveExecutionCount() {
                return provider.liveExecutionCount();
            }

            @Override
            public List<String> outputCommand(String value) {
                return List.of("/bin/sh", "-c", "printf '%s\\n' \"$1\"", "sandbox-tck", value);
            }

            @Override
            public List<String> longRunningCommand() {
                return List.of("/bin/sh", "-c", "sleep 10");
            }
        };
    }
}
