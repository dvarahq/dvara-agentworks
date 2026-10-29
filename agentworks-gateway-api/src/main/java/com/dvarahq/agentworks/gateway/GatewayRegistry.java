package com.dvarahq.agentworks.gateway;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * The immutable set of configured gateways, keyed by the identifier AgentSpec bindings name. It is also the
 * release-time {@link ModelRoleResolver}. An empty registry is valid: releases without model roles work, and releases
 * that declare roles fail with {@link GatewayErrorCode#GATEWAY_NOT_CONFIGURED}; nothing falls back silently.
 */
public final class GatewayRegistry implements ModelRoleResolver {

    private final Map<String, ConfiguredGateway> gateways;

    private GatewayRegistry(Map<String, ConfiguredGateway> gateways) {
        this.gateways = Collections.unmodifiableMap(gateways);
    }

    public static GatewayRegistry empty() {
        return new GatewayRegistry(Map.of());
    }

    /** @throws IllegalArgumentException if two gateways share an identifier */
    public static GatewayRegistry of(Collection<ConfiguredGateway> configured) {
        Map<String, ConfiguredGateway> byId = new LinkedHashMap<>();
        for (ConfiguredGateway gateway : Objects.requireNonNull(configured, "configured")) {
            if (byId.putIfAbsent(gateway.gatewayId(), gateway) != null) {
                throw new IllegalArgumentException("gateway identifier configured twice: " + gateway.gatewayId());
            }
        }
        return new GatewayRegistry(byId);
    }

    public Optional<ConfiguredGateway> find(String gatewayId) {
        return Optional.ofNullable(gateways.get(gatewayId));
    }

    public Collection<ConfiguredGateway> gateways() {
        return gateways.values();
    }

    @Override
    public SortedMap<ModelRole, ResolvedModelIdentity> resolve(Collection<ModelRoleBinding> bindings) {
        Map<ModelRole, ResolvedModelIdentity> resolved = new EnumMap<>(ModelRole.class);
        for (ModelRoleBinding binding : List.copyOf(bindings)) {
            ConfiguredGateway gateway = find(binding.gatewayId()).orElseThrow(() -> new GatewayException(
                    GatewayErrorCode.GATEWAY_NOT_CONFIGURED, "model role " + binding.role().manifestKey()
                    + " names gateway '" + binding.gatewayId() + "', which is not configured"));
            ModelResolutionRequest request = new ModelResolutionRequest(binding.role(), binding.alias());
            ResolvedModel model;
            try {
                model = gateway.adapter().resolveModel(request);
            } catch (GatewayException failure) {
                throw failure.code() == GatewayErrorCode.MODEL_ALIAS_UNKNOWN
                        ? new GatewayException(GatewayErrorCode.MODEL_ALIAS_UNKNOWN, "model role "
                                + binding.role().manifestKey() + " alias '" + binding.alias()
                                + "' is not in the catalog of gateway '" + binding.gatewayId() + "'")
                        : failure.withoutCause();
            } catch (RuntimeException broken) {
                throw contractViolation(binding);
            }
            GatewayDescriptor descriptor = adapterCall(binding, () -> gateway.adapter().descriptor());
            // Snapshot inside the adapter call, straight into an EnumSet (#174): reading the adapter's set (which may be
            // immutable or failing) happens once, under adapter-failure mapping, and a null element fails the copy, which
            // that mapping reports as an adapter contract violation.
            Set<GatewayCapability> capabilities = adapterCall(binding, () -> {
                Set<GatewayCapability> advertised = gateway.adapter().capabilities();
                if (advertised == null) return null;
                Set<GatewayCapability> snapshot = EnumSet.noneOf(GatewayCapability.class);
                snapshot.addAll(advertised);
                return snapshot;
            });
            if (model == null || descriptor == null || capabilities == null) {
                throw contractViolation(binding);
            }
            // Every input is now validated; Core-side construction failures are Core defects and are not relabelled.
            ResolvedModelIdentity identity = ResolvedModelIdentity.of(binding, descriptor, model, capabilities,
                    gateway.policy());
            if (resolved.put(binding.role(), identity) != null) {
                throw new GatewayException(GatewayErrorCode.INVALID_REQUEST,
                        "model role " + binding.role().manifestKey() + " is bound twice");
            }
        }
        return Collections.unmodifiableSortedMap(new TreeMap<>(resolved));
    }

    /**
     * Runs one adapter call. A typed {@link GatewayException} keeps its code (without any cause); any other exception
     * is an adapter contract violation, reported as {@code ADAPTER_FAILURE} without its message.
     */
    private static <T> T adapterCall(ModelRoleBinding binding, Supplier<T> call) {
        try {
            return call.get();
        } catch (GatewayException typed) {
            throw typed.withoutCause();
        } catch (RuntimeException broken) {
            throw contractViolation(binding);
        }
    }

    /** The adapter broke its contract (threw, or returned an invalid identity); its message is not propagated. */
    private static GatewayException contractViolation(ModelRoleBinding binding) {
        return new GatewayException(GatewayErrorCode.ADAPTER_FAILURE, "gateway '" + binding.gatewayId()
                + "' violated the adapter contract while resolving model role " + binding.role().manifestKey());
    }

    @Override
    public String toString() {
        return "GatewayRegistry" + gateways.values();
    }
}
