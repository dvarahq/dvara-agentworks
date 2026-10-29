package com.dvarahq.agentworks.runplane.session.web;

import com.dvarahq.agentworks.session.AgentSession;
import com.dvarahq.agentworks.session.InboxMessageType;
import com.dvarahq.agentworks.session.SessionInboxMessage;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public final class SessionApiResources {
    private SessionApiResources() {}

    public record CreateSessionRequest(UUID deploymentId) {}
    public record DeliverMessageRequest(UUID runId, InboxMessageType type, String correlationId,
            Map<String, Object> payload) {}

    public record SessionResource(UUID id, UUID deploymentId, long version, Instant createdAt, Instant updatedAt) {
        static SessionResource of(AgentSession value) {
            return new SessionResource(value.id(), value.deploymentId(), value.version(), value.createdAt(),
                    value.updatedAt());
        }
    }

    public record InboxResource(UUID id, UUID sessionId, UUID runId, UUID releaseId, UUID deploymentId,
            UUID revisionId, UUID cohortId, long sequence, InboxMessageType type, String correlationId,
            Map<String, Object> payload, String status, long version, Instant createdAt, Instant claimUntil,
            Instant terminalAt) {
        static InboxResource of(SessionInboxMessage value) {
            return new InboxResource(value.id(), value.sessionId(), value.runId(), value.releaseId(),
                    value.deploymentId(), value.revisionId(), value.cohortId(), value.sequence(), value.type(),
                    value.correlationId(), value.payload(), value.status().name(), value.version(), value.createdAt(),
                    value.claimUntil().orElse(null), value.terminalAt().orElse(null));
        }
    }
}
