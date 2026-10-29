package com.dvarahq.agentworks.runplane.exposure;

import com.dvarahq.agentworks.domain.run.RunInvoker;
import com.dvarahq.agentworks.domain.run.RunRecord;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import com.dvarahq.agentworks.exposure.ChannelEnvelope;
import com.dvarahq.agentworks.exposure.ExposureRateLimiter;
import com.dvarahq.agentworks.exposure.ExposureSpec;
import com.dvarahq.agentworks.exposure.InteractionChannel;
import com.dvarahq.agentworks.interaction.InteractionContent;
import com.dvarahq.agentworks.interaction.TypedInteractionValidator;
import com.dvarahq.agentworks.runplane.execution.DurableRunEngine;
import com.dvarahq.agentworks.runplane.execution.SessionDeliveryCoordinator;
import com.dvarahq.agentworks.session.InboxMessageType;
import com.dvarahq.agentworks.storage.session.JdbcSessionInboxAuthority;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Governs channel normalization and delivers through the existing dedup-before-routing inbox contract. */
public final class AgentExposureCoordinator {
    private final Map<String, InteractionChannel> channels;
    private final ExposureRevisionSource revisions;
    private final ExposureRateLimiter rates;
    private final JdbcSessionInboxAuthority sessions;
    private final SessionDeliveryCoordinator deliveries;
    private final DurableRunEngine runs;
    private final JsonMapper json = new JsonMapper();

    public AgentExposureCoordinator(List<InteractionChannel> channels, ExposureRevisionSource revisions,
            ExposureRateLimiter rates, JdbcSessionInboxAuthority sessions, SessionDeliveryCoordinator deliveries,
            DurableRunEngine runs) {
        Map<String, InteractionChannel> indexed = new LinkedHashMap<>();
        for (InteractionChannel channel : channels) {
            if (channel.type() == null || channel.type().isBlank() || indexed.put(channel.type(), channel) != null) {
                throw new IllegalArgumentException("interaction channel types must be unique");
            }
        }
        this.channels = Map.copyOf(indexed);
        this.revisions = Objects.requireNonNull(revisions, "revisions");
        this.rates = Objects.requireNonNull(rates, "rates");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.deliveries = Objects.requireNonNull(deliveries, "deliveries");
        this.runs = Objects.requireNonNull(runs, "runs");
    }

