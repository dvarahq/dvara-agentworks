package com.dvarahq.agentworks.capability;

import java.util.List;
import java.util.Objects;

/** Explainable resolver output; excluded tools never enter model context. */
public record CapabilityResolution(String resolverVersion, List<PackDecision> packs, List<Decision> decisions,
        int summaryTokens, int detailTokens) {
    public enum PackReason { INCLUDED, UNAVAILABLE, NOT_ACTIVE, DISABLED, DENIED }
    public record PackDecision(String pack, boolean included, PackReason reason) {
        public PackDecision {
            Objects.requireNonNull(pack, "pack");
            Objects.requireNonNull(reason, "reason");
        }
    }
    public enum Disclosure { NONE, SUMMARY, DETAIL }
    public enum Reason { INCLUDED, TOOL_UNAVAILABLE, TOOL_NOT_ACTIVE, TOOL_DISABLED, TOOL_DENIED, POLICY_DENIED,
        INCOMPATIBLE, SUMMARY_BUDGET, DETAIL_BUDGET, NOT_TRIGGERED }
    public record Decision(ToolId tool, String pack, Disclosure disclosure, Reason reason, int relevance,
            String schemaHash) {
        public Decision {
            Objects.requireNonNull(tool, "tool"); Objects.requireNonNull(pack, "pack");
            Objects.requireNonNull(disclosure, "disclosure"); Objects.requireNonNull(reason, "reason");
            if ((reason == Reason.TOOL_UNAVAILABLE) == (schemaHash != null)) {
                throw new IllegalArgumentException("only a missing tool may omit its schema hash");
            }
        }
    }
    public CapabilityResolution {
        Objects.requireNonNull(resolverVersion, "resolverVersion");
        packs = List.copyOf(Objects.requireNonNull(packs, "packs"));
        decisions = List.copyOf(Objects.requireNonNull(decisions, "decisions"));
        if (summaryTokens < 0 || detailTokens < 0) throw new IllegalArgumentException("negative token total");
    }
}
