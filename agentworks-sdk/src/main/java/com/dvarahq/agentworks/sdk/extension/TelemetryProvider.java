package com.dvarahq.agentworks.sdk.extension;

import java.time.Instant;
import java.util.Map;

/** Content-free telemetry extension; secret and model content are forbidden attributes. */
public interface TelemetryProvider {
    String id();
    void record(ProviderInvocationContext context, Observation observation);

    record Observation(String name, Instant at, Map<String, String> attributes) {
        public Observation {
            if (name == null || name.isBlank()) throw new IllegalArgumentException("name");
            if (at == null) throw new IllegalArgumentException("at");
            attributes = Map.copyOf(attributes);
        }
    }
}
