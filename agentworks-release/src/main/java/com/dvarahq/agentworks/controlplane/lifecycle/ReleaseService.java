package com.dvarahq.agentworks.controlplane.lifecycle;

import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.AliasView;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.ReleaseView;
import com.dvarahq.agentworks.controlplane.evidence.ReleaseEvidence.Abom;
import com.dvarahq.agentworks.controlplane.evidence.ReleaseEvidence.EvidenceReference;
import com.dvarahq.agentworks.controlplane.mutation.ControlPlaneMutationAuthority;
import com.dvarahq.agentworks.capability.AgentTool;
import com.dvarahq.agentworks.capability.CapabilityPack;
import com.dvarahq.agentworks.capability.CapabilityPackRegistry;
import com.dvarahq.agentworks.capability.PinnedCapabilityPack;
import com.dvarahq.agentworks.capability.PinnedSkill;
import com.dvarahq.agentworks.capability.ResolvedSkill;
import com.dvarahq.agentworks.capability.SkillRegistry;
import com.dvarahq.agentworks.capability.ToolId;
import com.dvarahq.agentworks.capability.ToolRegistry;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import com.dvarahq.agentworks.gateway.GatewayException;
import com.dvarahq.agentworks.gateway.ModelRole;
import com.dvarahq.agentworks.gateway.ModelRoleBinding;
import com.dvarahq.agentworks.gateway.ModelRoleResolver;
import com.dvarahq.agentworks.gateway.PinnedModelRoles;
import com.dvarahq.agentworks.gateway.ModelResiliencePolicy;
import com.dvarahq.agentworks.gateway.ResolvedModelIdentity;
import com.dvarahq.agentworks.spec.AgentSpecCodec;
import com.dvarahq.agentworks.spec.CanonicalAgentSpec;
import com.dvarahq.agentworks.spec.SpecFormat;
import com.dvarahq.agentworks.spec.model.AgentSpecBody;
import com.dvarahq.agentworks.spec.model.ModelBinding;
import com.dvarahq.agentworks.spec.model.ModelRoles;
import com.dvarahq.agentworks.spec.model.ModelResilience;
import com.dvarahq.agentworks.storage.JdbcAgentDefinitionRepository;
import com.dvarahq.agentworks.storage.TenantTransactionTemplate;
import com.dvarahq.agentworks.storage.lifecycle.DatabaseClock;
import com.dvarahq.agentworks.storage.lifecycle.Digests;
import com.dvarahq.agentworks.storage.lifecycle.JdbcLifecycleRepository;
import com.dvarahq.agentworks.storage.lifecycle.LifecycleRetry;
import com.dvarahq.agentworks.domain.lifecycle.state.LifecycleRows.AliasHistoryRow;
import com.dvarahq.agentworks.domain.lifecycle.state.LifecycleRows.ReleaseRow;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Creates immutable AgentReleases from published AgentVersions and maintains audited release aliases. The manifest
 * records the exact version, specification digest, and every declared dependency identity. Each declared model role is
 * resolved through the configured gateways at creation (manifest version 2, #33), so the release pins a concrete model
 * identity and capability policy. Manifest version 3 (#38) also pins one unambiguous active version of every declared
 * capability pack, including its exact tool identities and schema hashes. Manifest version 4 (#39) pins the initial
 * execution intent and autonomy profile, conservatively defaulting omitted values to OBSERVE and READ_ONLY. Manifest
 * version 5 (#39) adds the declared resource, action, and risk of every pinned tool version. Manifest version 6 (#40)
 * pins the complete sandbox request, including conservative defaults for every resource and network bound. Manifest
 * version 7 (#41) pins the authored run limits so admission never consults a mutable AgentSpec or operator default.
 * Manifest version 8 (#46) pins external executors; version 9 (#47) replaces skill names with immutable source,
 * digest, provenance, lifecycle-loading, and trigger evidence. Manifest version 10 (#48) pins context compiler,
 * loading, budget, cache, and compaction policy. Manifest version 11 (#49) pins memory tiers, retention, and bounded
 * knowledge retrieval policy. Manifest version 12 (#54) adds content-addressed AgentTest, simulation, evaluation,
 * configuration, security, and build evidence plus a deterministic Agent Bill of Materials (ABOM). An unresolved
 * dependency fails release creation instead of being recorded ambiguously.
 */
public final class ReleaseService implements ControlPlaneMutationAuthority {
    public static final int MANIFEST_VERSION = PinnedModelRoles.MODEL_RESILIENCE_MANIFEST_VERSION;
    private static final Pattern RELEASE_NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._+-]{0,127}$");

    private final TenantTransactionTemplate tenants;
    private final JdbcLifecycleRepository lifecycle;
    private final JdbcAgentDefinitionRepository agents;
    private final AgentSpecCodec codec;
    private final DatabaseClock clock;
    private final LifecycleRetry retry;
    private final ModelRoleResolver models;
    private final CapabilityPackRegistry packs;
    private final ToolRegistry tools;
    private final SkillRegistry skills;

    /**
     * @param models resolves model roles at the release boundary; pass an empty gateway registry when no gateway is
     *        configured, which refuses every release that declares a model role
     */
    public ReleaseService(TenantTransactionTemplate tenants, JdbcLifecycleRepository lifecycle,
            JdbcAgentDefinitionRepository agents, AgentSpecCodec codec, DatabaseClock clock, LifecycleRetry retry,
            ModelRoleResolver models, CapabilityPackRegistry packs, ToolRegistry tools) {
        this(tenants, lifecycle, agents, codec, clock, retry, models, packs, tools, SkillRegistry.empty());
    }

    public ReleaseService(TenantTransactionTemplate tenants, JdbcLifecycleRepository lifecycle,
            JdbcAgentDefinitionRepository agents, AgentSpecCodec codec, DatabaseClock clock, LifecycleRetry retry,
            ModelRoleResolver models, CapabilityPackRegistry packs, ToolRegistry tools, SkillRegistry skills) {
        this.tenants = Objects.requireNonNull(tenants, "tenants");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.agents = Objects.requireNonNull(agents, "agents");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.retry = Objects.requireNonNull(retry, "retry");
        this.models = Objects.requireNonNull(models, "models");
        this.packs = Objects.requireNonNull(packs, "packs");
        this.tools = Objects.requireNonNull(tools, "tools");
        this.skills = Objects.requireNonNull(skills, "skills");
    }

    /**
     * Creates a release. Repeating the same name for the same version returns the existing release unchanged, without
     * resolving models again: a replay after an operator repointed an alias, removed a gateway, or upgraded an adapter
     * (or of a release created before the current manifest version) still returns what was pinned. The same name for
     * another version conflicts with {@code RELEASE_NAME_EXISTS}. A model role naming an unconfigured gateway or an
     * unknown alias fails with {@code GATEWAY_NOT_CONFIGURED} or {@code MODEL_ALIAS_UNKNOWN} and creates nothing.
     * Resolution happens inside the release transaction.
     */
    public ReleaseView createRelease(WorkspaceId workspace, UUID versionId, String name) {
        return createRelease(workspace, versionId, name, List.of());
    }

    /** Creates a release with secret-free content-addressed evidence captured into its immutable manifest. */
    public ReleaseView createRelease(WorkspaceId workspace, UUID versionId, String name,
            List<EvidenceReference> evidence) {
        if (name == null || !RELEASE_NAME.matcher(name).matches()) {
            throw LifecycleValidation.invalid("/name", "must match " + RELEASE_NAME.pattern());
        }
        List<EvidenceReference> capturedEvidence = evidence == null ? List.of() : List.copyOf(evidence);
        return retry.execute("release.create", () -> tenants.execute(workspace, () -> {
            var version = agents.findVersionById(versionId).orElseThrow(LifecycleNotFoundException::new);
            var definition = agents.findDefinition(version.definitionId()).orElseThrow(LifecycleNotFoundException::new);
            CanonicalAgentSpec spec = codec.read(version.specJson().getBytes(StandardCharsets.UTF_8), SpecFormat.JSON);
            if (!spec.digest().equals(version.specDigest())) {
                throw new IllegalStateException("agent version " + versionId + " failed integrity verification");
            }
            var existing = lifecycle.findReleaseByName(name);
            if (existing.isPresent()) {
                if (existing.get().versionId().equals(versionId)) {
                    verifyReplayEvidence(existing.get(), capturedEvidence);
                    return view(existing.get());
                }
                throw new LifecycleConflictException("RELEASE_NAME_EXISTS", "a different release already uses this name");
            }
            String manifest = LifecycleJson.write(manifest(workspace, definition.id(), definition.name(), version, spec,
                    models, packs, tools, skills, capturedEvidence));
            String digest = Digests.prefixed(manifest);
            ReleaseRow row = new ReleaseRow(UUID.randomUUID(), workspace.value(), definition.id(), versionId, name,
                    manifest, digest, clock.now());
            lifecycle.insertRelease(row);
            return view(row);
        }));
    }

    /** Legacy manifests predate evidence and retain their historical replay behavior. */
    private static void verifyReplayEvidence(ReleaseRow existing, List<EvidenceReference> supplied) {
        var manifest = LifecycleJson.MAPPER.readTree(existing.manifestJson());
        if (manifest.path("manifestVersion").asInt() < MANIFEST_VERSION) return;
        List<EvidenceReference> expected = new ArrayList<>();
        for (var value : manifest.path("releaseEvidence").values()) {
            expected.add(LifecycleJson.read(value.toString(), EvidenceReference.class));
        }
        var ordering = java.util.Comparator.comparing((EvidenceReference item) -> item.kind().name())
                .thenComparing(EvidenceReference::identity).thenComparing(EvidenceReference::digest);
        expected = expected.stream().sorted(ordering).toList();
        List<EvidenceReference> actual = supplied.stream().sorted(ordering).toList();
        if (!expected.equals(actual)) {
            throw new LifecycleConflictException("RELEASE_EVIDENCE_MISMATCH",
                    "the existing release was created with different evidence");
        }
    }

    public ReleaseView getRelease(WorkspaceId workspace, UUID id) {
        return tenants.execute(workspace, () -> lifecycle.findRelease(id).map(ReleaseService::view)
                .orElseThrow(LifecycleNotFoundException::new));
    }

    public List<ReleaseView> listReleases(WorkspaceId workspace) {
        return tenants.execute(workspace, () -> lifecycle.listReleases().stream().map(ReleaseService::view).toList());
    }

    /** One keyset page of releases by creation time, then id (issue #35). */
    public List<ReleaseView> listReleases(WorkspaceId workspace, java.time.Instant afterCreatedAt, UUID afterId,
            int limit) {
        return tenants.execute(workspace, () -> lifecycle.listReleases(afterCreatedAt, afterId, limit).stream()
                .map(ReleaseService::view).toList());
    }

    /** The stored canonical manifest; its SHA-256 equals the release manifest digest. */
    public String manifest(WorkspaceId workspace, UUID id) {
        return tenants.execute(workspace, () -> lifecycle.findRelease(id).map(ReleaseRow::manifestJson)
                .orElseThrow(LifecycleNotFoundException::new));
    }

    /** Creates ({@code expectedVersion} 0) or moves an alias with optimistic concurrency and an audit record. */
    public AliasView setAlias(WorkspaceId workspace, String name, UUID releaseId, long expectedVersion) {
        if (releaseId == null) throw LifecycleValidation.invalid("/releaseId", "is required");
        if (name == null || !AgentSpecCodec.isValidAgentName(name)) {
            throw LifecycleValidation.invalid("/name", "must match " + AgentSpecCodec.AGENT_NAME_PATTERN);
        }
        return retry.execute("release.alias", () -> tenants.execute(workspace, () -> {
            lifecycle.findRelease(releaseId).orElseThrow(LifecycleNotFoundException::new);
            Instant now = clock.now();
            var current = lifecycle.lockAlias(name);
            if (current.isEmpty()) {
                if (expectedVersion != 0) throw LifecycleConflictException.stale("alias");
                lifecycle.insertAlias(name, releaseId, now);
                lifecycle.insertAliasHistory(name, 1, releaseId, null, now);
                return new AliasView(name, releaseId, 1, now);
            }
            if (current.get().aliasVersion() != expectedVersion
                    || !lifecycle.moveAlias(name, releaseId, expectedVersion, now)) {
                throw LifecycleConflictException.stale("alias");
            }
            lifecycle.insertAliasHistory(name, expectedVersion + 1, releaseId, current.get().releaseId(), now);
            return new AliasView(name, releaseId, expectedVersion + 1, now);
        }));
    }

    public AliasView getAlias(WorkspaceId workspace, String name) {
        return tenants.execute(workspace, () -> {
            var history = lifecycle.aliasHistory(name);
            if (history.isEmpty()) throw new LifecycleNotFoundException();
            AliasHistoryRow last = history.getLast();
            return new AliasView(name, last.releaseId(), last.aliasVersion(), last.changedAt());
        });
    }

    public List<AliasHistoryRow> aliasHistory(WorkspaceId workspace, String name) {
        return tenants.execute(workspace, () -> lifecycle.aliasHistory(name));
    }

    private static Map<String, Object> manifest(WorkspaceId workspace, UUID definitionId, String definitionName,
            JdbcAgentDefinitionRepository.VersionRow version, CanonicalAgentSpec spec, ModelRoleResolver resolver,
            CapabilityPackRegistry packs, ToolRegistry tools, SkillRegistry skills, List<EvidenceReference> evidence) {
        AgentSpecBody body = spec.spec().spec();
        Map<String, Object> manifest = new TreeMap<>();
        manifest.put("manifestVersion", MANIFEST_VERSION);
        manifest.put("agent", Map.of("definitionId", definitionId.toString(), "definitionName", definitionName,
                "versionId", version.id().toString(), "version", version.versionLabel(),
                "specApiVersion", spec.spec().apiVersion(), "specDigest", spec.digest()));
        Map<String, Object> executor = new TreeMap<>();
        executor.put("type", body.executor().type());
        executor.put("requiredCapabilities", body.executor().requiredCapabilities() == null ? List.of()
                : body.executor().requiredCapabilities().stream().map(Enum::name).sorted().toList());
        if (body.executor().external() != null) executor.put("external", body.executor().external());
        manifest.put("executor", executor);
        Map<String, Object> models = new TreeMap<>();
        List<ModelRoleBinding> bindings = bindings(body.models());
        if (!bindings.isEmpty()) {
            try {
                resolver.resolve(bindings).forEach((role, identity) -> {
                    Map<String, Object> entry = new TreeMap<>(identity.toManifestEntry());
                    entry.put("resilience", resilience(role, identity, binding(body.models(), role), resolver));
                    models.put(role.manifestKey(), entry);
                });
            } catch (GatewayException unresolvable) {
                throw new LifecycleConflictException(unresolvable.code().name(), unresolvable.detail());
            } catch (IllegalArgumentException invalidPolicy) {
                throw new LifecycleConflictException("MODEL_RESILIENCE_INVALID", invalidPolicy.getMessage());
            }
        }
        manifest.put("models", models);
        manifest.put("capabilityPacks", pinnedCapabilities(workspace, body.capabilityPacks(), packs, tools));
        manifest.put("skills", pinnedSkills(workspace, body.skills(), skills));
        manifest.put("sandbox", sandbox(body));
        manifest.put("limits", limits(body));
        manifest.put("context", context(body));
        manifest.put("memory", memory(body));
        var execution = body.execution();
        manifest.put("execution", Map.of(
                "initialIntent", execution == null || execution.initialIntent() == null
                        ? "OBSERVE" : execution.initialIntent().name(),
                "autonomyProfile", execution == null || execution.autonomyProfile() == null
                        ? "READ_ONLY" : execution.autonomyProfile().name()));
        List<EvidenceReference> orderedEvidence = evidence.stream()
                .sorted(java.util.Comparator.comparing((EvidenceReference item) -> item.kind().name())
                        .thenComparing(EvidenceReference::identity).thenComparing(EvidenceReference::digest)).toList();
        manifest.put("releaseEvidence", orderedEvidence);
        Abom abom = Abom.fromManifest(LifecycleJson.write(manifest));
        manifest.put("abom", Map.of("version", abom.version(), "digest", abom.digest(),
                "components", abom.components()));
        return manifest;
    }

    private static Map<String, Object> context(AgentSpecBody body) {
        var authored = body.context();
        Map<String, Object> compaction = new TreeMap<>();
        boolean enabled = authored != null && authored.compaction() != null
                && Boolean.TRUE.equals(authored.compaction().enabled());
        compaction.put("enabled", enabled);
        compaction.put("strategy", authored == null || authored.compaction() == null
                || authored.compaction().strategy() == null ? "NONE" : authored.compaction().strategy().name());
        compaction.put("threshold", authored == null || authored.compaction() == null
                || authored.compaction().threshold() == null ? java.math.BigDecimal.valueOf(0.8)
                        : authored.compaction().threshold());
        Map<String, Object> pinned = new TreeMap<>();
        pinned.put("maximumTokens", authored == null || authored.maxTokens() == null ? 8192 : authored.maxTokens());
        pinned.put("loadingPolicy", authored == null || authored.loadingPolicy() == null
                ? "ON_DEMAND" : authored.loadingPolicy().name());
        pinned.put("cacheAware", authored == null || authored.cacheAware() == null
                || authored.cacheAware());
        pinned.put("compaction", compaction);
        pinned.put("policyHash", Digests.prefixed(LifecycleJson.write(pinned)));
        return Map.copyOf(pinned);
    }

    private static Map<String, Object> memory(AgentSpecBody body) {
        var authored = body.memory();
        boolean conversation = authored != null && authored.conversation() != null
                && Boolean.TRUE.equals(authored.conversation().enabled());
        boolean working = authored != null && authored.working() != null
                && Boolean.TRUE.equals(authored.working().enabled());
        boolean semantic = authored != null && authored.semantic() != null
                && Boolean.TRUE.equals(authored.semantic().enabled());
        Map<String, Object> pinned = new TreeMap<>();
        pinned.put("conversationEnabled", conversation);
        pinned.put("conversationRetention", "P30D");
        pinned.put("conversationItems", 64);
        pinned.put("workingEnabled", working);
        pinned.put("workingRetention", "PT24H");
        pinned.put("workingItems", 32);
        pinned.put("semanticEnabled", semantic);
        pinned.put("knowledgeItems", 16);
        pinned.put("knowledgeTokens", 4096);
        pinned.put("knowledgeTimeout", "PT5S");
        pinned.put("policyHash", Digests.prefixed(LifecycleJson.write(pinned)));
        return Map.copyOf(pinned);
    }

    private static List<Map<String, Object>> pinnedSkills(WorkspaceId workspace, List<String> declared,
            SkillRegistry registry) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (String id : sorted(declared)) {
            List<ResolvedSkill> active = registry.activeVersions(workspace, id);
            if (active.isEmpty()) {
                throw new LifecycleConflictException("SKILL_UNAVAILABLE", "skill " + id + " has no active version");
            }
            if (active.size() != 1) {
                throw new LifecycleConflictException("SKILL_AMBIGUOUS", "skill " + id + " has multiple active versions");
            }
            PinnedSkill skill = PinnedSkill.from(active.getFirst());
            Map<String, Object> pinned = new TreeMap<>();
            pinned.put("id", skill.id());
            pinned.put("sourceKind", skill.sourceKind().name());
            pinned.put("sourceUri", skill.sourceUri());
            pinned.put("authoredRef", skill.authoredRef());
            pinned.put("resolvedRef", skill.resolvedRef());
            pinned.put("contentDigest", skill.contentDigest());
            if (skill.signatureDigest() != null) pinned.put("signatureDigest", skill.signatureDigest());
            pinned.put("trustPolicy", skill.trustPolicy());
            pinned.put("manifestVersion", skill.manifestVersion());
            pinned.put("loadingPolicy", skill.loadingPolicy().name());
            pinned.put("contextTriggers", skill.contextTriggers().stream().sorted().toList());
            pinned.put("license", skill.license());
            pinned.put("provenance", skill.provenance());
            result.add(Map.copyOf(pinned));
        }
        return List.copyOf(result);
    }

    private static Map<String, Object> limits(AgentSpecBody body) {
        if (body.limits() == null) return Map.of();
        var authored = body.limits();
        Map<String, Object> pinned = new TreeMap<>();
        if (authored.maxSteps() != null) pinned.put("maxSteps", authored.maxSteps());
        if (authored.maxToolCalls() != null) pinned.put("maxToolCalls", authored.maxToolCalls());
        if (authored.maxRuntime() != null) pinned.put("maxRuntime", authored.maxRuntime());
        if (authored.maxCostUsd() != null) pinned.put("maxCostUsd", authored.maxCostUsd());
        return pinned;
    }

    private static Map<String, Object> sandbox(AgentSpecBody body) {
        if (body.sandbox() == null) return Map.of();
        var authored = body.sandbox();
        Map<String, Object> pinned = new TreeMap<>();
        pinned.put("provider", authored.provider());
        pinned.put("provisioning", authored.provisioning() == null ? "demand"
                : authored.provisioning().name().toLowerCase(java.util.Locale.ROOT));
        if (authored.image() != null) pinned.put("image", authored.image());
        pinned.put("cpu", authored.cpu() == null ? java.math.BigDecimal.ONE : authored.cpu());
        pinned.put("memory", authored.memory() == null ? "512Mi" : authored.memory());
        pinned.put("pidLimit", 128);
        pinned.put("networkPolicy", "NONE");
        pinned.put("maxRuntime", body.limits() == null || body.limits().maxRuntime() == null
                ? "5m" : body.limits().maxRuntime());
        return pinned;
    }

    private static List<Map<String, Object>> pinnedCapabilities(WorkspaceId workspace, List<String> declared,
            CapabilityPackRegistry registry, ToolRegistry tools) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (String id : sorted(declared)) {
            List<CapabilityPack> active = registry.activeVersions(workspace, id);
            if (active.isEmpty()) {
                throw new LifecycleConflictException("CAPABILITY_PACK_UNAVAILABLE",
                        "capability pack " + id + " has no active version");
            }
            if (active.size() != 1) {
                throw new LifecycleConflictException("CAPABILITY_PACK_AMBIGUOUS",
                        "capability pack " + id + " has more than one active version");
            }
            CapabilityPack pack = active.getFirst();
            Map<ToolId, AgentTool> available = new TreeMap<>();
            tools.details(workspace, Set.copyOf(pack.tools())).forEach(tool -> available.put(tool.id(), tool));
            if (available.size() != pack.tools().size()) {
                throw new LifecycleConflictException("CAPABILITY_TOOL_UNAVAILABLE",
                        "capability pack " + pack.identity() + " contains a tool that is not active");
            }
            List<PinnedCapabilityPack.PinnedTool> pinned = pack.tools().stream().sorted().map(toolId -> {
                AgentTool tool = available.get(toolId);
                if (tool == null) {
                    throw new LifecycleConflictException("CAPABILITY_TOOL_UNAVAILABLE",
                            "capability pack " + pack.identity() + " contains a tool that is not active");
                }
                return new PinnedCapabilityPack.PinnedTool(tool.id(), tool.schemaHash(), tool.effect());
            }).toList();
            result.add(Map.of("id", pack.id(), "version", pack.version(), "tools", pinned.stream().map(tool -> {
                var effect = tool.effect().orElseThrow();
                return Map.of("namespace", tool.id().namespace(), "name", tool.id().name(),
                        "version", tool.id().version(), "schemaHash", tool.schemaHash(), "effect",
                        Map.of("resource", effect.resource(), "action", effect.action(), "risk", effect.risk().name()));
            }).toList()));
        }
        return List.copyOf(result);
    }

    private static List<ModelRoleBinding> bindings(ModelRoles roles) {
        List<ModelRoleBinding> bindings = new ArrayList<>();
        if (roles != null) {
            add(bindings, ModelRole.REASONING, roles.reasoning());
            add(bindings, ModelRole.COMPACTION, roles.compaction());
            add(bindings, ModelRole.SUMMARIZATION, roles.summarization());
            add(bindings, ModelRole.MEMORY, roles.memory());
            add(bindings, ModelRole.CAPABILITY_ROUTING, roles.capabilityRouting());
            add(bindings, ModelRole.GUARDRAIL, roles.guardrail());
            add(bindings, ModelRole.EVALUATION, roles.evaluation());
        }
        return bindings;
    }

    private static void add(List<ModelRoleBinding> bindings, ModelRole role, ModelBinding binding) {
        if (binding != null) {
            bindings.add(new ModelRoleBinding(role, binding.gateway(), binding.model()));
        }
    }

    private static ModelBinding binding(ModelRoles roles, ModelRole role) {
        return switch (role) {
            case REASONING -> roles.reasoning();
            case COMPACTION -> roles.compaction();
            case SUMMARIZATION -> roles.summarization();
            case MEMORY -> roles.memory();
            case CAPABILITY_ROUTING -> roles.capabilityRouting();
            case GUARDRAIL -> roles.guardrail();
            case EVALUATION -> roles.evaluation();
        };
    }

    /** Resolves every fallback now and serializes a complete closed policy; runtime never consults an alias. */
    private static Map<String, Object> resilience(ModelRole role, ResolvedModelIdentity primary, ModelBinding binding,
            ModelRoleResolver resolver) {
        ModelResilience authored = binding.resilience();
        ModelResiliencePolicy defaults = ModelResiliencePolicy.DEFAULT;
        List<ResolvedModelIdentity> fallbacks = new ArrayList<>();
        if (authored != null && authored.fallbacks() != null) {
            authored.fallbacks().forEach(fallback -> {
                ResolvedModelIdentity resolved = resolver.resolve(List.of(
                        new ModelRoleBinding(role, fallback.gateway(), fallback.model()))).get(role);
                for (var capability : com.dvarahq.agentworks.gateway.GatewayCapability.negotiatedCapabilities()) {
                    if (primary.capabilityPolicy().modeFor(capability)
                            == com.dvarahq.agentworks.gateway.CapabilityMode.REQUIRED
                            && !resolved.advertisedCapabilities().contains(capability)) {
                        throw new IllegalArgumentException("fallback " + fallback.gateway() + "/" + fallback.model()
                                + " lacks required capability " + capability);
                    }
                }
                if (resolved.gatewayId().equals(primary.gatewayId())
                        && resolved.providerModel().equals(primary.providerModel())) {
                    throw new IllegalArgumentException("fallback must differ from the primary model route");
                }
                if (fallbacks.stream().anyMatch(existing -> existing.gatewayId().equals(resolved.gatewayId())
                        && existing.providerModel().equals(resolved.providerModel()))) {
                    throw new IllegalArgumentException("fallback model routes must be unique");
                }
                fallbacks.add(resolved);
            });
        }
        ModelResiliencePolicy policy = new ModelResiliencePolicy(
                authored == null || authored.maxAttempts() == null ? defaults.maxAttemptsPerRoute()
                        : authored.maxAttempts(),
                java.time.Duration.ofMillis(authored == null || authored.initialBackoffMillis() == null
                        ? defaults.initialBackoff().toMillis() : authored.initialBackoffMillis()),
                java.time.Duration.ofMillis(authored == null || authored.maxBackoffMillis() == null
                        ? defaults.maxBackoff().toMillis() : authored.maxBackoffMillis()),
                authored == null || authored.jitter() == null ? defaults.jitter() : authored.jitter(),
                authored == null || authored.maxConcurrent() == null ? defaults.maxConcurrent()
                        : authored.maxConcurrent(),
                java.time.Duration.ofMillis(authored == null || authored.bulkheadWaitMillis() == null
                        ? defaults.bulkheadWait().toMillis() : authored.bulkheadWaitMillis()),
                authored == null || authored.breakerFailureThreshold() == null ? defaults.breakerFailureThreshold()
                        : authored.breakerFailureThreshold(),
                java.time.Duration.ofMillis(authored == null || authored.breakerOpenMillis() == null
                        ? defaults.breakerOpenDuration().toMillis() : authored.breakerOpenMillis()),
                java.time.Duration.ofMillis(authored == null || authored.requestTimeoutMillis() == null
                        ? defaults.requestTimeout().toMillis() : authored.requestTimeoutMillis()),
                java.time.Duration.ofMillis(authored == null || authored.streamIdleTimeoutMillis() == null
                        ? defaults.streamIdleTimeout().toMillis() : authored.streamIdleTimeoutMillis()), fallbacks);
        Map<String, Object> pinned = new TreeMap<>();
        pinned.put("maxAttempts", policy.maxAttemptsPerRoute());
        pinned.put("initialBackoffMillis", policy.initialBackoff().toMillis());
        pinned.put("maxBackoffMillis", policy.maxBackoff().toMillis());
        pinned.put("jitter", policy.jitter());
        pinned.put("maxConcurrent", policy.maxConcurrent());
        pinned.put("bulkheadWaitMillis", policy.bulkheadWait().toMillis());
        pinned.put("breakerFailureThreshold", policy.breakerFailureThreshold());
        pinned.put("breakerOpenMillis", policy.breakerOpenDuration().toMillis());
        pinned.put("requestTimeoutMillis", policy.requestTimeout().toMillis());
        pinned.put("streamIdleTimeoutMillis", policy.streamIdleTimeout().toMillis());
        pinned.put("fallbacks", policy.fallbacks().stream().map(ResolvedModelIdentity::toManifestEntry).toList());
        return pinned;
    }

    private static List<String> sorted(List<String> values) {
        return values == null ? List.of() : values.stream().sorted().toList();
    }

    public static ReleaseView view(ReleaseRow row) {
        return new ReleaseView(row.id(), row.workspaceId(), row.definitionId(), row.versionId(), row.name(),
                row.manifestDigest(), row.createdAt());
    }
}
