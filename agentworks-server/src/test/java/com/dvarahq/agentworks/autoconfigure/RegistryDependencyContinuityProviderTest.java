package com.dvarahq.agentworks.autoconfigure;

import com.dvarahq.agentworks.capability.CapabilityPackRegistry;
import com.dvarahq.agentworks.capability.SkillRegistry;
import com.dvarahq.agentworks.capability.ToolId;
import com.dvarahq.agentworks.capability.ToolRegistry;
import com.dvarahq.agentworks.controlplane.deployment.DependencyAvailabilitySource;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleJson;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.DependencyType;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class RegistryDependencyContinuityProviderTest {
    @Test
    void legacyManifestsWithoutOptionalCapabilityAndSkillListsRemainAvailable() {
        var provider = provider(List.of(source(DependencyType.POLICY), source(DependencyType.RUNTIME)));

        var captured = provider.capture(new WorkspaceId(UUID.randomUUID()), LifecycleJson.write(Map.of()));

        assertThat(captured.get(DependencyType.TOOL)).isEqualTo(List.of());
        assertThat(captured.get(DependencyType.SKILL)).isEqualTo(List.of());
        assertThat(captured.get(DependencyType.MCP)).isEqualTo(Map.of());
    }

    @Test
    void unsupportedLiveDependencyClassesFailClosedInsteadOfEchoingApprovedPins() {
        var provider = provider(List.of());
        String manifest = LifecycleJson.write(Map.of(
                "execution", Map.of("mode", "strict"),
                "executor", Map.of("type", "spring-ai", "external", Map.of("agent", "child")),
                "sandbox", Map.of("image", "sandbox@sha256:" + "1".repeat(64))));

        var captured = provider.capture(new WorkspaceId(UUID.randomUUID()), manifest);

        assertThat(captured.get(DependencyType.POLICY)).isEqualTo(
                Map.of("availability", "UNAVAILABLE", "type", "POLICY"));
        assertThat(captured.get(DependencyType.CHILD)).isEqualTo(
                Map.of("availability", "UNAVAILABLE", "type", "CHILD"));
        assertThat(captured.get(DependencyType.RUNTIME)).isEqualTo(
                Map.of("availability", "UNAVAILABLE", "type", "RUNTIME"));
    }

    @Test
    void mcpAuthorityIsConsultedEvenWhenTheCoreManifestHasNoSeparateMcpList() {
        AtomicBoolean called = new AtomicBoolean();
        DependencyAvailabilitySource mcp = new DependencyAvailabilitySource() {
            @Override public DependencyType type() { return DependencyType.MCP; }
            @Override public Optional<Object> capture(WorkspaceId workspace, Object approved) {
                called.set(true);
                assertThat(approved).isEqualTo(Map.of());
                return Optional.of(Map.of("connection", "revoked"));
            }
        };
        var provider = provider(List.of(mcp, source(DependencyType.POLICY), source(DependencyType.RUNTIME)));

        var captured = provider.capture(new WorkspaceId(UUID.randomUUID()), LifecycleJson.write(Map.of()));

        assertThat(called).isTrue();
        assertThat(captured.get(DependencyType.MCP)).isEqualTo(Map.of("connection", "revoked"));
    }

    private static RegistryDependencyContinuityProvider provider(List<DependencyAvailabilitySource> sources) {
        CapabilityPackRegistry packs = new CapabilityPackRegistry() {
            @Override public Optional<com.dvarahq.agentworks.capability.CapabilityPack> get(
                    WorkspaceId workspace, String id, String version) { return Optional.empty(); }
            @Override public List<com.dvarahq.agentworks.capability.CapabilityPack> activeVersions(
                    WorkspaceId workspace, String id) { return List.of(); }
        };
        ToolRegistry tools = new ToolRegistry() {
            @Override public Optional<com.dvarahq.agentworks.capability.ToolSummary> get(
                    WorkspaceId workspace, ToolId id) { return Optional.empty(); }
            @Override public Page search(WorkspaceId workspace, Search request) { return new Page(List.of(), null); }
            @Override public List<com.dvarahq.agentworks.capability.AgentTool> details(
                    WorkspaceId workspace, Set<ToolId> ids) { return List.of(); }
        };
        return new RegistryDependencyContinuityProvider(bindings -> new TreeMap<>(), packs, tools,
                SkillRegistry.empty(), sources);
    }

    private static DependencyAvailabilitySource source(DependencyType type) {
        return new DependencyAvailabilitySource() {
            @Override public DependencyType type() { return type; }
            @Override public Optional<Object> capture(WorkspaceId workspace, Object approved) {
                return Optional.of(approved);
            }
        };
    }
}
