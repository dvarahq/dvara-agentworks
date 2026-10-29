package com.dvarahq.agentworks.gateway.dvarahq;

import com.dvarahq.agentworks.gateway.AiGatewayAdapter;
import com.dvarahq.agentworks.gateway.GatewayCapability;
import com.dvarahq.agentworks.gateway.conformance.AiGatewayAdapterConformanceSuite;
import com.dvarahq.agentworks.gateway.conformance.ConformanceHarness;
import com.dvarahq.agentworks.gateway.conformance.ProviderScript;
import com.dvarahq.agentworks.gateway.openaicompat.StubChatCompletionsServer;

import java.util.Map;
import java.util.Set;

final class DvaraHqGatewayConformanceTest extends AiGatewayAdapterConformanceSuite {
    @Override protected ConformanceHarness createHarness() { return new Harness(); }

    private static final class Harness implements ConformanceHarness {
        private final StubChatCompletionsServer server = new StubChatCompletionsServer();
        @Override public AiGatewayAdapter adapter(Map<String, String> catalog, Set<GatewayCapability> advertised) {
            DvaraHqGatewayConfig base = DvaraHqGatewayConfig.of(GATEWAY_ID, server.baseUri(), catalog);
            return new DvaraHqGatewayAdapter(new DvaraHqGatewayConfig(base.gatewayId(), base.modelBaseUri(),
                    base.models(), advertised, base.mcpEndpoint(), base.a2aOrigin(), base.connectTimeout(),
                    base.maxModelResponseBytes(), base.maxModelStreamBytes()));
        }
        @Override public Set<GatewayCapability> supportedCapabilities() {
            return GatewayCapability.negotiatedCapabilities();
        }
        @Override public void script(ProviderScript script) { server.script(script); }
        @Override public int providerRequests() { return server.requests(); }
        @Override public ProviderView lastRequest() { return server.lastView(); }
        @Override public String lastPresentedCredentialDigest() { return server.lastCredentialDigest(); }
        @Override public void close() { server.close(); }
    }
}
