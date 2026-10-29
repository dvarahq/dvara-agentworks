package com.dvarahq.agentworks.gateway;

import com.dvarahq.agentworks.domain.lifecycle.AgentReleaseId;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.time.Duration;
import tools.jackson.databind.node.ObjectNode;

/**
 * The model identities a release pinned, read from its immutable manifest. A run pins its release, so every invocation
 * in the run uses these identities; later alias-catalog changes affect only releases created afterwards.
 *
 * <p>Manifest versions: versions 2 through 12 carry resolved identities; versions 3 through 12 add capability,
 * execution-policy, effect, sandbox, admission-limit, external-executor, and skill pins outside this unchanged model
 * section. Version 1 recorded
 * {@code UNRESOLVED_ALIAS}; its roles are known but refuse invocation with {@link GatewayErrorCode#MODEL_ROLE_UNRESOLVED}
 * because resolving them now would substitute today's catalog for a decision the release never made. Any other version
 * is refused with {@link GatewayErrorCode#MANIFEST_UNSUPPORTED}.
 */
public final class PinnedModelRoles {

    public static final int UNRESOLVED_MANIFEST_VERSION = 1;
    public static final int RESOLVED_MANIFEST_VERSION = 2;
    /** Version 3 keeps the version 2 model shape and adds exact capability evidence. */
    public static final int CAPABILITY_PINNED_MANIFEST_VERSION = 3;
    /** Version 4 keeps the version 3 model shape and adds exact execution intent and autonomy evidence. */
    public static final int EXECUTION_POLICY_PINNED_MANIFEST_VERSION = 4;
    /** Version 5 keeps the version 4 model shape and adds authorization effect evidence to tool pins. */
    public static final int EFFECT_PINNED_MANIFEST_VERSION = 5;
    /** Version 6 keeps the model shape and adds a complete immutable sandbox configuration outside this section. */
    public static final int SANDBOX_PINNED_MANIFEST_VERSION = 6;
    /** Version 7 keeps the model shape and adds release-captured run limits outside this section. */
    public static final int ADMISSION_LIMITS_MANIFEST_VERSION = 7;
    public static final int EXTERNAL_EXECUTOR_PINNED_MANIFEST_VERSION = 8;
    /** Version 9 keeps the model shape and pins complete immutable skill evidence outside this section. */
    public static final int SKILL_PINNED_MANIFEST_VERSION = 9;
    /** Version 10 keeps the model shape and pins context compiler policy outside this section. */
    public static final int CONTEXT_POLICY_PINNED_MANIFEST_VERSION = 10;
    /** Version 11 keeps the model shape and pins memory/knowledge policy outside this section. */
    public static final int MEMORY_POLICY_PINNED_MANIFEST_VERSION = 11;
    /** Version 12 keeps the model shape and adds release evidence and ABOM outside this section. */
    public static final int RELEASE_EVIDENCE_MANIFEST_VERSION = 12;
    /** Version 13 pins model retry, bulkhead, breaker, and concrete fallback policy per role. */
    public static final int MODEL_RESILIENCE_MANIFEST_VERSION = 13;

    /** Duplicate keys are refused so that no later duplicate can silently replace a pinned value. */
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    /** The closed top-level shape shared by manifest versions 1, 2, and 3. */
    private static final Set<String> LEGACY_MANIFEST_FIELDS = Set.of("manifestVersion", "agent", "executor", "models",
            "capabilityPacks", "skills", "sandbox");
    private static final Set<String> EXECUTION_POLICY_MANIFEST_FIELDS = Set.of("manifestVersion", "agent", "executor",
            "models", "capabilityPacks", "skills", "sandbox", "execution");
    private static final Set<String> ADMISSION_LIMITS_MANIFEST_FIELDS = Set.of("manifestVersion", "agent", "executor",
            "models", "capabilityPacks", "skills", "sandbox", "execution", "limits");
    private static final Set<String> CONTEXT_POLICY_MANIFEST_FIELDS = Set.of("manifestVersion", "agent", "executor",
            "models", "capabilityPacks", "skills", "sandbox", "execution", "limits", "context");
    private static final Set<String> MEMORY_POLICY_MANIFEST_FIELDS = Set.of("manifestVersion", "agent", "executor",
            "models", "capabilityPacks", "skills", "sandbox", "execution", "limits", "context", "memory");
    private static final Set<String> RELEASE_EVIDENCE_MANIFEST_FIELDS = Set.of("manifestVersion", "agent", "executor",
            "models", "capabilityPacks", "skills", "sandbox", "execution", "limits", "context", "memory",
            "releaseEvidence", "abom");

