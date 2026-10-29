package com.dvarahq.agentworks.controlplane.capability.web;

import com.dvarahq.agentworks.capability.AgentTool;
import com.dvarahq.agentworks.capability.CapabilityPack;
import com.dvarahq.agentworks.capability.ToolId;
import com.dvarahq.agentworks.capability.ToolEffect;
import com.dvarahq.agentworks.capability.ToolSummary;
import com.dvarahq.agentworks.capability.ToolType;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import com.dvarahq.agentworks.domain.security.PermissionRisk;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Request and response shapes for the additive v1 capability catalog API. */
public final class CapabilityApiResources {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private CapabilityApiResources() {}

    public enum LifecycleState { ACTIVE, DISABLED, DENIED }

    public record ToolIdentity(String namespace, String name, String version) {
        ToolId value() { return new ToolId(namespace, name, version); }
        static ToolIdentity of(ToolId id) { return new ToolIdentity(id.namespace(), id.name(), id.version()); }
    }

    public record ToolEffectResource(String resource, String action, PermissionRisk risk) {
        ToolEffect value() { return new ToolEffect(resource, action, risk); }
        static ToolEffectResource of(ToolEffect effect) {
            return new ToolEffectResource(effect.resource(), effect.action(), effect.risk());
        }
    }

    public record CreateToolRequest(String namespace, String name, String version, ToolType type, String summary,
            String description, JsonNode inputSchema, ToolEffectResource effect, Set<String> tags, Set<String> compatibility,
            int summaryTokens, int detailTokens) {
        AgentTool value() {
            if (inputSchema == null) throw new IllegalArgumentException("inputSchema is required");
            if (tags == null) throw new IllegalArgumentException("tags are required");
            if (compatibility == null) throw new IllegalArgumentException("compatibility is required");
            String schema = inputSchema.toString();
            return new AgentTool(new ToolId(namespace, name, version), type, summary, description, schema,
                    AgentTool.digest(schema), effect == null ? ToolEffect.unclassified() : effect.value(),
                    tags, compatibility, summaryTokens, detailTokens,
                    AgentTool.Availability.AVAILABLE);
        }
    }

    public record ToolSummaryResource(UUID workspaceId, ToolIdentity id, ToolType type, String summary,
            String schemaHash, ToolEffectResource effect, Set<String> tags, Set<String> compatibility,
            int summaryTokens, int detailTokens) {
        static ToolSummaryResource of(WorkspaceId workspace, ToolSummary tool) {
            return new ToolSummaryResource(workspace.value(), ToolIdentity.of(tool.id()), tool.type(), tool.summary(),
                    tool.schemaHash(), ToolEffectResource.of(tool.effect()), tool.tags(), tool.compatibility(),
                    tool.summaryTokens(), tool.detailTokens());
        }
    }

    public record ToolDetailResource(UUID workspaceId, ToolIdentity id, ToolType type, String summary,
            String description, JsonNode inputSchema, String schemaHash, ToolEffectResource effect,
            Set<String> tags, Set<String> compatibility,
            int summaryTokens, int detailTokens, AgentTool.Availability state) {
        static ToolDetailResource of(WorkspaceId workspace, AgentTool tool) {
            return new ToolDetailResource(workspace.value(), ToolIdentity.of(tool.id()), tool.type(), tool.summary(),
                    tool.description(), JSON.readTree(tool.inputSchema()), tool.schemaHash(),
                    ToolEffectResource.of(tool.effect()), tool.tags(),
                    tool.compatibility(), tool.summaryTokens(), tool.detailTokens(), tool.availability());
        }
    }

    public record CreatePackRequest(String id, String version, List<ToolIdentity> tools) {
        CapabilityPack value() {
            if (tools == null) throw new IllegalArgumentException("tools are required");
            return new CapabilityPack(id, version, CapabilityPack.State.AVAILABLE,
                    tools.stream().map(ToolIdentity::value).toList());
        }
    }

    public record CapabilityPackResource(UUID workspaceId, String id, String version, CapabilityPack.State state,
            List<ToolIdentity> tools) {
        static CapabilityPackResource of(WorkspaceId workspace, CapabilityPack pack) {
            return new CapabilityPackResource(workspace.value(), pack.id(), pack.version(), pack.state(),
                    pack.tools().stream().map(ToolIdentity::of).toList());
        }
    }

    public record LifecycleRequest(LifecycleState state) {
        AgentTool.Availability toolState() {
            if (state == null) throw new IllegalArgumentException("state is required");
            return AgentTool.Availability.valueOf(state.name());
        }
        CapabilityPack.State packState() {
            if (state == null) throw new IllegalArgumentException("state is required");
            return CapabilityPack.State.valueOf(state.name());
        }
    }

    public record LifecycleResource(String identity, LifecycleState state) {}
}