    public ChannelDelivery deliver(ChannelEnvelope envelope, String traceId, String actor, RunInvoker invoker) {
        Objects.requireNonNull(envelope, "envelope");
        WorkspaceId workspace = new WorkspaceId(envelope.workspaceId());
        RunRecord correlated = envelope.correlatedRunId() == null ? null : runs.run(workspace, envelope.correlatedRunId());
        if (correlated != null && !correlated.pins().deploymentId().equals(envelope.deploymentId())) {
            throw refused("EXPOSURE_CORRELATION_MISMATCH", "correlated run belongs to another deployment");
        }
        UUID revisionId = correlated == null ? null : correlated.pins().revisionId();
        ExposureRevisionSource.RevisionConfiguration revision = revisions.read(workspace, envelope.deploymentId(), revisionId);
        ExposureSpec exposure = exposure(revision.configurationJson(), envelope.exposureId());
        if (exposure.type() != com.dvarahq.agentworks.exposure.ExposureType.INTERACTION_CHANNEL) {
            throw refused("EXPOSURE_TYPE_MISMATCH", "exposure is not an interaction channel");
        }
        InteractionChannel channel = channels.get(exposure.channelType());
        if (channel == null) throw refused("CHANNEL_UNAVAILABLE", "interaction channel adapter is unavailable");
        if (!envelope.scopes().containsAll(exposure.policy().requiredScopes())) {
            throw refused("EXPOSURE_FORBIDDEN", "channel principal lacks an exposure scope");
        }
        if (!rates.acquire(envelope.workspaceId(), exposure.id(), envelope.principalId(),
                exposure.policy().requestsPerMinute(), envelope.receivedAt())) {
            throw refused("EXPOSURE_RATE_LIMITED", "channel rate policy refused delivery");
        }
        var normalized = channel.normalize(envelope);
        if (!normalized.workspaceId().equals(envelope.workspaceId())
                || !normalized.deploymentId().equals(envelope.deploymentId())
                || !normalized.exposureId().equals(envelope.exposureId())
                || !normalized.externalThreadId().equals(envelope.externalThreadId())
                || !normalized.externalMessageId().equals(envelope.externalMessageId())
                || !Objects.equals(normalized.correlatedRunId(), envelope.correlatedRunId())) {
            throw refused("CHANNEL_IDENTITY_MUTATION", "channel adapter changed an authoritative identity");
        }
        List<InteractionContent> content = new TypedInteractionValidator(exposure.policy().maxItemBytes(),
                exposure.policy().maxEnvelopeBytes()).validate(normalized.content(),
                        exposure.policy().classifications(), exposure.policy().toolContentAllowed());
        UUID sessionId = deterministicSession(envelope.workspaceId(), envelope.deploymentId(), exposure.id(),
                exposure.channelType(), envelope.externalThreadId());
        sessions.findOrCreate(workspace, sessionId, envelope.deploymentId());
        Map<String, Object> payload = payload(exposure.id(), exposure.channelType(), envelope.externalThreadId(),
                envelope.externalMessageId(), envelope.principalId(), content);
        var delivered = deliveries.deliver(workspace, sessionId, envelope.correlatedRunId(),
                InboxMessageType.CHANNEL_MESSAGE, envelope.correlationId(), envelope.externalMessageId(), payload,
                traceId, actor, invoker);
        RunRecord run = runs.run(workspace, delivered.message().runId());
        return new ChannelDelivery(delivered.message().id(), sessionId, run.pins().id(), run.pins().releaseId(),
                run.pins().revisionId(), run.pins().cohortId(), run.pins().routingGenerationId(),
                run.pins().routingTargetOrdinal(), delivered.replayed(), delivered.rootRun());
    }

    private ExposureSpec exposure(String configurationJson, String id) {
        try {
            JsonNode root = json.readTree(configurationJson);
            JsonNode values = root == null ? null : root.get("exposures");
            if (values == null || !values.isArray()) throw refused("EXPOSURE_NOT_FOUND", "exposure is not configured");
            for (JsonNode value : values.values()) {
                if (value.path("id").isString() && id.equals(value.path("id").stringValue())) {
                    return json.treeToValue(value, ExposureSpec.class);
                }
            }
            throw refused("EXPOSURE_NOT_FOUND", "exposure is not configured in the selected revision");
        } catch (ExposureException refused) {
            throw refused;
        } catch (JacksonException | IllegalArgumentException invalid) {
            throw refused("INVALID_EXPOSURE", "deployment exposure configuration is invalid");
        }
    }

    private Map<String, Object> payload(String exposureId, String channelType, String threadId, String messageId,
            String principalId, List<InteractionContent> content) {
        List<Object> encoded = new ArrayList<>();
        for (InteractionContent item : content) encoded.add(json.convertValue(item, Object.class));
        return Map.of("protocolVersion", "interaction/v1", "exposureId", exposureId, "channelType", channelType,
                "externalThreadId", threadId, "externalMessageId", messageId, "principalId", principalId,
                "content", List.copyOf(encoded));
    }

    static UUID deterministicSession(UUID workspace, UUID deployment, String exposure, String channel, String thread) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : List.of(workspace.toString(), deployment.toString(), exposure, channel, thread)) {
                digest.update(value.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            byte[] bytes = digest.digest();
            long most = ByteBuffer.wrap(bytes, 0, 8).getLong();
            long least = ByteBuffer.wrap(bytes, 8, 8).getLong();
            most = (most & 0xffffffffffff0fffL) | 0x0000000000005000L;
            least = (least & 0x3fffffffffffffffL) | 0x8000000000000000L;
            return new UUID(most, least);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static ExposureException refused(String code, String detail) { return new ExposureException(code, detail); }

    public record ChannelDelivery(UUID messageId, UUID sessionId, UUID runId, UUID releaseId, UUID revisionId,
            UUID cohortId, UUID routingGenerationId, Integer routingTargetOrdinal, boolean replayed, boolean rootRun) {}
}