    private static final String UNRESOLVED_ALIAS = "UNRESOLVED_ALIAS";
    private static final Set<String> V1_ENTRY_FIELDS = Set.of("gateway", "alias", "resolution");

    private final WorkspaceId workspace;
    private final AgentReleaseId release;
    private final Map<ModelRole, ResolvedModelIdentity> resolved;
    private final Set<ModelRole> unresolved;
    private final Map<ModelRole, ModelResiliencePolicy> resilience;

    private PinnedModelRoles(WorkspaceId workspace, AgentReleaseId release, Map<ModelRole, ResolvedModelIdentity> resolved,
            Set<ModelRole> unresolved, Map<ModelRole, ModelResiliencePolicy> resilience) {
        this.workspace = Objects.requireNonNull(workspace, "workspace");
        this.release = Objects.requireNonNull(release, "release");
        this.resolved = Collections.unmodifiableMap(resolved.isEmpty() ? new EnumMap<>(ModelRole.class)
                : new EnumMap<>(resolved));
        this.unresolved = Collections.unmodifiableSet(unresolved.isEmpty() ? EnumSet.noneOf(ModelRole.class)
                : EnumSet.copyOf(unresolved));
        this.resilience = Collections.unmodifiableMap(resilience.isEmpty() ? new EnumMap<>(ModelRole.class)
                : new EnumMap<>(resilience));
    }

    /** Pins from already-resolved identities, for example those just written into a new release. */
    public static PinnedModelRoles of(WorkspaceId workspace, AgentReleaseId release,
            Map<ModelRole, ResolvedModelIdentity> identities) {
        identities.forEach((role, identity) -> {
            if (identity.role() != role) {
                throw new IllegalArgumentException("identity for " + role + " names role " + identity.role());
            }
        });
        return new PinnedModelRoles(workspace, release, identities, Set.of(), Map.of());
    }

