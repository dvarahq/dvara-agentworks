package com.dvarahq.agentworks.runplane.execution;

import com.dvarahq.agentworks.capability.PinnedCapabilityPack;
import com.dvarahq.agentworks.capability.PinnedSkill;
import com.dvarahq.agentworks.capability.SkillLoadingPolicy;
import com.dvarahq.agentworks.capability.SkillSourceKind;
import com.dvarahq.agentworks.capability.ToolId;
import com.dvarahq.agentworks.capability.ToolEffect;
import com.dvarahq.agentworks.context.PinnedContextPolicy;
import com.dvarahq.agentworks.memory.PinnedMemoryPolicy;
import com.dvarahq.agentworks.domain.external.ExternalExecutorDescriptor;
import com.dvarahq.agentworks.domain.lifecycle.AgentReleaseId;
import com.dvarahq.agentworks.domain.security.AutonomyProfile;
import com.dvarahq.agentworks.domain.security.ExecutionIntent;
import com.dvarahq.agentworks.domain.sandbox.SandboxConfiguration;
import com.dvarahq.agentworks.domain.security.ExecutionPolicy;
import com.dvarahq.agentworks.domain.security.PermissionRisk;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import com.dvarahq.agentworks.gateway.GatewayException;
import com.dvarahq.agentworks.gateway.PinnedModelRoles;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * The first reader that admits work from a release manifest (docs/implementation/LIFECYCLE-CONTRACTS.md). It
 * dispatches on {@code manifestVersion}, refuses a version it does not implement with
 * {@code UNSUPPORTED_MANIFEST_VERSION}, and refuses unknown or missing fields of a version it implements with
 * {@code INVALID_MANIFEST}: a manifest version is a closed shape.
 *
 * <p>Model roles are read only by the gateway's strict reader, {@link PinnedModelRoles} (#33): this reader never
 * parses a model entry itself, so it accepts exactly the version 1 through 12 model sections the gateway accepts and
 * refuses the rest. Version 3 replaces capability-pack names with exact pack, tool-version, and schema-hash pins;
 * version 4 adds immutable execution-intent and autonomy-profile evidence; version 5 adds immutable tool-effect
 * evidence; version 6 adds the complete immutable sandbox request; version 7 adds admission limits; version 8 pins
 * the complete external-executor identity and protocol contract; version 9 pins verified skill evidence; version 10
 * pins context compiler policy; version 11 pins memory and knowledge policy; version 12 pins release evidence and
 * the ABOM outside the execution sections. It
 * validates the remaining closed sections (agent, executor, capability packs, skills, and sandbox), which the gateway
 * does not read. Whether a version 1 role may be invoked is the gateway's decision
 * ({@code MODEL_ROLE_UNRESOLVED}); admitting the run is not. Support for a version is kept for as long as any
 * retained release carries it; a new version adds a reader and never rewrites a recorded manifest.
 */
public final class ReleaseManifestReader {
    /** Manifest versions this node can read; workers only claim runs admitted under one of them. */
    public static final Set<Integer> SUPPORTED_VERSIONS = Set.of(PinnedModelRoles.UNRESOLVED_MANIFEST_VERSION,
            PinnedModelRoles.RESOLVED_MANIFEST_VERSION, PinnedModelRoles.CAPABILITY_PINNED_MANIFEST_VERSION,
            PinnedModelRoles.EXECUTION_POLICY_PINNED_MANIFEST_VERSION,
            PinnedModelRoles.EFFECT_PINNED_MANIFEST_VERSION, PinnedModelRoles.SANDBOX_PINNED_MANIFEST_VERSION,
            PinnedModelRoles.ADMISSION_LIMITS_MANIFEST_VERSION,
            PinnedModelRoles.EXTERNAL_EXECUTOR_PINNED_MANIFEST_VERSION,
            PinnedModelRoles.SKILL_PINNED_MANIFEST_VERSION,
            PinnedModelRoles.CONTEXT_POLICY_PINNED_MANIFEST_VERSION,
            PinnedModelRoles.MEMORY_POLICY_PINNED_MANIFEST_VERSION,
            PinnedModelRoles.RELEASE_EVIDENCE_MANIFEST_VERSION,
            PinnedModelRoles.MODEL_RESILIENCE_MANIFEST_VERSION);

