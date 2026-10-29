package com.dvarahq.agentworks.gateway;

import com.dvarahq.agentworks.domain.lifecycle.AgentReleaseId;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import com.dvarahq.agentworks.gateway.fake.DeterministicFakeGatewayAdapter;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/** Release-boundary resolution and the strict, versioned manifest reader behind run pinning. */
class PinnedModelRolesTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final WorkspaceId workspace = new WorkspaceId(UUID.randomUUID());
    private final AgentReleaseId release = new AgentReleaseId(UUID.randomUUID());

    private static GatewayRegistry registry(String model) {
        return GatewayRegistry.of(List.of(new ConfiguredGateway(DeterministicFakeGatewayAdapter.builder("direct")
                .model("coding-premium", model).model("economical-small", "small-1").build(),
                CapabilityPolicy.DEFAULT, null)));
    }

    private static List<ModelRoleBinding> bindings() {
        return List.of(new ModelRoleBinding(ModelRole.REASONING, "direct", "coding-premium"),
                new ModelRoleBinding(ModelRole.CAPABILITY_ROUTING, "direct", "economical-small"));
    }

    private static String manifest(int version, Map<String, Object> models) {
        return JSON.writeValueAsString(Map.of("manifestVersion", version, "models", models));
    }

    private static Map<String, Object> entries(Map<ModelRole, ResolvedModelIdentity> identities) {
        Map<String, Object> models = new HashMap<>();
        identities.forEach((role, identity) -> models.put(role.manifestKey(), identity.toManifestEntry()));
        return models;
    }

    @Test
    void resolvedIdentitiesRoundTripThroughTheVersion2Manifest() {
        var identities = registry("model-a").resolve(bindings());

        PinnedModelRoles pins = PinnedModelRoles.fromReleaseManifest(workspace, release, manifest(2, entries(identities)));

        assertThat(pins.identities()).isEqualTo(identities);
        ResolvedModelIdentity reasoning = pins.role(ModelRole.REASONING).identity();
        assertThat(reasoning.providerModel()).isEqualTo("model-a");
        assertThat(reasoning.gatewayType()).isEqualTo(DeterministicFakeGatewayAdapter.TYPE);
        assertThat(reasoning.resolutionSource()).isEqualTo(ResolvedModel.ResolutionSource.GATEWAY_ALIAS_CATALOG);
        assertThat(reasoning.capabilityPolicy()).isEqualTo(CapabilityPolicy.DEFAULT);
        assertThat(pins.role(ModelRole.CAPABILITY_ROUTING).identity().alias()).isEqualTo("economical-small");
        assertThat(reasoning.toManifestEntry()).containsOnlyKeys("alias", "gateway", "gatewayType", "adapterVersion",
                "protocol", "model", "resolution", "resolutionSource", "capabilities", "capabilityPolicy");
    }

    @Test
    void version13RoundTripsClosedResilienceAndConcreteFallbackPins() {
        var identities = registry("model-a").resolve(bindings());
        Map<String, Object> primary = new HashMap<>(identities.get(ModelRole.REASONING).toManifestEntry());
        primary.put("resilience", Map.ofEntries(
                Map.entry("maxAttempts", 2), Map.entry("initialBackoffMillis", 25),
                Map.entry("maxBackoffMillis", 200), Map.entry("jitter", 0.1),
                Map.entry("maxConcurrent", 8), Map.entry("bulkheadWaitMillis", 5),
                Map.entry("breakerFailureThreshold", 3), Map.entry("breakerOpenMillis", 1000),
                Map.entry("requestTimeoutMillis", 5000), Map.entry("streamIdleTimeoutMillis", 500),
                Map.entry("fallbacks", List.of(identities.get(ModelRole.CAPABILITY_ROUTING).toManifestEntry()))));

        PinnedModel pin = PinnedModelRoles.fromReleaseManifest(workspace, release,
                manifest(PinnedModelRoles.MODEL_RESILIENCE_MANIFEST_VERSION, Map.of("reasoning", primary)))
                .role(ModelRole.REASONING);

        assertThat(pin.resilience().maxAttemptsPerRoute()).isEqualTo(2);
        assertThat(pin.resilience().maxConcurrent()).isEqualTo(8);
        assertThat(pin.resilience().fallbacks()).singleElement()
                .extracting(ResolvedModelIdentity::providerModel).isEqualTo("small-1");

        Map<String, Object> openPolicy = new HashMap<>((Map<String, Object>) primary.get("resilience"));
        openPolicy.put("surprise", true);
        Map<String, Object> invalid = new HashMap<>(primary);
        invalid.put("resilience", openPolicy);
        GatewayException refused = catchThrowableOfType(GatewayException.class,
                () -> PinnedModelRoles.fromReleaseManifest(workspace, release,
                        manifest(PinnedModelRoles.MODEL_RESILIENCE_MANIFEST_VERSION, Map.of("reasoning", invalid))));
        assertThat(refused.code()).isEqualTo(GatewayErrorCode.MANIFEST_UNSUPPORTED);
    }

    @Test
    void aStoredPinIgnoresLaterCatalogChangesWhileNewResolutionsFollowThem() {
        String stored = manifest(2, entries(registry("model-a").resolve(bindings())));
        GatewayRegistry changed = registry("model-b");

        assertThat(PinnedModelRoles.fromReleaseManifest(workspace, release, stored).role(ModelRole.REASONING)
                .identity().providerModel()).isEqualTo("model-a");
        assertThat(changed.resolve(bindings()).get(ModelRole.REASONING).providerModel()).isEqualTo("model-b");
    }

    @Test
    void version1ManifestsKnowTheirRolesButRefuseToInvokeThem() {
        String legacy = manifest(1, Map.of("reasoning", Map.of("gateway", "direct", "alias", "coding-premium",
                "resolution", "UNRESOLVED_ALIAS")));

        PinnedModelRoles pins = PinnedModelRoles.fromReleaseManifest(workspace, release, legacy);

        assertThat(catchThrowableOfType(GatewayException.class, () -> pins.role(ModelRole.REASONING)).code())
                .isEqualTo(GatewayErrorCode.MODEL_ROLE_UNRESOLVED);
        assertThat(catchThrowableOfType(GatewayException.class, () -> pins.role(ModelRole.GUARDRAIL)).code())
                .isEqualTo(GatewayErrorCode.MODEL_ROLE_NOT_DECLARED);
    }

    // Review finding 3: stored version 2 policies stay readable when Core negotiates a capability later.
    @Test
    void storedPoliciesStayReadableWhenACapabilityIsNegotiatedLater() {
        Map<GatewayCapability, CapabilityMode> storedV2 = new java.util.EnumMap<>(CapabilityPolicy.DEFAULT.modes());
        java.util.Set<GatewayCapability> laterNegotiated = java.util.EnumSet.copyOf(CapabilityPolicy.V2_CAPABILITIES);
        laterNegotiated.add(GatewayCapability.MULTIMODAL_INPUT);

        CapabilityPolicy read = CapabilityPolicy.fromStored(storedV2, laterNegotiated);

        assertThat(read.modeFor(GatewayCapability.MULTIMODAL_INPUT)).isEqualTo(CapabilityMode.REQUIRED);
        assertThat(read.modeFor(GatewayCapability.STREAMING)).isEqualTo(CapabilityMode.DEGRADE);
        assertThat(CapabilityPolicy.fromStored(storedV2, GatewayCapability.negotiatedCapabilities()))
                .isEqualTo(CapabilityPolicy.DEFAULT);
    }

    @Test
    void aStoredVersion2PolicyMissingAVersion2CapabilityIsRefused() {
        Map<String, Object> entry = new HashMap<>(registry("model-a").resolve(bindings()).get(ModelRole.REASONING)
                .toManifestEntry());
        Map<String, String> policy = new java.util.TreeMap<>(CapabilityPolicy.DEFAULT.toManifest());
        policy.remove("STREAMING");
        entry.put("capabilityPolicy", policy);

        assertThat(catchThrowableOfType(GatewayException.class, () -> PinnedModelRoles.fromReleaseManifest(workspace,
                release, manifest(2, Map.of("reasoning", entry)))).code())
                .isEqualTo(GatewayErrorCode.MANIFEST_UNSUPPORTED);
    }

    // Review finding 10: version 1 entries are a closed shape too.
    @Test
    void malformedVersion1EntriesAreRefused() {
        Map<String, Object> valid = Map.of("gateway", "direct", "alias", "coding-premium",
                "resolution", "UNRESOLVED_ALIAS");
        Map<String, Object> extra = new HashMap<>(valid);
        extra.put("model", "claims-a-model");
        Map<String, Object> missing = new HashMap<>(valid);
        missing.remove("alias");
        Map<String, Object> resolved = new HashMap<>(valid);
        resolved.put("resolution", "RESOLVED");

        assertThat(PinnedModelRoles.fromReleaseManifest(workspace, release, manifest(1, Map.of("reasoning", valid))))
                .isNotNull();
        for (Object entry : List.of(extra, missing, resolved, "not-an-object", Map.of("gateway", 7, "alias", "a",
                "resolution", "UNRESOLVED_ALIAS"))) {
            String manifest = manifest(1, Map.of("reasoning", entry));
            assertThat(catchThrowableOfType(GatewayException.class,
                    () -> PinnedModelRoles.fromReleaseManifest(workspace, release, manifest)).code())
                    .as(manifest).isEqualTo(GatewayErrorCode.MANIFEST_UNSUPPORTED);
        }
    }

    @Test
    void unknownVersionsFieldsAndMalformedEntriesAreRefused() {
        Map<String, Object> entry = new HashMap<>(registry("model-a").resolve(bindings()).get(ModelRole.REASONING)
                .toManifestEntry());
        Map<String, Object> extra = new HashMap<>(entry);
        extra.put("endpoint", "https://example.invalid");
        Map<String, Object> missing = new HashMap<>(entry);
        missing.remove("capabilityPolicy");
        Map<String, Object> unresolved = new HashMap<>(entry);
        unresolved.put("resolution", "UNRESOLVED_ALIAS");
        Map<String, Object> badCapability = new HashMap<>(entry);
        badCapability.put("capabilities", List.of("TELEPORTATION"));

        for (String manifest : List.of(
                manifest(PinnedModelRoles.MODEL_RESILIENCE_MANIFEST_VERSION + 1, Map.of()),
                "{\"manifestVersion\":\"2\",\"models\":{}}",
                "{\"manifestVersion\":2,\"models\":{},\"surprise\":true}",
                "{\"manifestVersion\":2}",
                "not json",
                "{\"manifestVersion\":2,\"models\":{},\"models\":{}}",
                manifest(2, Map.of("reasoning", extra)),
                manifest(2, Map.of("reasoning", missing)),
                manifest(2, Map.of("reasoning", unresolved)),
                manifest(2, Map.of("reasoning", badCapability)),
                manifest(2, Map.of("telepathy", entry)))) {
            assertThat(catchThrowableOfType(GatewayException.class,
                    () -> PinnedModelRoles.fromReleaseManifest(workspace, release, manifest)).code())
                    .as(manifest).isEqualTo(GatewayErrorCode.MANIFEST_UNSUPPORTED);
        }
    }

    @Test
    void versionFourAllowsExecutionEvidenceWithoutChangingModelPins() {
        Map<String, Object> models = entries(registry("model-a").resolve(bindings()));
        String stored = JSON.writeValueAsString(Map.of(
                "manifestVersion", 4,
                "models", models,
                "execution", Map.of("initialIntent", "ANALYZE", "autonomyProfile", "SUPERVISED")));

        PinnedModelRoles pins = PinnedModelRoles.fromReleaseManifest(workspace, release, stored);

        assertThat(pins.role(ModelRole.REASONING).identity().providerModel()).isEqualTo("model-a");
    }

    @Test
    void versionFiveAllowsEffectEvidenceWithoutChangingModelPins() {
        Map<String, Object> models = entries(registry("model-a").resolve(bindings()));
        String stored = JSON.writeValueAsString(Map.of(
                "manifestVersion", 5,
                "models", models,
                "execution", Map.of("initialIntent", "ANALYZE", "autonomyProfile", "SUPERVISED")));

        PinnedModelRoles pins = PinnedModelRoles.fromReleaseManifest(workspace, release, stored);

        assertThat(pins.role(ModelRole.REASONING).identity().providerModel()).isEqualTo("model-a");
    }

    @Test
    void versionSixAllowsSandboxEvidenceWithoutChangingModelPins() {
        Map<String, Object> models = entries(registry("model-a").resolve(bindings()));
        String stored = JSON.writeValueAsString(Map.of(
                "manifestVersion", 6,
                "models", models,
                "execution", Map.of("initialIntent", "ANALYZE", "autonomyProfile", "SUPERVISED"),
                "sandbox", Map.of("provider", "docker")));

        PinnedModelRoles pins = PinnedModelRoles.fromReleaseManifest(workspace, release, stored);

        assertThat(pins.role(ModelRole.REASONING).identity().providerModel()).isEqualTo("model-a");
    }

    @Test
    void resolutionFailsClosedForUnknownGatewaysAndAliases() {
        GatewayRegistry registry = registry("model-a");

        var unconfigured = catchThrowableOfType(GatewayException.class, () -> registry.resolve(
                List.of(new ModelRoleBinding(ModelRole.REASONING, "dvarahq", "coding-premium"))));
        var unknownAlias = catchThrowableOfType(GatewayException.class, () -> registry.resolve(
                List.of(new ModelRoleBinding(ModelRole.EVALUATION, "direct", "judge-model"))));
        var none = catchThrowableOfType(GatewayException.class, () -> GatewayRegistry.empty().resolve(bindings()));

        assertThat(unconfigured.code()).isEqualTo(GatewayErrorCode.GATEWAY_NOT_CONFIGURED);
        assertThat(unconfigured.getMessage()).contains("reasoning").contains("dvarahq");
        assertThat(unknownAlias.code()).isEqualTo(GatewayErrorCode.MODEL_ALIAS_UNKNOWN);
        assertThat(unknownAlias.getMessage()).contains("evaluation").contains("judge-model");
        assertThat(none.code()).isEqualTo(GatewayErrorCode.GATEWAY_NOT_CONFIGURED);
        assertThat(GatewayRegistry.empty().resolve(List.of())).isEmpty();
    }

    // Review 2, finding 7: adapter contract violations during resolution are typed, never raw exceptions.
    @Test
    void adapterContractViolationsDuringResolutionAreTyped() {
        var identity = DeterministicFakeGatewayAdapter.builder("direct").model("coding-premium", "m").build();
        AiGatewayAdapter nullCapabilities = new ForwardingAdapter(identity) {
            @Override
            public java.util.Set<GatewayCapability> capabilities() {
                return null;
            }
        };
        AiGatewayAdapter throwing = new ForwardingAdapter(identity) {
            @Override
            public ResolvedModel resolveModel(ModelResolutionRequest request) {
                throw new IllegalStateException("catalog lookup failed for sk-secret-9");
            }
        };
        List<ModelRoleBinding> binding = List.of(new ModelRoleBinding(ModelRole.REASONING, "direct", "coding-premium"));

        for (AiGatewayAdapter broken : List.of(nullCapabilities, throwing)) {
            GatewayRegistry registry = GatewayRegistry.of(List.of(new ConfiguredGateway(broken, CapabilityPolicy.DEFAULT,
                    null)));
            GatewayException failure = catchThrowableOfType(GatewayException.class, () -> registry.resolve(binding));
            assertThat(failure.code()).isEqualTo(GatewayErrorCode.ADAPTER_FAILURE);
            assertThat(failure.getMessage()).contains("direct").doesNotContain("sk-secret-9");
            assertThat(failure.getCause()).isNull();
        }
    }

    // Review 3, finding 7: a typed failure from an adapter call keeps its code; only untyped ones become ADAPTER_FAILURE.
    @Test
    void typedAdapterFailuresDuringResolutionKeepTheirCode() {
        var identity = DeterministicFakeGatewayAdapter.builder("direct").model("coding-premium", "m").build();
        AiGatewayAdapter unavailable = new ForwardingAdapter(identity) {
            @Override
            public java.util.Set<GatewayCapability> capabilities() {
                throw new GatewayException(GatewayErrorCode.PROVIDER_UNAVAILABLE, "capability discovery is down");
            }
        };
        GatewayRegistry registry = GatewayRegistry.of(List.of(new ConfiguredGateway(unavailable, CapabilityPolicy.DEFAULT,
                null)));

        GatewayException failure = catchThrowableOfType(GatewayException.class, () -> registry.resolve(
                List.of(new ModelRoleBinding(ModelRole.REASONING, "direct", "coding-premium"))));

        assertThat(failure.code()).isEqualTo(GatewayErrorCode.PROVIDER_UNAVAILABLE);
    }

    // Review 4, item 1: capability sets that reject contains(null), such as Set.of, are valid adapter output.
    @Test
    void immutableCapabilitySetsResolveAndNullElementsAreAdapterFailures() {
        var identity = DeterministicFakeGatewayAdapter.builder("direct").model("coding-premium", "m").build();
        AiGatewayAdapter setOf = new ForwardingAdapter(identity) {
            @Override
            public java.util.Set<GatewayCapability> capabilities() {
                return java.util.Set.of(GatewayCapability.STREAMING, GatewayCapability.USAGE);
            }
        };
        java.util.Set<GatewayCapability> withNull = new java.util.HashSet<>();
        withNull.add(null);
        AiGatewayAdapter nullElement = new ForwardingAdapter(identity) {
            @Override
            public java.util.Set<GatewayCapability> capabilities() {
                return withNull;
            }
        };
        List<ModelRoleBinding> binding = List.of(new ModelRoleBinding(ModelRole.REASONING, "direct", "coding-premium"));

        var resolved = GatewayRegistry.of(List.of(new ConfiguredGateway(setOf, CapabilityPolicy.DEFAULT, null)))
                .resolve(binding).get(ModelRole.REASONING);
        var failure = catchThrowableOfType(GatewayException.class, () -> GatewayRegistry.of(List.of(
                new ConfiguredGateway(nullElement, CapabilityPolicy.DEFAULT, null))).resolve(binding));

        assertThat(resolved.advertisedCapabilities()).containsExactlyInAnyOrder(GatewayCapability.STREAMING,
                GatewayCapability.USAGE);
        assertThat(failure.code()).isEqualTo(GatewayErrorCode.ADAPTER_FAILURE);
    }

    // Review 5, item 5: a capability set that fails while being read is an adapter failure, not a raw exception.
    @Test
    void aCapabilitySetThatFailsWhileBeingReadIsAnAdapterFailure() {
        var identity = DeterministicFakeGatewayAdapter.builder("direct").model("coding-premium", "m").build();
        AiGatewayAdapter brokenIterator = new ForwardingAdapter(identity) {
            @Override
            public java.util.Set<GatewayCapability> capabilities() {
                return new java.util.AbstractSet<>() {
                    @Override
                    public java.util.Iterator<GatewayCapability> iterator() {
                        throw new IllegalStateException("capability store unavailable");
                    }

                    @Override
                    public int size() {
                        return 1;
                    }
                };
            }
        };

        GatewayException failure = catchThrowableOfType(GatewayException.class, () -> GatewayRegistry.of(List.of(
                new ConfiguredGateway(brokenIterator, CapabilityPolicy.DEFAULT, null))).resolve(List.of(
                new ModelRoleBinding(ModelRole.REASONING, "direct", "coding-premium"))));

        assertThat(failure).isNotNull();
        assertThat(failure.code()).isEqualTo(GatewayErrorCode.ADAPTER_FAILURE);
    }

    /** Delegates everything, so a test overrides only the misbehaving method. */
    private static class ForwardingAdapter implements AiGatewayAdapter {
        private final AiGatewayAdapter delegate;

        ForwardingAdapter(AiGatewayAdapter delegate) {
            this.delegate = delegate;
        }

        @Override
        public GatewayDescriptor descriptor() {
            return delegate.descriptor();
        }

        @Override
        public java.util.Set<GatewayCapability> capabilities() {
            return delegate.capabilities();
        }

        @Override
        public ResolvedModel resolveModel(ModelResolutionRequest request) {
            return delegate.resolveModel(request);
        }

        @Override
        public AdapterResult invoke(AdapterInvocation invocation, ChunkSink sink) {
            return delegate.invoke(invocation, sink);
        }
    }

    @Test
    void gatewayIdentifiersAreUniqueAndBindingsAreValidated() {
        var adapter = DeterministicFakeGatewayAdapter.builder("direct").build();

        assertThatThrownBy(() -> GatewayRegistry.of(List.of(new ConfiguredGateway(adapter, CapabilityPolicy.DEFAULT, null),
                new ConfiguredGateway(adapter, CapabilityPolicy.DEFAULT, null))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ModelRoleBinding(ModelRole.REASONING, "Not An Id", "alias"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ModelRoleBinding(ModelRole.REASONING, "direct", "bad alias"))
                .isInstanceOf(IllegalArgumentException.class);
        // Review 3, finding 7: a Core-side input error is its own typed failure, not an adapter failure.
        assertThat(catchThrowableOfType(GatewayException.class, () -> registry("model-a").resolve(List.of(
                new ModelRoleBinding(ModelRole.REASONING, "direct", "coding-premium"),
                new ModelRoleBinding(ModelRole.REASONING, "direct", "economical-small")))).code())
                .isEqualTo(GatewayErrorCode.INVALID_REQUEST);
    }

    @Test
    void pinsFromIdentitiesRejectMislabelledRoles() {
        var identity = registry("model-a").resolve(bindings()).get(ModelRole.REASONING);

        assertThatThrownBy(() -> PinnedModelRoles.of(workspace, release, Map.of(ModelRole.EVALUATION, identity)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(PinnedModelRoles.of(workspace, release, Map.of(ModelRole.REASONING, identity))
                .role(ModelRole.REASONING).workspace()).isEqualTo(workspace);
    }
}
