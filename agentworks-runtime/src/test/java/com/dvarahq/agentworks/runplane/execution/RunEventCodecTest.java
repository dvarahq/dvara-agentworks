package com.dvarahq.agentworks.runplane.execution;

import com.dvarahq.agentworks.domain.run.RunEvent;
import com.dvarahq.agentworks.domain.run.RunEventType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.dvarahq.agentworks.runplane.execution.DurableRunEngineTest.assertCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunEventCodecTest {
    private final RunEventCodec codec = RunEventCodec.standard();

    @Test
    void canonicalPayloadsSortKeysAndKeepOneNumberAndTextForm() {
        String canonical = codec.canonicalPayload("{ \"b\": [3, 1.50, 1e2], \"a\": {\"z\": \"é\\u00e9\", \"y\": null}, "
                + "\"c\": 12345678901234567890123 }");

        assertThat(canonical).startsWith("{\"a\":{\"y\":null,\"z\":\"éé\"},\"b\":[3,");
        assertThat(codec.canonicalPayload(canonical)).isEqualTo(canonical);
        // PostgreSQL JSONB re-formats a payload; its canonical form is unchanged.
        String jsonb = canonical.replace(":", ": ").replace(",", ", ");
        assertThat(codec.canonicalPayload(jsonb)).isEqualTo(canonical);
        assertThat(codec.canonicalPayload(Map.of("value", new java.math.BigDecimal("0.000001"))))
                .isEqualTo("{\"value\":0.000001}");
    }

    @Test
    void payloadBoundsAndShapesAreEnforced() {
        assertCode(() -> codec.canonicalPayload("[1,2]"), "INVALID_PAYLOAD");
        assertCode(() -> codec.canonicalPayload("{\"a\":1,\"a\":2}"), "INVALID_PAYLOAD");
        assertCode(() -> codec.canonicalPayload("{\"a\":\"nul\\u0000\"}"), "INVALID_PAYLOAD");
        assertCode(() -> codec.canonicalPayload("{\"a\":1} trailing"), "INVALID_PAYLOAD");
        assertCode(() -> codec.canonicalPayload(Map.of("big", "x".repeat(RunEventCodec.MAX_PAYLOAD_BYTES))),
                "PAYLOAD_TOO_LARGE");
        String deep = "{\"a\":".repeat(RunEventCodec.MAX_DEPTH) + "1" + "}".repeat(RunEventCodec.MAX_DEPTH);
        assertCode(() -> codec.canonicalPayload(deep), "INVALID_PAYLOAD");
        assertCode(() -> codec.canonicalPayload(Map.of("value", Double.NaN)), "INVALID_PAYLOAD");
        assertCode(() -> codec.canonicalPayload(Map.of("value", Double.POSITIVE_INFINITY)), "INVALID_PAYLOAD");
        assertCode(() -> codec.canonicalPayload(Map.of("value", "x\uD800y")), "INVALID_PAYLOAD");
        assertCode(() -> codec.canonicalPayload("{\"value\":\"x\\uDC00\"}"), "INVALID_PAYLOAD");
        assertThat(codec.canonicalPayload(Map.of("emoji", "😀"))).contains("😀");
        assertCode(() -> RunEventCodec.traceId("0".repeat(32)), "INVALID_EVENT");
        assertCode(() -> RunEventCodec.identity("worker\nforged", "owner"), "INVALID_EVENT");
    }

    @Test
    void theDigestChainDetectsGapsReorderingAndTampering() {
        List<RunEvent> history = history(3);
        assertThat(codec.decodeHistory(history)).hasSize(3);

        assertCode(() -> codec.decodeHistory(List.of(history.get(0), history.get(2))), "CORRUPT_HISTORY");
        assertCode(() -> codec.decodeHistory(List.of(history.get(1), history.get(0), history.get(2))), "CORRUPT_HISTORY");
        RunEvent original = history.get(1);
        RunEvent edited = new RunEvent(original.id(), original.workspaceId(), original.runId(), original.sequence(),
                original.type(), original.schemaVersion(), original.payloadJson(), original.payloadDigest(),
                original.eventDigest(), original.traceId(), original.spanId(), original.releaseId(),
                original.deploymentId(), original.revisionId(), original.cohortId(), "worker:forged",
                original.leaseOwner(), original.leaseFence(), original.createdAt());
        assertCode(() -> codec.decodeHistory(List.of(history.get(0), edited, history.get(2))), "CORRUPT_HISTORY");
    }

    @Test
    void olderPayloadVersionsAreUpcastOnReadAndUnknownVersionsFailSafely() {
        RunEvent v1 = history(2).get(1);
        EventSchemas upgraded = EventSchemas.builder().current(RunEventType.MODEL_RESPONSE_RECEIVED, 2)
                .upcaster(RunEventType.MODEL_RESPONSE_RECEIVED, 1, payload -> payload.put("finishReason", "UNKNOWN"))
                .build();

        var lifted = new RunEventCodec(upgraded).decode(v1);
        assertThat(lifted.payload().path("finishReason").stringValue()).isEqualTo("UNKNOWN");
        assertThat(lifted.payload().path("model").stringValue()).isEqualTo("coding-premium");
        // A node that only reads version 1 refuses a version 2 event instead of guessing.
        RunEvent v2 = rewrite(v1, 2);
        assertCode(() -> codec.decode(v2), "UNSUPPORTED_EVENT_VERSION");
        EventSchemas gap = EventSchemas.builder().current(RunEventType.MODEL_RESPONSE_RECEIVED, 3)
                .upcaster(RunEventType.MODEL_RESPONSE_RECEIVED, 2, payload -> payload).build();
        assertCode(() -> new RunEventCodec(gap).decode(v1), "UNSUPPORTED_EVENT_VERSION");
    }

    @Test
    void unknownEventTypesFailSafely() {
        RunEvent event = history(1).getFirst();
        RunEvent unknown = new RunEvent(event.id(), event.workspaceId(), event.runId(), 1, "RUN_TELEPORTED", 1,
                event.payloadJson(), event.payloadDigest(), event.eventDigest(), event.traceId(), event.spanId(),
                event.releaseId(), event.deploymentId(), event.revisionId(), event.cohortId(), event.actor(),
                event.leaseOwner(), event.leaseFence(), event.createdAt());
        assertCode(() -> codec.decode(unknown), "UNKNOWN_EVENT_TYPE");
        assertThatThrownBy(() -> RunEventType.valueOf("RUN_TELEPORTED")).isInstanceOf(IllegalArgumentException.class);
    }

    /** A chained history: RUN_CREATED then model responses. */
    private List<RunEvent> history(int size) {
        UUID workspace = UUID.randomUUID();
        UUID run = UUID.randomUUID();
        List<RunEvent> events = new java.util.ArrayList<>();
        String previous = null;
        for (int sequence = 1; sequence <= size; sequence++) {
            String type = sequence == 1 ? "RUN_CREATED" : "MODEL_RESPONSE_RECEIVED";
            String payload = codec.canonicalPayload(Map.of("model", "coding-premium", "sequence", sequence));
            RunEvent unsigned = new RunEvent(UUID.randomUUID(), workspace, run, sequence, type, 1, payload,
                    RunEventCodec.sha256(payload), RunEventCodec.GENESIS, "0af7651916cd43dd8448eb211c80319c",
                    "b7ad6b7169203331", UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                    "worker:a", "a", 1, Instant.parse("2026-09-25T10:00:00Z").plusSeconds(sequence));
            RunEvent signed = rewriteDigest(unsigned, RunEventCodec.eventDigest(previous, unsigned));
            events.add(signed);
            previous = signed.eventDigest();
        }
        return events;
    }

    private static RunEvent rewrite(RunEvent event, int version) {
        return new RunEvent(event.id(), event.workspaceId(), event.runId(), event.sequence(), event.type(), version,
                event.payloadJson(), event.payloadDigest(), event.eventDigest(), event.traceId(), event.spanId(),
                event.releaseId(), event.deploymentId(), event.revisionId(), event.cohortId(), event.actor(),
                event.leaseOwner(), event.leaseFence(), event.createdAt());
    }

    private static RunEvent rewriteDigest(RunEvent event, String digest) {
        return new RunEvent(event.id(), event.workspaceId(), event.runId(), event.sequence(), event.type(),
                event.schemaVersion(), event.payloadJson(), event.payloadDigest(), digest, event.traceId(),
                event.spanId(), event.releaseId(), event.deploymentId(), event.revisionId(), event.cohortId(),
                event.actor(), event.leaseOwner(), event.leaseFence(), event.createdAt());
    }
}
