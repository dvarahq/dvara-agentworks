package com.dvarahq.agentworks.agent.springai;

import com.dvarahq.agentworks.capability.ToolEffect;
import com.dvarahq.agentworks.domain.security.AutonomyProfile;
import com.dvarahq.agentworks.domain.security.ExecutionIntent;
import com.dvarahq.agentworks.domain.security.ExecutionPolicy;
import com.dvarahq.agentworks.domain.security.InvocationIdentity;
import com.dvarahq.agentworks.domain.security.PermissionRisk;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolEffectBindingTest {
    @Test
    void binaryOffloadFollowsRedactionAndItsReferenceIsReplayedWithoutAnotherWrite() {
        RecordingRunContext context = new RecordingRunContext();
        InvocationIdentity identity = identity(context);
        AtomicInteger calls = new AtomicInteger(), writes = new AtomicInteger();
        AtomicReference<String> stored = new AtomicReference<>();
        var store = org.mockito.Mockito.mock(com.dvarahq.agentworks.artifact.ArtifactStore.class);
        var reference = new com.dvarahq.agentworks.domain.artifact.ArtifactRef(UUID.randomUUID());
        org.mockito.Mockito.when(store.put(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            com.dvarahq.agentworks.domain.artifact.ArtifactWrite write = invocation.getArgument(0);
            stored.set(new String(write.content().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            writes.incrementAndGet(); return reference;
        });
        var invoker = new com.dvarahq.agentworks.domain.run.RunInvoker(identity.organizationId(), context.pins.workspaceId(),
                identity.userId(), UUID.randomUUID(), "api-token");
        var artifact = new ToolResultArtifactBinding(store, context.pins, invoker);
        ToolCallback delegate = new ArtifactToolCallback() {
            @Override public ToolDefinition getToolDefinition() { return ToolDefinition.builder().name("binary")
                    .description("binary").inputSchema("{\"type\":\"object\"}").build(); }
            @Override public String call(String input) { calls.incrementAndGet(); return "raw-secret-canary"; }
            @Override public boolean requiresArtifact(String output) { return true; }
        };
        assertThatThrownBy(() -> recorded(context, identity, delegate).call("{}"))
                .hasMessage("TOOL_ARTIFACT_STORE_REQUIRED");
        assertThat(calls).hasValue(0);
        assertThat(context.effectInvocations).hasValue(0);
        String first = recorded(context, identity, delegate, artifact).call("{}");
        String replay = recorded(context, identity, delegate, artifact).call("{}");
        assertThat(first).contains(reference.value().toString()).doesNotContain("raw-secret-canary", "safe replacement");
        assertThat(replay).isEqualTo(first);
        assertThat(stored).hasValue("safe replacement");
        assertThat(calls).hasValue(1); assertThat(writes).hasValue(1);
        assertThat(context.events.toString()).doesNotContain("raw-secret-canary", "safe replacement");
    }

    private static final ExecutionPolicy POLICY =
            new ExecutionPolicy(ExecutionIntent.EXECUTE, AutonomyProfile.SUPERVISED);
    private static final ToolEffect EFFECT = new ToolEffect("ledger", "write", PermissionRisk.HIGH);

    @Test
    void recoveryReplaysTheRedactedResultWithoutRepeatingTheEffectOrCredentialBoundary() {
        RecordingRunContext context = new RecordingRunContext();
        InvocationIdentity identity = identity(context);
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> firstKey = new AtomicReference<>();
        ToolCallback delegate = callback(calls, firstKey);

        String first = recorded(context, identity, delegate).call("{\"account\":\"input-secret-canary\"}",
                new ToolContext(Map.of("caller", "preserved")));
        String replay = recorded(context, identity, delegate).call("{\"account\":\"input-secret-canary\"}",
                new ToolContext(Map.of("caller", "preserved")));

        assertThat(first).isEqualTo("safe replacement");
        assertThat(replay).isEqualTo(first);
        assertThat(calls).hasValue(1);
        assertThat(context.effectInvocations).hasValue(1);
        assertThat(context.effectKeys).hasSize(2).allSatisfy(key -> assertThat(key).isEqualTo(
                context.effectKeys.getFirst()));
        assertThat(context.externalEffectKeys).containsExactly(firstKey.get());
        assertThat(firstKey.get()).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(context.effectRequests.toString()).doesNotContain("input-secret-canary", "raw-secret-canary");
        assertThat(context.events.toString()).doesNotContain("input-secret-canary", "raw-secret-canary",
                "safe replacement");
        assertThat(context.events.stream().filter(event -> event.type().name().equals("TOOL_OUTPUT_GUARDRAIL_EVALUATED")))
                .hasSize(1);
        assertThat(context.events.stream().filter(event -> event.type().name().equals("TOOL_COMPLETED"))
                .map(event -> payload(event.payload()).get("effectReplayed"))).containsExactly(false, true);
    }

    @Test
    void aRecoveredOrdinalWithDifferentArgumentsFailsBeforeTheCallback() {
        RecordingRunContext context = new RecordingRunContext();
        InvocationIdentity identity = identity(context);
        AtomicInteger calls = new AtomicInteger();
        ToolCallback delegate = callback(calls, new AtomicReference<>());
        ToolContext toolContext = new ToolContext(Map.of("caller", "preserved"));
        recorded(context, identity, delegate).call("{\"value\":1}", toolContext);

        assertThatThrownBy(() -> recorded(context, identity, delegate).call("{\"value\":2}", toolContext))
                .hasMessageContaining("another request");
        assertThat(calls).hasValue(1);
    }

    private static RecordingToolCallback recorded(RecordingRunContext context, InvocationIdentity identity,
            ToolCallback delegate) {
        return recorded(context, identity, delegate, null);
    }

    private static RecordingToolCallback recorded(RecordingRunContext context, InvocationIdentity identity,
            ToolCallback delegate, ToolResultArtifactBinding artifact) {
        ToolPolicyGate policy = new ToolPolicyGate(context.pins.workspaceId(), identity, Optional.of(POLICY));
        ToolOutputGuardrail guardrail = new ToolOutputGuardrail() {
            @Override public String id() { return "app:redact:1"; }
            @Override public Decision evaluate(Request request) {
                return Decision.redact("SECRET_REDACTED", "safe replacement");
            }
        };
        var output = new ToolOutputGuardrailGate(guardrail, context.pins, identity, Optional.of(POLICY), "ledger",
                "core:ledger:1", Optional.of(EFFECT));
        return new RecordingToolCallback(delegate, new DurableRunRecorder(context, 64), policy,
                ToolInputSchema.compile(delegate.getToolDefinition().inputSchema()),
                ToolInputGuardrailGate.legacyCompatibility(), output, null,
                new ToolEffectBinding(context, "ledger", "core:ledger:1", EFFECT), null, artifact);
    }

    private static ToolCallback callback(AtomicInteger calls, AtomicReference<String> key) {
        return new ToolCallback() {
            private final ToolDefinition definition = ToolDefinition.builder().name("ledger")
                    .description("ledger test").inputSchema("{\"type\":\"object\"}").build();

            @Override public ToolDefinition getToolDefinition() { return definition; }

            @Override public String call(String input) {
                return call(input, null);
            }

            @Override public String call(String input, ToolContext context) {
                calls.incrementAndGet();
                assertThat(context.getContext()).containsEntry("caller", "preserved");
                key.set(ToolEffectContext.idempotencyKey(context).orElseThrow());
                return "raw-secret-canary";
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> payload(Object value) {
        return (Map<String, Object>) value;
    }

    private static InvocationIdentity identity(RecordingRunContext context) {
        UUID user = UUID.randomUUID();
        return new InvocationIdentity(UUID.randomUUID(), context.pins.workspaceId(), user, UUID.randomUUID(), user);
    }
}
