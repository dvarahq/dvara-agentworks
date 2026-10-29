package com.dvarahq.agentworks.exposure;

import com.dvarahq.agentworks.interaction.InteractionContent;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Transport-authenticated inbound message. Secret-bearing credentials must not be placed in this value. */
public record ChannelEnvelope(UUID workspaceId, String exposureId, UUID deploymentId, String externalThreadId,
        String externalMessageId, String principalId, Set<String> scopes, UUID correlatedRunId,
        String correlationId, List<InteractionContent> content, Instant receivedAt) {
    public ChannelEnvelope {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(deploymentId, "deploymentId");
        if (exposureId == null || exposureId.isBlank() || exposureId.length() > 128
                || externalThreadId == null || externalThreadId.isBlank() || externalThreadId.length() > 512
                || externalMessageId == null || externalMessageId.isBlank() || externalMessageId.length() > 256
                || principalId == null || principalId.isBlank() || principalId.length() > 256
                || correlationId == null || correlationId.isBlank() || correlationId.length() > 128) {
            throw new IllegalArgumentException("invalid channel envelope identity");
        }
        scopes = Set.copyOf(scopes == null ? Set.of() : scopes);
        content = List.copyOf(Objects.requireNonNull(content, "content"));
        Objects.requireNonNull(receivedAt, "receivedAt");
    }
    @Override public String toString() {
        return "ChannelEnvelope[workspaceId=" + workspaceId + ", exposureId=" + exposureId
                + ", deploymentId=" + deploymentId + ", externalMessageId=" + externalMessageId
                + ", principalId=" + principalId + ", content=<redacted>]";
    }
}
