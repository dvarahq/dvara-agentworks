package com.dvarahq.agentworks.gateway;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Per-capability fail-or-degrade policy. A release captures the policy of each role's gateway, and invocations
 * negotiate against the captured policy, so an operator change never alters the behavior of an existing release.
 *
 * <p>Defaults: tool calls and structured output are {@link CapabilityMode#REQUIRED} because degrading them changes
 * what the model can do; streaming, usage, cancellation, and model metadata {@link CapabilityMode#DEGRADE}.
 */
public record CapabilityPolicy(Map<GatewayCapability, CapabilityMode> modes) {

    public static final CapabilityPolicy DEFAULT = new CapabilityPolicy(Map.of(
            GatewayCapability.STREAMING, CapabilityMode.DEGRADE,
            GatewayCapability.TOOL_CALLS, CapabilityMode.REQUIRED,
            GatewayCapability.STRUCTURED_OUTPUT, CapabilityMode.REQUIRED,
            GatewayCapability.USAGE, CapabilityMode.DEGRADE,
            GatewayCapability.CANCELLATION, CapabilityMode.DEGRADE,
            GatewayCapability.MODEL_METADATA, CapabilityMode.DEGRADE));

    /**
     * The capabilities every version 2 manifest states a mode for. This set is frozen: capabilities negotiated later
     * are absent from existing manifests and are filled in by {@link #fromStored}.
     */
    public static final Set<GatewayCapability> V2_CAPABILITIES = Collections.unmodifiableSet(EnumSet.of(
            GatewayCapability.STREAMING, GatewayCapability.TOOL_CALLS, GatewayCapability.STRUCTURED_OUTPUT,
            GatewayCapability.USAGE, GatewayCapability.CANCELLATION, GatewayCapability.MODEL_METADATA));

    public CapabilityPolicy {
        Objects.requireNonNull(modes, "modes");
        EnumMap<GatewayCapability, CapabilityMode> complete = new EnumMap<>(GatewayCapability.class);
        modes.forEach((capability, mode) -> complete.put(Objects.requireNonNull(capability, "capability"),
                Objects.requireNonNull(mode, "mode")));
        for (GatewayCapability capability : GatewayCapability.negotiatedCapabilities()) {
            if (!complete.containsKey(capability)) {
                throw new IllegalArgumentException("capability policy must state a mode for " + capability);
            }
        }
        modes = Collections.unmodifiableMap(complete);
    }

    /** The default policy with the given overrides applied; only negotiated capabilities can be overridden. */
    public static CapabilityPolicy withOverrides(Map<GatewayCapability, CapabilityMode> overrides) {
        Objects.requireNonNull(overrides, "overrides");
        for (GatewayCapability capability : overrides.keySet()) {
            if (!capability.negotiated()) {
                throw new IllegalArgumentException(capability + " is not a negotiated capability");
            }
        }
        EnumMap<GatewayCapability, CapabilityMode> merged = new EnumMap<>(DEFAULT.modes);
        merged.putAll(overrides);
        return new CapabilityPolicy(merged);
    }

    /**
     * Reads a policy captured by a stored release. It must state every {@link #V2_CAPABILITIES} mode. A capability
     * negotiated today but absent from the stored policy was added after the release was captured; the release never
     * decided how to treat it, so it is {@link CapabilityMode#REQUIRED}: an invocation that needs it fails before
     * provider contact rather than degrading on a decision nobody made.
     *
     * @param negotiatedNow the currently negotiated capabilities, normally {@link GatewayCapability#negotiatedCapabilities()}
     * @throws IllegalArgumentException when a version 2 capability is missing
     */
    public static CapabilityPolicy fromStored(Map<GatewayCapability, CapabilityMode> stored,
            Set<GatewayCapability> negotiatedNow) {
        Objects.requireNonNull(stored, "stored");
        for (GatewayCapability capability : V2_CAPABILITIES) {
            if (!stored.containsKey(capability)) {
                throw new IllegalArgumentException("stored capability policy lacks " + capability);
            }
        }
        EnumMap<GatewayCapability, CapabilityMode> filled = new EnumMap<>(GatewayCapability.class);
        filled.putAll(stored);
        for (GatewayCapability capability : negotiatedNow) {
            filled.putIfAbsent(capability, CapabilityMode.REQUIRED);
        }
        return new CapabilityPolicy(filled);
    }

    public CapabilityMode modeFor(GatewayCapability capability) {
        CapabilityMode mode = modes.get(capability);
        if (mode == null) {
            throw new IllegalArgumentException(capability + " is not a negotiated capability");
        }
        return mode;
    }

    /** Canonical manifest form: capability name to mode name, sorted by key. */
    public Map<String, String> toManifest() {
        Map<String, String> manifest = new TreeMap<>();
        modes.forEach((capability, mode) -> manifest.put(capability.name(), mode.name()));
        return manifest;
    }
}