    private static final Set<String> LEGACY_FIELDS = Set.of("manifestVersion", "agent", "executor", "models",
            "capabilityPacks", "skills", "sandbox");
    private static final Set<String> EXECUTION_POLICY_FIELDS = Set.of("manifestVersion", "agent", "executor", "models",
            "capabilityPacks", "skills", "sandbox", "execution");
    private static final Set<String> ADMISSION_LIMITS_FIELDS = Set.of("manifestVersion", "agent", "executor", "models",
            "capabilityPacks", "skills", "sandbox", "execution", "limits");
    private static final Set<String> CONTEXT_POLICY_FIELDS = Set.of("manifestVersion", "agent", "executor", "models",
            "capabilityPacks", "skills", "sandbox", "execution", "limits", "context");
    private static final Set<String> MEMORY_POLICY_FIELDS = Set.of("manifestVersion", "agent", "executor", "models",
            "capabilityPacks", "skills", "sandbox", "execution", "limits", "context", "memory");
    private static final Set<String> RELEASE_EVIDENCE_FIELDS = Set.of("manifestVersion", "agent", "executor", "models",
            "capabilityPacks", "skills", "sandbox", "execution", "limits", "context", "memory",
            "releaseEvidence", "abom");
    private static final Set<String> AGENT = Set.of("definitionId", "definitionName", "versionId", "version",
            "specApiVersion", "specDigest");
    private static final Set<String> EXECUTOR = Set.of("type", "requiredCapabilities");
    private static final Set<String> EXTERNAL_EXECUTOR = Set.of("type", "requiredCapabilities", "external");
    private static final Set<String> PACK = Set.of("id", "version", "tools");
    private static final Set<String> LEGACY_TOOL = Set.of("namespace", "name", "version", "schemaHash");
    private static final Set<String> EFFECT_TOOL = Set.of("namespace", "name", "version", "schemaHash", "effect");
    private static final Set<String> EFFECT = Set.of("resource", "action", "risk");
    private static final Set<String> EXECUTION = Set.of("initialIntent", "autonomyProfile");
    private static final Set<String> PINNED_SANDBOX = Set.of("provider", "provisioning", "image", "cpu", "memory",
            "pidLimit", "networkPolicy", "maxRuntime");
    private static final Set<String> LIMITS = Set.of("maxSteps", "maxToolCalls", "maxRuntime", "maxCostUsd");
    private static final Set<String> CONTEXT = Set.of("maximumTokens", "loadingPolicy", "cacheAware", "compaction",
            "policyHash");
    private static final Set<String> COMPACTION = Set.of("enabled", "strategy", "threshold");
    private static final Set<String> MEMORY = Set.of("conversationEnabled", "conversationRetention",
            "conversationItems", "workingEnabled", "workingRetention", "workingItems", "semanticEnabled",
            "knowledgeItems", "knowledgeTokens", "knowledgeTimeout", "policyHash");
    private static final Set<String> PINNED_SKILL = Set.of("id", "sourceKind", "sourceUri", "authoredRef",
            "resolvedRef", "contentDigest", "signatureDigest", "trustPolicy", "manifestVersion", "loadingPolicy",
            "contextTriggers", "license", "provenance");
    private static final Set<String> UNSIGNED_PINNED_SKILL = Set.of("id", "sourceKind", "sourceUri", "authoredRef",
            "resolvedRef", "contentDigest", "trustPolicy", "manifestVersion", "loadingPolicy", "contextTriggers",
            "license", "provenance");

