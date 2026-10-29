package com.dvarahq.agentworks.sandbox.docker;

import com.dvarahq.agentworks.domain.sandbox.SandboxConfiguration;

import java.math.BigDecimal;
import java.util.Optional;

final class DockerSandboxTestSupport {
    static final String ALPINE_IMAGE =
            "alpine@sha256:4b7ce07002c69e8f3d704a9c5d6fd3053be500b7f1c69fc0d80990c2ad8dd412";

    private DockerSandboxTestSupport() {}

    static SandboxConfiguration configuration() {
        return new SandboxConfiguration("docker", "demand", Optional.of(ALPINE_IMAGE), new BigDecimal("0.5"),
                "64Mi", 32, "NONE", "5m");
    }
}
