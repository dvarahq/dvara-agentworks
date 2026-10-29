package com.dvarahq.agentworks.gateway.dvarahq;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

final class DvaraHqMcpProxyTest {
    @Test void createsOnlyAnExplicitlyConfiguredFixedEndpointFactory() {
        DvaraHqGatewayConfig base = DvaraHqGatewayConfig.of("dvarahq",
                URI.create("https://gateway.example/v1"), Map.of("standard", "route"));
        DvaraHqGatewayConfig configured = new DvaraHqGatewayConfig(base.gatewayId(), base.modelBaseUri(),
                base.models(), base.modelCapabilities(), Optional.of(URI.create("https://gateway.example/mcp")),
                Optional.empty(), base.connectTimeout(), base.maxModelResponseBytes(), base.maxModelStreamBytes());

        assertThat(DvaraHqMcpProxy.sessions(configured, Duration.ofSeconds(2), Duration.ofSeconds(10), 64 * 1024))
                .isNotNull();
        assertThatIllegalStateException().isThrownBy(() -> DvaraHqMcpProxy.sessions(base,
                Duration.ofSeconds(2), Duration.ofSeconds(10), 64 * 1024))
                .withMessage("DVARAHQ_MCP_NOT_CONFIGURED");
    }
}