    /** The execution identities a run loads from its pinned release; model roles are the gateway's pins. */
    public record RunManifest(int manifestVersion, UUID definitionId, UUID versionId, String version,
            String specDigest, String executorType, List<String> requiredCapabilities, PinnedModelRoles models,
            List<String> capabilityPacks, List<PinnedCapabilityPack> pinnedCapabilities, List<String> skills,
            List<PinnedSkill> pinnedSkills,
            String sandboxProvider, Optional<ExecutionPolicy> executionPolicy,
            Optional<SandboxConfiguration> sandboxConfiguration, Optional<ExternalExecutorDescriptor> externalExecutor,
            Optional<PinnedContextPolicy> contextPolicy, Optional<PinnedMemoryPolicy> memoryPolicy) {
        public RunManifest(int manifestVersion, UUID definitionId, UUID versionId, String version,
                String specDigest, String executorType, List<String> requiredCapabilities, PinnedModelRoles models,
                List<String> capabilityPacks, List<PinnedCapabilityPack> pinnedCapabilities, List<String> skills,
                String sandboxProvider, Optional<ExecutionPolicy> executionPolicy,
                Optional<SandboxConfiguration> sandboxConfiguration,
                Optional<ExternalExecutorDescriptor> externalExecutor) {
            this(manifestVersion, definitionId, versionId, version, specDigest, executorType, requiredCapabilities,
                    models, capabilityPacks, pinnedCapabilities, skills, List.of(), sandboxProvider, executionPolicy,
                    sandboxConfiguration, externalExecutor, Optional.empty(), Optional.empty());
        }
    }

    private final JsonMapper mapper = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

