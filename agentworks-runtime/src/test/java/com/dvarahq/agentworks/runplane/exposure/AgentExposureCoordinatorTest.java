package com.dvarahq.agentworks.runplane.exposure;

import com.dvarahq.agentworks.domain.run.RunInvoker;
import com.dvarahq.agentworks.domain.run.RunPins;
import com.dvarahq.agentworks.domain.run.RunRecord;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import com.dvarahq.agentworks.exposure.ChannelEnvelope;
import com.dvarahq.agentworks.exposure.DeliveryResult;
import com.dvarahq.agentworks.exposure.InboundInteraction;
import com.dvarahq.agentworks.exposure.InteractionChannel;
import com.dvarahq.agentworks.exposure.OutboundInteraction;
import com.dvarahq.agentworks.interaction.ContentMetadata;
import com.dvarahq.agentworks.interaction.TextContent;
import com.dvarahq.agentworks.runplane.execution.DurableRunEngine;
import com.dvarahq.agentworks.runplane.execution.SessionDeliveryCoordinator;
import com.dvarahq.agentworks.session.AgentSession;
import com.dvarahq.agentworks.session.SessionInboxMessage;
import com.dvarahq.agentworks.storage.session.JdbcSessionInboxAuthority;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentExposureCoordinatorTest {
    private static final String CONFIGURATION = """
            {"placement":"SHARED","credentialReferences":{},"policyIdentities":[],"exposures":[{
              "id":"chat","type":"INTERACTION_CHANNEL","channelType":"test-channel","policy":{
                "authentication":"signed-callback@1","requiredScopes":["chat.send"],"requestsPerMinute":10,
                "maxItemBytes":100,"maxEnvelopeBytes":1000,"classifications":["INTERNAL"],
                "toolContentAllowed":false},"presentation":{}}]}
            """;
    private final UUID workspaceId = UUID.randomUUID();
    private final UUID deploymentId = UUID.randomUUID();
    private final UUID revisionId = UUID.randomUUID();
    private final UUID releaseId = UUID.randomUUID();
    private final UUID cohortId = UUID.randomUUID();
    private final UUID routingId = UUID.randomUUID();
    private final UUID runId = UUID.randomUUID();
    private final JdbcSessionInboxAuthority sessions = mock(JdbcSessionInboxAuthority.class);
    private final SessionDeliveryCoordinator deliveries = mock(SessionDeliveryCoordinator.class);
    private final DurableRunEngine runs = mock(DurableRunEngine.class);
    private final InteractionChannel channel = new TestChannel(false);

    @Test
    void normalizesValidatesAndDeduplicatesBeforeReturningExactPins() {
        ChannelEnvelope envelope = envelope(Set.of("chat.send"), null);
        SessionInboxMessage message = mock(SessionInboxMessage.class);
        UUID messageId = UUID.randomUUID();
        when(message.id()).thenReturn(messageId);
        when(message.runId()).thenReturn(runId);
        when(deliveries.deliver(any(), any(), eq(null), any(), eq("corr-1"), eq("message-1"), any(),
                eq("trace-1"), eq("actor"), any())).thenReturn(
                        new SessionDeliveryCoordinator.Delivery(message, false, true));
        RunRecord run = mock(RunRecord.class);
        RunPins pins = mock(RunPins.class);
        when(run.pins()).thenReturn(pins);
        when(pins.id()).thenReturn(runId);
        when(pins.releaseId()).thenReturn(releaseId);
        when(pins.deploymentId()).thenReturn(deploymentId);
        when(pins.revisionId()).thenReturn(revisionId);
        when(pins.cohortId()).thenReturn(cohortId);
        when(pins.routingGenerationId()).thenReturn(routingId);
        when(pins.routingTargetOrdinal()).thenReturn(0);
        when(runs.run(new WorkspaceId(workspaceId), runId)).thenReturn(run);
        when(sessions.findOrCreate(any(), any(), eq(deploymentId))).thenReturn(mock(AgentSession.class));
        AgentExposureCoordinator coordinator = coordinator(channel, true);

        var result = coordinator.deliver(envelope, "trace-1", "actor", mock(RunInvoker.class));

        assertThat(result.messageId()).isEqualTo(messageId);
        assertThat(result.runId()).isEqualTo(runId);
        assertThat(result.releaseId()).isEqualTo(releaseId);
        assertThat(result.revisionId()).isEqualTo(revisionId);
        assertThat(result.cohortId()).isEqualTo(cohortId);
        assertThat(result.routingGenerationId()).isEqualTo(routingId);
        assertThat(result.routingTargetOrdinal()).isZero();
        assertThat(result.rootRun()).isTrue();
        verify(sessions).findOrCreate(new WorkspaceId(workspaceId), result.sessionId(), deploymentId);
    }

    @Test
    void refusesMissingScopeRateDenialAndAdapterIdentityMutationBeforeRouting() {
        assertThatThrownBy(() -> coordinator(channel, true).deliver(envelope(Set.of(), null), "trace", "actor",
                mock(RunInvoker.class))).isInstanceOfSatisfying(ExposureException.class,
                        failure -> assertThat(failure.code()).isEqualTo("EXPOSURE_FORBIDDEN"));
        assertThatThrownBy(() -> coordinator(channel, false).deliver(envelope(Set.of("chat.send"), null), "trace",
                "actor", mock(RunInvoker.class))).isInstanceOfSatisfying(ExposureException.class,
                        failure -> assertThat(failure.code()).isEqualTo("EXPOSURE_RATE_LIMITED"));
        assertThatThrownBy(() -> coordinator(new TestChannel(true), true).deliver(envelope(Set.of("chat.send"), null),
                "trace", "actor", mock(RunInvoker.class))).isInstanceOfSatisfying(ExposureException.class,
                        failure -> assertThat(failure.code()).isEqualTo("CHANNEL_IDENTITY_MUTATION"));
    }

    @Test
    void sessionMappingIsStableAndTenantExposureAndThreadBound() {
        UUID first = AgentExposureCoordinator.deterministicSession(workspaceId, deploymentId, "chat", "test", "thread");
        assertThat(AgentExposureCoordinator.deterministicSession(workspaceId, deploymentId, "chat", "test", "thread"))
                .isEqualTo(first);
        assertThat(AgentExposureCoordinator.deterministicSession(UUID.randomUUID(), deploymentId, "chat", "test", "thread"))
                .isNotEqualTo(first);
        assertThat(AgentExposureCoordinator.deterministicSession(workspaceId, deploymentId, "other", "test", "thread"))
                .isNotEqualTo(first);
        assertThat(AgentExposureCoordinator.deterministicSession(workspaceId, deploymentId, "chat", "test", "other"))
                .isNotEqualTo(first);
    }

    @Test
    void correlatedDeliveryReadsItsHistoricalRevisionInsteadOfTheCurrentRoute() {
        UUID correlatedId = UUID.randomUUID();
        RunRecord correlated = mock(RunRecord.class);
        RunPins correlatedPins = mock(RunPins.class);
        when(correlated.pins()).thenReturn(correlatedPins);
        when(correlatedPins.deploymentId()).thenReturn(deploymentId);
        when(correlatedPins.revisionId()).thenReturn(revisionId);
        when(runs.run(new WorkspaceId(workspaceId), correlatedId)).thenReturn(correlated);
        SessionInboxMessage message = mock(SessionInboxMessage.class);
        when(message.id()).thenReturn(UUID.randomUUID());
        when(message.runId()).thenReturn(runId);
        when(deliveries.deliver(any(), any(), eq(correlatedId), any(), eq("corr-1"), eq("message-1"), any(),
                eq("trace"), eq("actor"), any())).thenReturn(
                        new SessionDeliveryCoordinator.Delivery(message, false, false));
        RunRecord delivered = mock(RunRecord.class);
        RunPins deliveredPins = mock(RunPins.class);
        when(delivered.pins()).thenReturn(deliveredPins);
        when(deliveredPins.id()).thenReturn(runId);
        when(deliveredPins.releaseId()).thenReturn(releaseId);
        when(deliveredPins.revisionId()).thenReturn(revisionId);
        when(deliveredPins.cohortId()).thenReturn(cohortId);
        when(deliveredPins.routingGenerationId()).thenReturn(routingId);
        when(deliveredPins.routingTargetOrdinal()).thenReturn(0);
        when(runs.run(new WorkspaceId(workspaceId), runId)).thenReturn(delivered);
        when(sessions.findOrCreate(any(), any(), eq(deploymentId))).thenReturn(mock(AgentSession.class));
        AtomicReference<UUID> selectedRevision = new AtomicReference<>();
        ExposureRevisionSource source = (scope, deployment, revision) -> {
            selectedRevision.set(revision);
            return new ExposureRevisionSource.RevisionConfiguration(revisionId, CONFIGURATION);
        };
        AgentExposureCoordinator coordinator = new AgentExposureCoordinator(List.of(channel), source,
                (scope, exposure, principal, limit, now) -> true, sessions, deliveries, runs);

        var result = coordinator.deliver(envelope(Set.of("chat.send"), correlatedId), "trace", "actor",
                mock(RunInvoker.class));

        assertThat(selectedRevision).hasValue(revisionId);
        assertThat(result.rootRun()).isFalse();
    }

    private AgentExposureCoordinator coordinator(InteractionChannel adapter, boolean rateAllowed) {
        ExposureRevisionSource source = (workspace, deployment, revision) ->
                new ExposureRevisionSource.RevisionConfiguration(revisionId, CONFIGURATION);
        return new AgentExposureCoordinator(List.of(adapter), source,
                (workspace, exposure, principal, limit, now) -> rateAllowed, sessions, deliveries, runs);
    }

    private ChannelEnvelope envelope(Set<String> scopes, UUID correlated) {
        return new ChannelEnvelope(workspaceId, "chat", deploymentId, "thread-1", "message-1", "subject-1", scopes,
                correlated, "corr-1", List.of(new TextContent("hello",
                        new ContentMetadata("INTERNAL", "AUTHENTICATED", false, 5))), Instant.parse("2026-10-14T00:00:00Z"));
    }

    private static final class TestChannel implements InteractionChannel {
        private final boolean mutate;
        private TestChannel(boolean mutate) { this.mutate = mutate; }
        @Override public String type() { return "test-channel"; }
        @Override public InboundInteraction normalize(ChannelEnvelope envelope) {
            return new InboundInteraction(envelope.workspaceId(), envelope.exposureId(), envelope.deploymentId(),
                    envelope.externalThreadId(), mutate ? "other-message" : envelope.externalMessageId(),
                    envelope.principalId(), envelope.correlatedRunId(), envelope.correlationId(), envelope.content());
        }
        @Override public DeliveryResult send(OutboundInteraction output) {
            return new DeliveryResult(DeliveryResult.Status.DELIVERED, "delivery-1", null);
        }
    }
}
