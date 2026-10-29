package com.dvarahq.agentworks.context;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static com.dvarahq.agentworks.context.ContextDecision.Action.EXCLUDED;
import static com.dvarahq.agentworks.context.ContextDecision.Action.INCLUDED;

/** Deterministic OSS compiler: AOT cache, JIT assembly, exact dedupe, ranking, budgets, and explainability. */
public final class BasicContextCompiler implements ContextCompiler {
    private static final Comparator<ContextItem> RANK = Comparator
            .comparing(ContextItem::mandatory).reversed()
            .thenComparing(ContextItem::priority, Comparator.reverseOrder())
            .thenComparing(ContextItem::relevance, Comparator.reverseOrder())
            .thenComparing(ContextItem::cacheStability, Comparator.reverseOrder())
            .thenComparing(ContextItem::id);

    private final ContextCompilationCache cache;
    private final ContextCompactionStrategy compaction;

    public BasicContextCompiler(ContextCompilationCache cache, ContextCompactionStrategy compaction) {
        this.cache = Objects.requireNonNull(cache, "cache");
        this.compaction = Objects.requireNonNull(compaction, "compaction");
    }

    @Override
    public CompiledContext compile(ContextCompilationRequest request) {
        Objects.requireNonNull(request, "request");
        validateIds(request.stableItems(), request.dynamicItems());
        int mandatoryDynamic = request.dynamicItems().stream().filter(ContextItem::mandatory)
                .mapToInt(ContextItem::estimatedTokens).sum();
        int additionalReservations = request.budget().reservedTokens().entrySet().stream()
                .mapToInt(entry -> Math.max(0, Math.min(entry.getValue(), request.dynamicItems().stream()
                        .filter(item -> item.type() == entry.getKey()).mapToInt(ContextItem::estimatedTokens).sum())
                        - request.dynamicItems().stream().filter(ContextItem::mandatory)
                                .filter(item -> item.type() == entry.getKey())
                                .mapToInt(ContextItem::estimatedTokens).sum()))
                .sum();
        int stableLimit = request.budget().maximumTokens() - mandatoryDynamic - additionalReservations;
        if (stableLimit < 0) throw overflow();

        ContextCompilationCache.Lookup stable = request.cacheAware()
                ? cache.getOrCompile(request.cacheKey(), stableLimit, () -> compileStable(request, stableLimit))
                : new ContextCompilationCache.Lookup(compileStable(request, stableLimit), false);
        int remaining = request.budget().maximumTokens() - stable.entry().tokens();
        DynamicCompilation dynamic = compileDynamic(request, remaining, stable.entry().decisions());
        List<ContextDecision> decisions = new ArrayList<>(stable.entry().decisions());
        decisions.addAll(dynamic.decisions());
        return new CompiledContext(stable.entry().prefix(), render(dynamic.included()),
                stable.entry().tokens() + dynamic.tokens(), stable.hit(), dynamic.compacted(), decisions);
    }

    private ContextCompilationCache.Entry compileStable(ContextCompilationRequest request, int limit) {
        Selection selection = select(request, request.stableItems(), limit, Set.of(), true);
        return new ContextCompilationCache.Entry(render(selection.included()), selection.tokens(), selection.decisions());
    }

    private DynamicCompilation compileDynamic(ContextCompilationRequest request, int limit,
            List<ContextDecision> stableDecisions) {
        Set<String> priorDigests = new HashSet<>();
        stableDecisions.stream().filter(decision -> decision.action() == INCLUDED)
                .map(decision -> decision.provenance().contentDigest()).forEach(priorDigests::add);
        Selection selected = select(request, request.dynamicItems(), limit, priorDigests, false);
        boolean compacted = false;
        if (request.compactWhenNeeded() && selected.decisions().stream()
                .anyMatch(decision -> decision.action() == EXCLUDED && decision.reason().equals("BUDGET"))) {
            List<ContextItem> eligible = request.dynamicItems().stream()
                    .filter(item -> eligible(request, item, false) == null)
                    .filter(item -> !priorDigests.contains(item.provenance().contentDigest())).toList();
            List<ContextItem> compactedItems = compaction.compact(eligible, limit);
            if (!compactedItems.equals(eligible)) {
                Selection compactedSelection = select(request, compactedItems, limit, priorDigests, false);
                Set<String> retained = compactedItems.stream().map(ContextItem::id)
                        .collect(java.util.stream.Collectors.toSet());
                List<ContextDecision> decisions = new ArrayList<>();
                selected.decisions().stream()
                        .filter(decision -> decision.action() == EXCLUDED && !decision.reason().equals("BUDGET"))
                        .forEach(decisions::add);
                eligible.stream().filter(item -> !retained.contains(item.id()))
                        .map(item -> decision(item, ContextDecision.Action.COMPACTED, compaction.id()))
                        .forEach(decisions::add);
                decisions.addAll(compactedSelection.decisions());
                decisions.sort(Comparator.comparing(ContextDecision::itemId));
                selected = new Selection(compactedSelection.included(), compactedSelection.tokens(),
                        List.copyOf(decisions));
                compacted = true;
            }
        }
        return new DynamicCompilation(selected.included(), selected.tokens(), selected.decisions(), compacted);
    }

