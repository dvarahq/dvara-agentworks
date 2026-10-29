package com.dvarahq.agentworks.replay;

import com.dvarahq.agentworks.domain.run.RunEventType;
import com.dvarahq.agentworks.domain.run.RunEffect;
import com.dvarahq.agentworks.domain.run.RunLifecycle;
import com.dvarahq.agentworks.domain.run.RunPins;
import com.dvarahq.agentworks.domain.run.RunRecord;
import com.dvarahq.agentworks.domain.run.RunSummary;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import com.dvarahq.agentworks.runplane.execution.DurableRunEngine;
import com.dvarahq.agentworks.runplane.execution.ReleaseManifestReader.RunManifest;
import com.dvarahq.agentworks.runplane.execution.RunEventCodec.DecodedEvent;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReplayServiceTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final WorkspaceId workspace = new WorkspaceId(UUID.randomUUID());
    private final UUID runId = UUID.randomUUID();

    @Test
    void exactReturnsRecordedOutputWithoutCallingAModelOrMutatingTheSource() {
        DurableRunEngine engine = mock(DurableRunEngine.class);
        DurableRunEngine.ReplaySource source = source(List.of(event(RunEventType.RUN_SUCCEEDED,
                "{\"output\":{\"type\":\"text\",\"content\":\"recorded\"}}")));
        when(engine.replaySource(workspace, runId)).thenReturn(source);
        when(engine.compatibility()).thenReturn("test-runtime");
        AtomicInteger calls = new AtomicInteger();
        ReplayService service = new ReplayService(engine, invocation -> {
            calls.incrementAndGet();
            return JSON.nullNode();
        });

        ReplayService.ReplayResult result = service.replay(workspace, runId, ReplayMode.EXACT);

        assertThat(result.output().path("content").asString()).isEqualTo("recorded");
        assertThat(result.plan().provenance().sourceRunId()).isEqualTo(runId);
        assertThat(result.plan().effectPolicy()).isEqualTo("RECORDED_RESULTS_ONLY");
        assertThat(result.plan().liveEffectsAllowed()).isFalse();
        assertThat(calls).hasValue(0);
    }

    @Test
    void exactReconstructsARecordedFailureState() {
        DurableRunEngine engine = mock(DurableRunEngine.class);
        DurableRunEngine.ReplaySource source = source(List.of(event(RunEventType.RUN_FAILED,
                "{\"code\":\"MODEL_FAILED\",\"message\":\"recorded failure\"}")));
        when(engine.replaySource(workspace, runId)).thenReturn(source);
        when(engine.compatibility()).thenReturn("test-runtime");

        ReplayService.ReplayResult result = new ReplayService(engine, null)
                .replay(workspace, runId, ReplayMode.EXACT);

        assertThat(result.output().path("sourceEventType").asString()).isEqualTo("RUN_FAILED");
        assertThat(result.output().path("code").asString()).isEqualTo("MODEL_FAILED");
    }

    @Test
    void modelReplayInvokesOnlyTheSuppliedModelAdapterAfterCompatibilityPasses() {
        DurableRunEngine engine = mock(DurableRunEngine.class);
        DurableRunEngine.ReplaySource source = source(List.of(event(RunEventType.RUN_SUCCEEDED,
                "{\"output\":{\"content\":\"old\"}}")));
        when(engine.replaySource(workspace, runId)).thenReturn(source);
        when(engine.compatibility()).thenReturn("test-runtime");
        AtomicInteger calls = new AtomicInteger();
        ReplayService service = new ReplayService(engine, invocation -> {
            calls.incrementAndGet();
            assertThat(invocation.plan().provenance().sourceRunId()).isEqualTo(runId);
            return JSON.readTree("{\"type\":\"text\",\"content\":\"new\"}");
        });

        ReplayService.ReplayResult result = service.replay(workspace, runId, ReplayMode.MODEL_REPLAY);

        assertThat(result.output().path("content").asString()).isEqualTo("new");
        assertThat(calls).hasValue(1);
    }

    @Test
    void unavailableRecordedEffectFailsBeforeTheModelCanRun() {
        DurableRunEngine engine = mock(DurableRunEngine.class);
        DurableRunEngine.ReplaySource source = source(List.of(
                event(RunEventType.TOOL_COMPLETED, "{\"stepId\":\"tool-1\",\"resultDigest\":\"sha256:x\"}"),
                event(RunEventType.RUN_SUCCEEDED, "{\"output\":null}")));
        when(engine.replaySource(workspace, runId)).thenReturn(source);
        when(engine.compatibility()).thenReturn("test-runtime");
        AtomicInteger calls = new AtomicInteger();
        ReplayService service = new ReplayService(engine, invocation -> {
            calls.incrementAndGet();
            return JSON.nullNode();
        });

        assertThatThrownBy(() -> service.replay(workspace, runId, ReplayMode.MODEL_REPLAY))
                .isInstanceOf(ReplayException.class).hasMessageContaining("redacted or expired");
        assertThat(calls).hasValue(0);
    }

    @Test
    void modelReplaySuppliesRetainedHumanAndOperationOutcomesAsSubstitutions() {
        UUID interaction = UUID.randomUUID();
        UUID operation = UUID.randomUUID();
        DurableRunEngine engine = mock(DurableRunEngine.class);
        DurableRunEngine.ReplaySource source = source(List.of(
                event(RunEventType.HUMAN_INTERACTION_RESPONDED,
                        "{\"interactionId\":\"" + interaction + "\"}"),
                event(RunEventType.AUTHORIZATION_GRANTED,
                        "{\"interactionId\":\"" + interaction + "\"}"),
                event(RunEventType.OPERATION_COMPLETED, "{\"operationId\":\"" + operation + "\"}"),
                event(RunEventType.RUN_SUCCEEDED, "{\"output\":null}")));
        when(engine.replaySource(workspace, runId)).thenReturn(source);
        when(engine.compatibility()).thenReturn("test-runtime");
        RecordedOutcomeReader outcomes = new RecordedOutcomeReader() {
            @Override public Optional<String> human(WorkspaceId ignored, UUID sourceRun, UUID id) {
                return id.equals(interaction) && sourceRun.equals(runId) ? Optional.of("{\"answer\":\"yes\"}")
                        : Optional.empty();
            }
            @Override public Optional<String> operation(WorkspaceId ignored, UUID sourceRun, UUID id) {
                return id.equals(operation) && sourceRun.equals(runId) ? Optional.of("{\"value\":42}")
                        : Optional.empty();
            }
        };
        ReplayService service = new ReplayService(engine, invocation -> {
            assertThat(invocation.plan().substitutions()).extracting(ReplayService.Substitution::status)
                    .containsExactly("RECORDED", "RECORDED");
            return JSON.nullNode();
        }, outcomes);

        assertThat(service.replay(workspace, runId, ReplayMode.MODEL_REPLAY).plan().executable()).isTrue();
    }

    @Test
    void modelReplayUsesACommittedToolEffectResultInsteadOfCallingTheTool() {
        DurableRunEngine engine = mock(DurableRunEngine.class);
        RunEffect effect = new RunEffect(UUID.randomUUID(), workspace.value(), runId, UUID.randomUUID(), "lookup-1",
                "sha256:request", RunEffect.State.COMMITTED, 1, "{\"value\":42}", null, "worker", 1,
                Instant.now(), Instant.now());
        DurableRunEngine.ReplaySource source = source(List.of(
                event(RunEventType.TOOL_COMPLETED,
                        "{\"stepId\":\"tool-1\",\"effectKey\":\"lookup-1\",\"resultDigest\":\"sha256:x\"}"),
                event(RunEventType.RUN_SUCCEEDED, "{\"output\":null}")), List.of(effect));
        when(engine.replaySource(workspace, runId)).thenReturn(source);
        when(engine.compatibility()).thenReturn("test-runtime");
        ReplayService service = new ReplayService(engine, invocation -> {
            assertThat(invocation.plan().substitutions()).singleElement().satisfies(substitution -> {
                assertThat(substitution.status()).isEqualTo("RECORDED");
                assertThat(substitution.resultDigest()).startsWith("sha256:");
            });
            assertThat(invocation.recordedResults()).containsValue("{\"value\":42}");
            return JSON.nullNode();
        });

        assertThat(service.replay(workspace, runId, ReplayMode.MODEL_REPLAY).plan().executable()).isTrue();
    }

    private DurableRunEngine.ReplaySource source(List<DecodedEvent> history) {
        return source(history, List.of());
    }

    private DurableRunEngine.ReplaySource source(List<DecodedEvent> history, List<RunEffect> effects) {
        RunRecord run = mock(RunRecord.class);
        RunPins pins = mock(RunPins.class);
        RunSummary summary = mock(RunSummary.class);
        RunManifest manifest = mock(RunManifest.class);
        when(run.id()).thenReturn(runId);
        when(run.pins()).thenReturn(pins);
        when(run.summary()).thenReturn(summary);
        when(run.lifecycle()).thenReturn(RunLifecycle.SUCCEEDED);
        when(pins.workspaceId()).thenReturn(workspace.value());
        when(pins.releaseId()).thenReturn(UUID.randomUUID());
        when(pins.deploymentId()).thenReturn(UUID.randomUUID());
        when(pins.revisionId()).thenReturn(UUID.randomUUID());
        when(pins.cohortId()).thenReturn(UUID.randomUUID());
        when(summary.lastEventDigest()).thenReturn("sha256:source");
        when(summary.eventSequence()).thenReturn((long) history.size());
        when(manifest.contextPolicy()).thenReturn(Optional.empty());
        return new DurableRunEngine.ReplaySource(run, manifest, JSON.readTree("\"hello\""),
                Optional.of(mock(com.dvarahq.agentworks.domain.run.RunInvoker.class)),
                Optional.of(mock(com.dvarahq.agentworks.domain.run.PinnedAgentVersion.class)), history, effects);
    }

    private static DecodedEvent event(RunEventType type, String payload) {
        return new DecodedEvent(null, type, (tools.jackson.databind.node.ObjectNode) JSON.readTree(payload));
    }
}