    /** Reads the manifest of {@code release}, read under {@code workspace}. */
    public RunManifest read(WorkspaceId workspace, UUID release, String manifestJson) {
        Objects.requireNonNull(workspace, "workspace");
        Objects.requireNonNull(release, "release");
        JsonNode manifest;
        try {
            manifest = mapper.readTree(Objects.requireNonNull(manifestJson, "manifestJson"));
        } catch (JacksonException invalid) {
            throw invalid("the manifest is not valid JSON");
        }
        if (manifest == null || !manifest.isObject() || !manifest.path("manifestVersion").isInt()) {
            throw invalid("the manifest has no integer manifestVersion");
        }
        int version = manifest.get("manifestVersion").intValue();
        if (!SUPPORTED_VERSIONS.contains(version)) {
            throw new RunEngineException("UNSUPPORTED_MANIFEST_VERSION",
                    "manifest version " + version + " is not supported by this node " + new TreeSet<>(SUPPORTED_VERSIONS));
        }
        closed(manifest, version >= PinnedModelRoles.RELEASE_EVIDENCE_MANIFEST_VERSION
                ? RELEASE_EVIDENCE_FIELDS
                : version >= PinnedModelRoles.MEMORY_POLICY_PINNED_MANIFEST_VERSION ? MEMORY_POLICY_FIELDS
                : version >= PinnedModelRoles.CONTEXT_POLICY_PINNED_MANIFEST_VERSION
                ? CONTEXT_POLICY_FIELDS
                : version >= PinnedModelRoles.ADMISSION_LIMITS_MANIFEST_VERSION
                ? ADMISSION_LIMITS_FIELDS
                : version >= PinnedModelRoles.EXECUTION_POLICY_PINNED_MANIFEST_VERSION
                        ? EXECUTION_POLICY_FIELDS : LEGACY_FIELDS, "manifest");
        PinnedModelRoles models;
        try {
            models = PinnedModelRoles.fromReleaseManifest(workspace, new AgentReleaseId(release), manifestJson);
        } catch (GatewayException refused) {
            throw new RunEngineException("INVALID_MANIFEST", "the model roles are not readable: " + refused.detail(),
                    refused);
        }
        JsonNode agent = closed(manifest.get("agent"), AGENT, "agent");
        JsonNode executorNode = manifest.get("executor");
        Set<String> executorFields = version >= PinnedModelRoles.EXTERNAL_EXECUTOR_PINNED_MANIFEST_VERSION
                && executorNode != null && executorNode.has("external") ? EXTERNAL_EXECUTOR : EXECUTOR;
        JsonNode executor = closed(executorNode, executorFields, "executor");
        JsonNode sandbox = manifest.get("sandbox");
        Optional<SandboxConfiguration> sandboxConfiguration = version >= PinnedModelRoles.SANDBOX_PINNED_MANIFEST_VERSION
                ? sandboxConfiguration(sandbox) : Optional.empty();
        if (version < PinnedModelRoles.SANDBOX_PINNED_MANIFEST_VERSION
                && (!sandbox.isObject() || !Set.of("provider").containsAll(Set.copyOf(sandbox.propertyNames())))) {
            throw invalid("sandbox has only an optional provider");
        }
        List<PinnedCapabilityPack> pinned = version >= PinnedModelRoles.CAPABILITY_PINNED_MANIFEST_VERSION
                ? pinnedCapabilities(manifest.get("capabilityPacks"),
                        version >= PinnedModelRoles.EFFECT_PINNED_MANIFEST_VERSION) : List.of();
        List<String> capabilityPacks = pinned.isEmpty() && version < PinnedModelRoles.CAPABILITY_PINNED_MANIFEST_VERSION
                ? strings(manifest.get("capabilityPacks"), "capabilityPacks")
                : pinned.stream().map(PinnedCapabilityPack::identity).toList();
        Optional<ExecutionPolicy> executionPolicy = version >= PinnedModelRoles.EXECUTION_POLICY_PINNED_MANIFEST_VERSION
                ? Optional.of(executionPolicy(manifest.get("execution"))) : Optional.empty();
        if (version >= PinnedModelRoles.ADMISSION_LIMITS_MANIFEST_VERSION) limits(manifest.get("limits"));
        Optional<ExternalExecutorDescriptor> external = version >= PinnedModelRoles.EXTERNAL_EXECUTOR_PINNED_MANIFEST_VERSION
                && executor.has("external") ? Optional.of(externalExecutor(executor.get("external"))) : Optional.empty();
        List<PinnedSkill> pinnedSkills = version >= PinnedModelRoles.SKILL_PINNED_MANIFEST_VERSION
                ? pinnedSkills(manifest.get("skills")) : List.of();
        List<String> skillIds = pinnedSkills.isEmpty() && version < PinnedModelRoles.SKILL_PINNED_MANIFEST_VERSION
                ? strings(manifest.get("skills"), "skills") : pinnedSkills.stream().map(PinnedSkill::id).toList();
        Optional<PinnedContextPolicy> contextPolicy = version >= PinnedModelRoles.CONTEXT_POLICY_PINNED_MANIFEST_VERSION
                ? Optional.of(contextPolicy(manifest.get("context"))) : Optional.empty();
        Optional<PinnedMemoryPolicy> memoryPolicy = version >= PinnedModelRoles.MEMORY_POLICY_PINNED_MANIFEST_VERSION
                ? Optional.of(memoryPolicy(manifest.get("memory"))) : Optional.empty();
        return new RunManifest(version, uuid(agent, "definitionId"), uuid(agent, "versionId"), text(agent, "version"),
                text(agent, "specDigest"), text(executor, "type"), strings(executor.get("requiredCapabilities"),
                "executor.requiredCapabilities"), models, capabilityPacks, pinned,
                skillIds, pinnedSkills, sandbox.has("provider") ? text(sandbox, "provider") : null,
                executionPolicy, sandboxConfiguration, external, contextPolicy, memoryPolicy);
    }

    private static PinnedMemoryPolicy memoryPolicy(JsonNode node) {
        JsonNode memory = closed(node, MEMORY, "memory");
        for (String field : List.of("conversationEnabled", "workingEnabled", "semanticEnabled")) {
            if (!memory.path(field).isBoolean()) throw invalid("memory policy has invalid boolean fields");
        }
        for (String field : List.of("conversationItems", "workingItems", "knowledgeItems", "knowledgeTokens")) {
            if (!memory.path(field).isInt()) throw invalid("memory policy has invalid integer fields");
        }
        try {
            return new PinnedMemoryPolicy(memory.get("conversationEnabled").booleanValue(),
                    java.time.Duration.parse(text(memory, "conversationRetention")),
                    memory.get("conversationItems").intValue(), memory.get("workingEnabled").booleanValue(),
                    java.time.Duration.parse(text(memory, "workingRetention")), memory.get("workingItems").intValue(),
                    memory.get("semanticEnabled").booleanValue(), memory.get("knowledgeItems").intValue(),
                    memory.get("knowledgeTokens").intValue(), java.time.Duration.parse(text(memory, "knowledgeTimeout")),
                    text(memory, "policyHash"));
        } catch (IllegalArgumentException malformed) {
            throw invalid("memory policy is invalid");
        }
    }

