package com.dvarahq.agentworks.a2a;

import com.dvarahq.agentworks.domain.external.ExternalEventEnvelope;
import com.dvarahq.agentworks.domain.external.ExternalInvocationContext;
import com.dvarahq.agentworks.domain.external.ExternalProtocolRequirements;

import java.util.HashSet;
import java.util.Set;

final class ExternalEventValidator {
    private static final Set<String> EVENT_TYPES = Set.of("RUN_STARTED", "PROGRESS", "USAGE", "TOOL_REQUESTED",
            "ARTIFACT_TRANSFER_REQUESTED", "RUN_SUCCEEDED", "RUN_FAILED", "RUN_CANCELLED");
    private static final Set<String> TERMINAL_TYPES = Set.of("RUN_SUCCEEDED", "RUN_FAILED", "RUN_CANCELLED");
    private final ExternalInvocationContext context;
    private final ExternalProtocolRequirements requirements;
    private final Set<String> eventIds = new HashSet<>();
    private long nextSequence = 1;
    private boolean terminal;

    ExternalEventValidator(ExternalInvocationContext context, ExternalProtocolRequirements requirements) {
        this.context = context;
        this.requirements = requirements;
    }

    void accept(ExternalEventEnvelope event) {
        if (terminal) fail(ExternalProtocolError.LATE_EVENT, "event received after terminal state");
        if (!event.organizationId().equals(context.organizationId())
                || !event.workspaceId().equals(context.workspaceId())
                || !event.runId().equals(context.runId())
                || !event.releaseId().equals(context.releaseId())
                || !event.deploymentRevisionId().equals(context.deploymentRevisionId())
                || !event.cohortId().equals(context.cohortId())
                || !event.routingGenerationId().equals(context.routingGenerationId())
                || !event.traceId().equals(context.traceId())
                || !event.invocationId().equals(context.invocationId())) {
            fail(ExternalProtocolError.CORRELATION_MISMATCH, "external event authority or routing fence mismatch");
        }
        if (!event.protocolVersion().equals(requirements.protocolVersion())
                || !event.schemaVersion().equals(requirements.schemaVersion())) {
            fail(ExternalProtocolError.NEGOTIATION_MISMATCH, "external event protocol pin mismatch");
        }
        if (!EVENT_TYPES.contains(event.type()) || event.terminal() != TERMINAL_TYPES.contains(event.type())) {
            fail(ExternalProtocolError.MALFORMED_ENVELOPE, "external event type or terminal state is invalid");
        }
        if (event.sequence() != nextSequence++ || !eventIds.add(event.eventId())) {
            fail(ExternalProtocolError.SEQUENCE_VIOLATION, "external event gap, duplicate, or replay");
        }
        terminal = event.terminal();
    }

    boolean terminal() {
        return terminal;
    }

    private static void fail(ExternalProtocolError error, String message) {
        throw new ExternalProtocolException(error, message);
    }
}
