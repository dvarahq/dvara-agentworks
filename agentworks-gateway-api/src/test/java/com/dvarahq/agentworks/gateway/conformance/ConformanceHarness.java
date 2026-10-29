package com.dvarahq.agentworks.gateway.conformance;

import com.dvarahq.agentworks.gateway.AiGatewayAdapter;
import com.dvarahq.agentworks.gateway.GatewayCapability;

import java.util.Map;
import java.util.Set;

/**
 * Connects {@link AiGatewayAdapterConformanceSuite} to one adapter implementation and a deterministic provider behind
 * it (an in-process fake or a stub HTTP server). No harness may use a real provider or paid credential.
 */
public interface ConformanceHarness extends AutoCloseable {

    /** The gateway identifier every harness adapter must use. */
    String GATEWAY_ID = "conformance";

    /**
     * A new adapter bound to this harness's provider.
     *
     * @param catalog alias to provider model
     * @param advertised the capabilities it must advertise, a subset of {@link #supportedCapabilities()}
     */
    AiGatewayAdapter adapter(Map<String, String> catalog, Set<GatewayCapability> advertised);

    /** Everything this adapter type can advertise. */
    Set<GatewayCapability> supportedCapabilities();

    /** How the provider answers subsequent requests. */
    void script(ProviderScript script);

    /** Requests the provider has received. */
    int providerRequests();

    /** What the provider received last, or {@code null} if nothing. */
    ProviderView lastRequest();

    /** SHA-256 hex digest of the credential the provider last received, or {@code null}. */
    String lastPresentedCredentialDigest();

    @Override
    void close();

    /** The provider's view of one request, extracted from what actually reached it. */
    record ProviderView(String model, boolean streamed, int tools, boolean structuredOutput, int messages) {
    }
}
