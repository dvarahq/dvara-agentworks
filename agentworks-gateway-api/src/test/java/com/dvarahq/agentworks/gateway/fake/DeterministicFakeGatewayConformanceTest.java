package com.dvarahq.agentworks.gateway.fake;

import com.dvarahq.agentworks.gateway.AiGatewayAdapter;
import com.dvarahq.agentworks.gateway.FinishReason;
import com.dvarahq.agentworks.gateway.GatewayCapability;
import com.dvarahq.agentworks.gateway.ResponseFormat;
import com.dvarahq.agentworks.gateway.conformance.AiGatewayAdapterConformanceSuite;
import com.dvarahq.agentworks.gateway.conformance.ConformanceHarness;
import com.dvarahq.agentworks.gateway.conformance.ProviderScript;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/** Runs the adapter conformance suite against the deterministic fake adapter. */
class DeterministicFakeGatewayConformanceTest extends AiGatewayAdapterConformanceSuite {

    @Override
    protected ConformanceHarness createHarness() {
        return new FakeHarness();
    }

    private static final class FakeHarness implements ConformanceHarness {
        private final AtomicReference<ProviderScript> script = new AtomicReference<>(
                ProviderScript.text(List.of("ok"), null));
        private final List<DeterministicFakeGatewayAdapter> adapters = new CopyOnWriteArrayList<>();
        private final AtomicReference<DeterministicFakeGatewayAdapter> lastUsed = new AtomicReference<>();

        @Override
        public AiGatewayAdapter adapter(Map<String, String> catalog, Set<GatewayCapability> advertised) {
            AtomicReference<DeterministicFakeGatewayAdapter> self = new AtomicReference<>();
            DeterministicFakeGatewayAdapter adapter = DeterministicFakeGatewayAdapter.builder(GATEWAY_ID)
                    .models(catalog).capabilities(advertised).requireCredential(true)
                    .script(invocation -> {
                        lastUsed.set(self.get());
                        return reply(script.get());
                    }).build();
            self.set(adapter);
            adapters.add(adapter);
            return adapter;
        }

        private static FakeReply reply(ProviderScript script) {
            return switch (script.failure()) {
                case NONE -> new FakeReply(script.chunks(), script.toolCalls(),
                        script.toolCalls().isEmpty() ? FinishReason.STOP : FinishReason.TOOL_CALLS, script.usage(),
                        script.servedModel(), script.fingerprint(), script.chunkDelay(), null, false);
                case HANG -> FakeReply.hanging();
                default -> FakeReply.failing(script.failure().expected());
            };
        }

        @Override
        public Set<GatewayCapability> supportedCapabilities() {
            return GatewayCapability.negotiatedCapabilities();
        }

        @Override
        public void script(ProviderScript next) {
            script.set(next);
        }

        @Override
        public int providerRequests() {
            return adapters.stream().mapToInt(DeterministicFakeGatewayAdapter::requests).sum();
        }

        @Override
        public ProviderView lastRequest() {
            DeterministicFakeGatewayAdapter adapter = lastUsed.get();
            if (adapter == null) {
                return null;
            }
            var recorded = adapter.lastRequest();
            return new ProviderView(recorded.providerModel(), recorded.plan().stream(), recorded.request().tools().size(),
                    recorded.request().responseFormat().kind() == ResponseFormat.Kind.JSON_SCHEMA,
                    recorded.request().messages().size());
        }

        @Override
        public String lastPresentedCredentialDigest() {
            DeterministicFakeGatewayAdapter adapter = lastUsed.get();
            return adapter == null ? null : adapter.lastCredentialDigest();
        }

        @Override
        public void close() {
            // Nothing to release: the fake owns no sockets or threads.
        }
    }
}
