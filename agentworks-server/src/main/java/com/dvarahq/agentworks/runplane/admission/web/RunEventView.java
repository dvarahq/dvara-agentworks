package com.dvarahq.agentworks.runplane.admission.web;

import com.dvarahq.agentworks.runplane.execution.EventSchemas;
import com.dvarahq.agentworks.runplane.execution.RunEventCodec.DecodedEvent;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/**
 * The typed run event envelope (issue #35), identical in {@code GET /runs/{id}/events} pages and in the
 * {@code GET /runs/{id}/stream} SSE {@code data} field. Every event names its run, workspace, release, deployment,
 * deployment revision, runtime cohort, and trace, and carries its gapless per-run {@code sequence} and the chained
 * {@code eventDigest}.
 *
 * <ul>
 *   <li>{@code envelopeVersion} versions this envelope; new fields may be added within a version.</li>
 *   <li>{@code schemaVersion} is the payload version as recorded (its meaning since #32), and
 *       {@code servedSchemaVersion} is the version of the served {@code payload}, which registered upcasters lift
 *       from older recorded versions.</li>
 *   <li>{@code payload} is served exactly as recorded and upcast; prompts and tool arguments are recorded only as
 *       digests (#34), and no credential is ever recorded.</li>
 *   <li>{@code actor} is an identifier (for example {@code user:<id>/api-token:<id>}), never a credential.</li>
 * </ul>
 */
public record RunEventView(int envelopeVersion, UUID runId, UUID workspaceId, UUID releaseId, UUID deploymentId,
        UUID revisionId, UUID cohortId, long sequence, String type, String category, int schemaVersion,
        int servedSchemaVersion, String traceId, String spanId, String actor, long leaseFence, String eventDigest,
        Instant createdAt, JsonNode payload) {
    public static final int ENVELOPE_VERSION = 1;

    public static RunEventView of(DecodedEvent event, EventSchemas schemas) {
        var raw = event.raw();
        return new RunEventView(ENVELOPE_VERSION, raw.runId(), raw.workspaceId(), raw.releaseId(), raw.deploymentId(),
                raw.revisionId(), raw.cohortId(), raw.sequence(), event.type().name(), event.type().category().name(),
                raw.schemaVersion(), schemas.currentVersion(event.type()), raw.traceId(),
                raw.spanId(), raw.actor(), raw.leaseFence(), raw.eventDigest(), raw.createdAt(), event.payload());
    }
}
