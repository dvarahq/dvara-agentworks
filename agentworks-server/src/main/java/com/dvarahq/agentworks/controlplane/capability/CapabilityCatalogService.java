package com.dvarahq.agentworks.controlplane.capability;

import com.dvarahq.agentworks.capability.AgentTool;
import com.dvarahq.agentworks.capability.CapabilityCatalogAdministration;
import com.dvarahq.agentworks.capability.CapabilityNotFoundException;
import com.dvarahq.agentworks.capability.CapabilityPack;
import com.dvarahq.agentworks.capability.CapabilityPackRegistry;
import com.dvarahq.agentworks.capability.ToolId;
import com.dvarahq.agentworks.capability.ToolRegistry;
import com.dvarahq.agentworks.capability.ToolSummary;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import com.dvarahq.agentworks.storage.TenantTransactionTemplate;
import com.dvarahq.agentworks.storage.lifecycle.DatabaseClock;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Transaction boundary for the capability catalog; persistence remains Spring JDBC-only. */
public final class CapabilityCatalogService {
    private final TenantTransactionTemplate transactions;
    private final ToolRegistry tools;
    private final CapabilityPackRegistry packs;
    private final CapabilityCatalogAdministration administration;
    private final DatabaseClock clock;

    public CapabilityCatalogService(TenantTransactionTemplate transactions, ToolRegistry tools,
            CapabilityPackRegistry packs, CapabilityCatalogAdministration administration, DatabaseClock clock) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.tools = Objects.requireNonNull(tools, "tools");
        this.packs = Objects.requireNonNull(packs, "packs");
        this.administration = Objects.requireNonNull(administration, "administration");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public AgentTool createTool(WorkspaceId workspace, AgentTool tool) {
        transactions.executeWithoutResult(workspace, () -> administration.insertTool(workspace, tool, clock.now()));
        return tool;
    }

    public ToolRegistry.Page search(WorkspaceId workspace, ToolRegistry.Search search) {
        return transactions.execute(workspace, () -> tools.search(workspace, search));
    }

    public AgentTool detail(WorkspaceId workspace, ToolId id) {
        List<AgentTool> found = transactions.execute(workspace, () -> tools.details(workspace, Set.of(id)));
        if (found.isEmpty()) throw new CapabilityNotFoundException("tool version");
        return found.getFirst();
    }

    public void transitionTool(WorkspaceId workspace, ToolId id, AgentTool.Availability state) {
        transactions.executeWithoutResult(workspace,
                () -> administration.transitionTool(workspace, id, state, clock.now()));
    }

    public CapabilityPack createPack(WorkspaceId workspace, CapabilityPack pack) {
        transactions.executeWithoutResult(workspace, () -> administration.insertPack(workspace, pack, clock.now()));
        return pack;
    }

    public CapabilityPack pack(WorkspaceId workspace, String id, String version) {
        return transactions.execute(workspace, () -> packs.get(workspace, id, version))
                .orElseThrow(() -> new CapabilityNotFoundException("capability pack"));
    }

    public void transitionPack(WorkspaceId workspace, String id, String version, CapabilityPack.State state) {
        transactions.executeWithoutResult(workspace,
                () -> administration.transitionPack(workspace, id, version, state, clock.now()));
    }
}
