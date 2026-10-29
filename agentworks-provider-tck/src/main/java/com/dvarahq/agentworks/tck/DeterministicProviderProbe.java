package com.dvarahq.agentworks.tck;

import com.dvarahq.agentworks.sdk.extension.ProviderInvocationContext;

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Public deterministic fake illustrating the provider lifecycle and exact-context rules. */
public final class DeterministicProviderProbe implements ProviderProbe {
    private final boolean ready;
    private final Set<String> capabilities;
    private final AtomicBoolean closed = new AtomicBoolean();

    public DeterministicProviderProbe(boolean ready, Set<String> capabilities) {
        this.ready = ready;
        this.capabilities = Set.copyOf(capabilities);
    }
    @Override public Set<String> capabilities() { return capabilities; }
    @Override public boolean ready() { return ready && !closed.get(); }
    @Override public boolean closed() { return closed.get(); }
    @Override public ProviderObservation invoke(ProviderInvocationContext context, byte[] boundedInput) {
        if (closed.get()) throw new IllegalStateException("PROVIDER_CLOSED");
        ProviderObservation.Outcome outcome = !ready ? ProviderObservation.Outcome.UNAVAILABLE
                : context.cancellation().cancelled() ? ProviderObservation.Outcome.CANCELLED
                : ProviderObservation.Outcome.COMPLETED;
        return new ProviderObservation(context.workspaceId(), context.releaseId(), context.deploymentRevisionId(),
                context.cohortId(), context.runId(), outcome, outcome.name());
    }
    @Override public void close() { closed.set(true); }
}
