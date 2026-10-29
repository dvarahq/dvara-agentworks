package com.dvarahq.agentworks.gateway.openaicompat;

import com.dvarahq.agentworks.gateway.AiGatewayAdapter;
import com.dvarahq.agentworks.gateway.GatewayCapability;
import com.dvarahq.agentworks.gateway.conformance.AiGatewayAdapterConformanceSuite;
import com.dvarahq.agentworks.gateway.conformance.ConformanceHarness;
import com.dvarahq.agentworks.gateway.conformance.ProviderScript;

import java.util.Map;
import java.util.Set;

/**
 * Runs the adapter conformance suite against the OpenAI-compatible direct adapter over real HTTP to a loopback stub
 * server that speaks the Chat Completions wire format. No provider account or paid credential is involved.
 */
class OpenAiCompatibleGatewayConformanceTest extends AiGatewayAdapterConformanceSuite {

    @Override
    protected ConformanceHarness createHarness() {
        return new HttpHarness();
    }

    private static final class HttpHarness implements ConformanceHarness {
        private final StubChatCompletionsServer server = new StubChatCompletionsServer();

        @Override
        public AiGatewayAdapter adapter(Map<String, String> catalog, Set<GatewayCapability> advertised) {
            return new OpenAiCompatibleGatewayAdapter(OpenAiCompatibleGatewayConfig.of(GATEWAY_ID, server.baseUri(),
                    catalog).withCapabilities(advertised));
        }

        @Override
        public Set<GatewayCapability> supportedCapabilities() {
            return GatewayCapability.negotiatedCapabilities();
        }

        @Override
        public void script(ProviderScript script) {
            server.script(script);
        }

        @Override
        public int providerRequests() {
            return server.requests();
        }

        @Override
        public ProviderView lastRequest() {
            return server.lastView();
        }

        @Override
        public String lastPresentedCredentialDigest() {
            return server.lastCredentialDigest();
        }

        @Override
        public void close() {
            server.close();
        }
    }
}
