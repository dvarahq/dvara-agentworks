package com.dvarahq.agentworks.runplane.compiled;

import com.dvarahq.agentworks.domain.agent.compiled.CompiledAgentDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Immutable executable release material and handles owned exclusively by it. Shared clients are deliberately absent:
 * they belong to {@link SharedResourceRegistry} and are leased independently.
 */
public final class CompiledRelease implements AutoCloseable {
    private final CompiledAgentDefinition definition;
    private final List<? extends AutoCloseable> ownedHandles;
    private final boolean[] handleClosed;
    private volatile boolean closed;

    public CompiledRelease(CompiledAgentDefinition definition, List<? extends AutoCloseable> ownedHandles) {
        this.definition = Objects.requireNonNull(definition, "definition");
        this.ownedHandles = List.copyOf(Objects.requireNonNull(ownedHandles, "ownedHandles"));
        if (this.ownedHandles.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("ownedHandles contains null");
        }
        this.handleClosed = new boolean[this.ownedHandles.size()];
    }

    public CompiledAgentDefinition definition() {
        if (closed) throw new IllegalStateException("COMPILED_RELEASE_RETIRED");
        return definition;
    }

    public boolean closed() { return closed; }

    @Override
    public synchronized void close() throws Exception {
        if (closed) return;
        List<Exception> failures = new ArrayList<>();
        for (int index = ownedHandles.size() - 1; index >= 0; index--) {
            if (handleClosed[index]) continue;
            try { ownedHandles.get(index).close(); handleClosed[index] = true; }
            catch (Exception failure) { failures.add(failure); }
        }
        closed = failures.isEmpty();
        if (!failures.isEmpty()) {
            Exception combined = new Exception("one or more compiled-release handles could not be closed");
            failures.forEach(combined::addSuppressed);
            throw combined;
        }
    }
}
