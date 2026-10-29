package com.dvarahq.agentworks.capability;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Model-facing projection. Summary entries cannot expose schemas; only DETAIL decisions can. */
public record CapabilityDisclosure(List<Summary> summaries, List<Detail> details) {
    public record Summary(ToolId id, ToolType type, String summary, String schemaHash) {}
    public record Detail(ToolId id, ToolType type, String summary, String description, String inputSchema,
            String schemaHash) {}

    public CapabilityDisclosure {
        summaries = List.copyOf(Objects.requireNonNull(summaries, "summaries"));
        details = List.copyOf(Objects.requireNonNull(details, "details"));
    }

    public static CapabilityDisclosure from(CapabilityResolution resolution, List<AgentTool> catalog) {
        return from(resolution, catalog, catalog);
    }

    public static CapabilityDisclosure from(CapabilityResolution resolution,
            List<? extends ToolMetadata> summaryCatalog, List<AgentTool> detailsCatalog) {
        Objects.requireNonNull(resolution, "resolution");
        Map<ToolId, ToolMetadata> metadata = new HashMap<>();
        for (ToolMetadata tool : summaryCatalog) {
            if (metadata.putIfAbsent(tool.id(), tool) != null) {
                throw new IllegalArgumentException("duplicate tool identity: " + tool.id());
            }
        }
        Map<ToolId, AgentTool> detailById = new HashMap<>();
        for (AgentTool tool : detailsCatalog) {
            if (detailById.putIfAbsent(tool.id(), tool) != null) {
                throw new IllegalArgumentException("duplicate tool detail identity: " + tool.id());
            }
        }
        List<Summary> summaryOutput = new ArrayList<>();
        List<Detail> detailOutput = new ArrayList<>();
        for (CapabilityResolution.Decision decision : resolution.decisions()) {
            if (decision.disclosure() == CapabilityResolution.Disclosure.NONE) continue;
            ToolMetadata tool = Objects.requireNonNull(metadata.get(decision.tool()),
                    "resolved tool missing from catalog");
            if (!tool.schemaHash().equals(decision.schemaHash())) {
                throw new IllegalArgumentException("resolved schema changed for " + decision.tool().value());
            }
            if (decision.disclosure() == CapabilityResolution.Disclosure.SUMMARY) {
                summaryOutput.add(new Summary(tool.id(), tool.type(), tool.summary(), tool.schemaHash()));
            } else {
                AgentTool detail = Objects.requireNonNull(detailById.get(tool.id()),
                        "selected tool detail was not loaded");
                if (!detail.schemaHash().equals(tool.schemaHash())) {
                    throw new IllegalArgumentException("resolved schema changed for " + decision.tool().value());
                }
                detailOutput.add(new Detail(tool.id(), tool.type(), tool.summary(), detail.description(),
                        detail.inputSchema(), tool.schemaHash()));
            }
        }
        return new CapabilityDisclosure(summaryOutput, detailOutput);
    }
}
