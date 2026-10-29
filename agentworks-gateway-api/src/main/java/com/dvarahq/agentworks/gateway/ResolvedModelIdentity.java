package com.dvarahq.agentworks.gateway;

import com.dvarahq.agentworks.gateway.ResolvedModel.ResolutionSource;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * The concrete, secret-free model identity a release pins for one role (release manifest version 2). It names the
 * gateway, adapter, protocol, and provider model, the capabilities advertised at release time, and the capability
 * policy captured for invocations. It never contains an endpoint URL, a credential reference, or credential material:
 * credentials stay in gateway configuration and are resolved just in time.
 */
public record ResolvedModelIdentity(
        ModelRole role,
        String alias,
        String gatewayId,
        String gatewayType,
        String adapterVersion,
        String protocol,
        String providerModel,
        ResolutionSource resolutionSource,
        String fingerprint,
        Set<GatewayCapability> advertisedCapabilities,
        CapabilityPolicy capabilityPolicy) {

    /** The only resolution state version 2 records; unresolved models fail release creation instead. */
    public static final String RESOLVED = "RESOLVED";

    private static final Set<String> REQUIRED_FIELDS = Set.of("alias", "gateway", "gatewayType", "adapterVersion",
            "protocol", "model", "resolution", "resolutionSource", "capabilities", "capabilityPolicy");
    private static final Set<String> OPTIONAL_FIELDS = Set.of("fingerprint");

    public ResolvedModelIdentity {
        Objects.requireNonNull(role, "role");
        ModelResolutionRequest.requireAlias(alias);
        GatewayDescriptor.requireIdentifier(gatewayId, "gatewayId");
        GatewayDescriptor.requireIdentifier(gatewayType, "gatewayType");
        GatewayDescriptor.requireText(adapterVersion, "adapterVersion");
        GatewayDescriptor.requireText(protocol, "protocol");
        GatewayDescriptor.requireText(providerModel, "providerModel");
        Objects.requireNonNull(resolutionSource, "resolutionSource");
        if (fingerprint != null) {
            GatewayDescriptor.requireText(fingerprint, "fingerprint");
        }
        Objects.requireNonNull(advertisedCapabilities, "advertisedCapabilities");
        advertisedCapabilities = Collections.unmodifiableSet(advertisedCapabilities.isEmpty()
                ? EnumSet.noneOf(GatewayCapability.class) : EnumSet.copyOf(advertisedCapabilities));
        Objects.requireNonNull(capabilityPolicy, "capabilityPolicy");
    }

    /** Builds the pinned identity from a binding, the gateway's descriptor, and the adapter's resolution. */
    public static ResolvedModelIdentity of(ModelRoleBinding binding, GatewayDescriptor descriptor,
            ResolvedModel resolved, Set<GatewayCapability> advertised, CapabilityPolicy policy) {
        return new ResolvedModelIdentity(binding.role(), binding.alias(), descriptor.gatewayId(), descriptor.type(),
                descriptor.adapterVersion(), descriptor.protocol(), resolved.providerModel(), resolved.source(),
                resolved.fingerprint(), advertised, policy);
    }

    /** The canonical manifest entry. Keys are sorted by the manifest writer; lists are sorted here. */
    public Map<String, Object> toManifestEntry() {
        Map<String, Object> entry = new TreeMap<>();
        entry.put("alias", alias);
        entry.put("gateway", gatewayId);
        entry.put("gatewayType", gatewayType);
        entry.put("adapterVersion", adapterVersion);
        entry.put("protocol", protocol);
        entry.put("model", providerModel);
        entry.put("resolution", RESOLVED);
        entry.put("resolutionSource", resolutionSource.name());
        if (fingerprint != null) {
            entry.put("fingerprint", fingerprint);
        }
        entry.put("capabilities", advertisedCapabilities.stream().map(Enum::name).sorted().toList());
        entry.put("capabilityPolicy", capabilityPolicy.toManifest());
        return entry;
    }

    /** Strict reader for a version 2 manifest entry; any unknown, missing, or ill-typed field is refused. */
    static ResolvedModelIdentity fromManifestEntry(ModelRole role, JsonNode entry) {
        if (entry == null || !entry.isObject()) {
            throw unsupported("model entry for " + role.manifestKey() + " is not an object");
        }
        for (String field : entry.propertyNames()) {
            if (!REQUIRED_FIELDS.contains(field) && !OPTIONAL_FIELDS.contains(field)) {
                throw unsupported("model entry for " + role.manifestKey() + " has an unknown field");
            }
        }
        for (String field : REQUIRED_FIELDS) {
            if (!entry.has(field)) {
                throw unsupported("model entry for " + role.manifestKey() + " lacks " + field);
            }
        }
        if (!RESOLVED.equals(text(entry, "resolution", role))) {
            throw unsupported("model entry for " + role.manifestKey() + " is not resolved");
        }
        try {
            return new ResolvedModelIdentity(role, text(entry, "alias", role), text(entry, "gateway", role),
                    text(entry, "gatewayType", role), text(entry, "adapterVersion", role),
                    text(entry, "protocol", role), text(entry, "model", role),
                    ResolutionSource.valueOf(text(entry, "resolutionSource", role)),
                    entry.has("fingerprint") ? text(entry, "fingerprint", role) : null,
                    capabilities(entry.get("capabilities"), role), policy(entry.get("capabilityPolicy"), role));
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw unsupported("model entry for " + role.manifestKey() + " has an invalid value");
        }
    }

    private static Set<GatewayCapability> capabilities(JsonNode node, ModelRole role) {
        if (!node.isArray()) {
            throw unsupported("model entry for " + role.manifestKey() + " has invalid capabilities");
        }
        List<GatewayCapability> values = new ArrayList<>();
        for (JsonNode value : node.values()) {
            if (!value.isString()) {
                throw unsupported("model entry for " + role.manifestKey() + " has invalid capabilities");
            }
            values.add(GatewayCapability.valueOf(value.stringValue()));
        }
        return values.isEmpty() ? EnumSet.noneOf(GatewayCapability.class) : EnumSet.copyOf(values);
    }

    private static CapabilityPolicy policy(JsonNode node, ModelRole role) {
        if (!node.isObject()) {
            throw unsupported("model entry for " + role.manifestKey() + " has an invalid capability policy");
        }
        Map<GatewayCapability, CapabilityMode> modes = new EnumMap<>(GatewayCapability.class);
        for (Map.Entry<String, JsonNode> mode : node.properties()) {
            if (!mode.getValue().isString()) {
                throw unsupported("model entry for " + role.manifestKey() + " has an invalid capability policy");
            }
            modes.put(GatewayCapability.valueOf(mode.getKey()), CapabilityMode.valueOf(mode.getValue().stringValue()));
        }
        return CapabilityPolicy.fromStored(modes, GatewayCapability.negotiatedCapabilities());
    }

    private static String text(JsonNode entry, String field, ModelRole role) {
        JsonNode value = entry.get(field);
        if (value == null || !value.isString()) {
            throw unsupported("model entry for " + role.manifestKey() + " has a non-text " + field);
        }
        return value.stringValue();
    }

    private static GatewayException unsupported(String message) {
        return new GatewayException(GatewayErrorCode.MANIFEST_UNSUPPORTED, message);
    }
}
