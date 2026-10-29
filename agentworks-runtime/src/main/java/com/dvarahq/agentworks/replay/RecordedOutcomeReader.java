package com.dvarahq.agentworks.replay;

import com.dvarahq.agentworks.domain.tenant.WorkspaceId;

import java.util.Optional;
import java.util.UUID;

/** Reads retained, policy-authorized recorded outcomes; absence means replay must fail closed. */
public interface RecordedOutcomeReader {
    RecordedOutcomeReader NONE = new RecordedOutcomeReader() {};

    default Optional<String> human(WorkspaceId workspace, UUID sourceRunId, UUID interactionId) {
        return Optional.empty();
    }

    default Optional<String> operation(WorkspaceId workspace, UUID sourceRunId, UUID operationId) {
        return Optional.empty();
    }
}
