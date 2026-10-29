package com.dvarahq.agentworks.autoconfigure;

import com.dvarahq.agentworks.capability.CapabilityPackRegistry;
import com.dvarahq.agentworks.capability.PinnedSkill;
import com.dvarahq.agentworks.capability.SkillRegistry;
import com.dvarahq.agentworks.capability.ToolId;
import com.dvarahq.agentworks.capability.ToolRegistry;
import com.dvarahq.agentworks.controlplane.deployment.DependencyContinuityProvider;
import com.dvarahq.agentworks.controlplane.deployment.DependencyAvailabilitySource;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleJson;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.DependencyType;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import com.dvarahq.agentworks.gateway.ModelRole;
import com.dvarahq.agentworks.gateway.ModelRoleBinding;
import com.dvarahq.agentworks.gateway.ModelRoleResolver;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/** Re-resolves exact manifest pins from the current tenant registries without changing the approved manifest. */
final class RegistryDependencyContinuityProvider implements DependencyContinuityProvider {
    private final ModelRoleResolver models;
    private final CapabilityPackRegistry packs;
    private final ToolRegistry tools;
    private final SkillRegistry skills;
    private final Map<DependencyType, DependencyAvailabilitySource> additional;

    RegistryDependencyContinuityProvider(ModelRoleResolver models, CapabilityPackRegistry packs, ToolRegistry tools,
            SkillRegistry skills, List<DependencyAvailabilitySource> additional) {
        this.models = models; this.packs = packs; this.tools = tools; this.skills = skills;
        Map<DependencyType, DependencyAvailabilitySource> sources = new EnumMap<>(DependencyType.class);
        for (var source : additional) {
            if (sources.put(source.type(), source) != null) throw new IllegalStateException("duplicate dependency source");
        }
        this.additional = Map.copyOf(sources);
    }

    @Override public Map<DependencyType, Object> capture(WorkspaceId workspace, String approvedManifest) {
        Map<?, ?> manifest = LifecycleJson.read(approvedManifest, Map.class);
        Map<DependencyType, Object> current = new EnumMap<>(DependencyType.class);
        current.put(DependencyType.MODEL, models(manifest));
        current.put(DependencyType.TOOL, capabilities(workspace, manifest));
        current.put(DependencyType.MCP, live(workspace, DependencyType.MCP, Map.of()));
        current.put(DependencyType.SKILL, skills(workspace, manifest));
        Object policy = Map.of("execution", value(manifest, "execution"),
                "context", value(manifest, "context"), "memory", value(manifest, "memory"),
                "limits", value(manifest, "limits"));
        current.put(DependencyType.POLICY, live(workspace, DependencyType.POLICY, policy));
        Object executor = value(manifest, "executor");
        Object child = executor instanceof Map<?, ?> map && map.get("external") != null ? map.get("external") : Map.of();
        current.put(DependencyType.CHILD, live(workspace, DependencyType.CHILD, child));
        current.put(DependencyType.RUNTIME, live(workspace, DependencyType.RUNTIME,
                Map.of("executor", executor, "sandbox", value(manifest, "sandbox"))));
        return current;
    }

    private Object models(Map<?, ?> manifest) {
        try {
            Map<?, ?> pinned = (Map<?, ?>) value(manifest, "models");
            List<ModelRoleBinding> bindings = new ArrayList<>();
            for (var item : pinned.entrySet()) {
                Map<?, ?> identity = (Map<?, ?>) item.getValue();
                bindings.add(new ModelRoleBinding(ModelRole.fromManifestKey(item.getKey().toString()),
                        identity.get("gateway").toString(), identity.get("alias").toString()));
            }
            Map<String, Object> result = new TreeMap<>();
            models.resolve(bindings).forEach((role, identity) -> result.put(role.manifestKey(), identity.toManifestEntry()));
            return result;
        } catch (RuntimeException unavailable) { return Map.of("availability", "UNAVAILABLE"); }
    }

    private Object capabilities(WorkspaceId workspace, Map<?, ?> manifest) {
        try {
            List<?> pinned = list(manifest, "capabilityPacks");
            List<Object> result = new ArrayList<>();
            for (Object item : pinned) {
                Map<?, ?> expected = (Map<?, ?>) item;
                String id = expected.get("id").toString(), version = expected.get("version").toString();
                var pack = packs.get(workspace, id, version).orElseThrow();
                Map<ToolId, com.dvarahq.agentworks.capability.AgentTool> live = new TreeMap<>();
                tools.details(workspace, Set.copyOf(pack.tools())).forEach(tool -> live.put(tool.id(), tool));
                List<?> expectedTools = (List<?>) expected.get("tools");
                if (live.size() != expectedTools.size()) throw new IllegalStateException();
                List<Object> rebuilt = new ArrayList<>();
                for (Object pin : expectedTools) {
                    Map<?, ?> tool = (Map<?, ?>) pin;
                    ToolId toolId = new ToolId(tool.get("namespace").toString(), tool.get("name").toString(),
                            tool.get("version").toString());
                    var found = live.get(toolId);
                    if (found == null) throw new IllegalStateException();
                    Map<String, Object> copy = new TreeMap<>();
                    tool.forEach((key, val) -> copy.put(key.toString(), val));
                    copy.put("schemaHash", found.schemaHash());
                    copy.put("effect", Map.of("resource", found.effect().resource(), "action", found.effect().action(),
                            "risk", found.effect().risk().name()));
                    rebuilt.add(copy);
                }
                result.add(Map.of("id", id, "version", version, "tools", rebuilt));
            }
            return result;
        } catch (RuntimeException unavailable) { return Map.of("availability", "UNAVAILABLE"); }
    }

    private Object skills(WorkspaceId workspace, Map<?, ?> manifest) {
        try {
            List<?> pinned = list(manifest, "skills");
            List<Object> result = new ArrayList<>();
            for (Object item : pinned) {
                Map<?, ?> expected = (Map<?, ?>) item;
                String id = expected.get("id").toString();
                PinnedSkill match = skills.activeVersions(workspace, id).stream().map(PinnedSkill::from)
                        .filter(skill -> skill.resolvedRef().equals(expected.get("resolvedRef"))
                                && skill.contentDigest().equals(expected.get("contentDigest"))
                                && java.util.Objects.equals(skill.signatureDigest(), expected.get("signatureDigest"))
                                && skill.trustPolicy().equals(expected.get("trustPolicy"))
                                && skill.manifestVersion().equals(expected.get("manifestVersion")))
                        .findFirst().orElseThrow();
                result.add(expected);
            }
            return result;
        } catch (RuntimeException unavailable) { return Map.of("availability", "UNAVAILABLE"); }
    }

    private static Object value(Map<?, ?> manifest, String key) {
        return manifest.get(key) == null ? Map.of() : manifest.get(key);
    }

    private static List<?> list(Map<?, ?> manifest, String key) {
        Object value = manifest.get(key);
        return value == null ? List.of() : (List<?>) value;
    }

    private Object live(WorkspaceId workspace, DependencyType type, Object approved) {
        DependencyAvailabilitySource source = additional.get(type);
        if (source == null && approved instanceof Map<?, ?> map && map.isEmpty()) return approved;
        return Optional.ofNullable(source).flatMap(candidate -> candidate.capture(workspace, approved))
                .orElseGet(() -> Map.of("availability", "UNAVAILABLE", "type", type.name()));
    }
}