    private Selection select(ContextCompilationRequest request, List<ContextItem> candidates, int limit,
            Set<String> priorDigests, boolean stable) {
        List<ContextDecision> decisions = new ArrayList<>();
        List<ContextItem> eligible = new ArrayList<>();
        Set<String> digests = new HashSet<>(priorDigests);
        for (ContextItem item : candidates) {
            String refusal = eligible(request, item, stable);
            if (refusal == null && !digests.add(item.provenance().contentDigest())) refusal = "EXACT_DUPLICATE";
            if (refusal == null) eligible.add(item);
            else decisions.add(decision(item, EXCLUDED, refusal));
        }
        eligible.sort(RANK);
        List<ContextItem> included = new ArrayList<>();
        int used = 0;
        List<ContextItem> ordered = new ArrayList<>();
        eligible.stream().filter(ContextItem::mandatory).forEach(ordered::add);
        if (!stable) {
            request.budget().reservedTokens().keySet().stream().sorted().forEach(type -> {
                int selectedTokens = ordered.stream().filter(item -> item.type() == type)
                        .mapToInt(ContextItem::estimatedTokens).sum();
                for (ContextItem item : eligible) {
                    if (selectedTokens >= request.budget().reservedTokens().get(type)) break;
                    if (!item.mandatory() && item.type() == type) {
                        ordered.add(item);
                        selectedTokens += item.estimatedTokens();
                    }
                }
            });
        }
        eligible.stream().filter(item -> !ordered.contains(item)).forEach(ordered::add);
        for (ContextItem item : ordered) {
            if (used + item.estimatedTokens() <= limit) {
                included.add(item);
                used += item.estimatedTokens();
                decisions.add(decision(item, INCLUDED, stable ? "AOT_STABLE" : "JIT_DYNAMIC"));
            } else if (item.mandatory()) {
                throw overflow();
            } else {
                decisions.add(decision(item, EXCLUDED, "BUDGET"));
            }
        }
        included.sort(Comparator.comparing(ContextItem::id));
        decisions.sort(Comparator.comparing(ContextDecision::itemId));
        return new Selection(List.copyOf(included), used, List.copyOf(decisions));
    }

    private static String eligible(ContextCompilationRequest request, ContextItem item, boolean stable) {
        if (!item.constraints().workspaceId().equals(request.workspaceId())) return "TENANT_MISMATCH";
        if (item.constraints().redacted()) return "REDACTED";
        if (item.constraints().classification().ordinal() > request.maximumClassification().ordinal()) {
            return "CLASSIFICATION_DENIED";
        }
        if (!item.constraints().destinations().contains(request.destination())) return "DESTINATION_DENIED";
        if (stable && !item.constraints().destinations().contains(ContextDestination.CACHE)) return "CACHE_DENIED";
        return switch (item.loadingPolicy()) {
            case ALWAYS -> null;
            case NEVER_INLINE -> "NEVER_INLINE";
            case ON_DEMAND -> requested(request.demands(), item.id(), "item");
            case SEMANTIC -> requested(request.demands(), item.id(), "semantic");
            case TOOL_TRIGGERED -> requested(request.demands(), item.id(), "tool");
            case SKILL_TRIGGERED -> requested(request.demands(), item.id(), "skill");
        };
    }

    private static String requested(Set<String> demands, String id, String prefix) {
        return demands.contains(id) || demands.contains(prefix + ":" + id) ? null : "LOADING_POLICY";
    }

    private static ContextDecision decision(ContextItem item, ContextDecision.Action action, String reason) {
        return new ContextDecision(item.id(), item.type(), action, reason, item.estimatedTokens(), item.provenance(),
                item.constraints().classification());
    }

    private static String render(List<ContextItem> items) {
        StringBuilder value = new StringBuilder();
        for (ContextItem item : items) {
            if (!value.isEmpty()) value.append("\n\n");
            value.append("[").append(item.type()).append(':').append(item.id()).append("]\n").append(item.content());
        }
        return value.toString();
    }

    private static void validateIds(List<ContextItem> stable, List<ContextItem> dynamic) {
        Map<String, ContextItem> ids = new LinkedHashMap<>();
        for (ContextItem item : concat(stable, dynamic)) {
            if (ids.putIfAbsent(item.id(), item) != null) {
                throw new ContextCompilationException("CONTEXT_ITEM_DUPLICATE_ID", "context item ids must be unique");
            }
        }
    }

    private static List<ContextItem> concat(List<ContextItem> first, List<ContextItem> second) {
        List<ContextItem> result = new ArrayList<>(first);
        result.addAll(second);
        return result;
    }

    private static ContextCompilationException overflow() {
        return new ContextCompilationException("CONTEXT_MANDATORY_OVERFLOW", "mandatory context exceeds the budget");
    }

    @Override public int retire(UUID workspaceId, UUID releaseId, UUID deploymentRevisionId) {
        return cache.retire(workspaceId, releaseId, deploymentRevisionId);
    }

    private record Selection(List<ContextItem> included, int tokens, List<ContextDecision> decisions) {}
    private record DynamicCompilation(List<ContextItem> included, int tokens, List<ContextDecision> decisions,
            boolean compacted) {}
}
