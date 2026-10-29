package com.dvarahq.agentworks.testing.fake;

import java.util.*;
import java.util.function.Function;

/** Correlation-preserving deterministic peer used in place of an external A2A executor. */
public final class FakeA2aPeer {
    public record Request(String correlationId, String input, long seed) {
        public Request { if (correlationId == null || correlationId.isBlank()) throw new IllegalArgumentException("correlationId"); }
    }
    private final String protocolVersion;
    private final Function<Request, String> handler;
    private final Set<String> correlations = new HashSet<>();
    public FakeA2aPeer(String protocolVersion, Function<Request, String> handler) {
        if (protocolVersion == null || protocolVersion.isBlank()) throw new IllegalArgumentException("protocolVersion");
        this.protocolVersion = protocolVersion; this.handler = Objects.requireNonNull(handler);
    }
    public synchronized String invoke(Request request) {
        if (!correlations.add(request.correlationId())) throw new IllegalStateException("A2A_REPLAYED_CORRELATION");
        return Objects.requireNonNull(handler.apply(request));
    }
    public String protocolVersion() { return protocolVersion; }
    public FakeA2aPeer fresh() { return new FakeA2aPeer(protocolVersion, handler); }
}
