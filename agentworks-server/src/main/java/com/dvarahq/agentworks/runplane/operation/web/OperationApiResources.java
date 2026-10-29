package com.dvarahq.agentworks.runplane.operation.web;

import com.dvarahq.agentworks.operation.DurableOperationRecord;
import com.dvarahq.agentworks.operation.DurableOperationResult;
import com.dvarahq.agentworks.operation.DurableOperationStatus;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public final class OperationApiResources {
    private OperationApiResources() {}

    public record CallbackRequest(DurableOperationStatus status, String code, Map<String, Object> output) {
        DurableOperationResult result() {
            return new DurableOperationResult(status, code, output == null ? Map.of() : output);
        }
    }

    public record OperationResource(UUID id, UUID sessionId, UUID runId, UUID releaseId, UUID deploymentId,
            UUID revisionId, UUID cohortId, String correlationId, String provider, String operationType,
            String externalId, String status, Integer progressPercent, String resultCode,
            Map<String, Object> result, Instant createdAt, Instant deadline, Instant updatedAt, String resolvedBy,
            Instant terminalAt) {
        static OperationResource of(DurableOperationRecord value) {
            return new OperationResource(value.id(), value.sessionId(), value.runId(), value.releaseId(),
                    value.deploymentId(), value.revisionId(), value.cohortId(), value.correlationId(), value.provider(),
                    value.operationType(), value.handle().map(handle -> handle.externalId()).orElse(null),
                    value.status().name(), value.progressPercent(),
                    value.result().map(DurableOperationResult::code).orElse(null),
                    value.result().map(DurableOperationResult::output).orElse(null), value.createdAt(),
                    value.deadline(), value.updatedAt(), value.resolvedBy().orElse(null),
                    value.terminalAt().orElse(null));
        }
    }
}
