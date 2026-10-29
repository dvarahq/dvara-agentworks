package com.dvarahq.agentworks.sdk.stream;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Objects;

/** Typed SSE item. Run events carry an exact positive sequence; control events carry sequence zero. */
public record TypedRunEvent(Kind kind, String eventName, String runId, long sequence,
                            JsonNode payload, Instant receivedAt) {
    public enum Kind { RUN_EVENT, STREAM_END, RECONNECT, STREAM_ERROR }

    public TypedRunEvent {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(eventName, "eventName");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(receivedAt, "receivedAt");
        if (kind == Kind.RUN_EVENT && sequence <= 0) throw new IllegalArgumentException("run event sequence");
        if (kind != Kind.RUN_EVENT && sequence != 0) throw new IllegalArgumentException("control event sequence");
    }
}
