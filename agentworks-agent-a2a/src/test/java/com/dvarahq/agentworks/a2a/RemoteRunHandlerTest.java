package com.dvarahq.agentworks.a2a;

import com.dvarahq.agentworks.domain.external.*;
import com.dvarahq.agentworks.domain.run.*;
import com.dvarahq.agentworks.runplane.execution.*;
import com.dvarahq.agentworks.runplane.execution.DurableRunEngine.EffectInvoker;
import com.dvarahq.agentworks.runplane.execution.DurableRunEngine.EffectOutcome;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RemoteRunHandlerTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String DIGEST = "sha256:" + "a".repeat(64);
    private final ExternalExecutorDescriptor descriptor = new ExternalExecutorDescriptor("remote-python", "1",
            "fake", "python-3.13", DIGEST, DIGEST, RemoteExecutorHttpClient.PROTOCOL_VERSION,
            RemoteExecutorHttpClient.SCHEMA_VERSION, ExternalAssurance.INSTRUMENTED,
            ExternalAccessMode.DVARAOS_MEDIATED, Set.of(ExternalCapability.EVENT_STREAMING), false);

    @Test
    void commitsExactPinsDescriptorAndValidatedTranscriptThroughTheDurableEffect() {
        RecordingContext context = new RecordingContext(descriptor);
        ExternalExecutorPort port = new ExternalExecutorPort() {
            @Override public ExternalExecutorDescriptor negotiate(ExternalProtocolRequirements requirements) {
                return descriptor;
            }
            @Override public ExternalInvocationResult execute(ExternalInvocationRequest request,
                    ExternalInteractionHandler interactions) {
                context.observed = request;
                ExternalInvocationContext identity = request.context();
                return new ExternalInvocationResult(descriptor, List.of(new ExternalEventEnvelope(
                        identity.organizationId(), identity.workspaceId(), identity.runId(), identity.releaseId(),
                        identity.deploymentRevisionId(), identity.cohortId(), identity.routingGenerationId(),
                        identity.traceId(), identity.invocationId(), request.requirements().protocolVersion(),
                        request.requirements().schemaVersion(), "terminal", 1, "RUN_SUCCEEDED", "ok", true)));
            }
            @Override public void cancel(ExternalInvocationContext context, String reason) { throw new AssertionError(); }
        };

        RunOutcome outcome = new RemoteRunHandler(port, noInteractions()).execute(context);

        assertThat(context.effects).hasValue(1);
        assertThat(context.observed.expectedExecutor()).isEqualTo(descriptor);
        assertThat(context.observed.context().runId()).isEqualTo(context.pins().id());
        assertThat(context.observed.context().deploymentRevisionId()).isEqualTo(context.pins().revisionId());
        assertThat(context.observed.context().invocationId()).isEqualTo("stable-external-key");
        assertThat(outcome).isInstanceOfSatisfying(RunOutcome.Completed.class, completed ->
                assertThat((ExternalInvocationResult) completed.output()).satisfies(result -> {
                    assertThat(result.executor()).isEqualTo(descriptor);
                    assertThat(result.events()).singleElement().satisfies(event ->
                            assertThat(event.type()).isEqualTo("RUN_SUCCEEDED"));
                }));
    }

    @Test
    void refusesExecutorDriftBeforeStartingTheDurableEffect() {
        RecordingContext context = new RecordingContext(descriptor);
        ExternalExecutorDescriptor drifted = new ExternalExecutorDescriptor("another-runner", "1", "fake",
                "python-3.13", DIGEST, DIGEST, descriptor.protocolVersion(), descriptor.schemaVersion(),
                descriptor.assurance(), descriptor.accessMode(), descriptor.capabilities(), false);
        ExternalExecutorPort port = new ExternalExecutorPort() {
            @Override public ExternalExecutorDescriptor negotiate(ExternalProtocolRequirements requirements) { return drifted; }
            @Override public ExternalInvocationResult execute(ExternalInvocationRequest request,
                    ExternalInteractionHandler interactions) { throw new AssertionError(); }
            @Override public void cancel(ExternalInvocationContext context, String reason) { throw new AssertionError(); }
        };

        assertThatThrownBy(() -> new RemoteRunHandler(port, noInteractions()).execute(context))
                .isInstanceOfSatisfying(ExternalProtocolException.class,
                        failure -> assertThat(failure.error()).isEqualTo(ExternalProtocolError.NEGOTIATION_MISMATCH));
        assertThat(context.effects).hasValue(0);
    }

    private static ExternalInteractionHandler noInteractions() {
        return new ExternalInteractionHandler() {
            @Override public String invokeTool(String request) { throw new AssertionError(); }
            @Override public String transferArtifact(String reference) { throw new AssertionError(); }
        };
    }

    private static final class RecordingContext implements RunContext {
        private final UUID organization = UUID.randomUUID();
        private final RunPins pins = new RunPins(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "admission", UUID.randomUUID(), 0, null, UUID.randomUUID(), "ROOT",
                "trace-remote", 8, RunLimits.UNBOUNDED, Instant.parse("2026-10-14T12:00:00Z"));
        private final ReleaseManifestReader.RunManifest manifest;
        private final AtomicInteger effects = new AtomicInteger();
        private ExternalInvocationRequest observed;
        private RecordingContext(ExternalExecutorDescriptor descriptor) {
            manifest = new ReleaseManifestReader.RunManifest(8, pins.definitionId(), pins.versionId(), "1.0.0",
                    DIGEST, "remote-http", List.of("EVENT_STREAMING"), null, List.of(), List.of(), List.of(), null,
                    Optional.empty(), Optional.empty(), Optional.of(descriptor));
        }
        @Override public RunPins pins() { return pins; }
        @Override public ReleaseManifestReader.RunManifest manifest() { return manifest; }
        @Override public JsonNode input() { return JSON.valueToTree(Map.of("message", "hello")); }
        @Override public Optional<JsonNode> checkpointState() { return Optional.empty(); }
        @Override public boolean recovered() { return false; }
        @Override public void emit(List<DurableRunEngine.EventDraft> events) { throw new AssertionError(); }
        @Override public void checkpoint(Object state) { throw new AssertionError(); }
        @Override public EffectOutcome effect(String key, Object request, EffectInvoker invoker) {
            effects.incrementAndGet();
            try { return new EffectOutcome(invoker.invoke("stable-external-key", JSON.valueToTree(request)), null,
                    false, null, "stable-external-key", false); }
            catch (Exception failure) { throw new RuntimeException(failure); }
        }
        @Override public boolean suspended() { return false; }
        @Override public Optional<RunInvoker> invoker() { return Optional.of(new RunInvoker(organization,
                pins.workspaceId(), UUID.randomUUID(), UUID.randomUUID(), "api-token")); }
    }
}
