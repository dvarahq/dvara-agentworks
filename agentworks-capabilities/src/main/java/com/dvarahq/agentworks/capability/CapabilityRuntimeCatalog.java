package com.dvarahq.agentworks.capability;

import com.dvarahq.agentworks.domain.tenant.WorkspaceId;

import java.util.List;
import java.util.Objects;

/**
 * Run-plane read of the exact capability evidence pinned by a release. Unlike discovery, this read includes current
 * lifecycle state so a pack or tool disabled after release construction is excluded from model context.
 */
public interface CapabilityRuntimeCatalog {
    Snapshot snapshot(WorkspaceId workspace, List<PinnedCapabilityPack> pinned);

    /** Loads full schema-bearing metadata only for identities the resolver selected for DETAIL disclosure. */
    default List<AgentTool> details(WorkspaceId workspace, List<ToolId> selected) {
        return List.of();
    }

    record Snapshot(List<CapabilityPack> packs, List<ToolSummary> tools) {
        public Snapshot {
            packs = List.copyOf(Objects.requireNonNull(packs, "packs"));
            tools = List.copyOf(Objects.requireNonNull(tools, "tools"));
        }
    }
}
