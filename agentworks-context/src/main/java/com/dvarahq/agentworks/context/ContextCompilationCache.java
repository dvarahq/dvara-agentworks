package com.dvarahq.agentworks.context;

import java.util.Map;
import java.util.UUID;
import java.util.HashMap;
import java.util.function.Supplier;

/** Tenant- and exact-revision-scoped, bounded AOT cache. Entries contain no JIT/run/session material. */
public final class ContextCompilationCache implements AutoCloseable {
    public record Entry(String prefix, int tokens, java.util.List<ContextDecision> decisions) {}
    public record Lookup(Entry entry, boolean hit) {}
    private record Slot(ContextCacheKey key, int stableTokenLimit) {}

    private final int maximumEntries;
    private final Map<Slot, Entry> entries = new HashMap<>();

    public ContextCompilationCache(int maximumEntries) {
        if (maximumEntries < 1 || maximumEntries > 100_000) throw new IllegalArgumentException("invalid maximumEntries");
        this.maximumEntries = maximumEntries;
    }

    public synchronized Lookup getOrCompile(ContextCacheKey key, int stableTokenLimit, Supplier<Entry> compiler) {
        if (stableTokenLimit < 0) throw new IllegalArgumentException("stableTokenLimit must not be negative");
        Slot slot = new Slot(key, stableTokenLimit);
        Entry existing = entries.get(slot);
        if (existing != null) return new Lookup(existing, true);
        Entry created = compiler.get();
        if (entries.size() >= maximumEntries) throw new ContextCompilationException("CONTEXT_CACHE_FULL",
                "the bounded context cache is full; retire an exact revision before retrying");
        entries.put(slot, created);
        return new Lookup(created, false);
    }

    public synchronized int retire(UUID workspaceId, UUID releaseId, UUID revisionId) {
        int removed = 0;
        var iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Slot, Entry> entry = iterator.next();
            ContextCacheKey key = entry.getKey().key();
            if (key.workspaceId().equals(workspaceId) && key.releaseId().equals(releaseId)
                    && key.deploymentRevisionId().equals(revisionId)) {
                iterator.remove();
                removed++;
            }
        }
        return removed;
    }

    public synchronized int size() { return entries.size(); }
    @Override public synchronized void close() { entries.clear(); }
}
