package com.dvarahq.agentworks.storage.capability;

import com.dvarahq.agentworks.capability.AgentTool;
import com.dvarahq.agentworks.capability.CapabilityPack;
import com.dvarahq.agentworks.capability.PinnedCapabilityPack;
import com.dvarahq.agentworks.capability.ToolId;
import com.dvarahq.agentworks.capability.ToolEffect;
import com.dvarahq.agentworks.capability.ToolRegistry;
import com.dvarahq.agentworks.capability.ToolSummary;
import com.dvarahq.agentworks.capability.ToolType;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import com.dvarahq.agentworks.storage.TenantTransactionTemplate;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CapabilityRegistryH2Test {
    private static final String SCHEMA = "{\"type\":\"object\"}";
    private static final Instant NOW = Instant.parse("2026-10-07T12:00:00Z");

    private JdbcCapabilityRegistry registry;
    private TenantTransactionTemplate tenants;
    private WorkspaceId workspaceA;
    private WorkspaceId workspaceB;

    @BeforeEach
    void setUp() {
        var source = new DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(source).locations("classpath:db/migration/common").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(source);
        tenants = new TenantTransactionTemplate(new TransactionTemplate(new JdbcTransactionManager(source)));
        registry = new JdbcCapabilityRegistry(jdbc);
        workspaceA = workspace(jdbc, "a");
        workspaceB = workspace(jdbc, "b");
    }

    @Test
    void discoveryIsActiveTenantScopedAndFailsClosedOnBindingMismatch() {
        AgentTool search = tool("search", ToolType.MCP, AgentTool.Availability.ACTIVE, Set.of("web"));
        AgentTool disabled = tool("shell", ToolType.SHELL, AgentTool.Availability.DISABLED, Set.of("ops"));
        tenants.executeWithoutResult(workspaceA, () -> {
            registry.insertTool(workspaceA, search, NOW);
            registry.insertTool(workspaceA, disabled, NOW);
        });

        assertThat(tenants.execute(workspaceA, () -> registry.get(workspaceA, search.id()))).contains(search.metadata());
        assertThat(tenants.execute(workspaceA, () -> registry.get(workspaceA, disabled.id()))).isEmpty();
        assertThat(tenants.execute(workspaceB, () -> registry.get(workspaceB, search.id()))).isEmpty();
        assertThatThrownBy(() -> tenants.execute(workspaceA, () -> registry.get(workspaceB, search.id())))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("tenant binding");
    }

    @Test
    void boundedKeysetSearchFiltersAndPagesWithoutLeakingSchemasIntoCursor() {
        AgentTool alpha = tool("alpha-search", ToolType.MCP, AgentTool.Availability.ACTIVE, Set.of("web", "read"));
        AgentTool beta = tool("beta-search", ToolType.HTTP, AgentTool.Availability.ACTIVE, Set.of("web"));
        AgentTool gamma = tool("gamma", ToolType.MCP, AgentTool.Availability.ACTIVE, Set.of("data"));
        tenants.executeWithoutResult(workspaceA, () -> {
            registry.insertTool(workspaceA, gamma, NOW);
            registry.insertTool(workspaceA, beta, NOW);
            registry.insertTool(workspaceA, alpha, NOW);
        });

        ToolRegistry.Page first = tenants.execute(workspaceA, () -> registry.search(workspaceA,
                new ToolRegistry.Search("search", Set.of(), Set.of("web"), null, 1)));
        ToolRegistry.Page second = tenants.execute(workspaceA, () -> registry.search(workspaceA,
                new ToolRegistry.Search("search", Set.of(), Set.of("web"), first.nextCursor(), 1)));

        assertThat(first.items()).extracting(ToolSummary::id).containsExactly(alpha.id());
        assertThat(first.nextCursor()).isNotBlank().doesNotContain(SCHEMA);
        assertThat(second.items()).extracting(ToolSummary::id).containsExactly(beta.id());
        assertThat(second.nextCursor()).isNull();
        assertThat(tenants.execute(workspaceA, () -> registry.search(workspaceA,
                new ToolRegistry.Search("", Set.of(ToolType.MCP), Set.of("web", "read"), null, 20)).items()))
                .extracting(ToolSummary::id).containsExactly(alpha.id());
        assertThat(tenants.execute(workspaceA, () -> registry.details(workspaceA, Set.of(alpha.id()))))
                .containsExactly(alpha);
        assertThatThrownBy(() -> tenants.execute(workspaceA, () -> registry.search(workspaceA,
                new ToolRegistry.Search("", Set.of(), Set.of(), "not-base64", 20))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cursor");
    }

    @Test
    void exactPackVersionsReferenceExactToolsAndLifecycleControlsDiscovery() {
        AgentTool search = tool("search", ToolType.MCP, AgentTool.Availability.ACTIVE, Set.of("web"));
        CapabilityPack pack = new CapabilityPack("research", "1", CapabilityPack.State.ACTIVE, List.of(search.id()));
        tenants.executeWithoutResult(workspaceA, () -> {
            registry.insertTool(workspaceA, search, NOW);
            registry.insertPack(workspaceA, pack, NOW);
        });

        assertThat(tenants.execute(workspaceA, () -> registry.get(workspaceA, "research", "1"))).contains(pack);
        assertThat(tenants.execute(workspaceA, () -> registry.activeVersions(workspaceA, "research")))
                .containsExactly(pack);
        tenants.executeWithoutResult(workspaceA, () -> registry.transitionPack(workspaceA, "research", "1",
                CapabilityPack.State.DISABLED, NOW.plusSeconds(1)));
        assertThat(tenants.execute(workspaceA, () -> registry.get(workspaceA, "research", "1"))).isEmpty();
        assertThat(tenants.execute(workspaceA, () -> registry.activeVersions(workspaceA, "research"))).isEmpty();
    }

    @Test
    void runtimeSnapshotReadsExactPinnedEvidenceIncludingDisabledLifecycleState() {
        AgentTool search = tool("search", ToolType.MCP, AgentTool.Availability.ACTIVE, Set.of("web"));
        CapabilityPack pack = new CapabilityPack("research", "1", CapabilityPack.State.ACTIVE, List.of(search.id()));
        PinnedCapabilityPack pinned = new PinnedCapabilityPack("research", "1",
                List.of(new PinnedCapabilityPack.PinnedTool(search.id(), search.schemaHash())));
        tenants.executeWithoutResult(workspaceA, () -> {
            registry.insertTool(workspaceA, search, NOW);
            registry.insertPack(workspaceA, pack, NOW);
            registry.transitionTool(workspaceA, search.id(), AgentTool.Availability.DISABLED, NOW.plusSeconds(1));
            registry.transitionPack(workspaceA, "research", "1", CapabilityPack.State.DISABLED, NOW.plusSeconds(1));
        });

        var snapshot = tenants.execute(workspaceA, () -> registry.snapshot(workspaceA, List.of(pinned)));

        assertThat(snapshot.packs()).containsExactly(new CapabilityPack("research", "1",
                CapabilityPack.State.DISABLED, List.of(search.id())));
        assertThat(snapshot.tools()).singleElement().satisfies(tool -> {
            assertThat(tool.id()).isEqualTo(search.id());
            assertThat(tool.schemaHash()).isEqualTo(search.schemaHash());
            assertThat(tool.availability()).isEqualTo(AgentTool.Availability.DISABLED);
        });
        assertThat(tenants.execute(workspaceA, () -> registry.details(workspaceA, List.of(search.id()))))
                .singleElement().satisfies(detail -> {
                    assertThat(detail.id()).isEqualTo(search.id());
                    assertThat(detail.inputSchema()).isEqualTo(search.inputSchema());
                    assertThat(detail.availability()).isEqualTo(AgentTool.Availability.DISABLED);
                });
        assertThat(tenants.execute(workspaceB, () -> registry.snapshot(workspaceB, List.of(pinned)).packs())).isEmpty();
    }

    private static WorkspaceId workspace(JdbcTemplate jdbc, String name) {
        UUID organization = UUID.randomUUID();
        WorkspaceId workspace = new WorkspaceId(UUID.randomUUID());
        jdbc.update("insert into organization(id, name) values (?, ?)", organization, "org-" + name);
        jdbc.update("insert into workspace(id, organization_id, name) values (?, ?, ?)", workspace.value(),
                organization, name);
        return workspace;
    }

    private static AgentTool tool(String name, ToolType type, AgentTool.Availability availability, Set<String> tags) {
        return new AgentTool(new ToolId("core", name, "1.0.0"), type, name.replace('-', ' '),
                "Detailed " + name, SCHEMA, AgentTool.digest(SCHEMA), new ToolEffect("test-resource", "read",
                com.dvarahq.agentworks.domain.security.PermissionRisk.LOW), tags, Set.of("runtime-v1"), 4, 12,
                availability);
    }
}
