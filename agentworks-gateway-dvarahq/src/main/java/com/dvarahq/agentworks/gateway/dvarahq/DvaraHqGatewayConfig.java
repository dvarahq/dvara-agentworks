package com.dvarahq.agentworks.gateway.dvarahq;

import com.dvarahq.agentworks.gateway.GatewayCapability;
import com.dvarahq.agentworks.gateway.GatewayDescriptor;
import com.dvarahq.agentworks.gateway.ModelResolutionRequest;
import com.dvarahq.agentworks.gateway.openaicompat.OpenAiCompatibleGatewayConfig;

import java.net.URI;
import java.time.Duration;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Secret-free operator configuration for the optional DvaraHQ adapter. Credentials stay in Core as
 * {@code CredentialRef}s and are resolved immediately before each operation.
 */
public record DvaraHqGatewayConfig(
        String gatewayId,
        URI modelBaseUri,
        Map<String, ModelRoute> models,
        Set<GatewayCapability> modelCapabilities,
        Optional<URI> mcpEndpoint,
        Optional<URI> a2aOrigin,
        Duration connectTimeout,
        long maxModelResponseBytes,
        long maxModelStreamBytes) {

    private static final Set<GatewayCapability> MODEL_EXTENSIONS = EnumSet.of(
            GatewayCapability.MODEL_ROUTING,
            GatewayCapability.MODEL_FINGERPRINT,
            GatewayCapability.CONTINUITY_EVENTS);

    public DvaraHqGatewayConfig {
        new GatewayDescriptor(gatewayId, DvaraHqGatewayAdapter.TYPE,
                DvaraHqGatewayAdapter.ADAPTER_VERSION, DvaraHqGatewayAdapter.PROTOCOL);
        Objects.requireNonNull(modelBaseUri, "modelBaseUri");
        models = Collections.unmodifiableMap(new TreeMap<>(Objects.requireNonNull(models, "models")));
        if (models.isEmpty()) throw new IllegalArgumentException("models must not be empty");
        models.forEach((alias, route) -> {
            ModelResolutionRequest.requireAlias(alias);
            Objects.requireNonNull(route, "route for " + alias);
        });
        modelCapabilities = modelCapabilities == null
                ? GatewayCapability.negotiatedCapabilities()
                : immutableCapabilities(modelCapabilities);
        for (GatewayCapability capability : modelCapabilities) {
            if (!capability.negotiated() && !MODEL_EXTENSIONS.contains(capability)) {
                throw new IllegalArgumentException("DvaraHQ model endpoint cannot advertise " + capability
                        + "; configure MCP and A2A through their explicit endpoints");
            }
        }
        boolean advertisesStrictFingerprint = modelCapabilities.contains(GatewayCapability.MODEL_FINGERPRINT);
        boolean hasStrictRoute = models.values().stream().anyMatch(ModelRoute::requireFingerprintMatch);
        if (hasStrictRoute && !advertisesStrictFingerprint) {
            throw new IllegalArgumentException("strict model routes require MODEL_FINGERPRINT capability evidence");
        }
        if (advertisesStrictFingerprint && models.values().stream().anyMatch(route ->
                !route.requireFingerprintMatch() || route.fingerprint() == null)) {
            throw new IllegalArgumentException("MODEL_FINGERPRINT requires strict pinned evidence for every route");
        }
        mcpEndpoint = normalizedEndpoint(mcpEndpoint, "/mcp", "mcpEndpoint");
        a2aOrigin = normalizedEndpoint(a2aOrigin, "/a2a", "a2aOrigin");
        connectTimeout = connectTimeout == null ? OpenAiCompatibleGatewayConfig.DEFAULT_CONNECT_TIMEOUT : connectTimeout;
        if (connectTimeout.isZero() || connectTimeout.isNegative()) {
            throw new IllegalArgumentException("connectTimeout must be positive");
        }
        if (maxModelResponseBytes <= 0 || maxModelStreamBytes <= 0
                || maxModelStreamBytes < maxModelResponseBytes) {
            throw new IllegalArgumentException("model response bounds are invalid");
        }
        // Reuse the public direct adapter's complete URI and bound validation.
        openAiConfig(gatewayId, modelBaseUri, models, modelCapabilities, connectTimeout,
                maxModelResponseBytes, maxModelStreamBytes).validate();
    }

    public static DvaraHqGatewayConfig of(String gatewayId, URI modelBaseUri, Map<String, String> models) {
        Map<String, ModelRoute> routes = new TreeMap<>();
        models.forEach((alias, model) -> routes.put(alias, new ModelRoute(model, null, false)));
        return new DvaraHqGatewayConfig(gatewayId, modelBaseUri, routes,
                GatewayCapability.negotiatedCapabilities(), Optional.empty(), Optional.empty(),
                OpenAiCompatibleGatewayConfig.DEFAULT_CONNECT_TIMEOUT,
                OpenAiCompatibleGatewayConfig.DEFAULT_MAX_RESPONSE_BYTES,
                OpenAiCompatibleGatewayConfig.DEFAULT_MAX_STREAM_BYTES);
    }

    public Set<GatewayCapability> advertisedCapabilities() {
        Set<GatewayCapability> result = modelCapabilities.isEmpty()
                ? EnumSet.noneOf(GatewayCapability.class) : EnumSet.copyOf(modelCapabilities);
        if (mcpEndpoint.isPresent()) result.add(GatewayCapability.MCP_PROXY);
        if (a2aOrigin.isPresent()) result.add(GatewayCapability.A2A_PROXY);
        return Collections.unmodifiableSet(result);
    }

    OpenAiCompatibleGatewayConfig directConfig() {
        return openAiConfig(gatewayId, modelBaseUri, models, modelCapabilities, connectTimeout,
                maxModelResponseBytes, maxModelStreamBytes);
    }

    private static OpenAiCompatibleGatewayConfig openAiConfig(String gatewayId, URI modelBaseUri,
            Map<String, ModelRoute> models, Set<GatewayCapability> capabilities, Duration connectTimeout,
            long maxResponseBytes, long maxStreamBytes) {
        Map<String, String> catalog = new TreeMap<>();
        models.forEach((alias, route) -> catalog.put(alias, route.providerModel()));
        Set<GatewayCapability> negotiated = EnumSet.noneOf(GatewayCapability.class);
        capabilities.stream().filter(GatewayCapability::negotiated).forEach(negotiated::add);
        return new OpenAiCompatibleGatewayConfig(gatewayId, modelBaseUri, catalog, negotiated, connectTimeout,
                maxResponseBytes, maxStreamBytes, OpenAiCompatibleGatewayConfig.OutputTokenField.MAX_TOKENS);
    }

    private static Set<GatewayCapability> immutableCapabilities(Set<GatewayCapability> capabilities) {
        Objects.requireNonNull(capabilities, "modelCapabilities");
        return capabilities.isEmpty() ? Set.of()
                : Collections.unmodifiableSet(EnumSet.copyOf(capabilities));
    }

    private static Optional<URI> normalizedEndpoint(Optional<URI> value, String expectedPath, String name) {
        value = Objects.requireNonNull(value, name);
        if (value.isEmpty()) return value;
        URI uri = value.get();
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !expectedPath.equals(uri.getPath())) {
            throw new IllegalArgumentException(name + " must be a fixed " + expectedPath + " endpoint");
        }
        // The direct adapter has the shared HTTPS/loopback validation. Strip the endpoint path before validating.
        URI base = URI.create(uri.getScheme() + "://" + uri.getAuthority() + "/v1");
        OpenAiCompatibleGatewayConfig.of("validation", base, Map.of("validation", "validation"));
        return Optional.of(uri);
    }

    /** Release-time route evidence. A strict fingerprint is checked after every provider response. */
    public record ModelRoute(String providerModel, String fingerprint, boolean requireFingerprintMatch) {
        public ModelRoute {
            if (providerModel == null || providerModel.isBlank() || providerModel.length() > 256) {
                throw new IllegalArgumentException("providerModel must be 1 to 256 non-blank characters");
            }
            if (fingerprint != null && (fingerprint.isBlank() || fingerprint.length() > 256)) {
                throw new IllegalArgumentException("fingerprint must be 1 to 256 non-blank characters");
            }
            if (requireFingerprintMatch && fingerprint == null) {
                throw new IllegalArgumentException("a strict fingerprint check needs a release fingerprint");
            }
        }
    }
}