    /**
     * Reads the model section of a stored release manifest. The caller supplies the workspace and release identity it
     * read the manifest under; the manifest bytes themselves carry no tenant authority.
     */
    public static PinnedModelRoles fromReleaseManifest(WorkspaceId workspace, AgentReleaseId release, String manifestJson) {
        Objects.requireNonNull(manifestJson, "manifestJson");
        JsonNode manifest;
        try {
            manifest = JSON.readTree(manifestJson);
        } catch (JacksonException malformed) {
            throw new GatewayException(GatewayErrorCode.MANIFEST_UNSUPPORTED, "release manifest is not valid JSON");
        }
        if (manifest == null || !manifest.isObject()) {
            throw new GatewayException(GatewayErrorCode.MANIFEST_UNSUPPORTED, "release manifest is not an object");
        }
        JsonNode version = manifest.get("manifestVersion");
        if (version == null || !version.isInt()) {
            throw new GatewayException(GatewayErrorCode.MANIFEST_UNSUPPORTED, "release manifest has no integer version");
        }
        int manifestVersion = version.intValue();
        if (manifestVersion != UNRESOLVED_MANIFEST_VERSION && manifestVersion != RESOLVED_MANIFEST_VERSION
                && manifestVersion != CAPABILITY_PINNED_MANIFEST_VERSION
                && manifestVersion != EXECUTION_POLICY_PINNED_MANIFEST_VERSION
                && manifestVersion != EFFECT_PINNED_MANIFEST_VERSION
                && manifestVersion != SANDBOX_PINNED_MANIFEST_VERSION
                && manifestVersion != ADMISSION_LIMITS_MANIFEST_VERSION
                && manifestVersion != EXTERNAL_EXECUTOR_PINNED_MANIFEST_VERSION
                && manifestVersion != SKILL_PINNED_MANIFEST_VERSION
                && manifestVersion != CONTEXT_POLICY_PINNED_MANIFEST_VERSION
                && manifestVersion != MEMORY_POLICY_PINNED_MANIFEST_VERSION
                && manifestVersion != RELEASE_EVIDENCE_MANIFEST_VERSION
                && manifestVersion != MODEL_RESILIENCE_MANIFEST_VERSION) {
            throw new GatewayException(GatewayErrorCode.MANIFEST_UNSUPPORTED,
                    "release manifest version " + manifestVersion + " is not implemented by this reader");
        }
        Set<String> manifestFields = manifestVersion >= RELEASE_EVIDENCE_MANIFEST_VERSION
                ? RELEASE_EVIDENCE_MANIFEST_FIELDS
                : manifestVersion >= MEMORY_POLICY_PINNED_MANIFEST_VERSION ? MEMORY_POLICY_MANIFEST_FIELDS
                : manifestVersion >= CONTEXT_POLICY_PINNED_MANIFEST_VERSION
                ? CONTEXT_POLICY_MANIFEST_FIELDS
                : manifestVersion >= ADMISSION_LIMITS_MANIFEST_VERSION
                ? ADMISSION_LIMITS_MANIFEST_FIELDS
                : manifestVersion >= EXECUTION_POLICY_PINNED_MANIFEST_VERSION
                        ? EXECUTION_POLICY_MANIFEST_FIELDS : LEGACY_MANIFEST_FIELDS;
        for (String field : manifest.propertyNames()) {
            if (!manifestFields.contains(field)) {
                throw new GatewayException(GatewayErrorCode.MANIFEST_UNSUPPORTED, "release manifest has an unknown field");
            }
        }
        JsonNode models = manifest.get("models");
        if (models == null || !models.isObject()) {
            throw new GatewayException(GatewayErrorCode.MANIFEST_UNSUPPORTED, "release manifest has no models object");
        }
        Map<ModelRole, ResolvedModelIdentity> resolved = new EnumMap<>(ModelRole.class);
        Set<ModelRole> unresolved = EnumSet.noneOf(ModelRole.class);
        Map<ModelRole, ModelResiliencePolicy> resilience = new EnumMap<>(ModelRole.class);
        for (Map.Entry<String, JsonNode> entry : models.properties()) {
            ModelRole role = ModelRole.fromManifestKey(entry.getKey());
            if (manifestVersion == UNRESOLVED_MANIFEST_VERSION) {
                requireUnresolvedEntry(role, entry.getValue());
                unresolved.add(role);
            } else {
                JsonNode identityEntry = entry.getValue();
                if (manifestVersion >= MODEL_RESILIENCE_MANIFEST_VERSION) {
                    if (!(identityEntry instanceof ObjectNode object) || !identityEntry.has("resilience")) {
                        throw new GatewayException(GatewayErrorCode.MANIFEST_UNSUPPORTED,
                                "model entry for " + role.manifestKey() + " lacks resilience");
                    }
                    ObjectNode identityOnly = object.deepCopy();
                    JsonNode policy = identityOnly.remove("resilience");
                    ResolvedModelIdentity primary = ResolvedModelIdentity.fromManifestEntry(role, identityOnly);
                    ModelResiliencePolicy captured = resilience(role, policy);
                    validateFallbacks(primary, captured);
                    resolved.put(role, primary);
                    resilience.put(role, captured);
                } else {
                    resolved.put(role, ResolvedModelIdentity.fromManifestEntry(role, identityEntry));
                }
            }
        }
        return new PinnedModelRoles(workspace, release, resolved, unresolved, resilience);
    }

    private static ModelResiliencePolicy resilience(ModelRole role, JsonNode node) {
        Set<String> fields = Set.of("maxAttempts", "initialBackoffMillis", "maxBackoffMillis", "jitter",
                "maxConcurrent", "bulkheadWaitMillis", "breakerFailureThreshold", "breakerOpenMillis",
                "requestTimeoutMillis", "streamIdleTimeoutMillis", "fallbacks");
        if (node == null || !node.isObject() || !Set.copyOf(node.propertyNames()).equals(fields)) {
            throw new GatewayException(GatewayErrorCode.MANIFEST_UNSUPPORTED,
                    "model resilience for " + role.manifestKey() + " is not the closed version 13 shape");
        }
        try {
            List<ResolvedModelIdentity> fallbacks = new java.util.ArrayList<>();
            JsonNode fallbackNodes = node.get("fallbacks");
            if (!fallbackNodes.isArray()) throw new IllegalArgumentException();
            for (JsonNode fallback : fallbackNodes.values()) {
                fallbacks.add(ResolvedModelIdentity.fromManifestEntry(role, fallback));
            }
            return new ModelResiliencePolicy(integer(node, "maxAttempts"),
                    Duration.ofMillis(longValue(node, "initialBackoffMillis")),
                    Duration.ofMillis(longValue(node, "maxBackoffMillis")), number(node, "jitter"),
                    integer(node, "maxConcurrent"), Duration.ofMillis(longValue(node, "bulkheadWaitMillis")),
                    integer(node, "breakerFailureThreshold"),
                    Duration.ofMillis(longValue(node, "breakerOpenMillis")),
                    Duration.ofMillis(longValue(node, "requestTimeoutMillis")),
                    Duration.ofMillis(longValue(node, "streamIdleTimeoutMillis")), fallbacks);
        } catch (RuntimeException invalid) {
            if (invalid instanceof GatewayException gateway) throw gateway;
            throw new GatewayException(GatewayErrorCode.MANIFEST_UNSUPPORTED,
                    "model resilience for " + role.manifestKey() + " has an invalid value");
        }
    }