    private static PinnedContextPolicy contextPolicy(JsonNode node) {
        JsonNode context = closed(node, CONTEXT, "context");
        JsonNode compaction = closed(context.get("compaction"), COMPACTION, "context.compaction");
        if (!context.path("maximumTokens").isInt() || !context.path("cacheAware").isBoolean()
                || !compaction.path("enabled").isBoolean() || !compaction.path("threshold").isNumber()) {
            throw invalid("context policy has invalid field types");
        }
        try {
            return new PinnedContextPolicy(context.get("maximumTokens").intValue(), text(context, "loadingPolicy"),
                    context.get("cacheAware").booleanValue(), compaction.get("enabled").booleanValue(),
                    text(compaction, "strategy"), compaction.get("threshold").decimalValue(),
                    text(context, "policyHash"));
        } catch (IllegalArgumentException malformed) {
            throw invalid("context policy is invalid");
        }
    }

    private static List<PinnedSkill> pinnedSkills(JsonNode node) {
        if (node == null || !node.isArray()) throw invalid("skills is an array");
        List<PinnedSkill> result = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        try {
            for (JsonNode value : node.values()) {
                JsonNode skill = closed(value, value.has("signatureDigest") ? PINNED_SKILL : UNSIGNED_PINNED_SKILL,
                        "pinned skill");
                if (!ids.add(text(skill, "id"))) throw invalid("duplicate pinned skill");
                JsonNode triggers = skill.get("contextTriggers");
                Set<String> contextTriggers = new TreeSet<>(strings(triggers, "skill.contextTriggers"));
                result.add(new PinnedSkill(text(skill, "id"), SkillSourceKind.valueOf(text(skill, "sourceKind")),
                        text(skill, "sourceUri"), text(skill, "authoredRef"), text(skill, "resolvedRef"),
                        text(skill, "contentDigest"), skill.has("signatureDigest") ? text(skill, "signatureDigest") : null,
                        text(skill, "trustPolicy"), text(skill, "manifestVersion"),
                        SkillLoadingPolicy.valueOf(text(skill, "loadingPolicy")), contextTriggers,
                        text(skill, "license"), text(skill, "provenance")));
            }
        } catch (IllegalArgumentException malformed) {
            throw invalid("skills contains invalid pinned evidence");
        }
        return List.copyOf(result);
    }

    private ExternalExecutorDescriptor externalExecutor(JsonNode node) {
        try {
            return mapper.treeToValue(node, ExternalExecutorDescriptor.class);
        } catch (JacksonException | IllegalArgumentException malformed) {
            throw invalid("executor.external is not a valid pinned external executor descriptor");
        }
    }

    private static void limits(JsonNode node) {
        if (node == null || !node.isObject() || !LIMITS.containsAll(Set.copyOf(node.propertyNames()))) {
            throw invalid("limits is an object with only known fields");
        }
        for (String field : List.of("maxSteps", "maxToolCalls")) {
            if (node.has(field) && (!node.get(field).isIntegralNumber() || node.get(field).longValue() < 0)) {
                throw invalid("limits." + field + " is a non-negative integer");
            }
        }
        if (node.has("maxCostUsd") && (!node.get("maxCostUsd").isNumber()
                || node.get("maxCostUsd").decimalValue().signum() < 0)) {
            throw invalid("limits.maxCostUsd is non-negative");
        }
        if (node.has("maxRuntime")) {
            if (!node.get("maxRuntime").isString()) throw invalid("limits.maxRuntime is a duration");
            ManifestRunLimitsPolicy.duration(node.get("maxRuntime").stringValue());
        }
    }

