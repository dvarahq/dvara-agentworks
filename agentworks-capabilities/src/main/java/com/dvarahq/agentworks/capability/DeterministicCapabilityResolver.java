package com.dvarahq.agentworks.capability;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Resolver v1: policy and compatibility first, then deterministic relevance and explicit token budgets. */
public final class DeterministicCapabilityResolver {
    public static final String VERSION = "capability-resolver/v1";

    public CapabilityResolution resolve(CapabilityResolutionRequest request, List<CapabilityPack> packs,
            List<? extends ToolMetadata> tools) {
        Objects.requireNonNull(request, "request");
        Map<String, CapabilityPack> byPack = new HashMap<>();
        packs.forEach(pack -> requireUnique(byPack, pack.identity(), pack, "pack"));
        Map<ToolId, ToolMetadata> byTool = new HashMap<>();
        tools.forEach(tool -> requireUnique(byTool, tool.id(), tool, "tool"));

        List<Candidate> candidates = new ArrayList<>();
        List<CapabilityResolution.PackDecision> packDecisions = new ArrayList<>();
        List<CapabilityResolution.Decision> decisions = new ArrayList<>();
        Set<ToolId> seen = new HashSet<>();
        for (String packId : request.packIds().stream().sorted().toList()) {
            CapabilityPack pack = byPack.get(packId);
            if (pack == null) {
                packDecisions.add(new CapabilityResolution.PackDecision(packId, false,
                        CapabilityResolution.PackReason.UNAVAILABLE));
                continue;
            }
            if (pack.state() != CapabilityPack.State.ACTIVE) {
                packDecisions.add(new CapabilityResolution.PackDecision(packId, false,
                        switch (pack.state()) {
                            case AVAILABLE -> CapabilityResolution.PackReason.NOT_ACTIVE;
                            case DISABLED -> CapabilityResolution.PackReason.DISABLED;
                            case DENIED -> CapabilityResolution.PackReason.DENIED;
                            case ACTIVE -> throw new IllegalStateException("active pack excluded");
                        }));
                continue;
            }
            packDecisions.add(new CapabilityResolution.PackDecision(packId, true,
                    CapabilityResolution.PackReason.INCLUDED));
            for (ToolId id : pack.tools().stream().sorted().toList()) {
                if (!seen.add(id)) continue;
                ToolMetadata tool = byTool.get(id);
                if (tool == null) {
                    decisions.add(decision(id, packId, CapabilityResolution.Reason.TOOL_UNAVAILABLE, 0, null));
                } else if (tool.availability() != AgentTool.Availability.ACTIVE) {
                    CapabilityResolution.Reason reason = switch (tool.availability()) {
                        case AVAILABLE -> CapabilityResolution.Reason.TOOL_NOT_ACTIVE;
                        case DISABLED -> CapabilityResolution.Reason.TOOL_DISABLED;
                        case DENIED -> CapabilityResolution.Reason.TOOL_DENIED;
                        case ACTIVE -> throw new IllegalStateException("active tool excluded");
                    };
                    decisions.add(decision(id, packId, reason, 0, tool.schemaHash()));
                } else if (request.policyDenied().contains(id)) {
                    decisions.add(decision(id, packId, CapabilityResolution.Reason.POLICY_DENIED, 0, tool.schemaHash()));
                } else if (!request.compatibility().containsAll(tool.compatibility())) {
                    decisions.add(decision(id, packId, CapabilityResolution.Reason.INCOMPATIBLE, 0, tool.schemaHash()));
                } else {
                    candidates.add(new Candidate(packId, tool, relevance(tool, request.intentTerms())));
                }
            }
        }
        candidates.sort(Comparator.comparingInt(Candidate::relevance).reversed()
                .thenComparing(candidate -> candidate.tool().id()));
        int summary = 0, detail = 0;
        for (Candidate candidate : candidates) {
            ToolMetadata tool = candidate.tool();
            if (summary + tool.summaryTokens() > request.summaryBudgetTokens()) {
                decisions.add(decision(tool.id(), candidate.pack(), CapabilityResolution.Reason.SUMMARY_BUDGET,
                        candidate.relevance(), tool.schemaHash()));
                continue;
            }
            summary += tool.summaryTokens();
            if (!request.detailTriggers().contains(tool.id())) {
                decisions.add(new CapabilityResolution.Decision(tool.id(), candidate.pack(),
                        CapabilityResolution.Disclosure.SUMMARY, CapabilityResolution.Reason.NOT_TRIGGERED,
                        candidate.relevance(), tool.schemaHash()));
            } else if (detail + tool.detailTokens() > request.detailBudgetTokens()) {
                decisions.add(new CapabilityResolution.Decision(tool.id(), candidate.pack(),
                        CapabilityResolution.Disclosure.SUMMARY, CapabilityResolution.Reason.DETAIL_BUDGET,
                        candidate.relevance(), tool.schemaHash()));
            } else {
                detail += tool.detailTokens();
                decisions.add(new CapabilityResolution.Decision(tool.id(), candidate.pack(),
                        CapabilityResolution.Disclosure.DETAIL, CapabilityResolution.Reason.INCLUDED,
                        candidate.relevance(), tool.schemaHash()));
            }
        }
        decisions.sort(Comparator.comparing(decision -> decision.tool().value()));
        return new CapabilityResolution(VERSION, packDecisions, decisions, summary, detail);
    }

    private static CapabilityResolution.Decision decision(ToolId id, String pack,
            CapabilityResolution.Reason reason, int relevance, String hash) {
        return new CapabilityResolution.Decision(id, pack, CapabilityResolution.Disclosure.NONE, reason, relevance, hash);
    }

    private static int relevance(ToolMetadata tool, Set<String> terms) {
        String text = (tool.id().name() + " " + tool.summary() + " " + String.join(" ", tool.tags()))
                .toLowerCase(Locale.ROOT);
        return (int) terms.stream().filter(text::contains).count();
    }

    private static <K, V> void requireUnique(Map<K, V> values, K key, V value, String kind) {
        if (values.putIfAbsent(key, value) != null) throw new IllegalArgumentException("duplicate " + kind + " identity: " + key);
    }

    private record Candidate(String pack, ToolMetadata tool, int relevance) {}
}