    private static int integer(JsonNode node, String field) {
        if (!node.path(field).isInt()) throw new IllegalArgumentException();
        return node.path(field).intValue();
    }

    private static void validateFallbacks(ResolvedModelIdentity primary, ModelResiliencePolicy policy) {
        Set<String> routes = new java.util.HashSet<>();
        routes.add(primary.gatewayId() + "\u0000" + primary.providerModel());
        for (ResolvedModelIdentity fallback : policy.fallbacks()) {
            String route = fallback.gatewayId() + "\u0000" + fallback.providerModel();
            if (!routes.add(route)) throw new GatewayException(GatewayErrorCode.MANIFEST_UNSUPPORTED,
                    "model resilience fallback routes are not unique");
            for (GatewayCapability capability : GatewayCapability.negotiatedCapabilities()) {
                if (primary.capabilityPolicy().modeFor(capability) == CapabilityMode.REQUIRED
                        && !fallback.advertisedCapabilities().contains(capability)) {
                    throw new GatewayException(GatewayErrorCode.MANIFEST_UNSUPPORTED,
                            "model resilience fallback lacks a required capability");
                }
            }
        }
    }

    private static long longValue(JsonNode node, String field) {
        if (!node.path(field).isIntegralNumber()) throw new IllegalArgumentException();
        return node.path(field).longValue();
    }

    private static double number(JsonNode node, String field) {
        if (!node.path(field).isNumber()) throw new IllegalArgumentException();
        return node.path(field).doubleValue();
    }

    /** The closed version 1 entry: exactly {@code gateway}, {@code alias}, and {@code resolution=UNRESOLVED_ALIAS}. */
    private static void requireUnresolvedEntry(ModelRole role, JsonNode entry) {
        if (entry == null || !entry.isObject() || !Set.copyOf(entry.propertyNames()).equals(V1_ENTRY_FIELDS)) {
            throw new GatewayException(GatewayErrorCode.MANIFEST_UNSUPPORTED,
                    "version 1 model entry for " + role.manifestKey() + " is not the closed version 1 shape");
        }
        for (String field : V1_ENTRY_FIELDS) {
            JsonNode value = entry.get(field);
            if (!value.isString() || value.stringValue().isBlank()) {
                throw new GatewayException(GatewayErrorCode.MANIFEST_UNSUPPORTED,
                        "version 1 model entry for " + role.manifestKey() + " has a non-text " + field);
            }
        }
        if (!UNRESOLVED_ALIAS.equals(entry.get("resolution").stringValue())) {
            throw new GatewayException(GatewayErrorCode.MANIFEST_UNSUPPORTED,
                    "version 1 model entry for " + role.manifestKey() + " is not UNRESOLVED_ALIAS");
        }
    }

    public WorkspaceId workspace() {
        return workspace;
    }

    public AgentReleaseId release() {
        return release;
    }

    public Map<ModelRole, ResolvedModelIdentity> identities() {
        return resolved;
    }

    /** The pinned model for {@code role}, refusing roles the release did not declare or did not resolve. */
    public PinnedModel role(ModelRole role) {
        Objects.requireNonNull(role, "role");
        ResolvedModelIdentity identity = resolved.get(role);
        if (identity != null) {
            return new PinnedModel(workspace, release, identity,
                    resilience.getOrDefault(role, ModelResiliencePolicy.DEFAULT));
        }
        if (unresolved.contains(role)) {
            throw new GatewayException(GatewayErrorCode.MODEL_ROLE_UNRESOLVED, "release " + release.value()
                    + " predates model resolution; create a new release to pin role " + role.manifestKey());
        }
        throw new GatewayException(GatewayErrorCode.MODEL_ROLE_NOT_DECLARED,
                "release " + release.value() + " declares no model for role " + role.manifestKey());
    }
}