    private static Optional<SandboxConfiguration> sandboxConfiguration(JsonNode node) {
        if (node == null || !node.isObject()) throw invalid("sandbox is an object");
        if (node.isEmpty()) return Optional.empty();
        closed(node, PINNED_SANDBOX, "sandbox");
        if (!node.path("cpu").isNumber() || !node.path("pidLimit").isInt()) {
            throw invalid("sandbox cpu and pidLimit have numeric types");
        }
        try {
            return Optional.of(new SandboxConfiguration(text(node, "provider"), text(node, "provisioning"),
                    node.has("image") ? Optional.of(text(node, "image")) : Optional.empty(),
                    node.get("cpu").decimalValue(), text(node, "memory"), node.get("pidLimit").intValue(),
                    text(node, "networkPolicy"), text(node, "maxRuntime")));
        } catch (IllegalArgumentException malformed) {
            throw invalid("sandbox configuration is invalid");
        }
    }

    private static ExecutionPolicy executionPolicy(JsonNode node) {
        JsonNode execution = closed(node, EXECUTION, "execution");
        try {
            return new ExecutionPolicy(ExecutionIntent.valueOf(text(execution, "initialIntent")),
                    AutonomyProfile.valueOf(text(execution, "autonomyProfile")));
        } catch (IllegalArgumentException malformed) {
            throw invalid("execution contains an unknown intent or autonomy profile");
        }
    }

    private static List<PinnedCapabilityPack> pinnedCapabilities(JsonNode node, boolean effectsRequired) {
        if (node == null || !node.isArray()) throw invalid("capabilityPacks is an array");
        List<PinnedCapabilityPack> packs = new ArrayList<>();
        Set<String> identities = new HashSet<>();
        try {
            for (JsonNode value : node.values()) {
                JsonNode pack = closed(value, PACK, "capability pack");
                List<PinnedCapabilityPack.PinnedTool> tools = new ArrayList<>();
                JsonNode toolValues = pack.get("tools");
                if (toolValues == null || !toolValues.isArray()) throw invalid("capability pack tools is an array");
                for (JsonNode item : toolValues.values()) {
                    JsonNode tool = closed(item, effectsRequired ? EFFECT_TOOL : LEGACY_TOOL, "pinned tool");
                    ToolId id = new ToolId(text(tool, "namespace"), text(tool, "name"), text(tool, "version"));
                    if (effectsRequired) {
                        JsonNode effect = closed(tool.get("effect"), EFFECT, "tool effect");
                        tools.add(new PinnedCapabilityPack.PinnedTool(id, text(tool, "schemaHash"),
                                new ToolEffect(text(effect, "resource"), text(effect, "action"),
                                        PermissionRisk.valueOf(text(effect, "risk")))));
                    } else {
                        tools.add(new PinnedCapabilityPack.PinnedTool(id, text(tool, "schemaHash")));
                    }
                }
                PinnedCapabilityPack pinned = new PinnedCapabilityPack(text(pack, "id"), text(pack, "version"), tools);
                if (!identities.add(pinned.identity())) throw invalid("duplicate capability pack");
                packs.add(pinned);
            }
        } catch (IllegalArgumentException malformed) {
            throw invalid("capabilityPacks contains invalid identity, schema hash, or effect evidence");
        }
        return List.copyOf(packs);
    }

    private static JsonNode closed(JsonNode node, Set<String> fields, String name) {
        if (node == null || !node.isObject()) throw invalid(name + " is an object");
        if (!Set.copyOf(node.propertyNames()).equals(fields)) {
            throw invalid(name + " must have exactly the fields " + new TreeSet<>(fields));
        }
        return node;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isString()) throw invalid(field + " is a string");
        return value.stringValue();
    }

    private static UUID uuid(JsonNode node, String field) {
        try {
            return UUID.fromString(text(node, field));
        } catch (IllegalArgumentException invalid) {
            throw invalid(field + " is a UUID");
        }
    }

    private static List<String> strings(JsonNode node, String name) {
        if (node == null || !node.isArray()) throw invalid(name + " is an array");
        List<String> values = new ArrayList<>();
        for (JsonNode value : node.values()) {
            if (!value.isString()) throw invalid(name + " holds strings");
            values.add(value.stringValue());
        }
        return List.copyOf(values);
    }

    private static RunEngineException invalid(String message) {
        return new RunEngineException("INVALID_MANIFEST", message);
    }
}
