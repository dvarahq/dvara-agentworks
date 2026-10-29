package com.dvarahq.agentworks.capability;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.Locale;

/** All inputs that can affect resolution are explicit and therefore capturable. */
public record CapabilityResolutionRequest(List<String> packIds, Set<String> intentTerms, Set<ToolId> policyDenied,
        Set<String> compatibility, Set<ToolId> detailTriggers, int summaryBudgetTokens, int detailBudgetTokens) {
    public CapabilityResolutionRequest {
        packIds = List.copyOf(Objects.requireNonNull(packIds, "packIds"));
        if (packIds.stream().anyMatch(id -> id == null || id.isBlank())
                || packIds.stream().distinct().count() != packIds.size()) {
            throw new IllegalArgumentException("pack identities must be unique and nonblank");
        }
        intentTerms = Objects.requireNonNull(intentTerms, "intentTerms").stream()
                .map(term -> Objects.requireNonNull(term, "intent term").strip().toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (intentTerms.stream().anyMatch(String::isBlank)) throw new IllegalArgumentException("blank intent term");
        policyDenied = Set.copyOf(Objects.requireNonNull(policyDenied, "policyDenied"));
        compatibility = Set.copyOf(Objects.requireNonNull(compatibility, "compatibility"));
        detailTriggers = Set.copyOf(Objects.requireNonNull(detailTriggers, "detailTriggers"));
        if (summaryBudgetTokens < 0 || detailBudgetTokens < 0) throw new IllegalArgumentException("negative token budget");
    }
}
