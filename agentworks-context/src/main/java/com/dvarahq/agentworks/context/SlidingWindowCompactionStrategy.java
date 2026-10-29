package com.dvarahq.agentworks.context;

import java.util.ArrayList;
import java.util.List;

/** Keeps all mandatory state and the newest optional items that fit. Input order is chronological. */
public final class SlidingWindowCompactionStrategy implements ContextCompactionStrategy {
    @Override public String id() { return "sliding-window"; }

    @Override
    public List<ContextItem> compact(List<ContextItem> dynamicItems, int targetTokens) {
        List<ContextItem> mandatory = dynamicItems.stream().filter(ContextItem::mandatory).toList();
        int used = mandatory.stream().mapToInt(ContextItem::estimatedTokens).sum();
        if (used > targetTokens) throw new ContextCompilationException("CONTEXT_MANDATORY_OVERFLOW",
                "mandatory dynamic context exceeds the available budget");
        List<ContextItem> selectedNewestFirst = new ArrayList<>();
        for (int index = dynamicItems.size() - 1; index >= 0; index--) {
            ContextItem item = dynamicItems.get(index);
            if (item.mandatory()) continue;
            if (used + item.estimatedTokens() <= targetTokens) {
                selectedNewestFirst.add(item);
                used += item.estimatedTokens();
            }
        }
        List<ContextItem> selected = new ArrayList<>();
        for (ContextItem item : dynamicItems) {
            if (item.mandatory() || selectedNewestFirst.contains(item)) selected.add(item);
        }
        return List.copyOf(selected);
    }
}
