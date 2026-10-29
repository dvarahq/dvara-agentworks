package com.dvarahq.agentworks.agent.springai;

import com.dvarahq.agentworks.capability.ToolEffect;
import com.dvarahq.agentworks.domain.security.AutonomyProfile;
import com.dvarahq.agentworks.domain.security.ExecutionIntent;
import com.dvarahq.agentworks.domain.security.ExecutionPolicy;
import com.dvarahq.agentworks.domain.security.InvocationIdentity;
import com.dvarahq.agentworks.domain.security.PermissionRisk;
import com.dvarahq.agentworks.runplane.execution.RunEventCodec;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolOutputGuardrailGateTest {
    private static final ExecutionPolicy POLICY =
            new ExecutionPolicy(ExecutionIntent.EXECUTE, AutonomyProfile.SUPERVISED);
    private static final ToolEffect EFFECT = new ToolEffect("ledger", "write", PermissionRisk.HIGH);

    @Test
    void allowedDecisionReceivesExactFactsButPersistsOnlyNormalizedEvidence() {
        RecordingRunContext context = new RecordingRunContext();
        InvocationIdentity identity = identity(context);
        AtomicReference<ToolOutputGuardrail.Request> observed = new AtomicReference<>();
        ToolOutputGuardrail guardrail = guardrail("app:ledger-output:1", request -> {
            observed.set(request);
            return ToolOutputGuardrail.Decision.allow("LEDGER_OUTPUT_ALLOWED");
        });

        String result = callback(context, identity, guardrail, "raw-secret-canary", new AtomicInteger()).call("{}");

        assertThat(result).isEqualTo("raw-secret-canary");
        assertThat(observed.get()).satisfies(request -> {
            assertThat(request.runId()).isEqualTo(context.pins.id());
            assertThat(request.environmentId()).isEqualTo(context.pins.environmentId());
            assertThat(request.delegatedSubjectId()).isEqualTo(identity.delegatedSubjectId());
            assertThat(request.capability()).isEqualTo("core:ledger:1");
            assertThat(request.effect()).isEqualTo(EFFECT);
            assertThat(request.result()).isEqualTo("raw-secret-canary");
            assertThat(request.toString()).doesNotContain("raw-secret-canary").contains("result=<redacted>");
        });
        assertThat(context.types()).containsExactly("TOOL_REQUESTED", "TOOL_INPUT_SCHEMA_VALIDATED",
                "TOOL_POLICY_EVALUATED", "TOOL_INPUT_GUARDRAIL_EVALUATED", "TOOL_STARTED",
                "TOOL_OUTPUT_GUARDRAIL_EVALUATED", "TOOL_COMPLETED");
        assertThat(guardrailEvidence(context)).containsEntry("decision", "ALLOW")
                .containsEntry("reason", "LEDGER_OUTPUT_ALLOWED")
                .containsEntry("guardrail", "app:ledger-output:1").containsEntry("risk", "HIGH");
        assertThat(context.events.toString()).doesNotContain("raw-secret-canary");
    }

    @Test
    void redactionReplacesWhatTheModelReceivesAndWhatCompletionEvidenceDescribes() {
        RecordingRunContext context = new RecordingRunContext();
        ToolOutputGuardrail guardrail = guardrail("app:redact:1",
                request -> ToolOutputGuardrail.Decision.redact("SECRET_REDACTED", "safe replacement"));

        String result = callback(context, identity(context), guardrail, "raw-secret-canary", new AtomicInteger())
                .call("{}");

        assertThat(result).isEqualTo("safe replacement");
        assertThat(guardrailEvidence(context)).containsEntry("decision", "REDACT")
                .containsEntry("reason", "SECRET_REDACTED");
        assertThat(completionEvidence(context)).containsEntry("resultLength", 16)
                .containsEntry("resultDigest", RunEventCodec.sha256("safe replacement"));
        assertThat(context.events.toString()).doesNotContain("raw-secret-canary", "safe replacement");
    }

    @Test
    void denyAndProviderFailuresWithholdTheResultAndFailClosed() {
        record Scenario(ToolOutputGuardrail guardrail, String reason) {}
        for (Scenario scenario : new Scenario[] {
                new Scenario(guardrail("app:deny:1", request ->
                        ToolOutputGuardrail.Decision.deny("CONTENT_DENIED")), "CONTENT_DENIED"),
                new Scenario(guardrail("app:unavailable:1", request -> {
                    throw new IllegalStateException("raw-secret-canary provider outage");
                }), "GUARDRAIL_UNAVAILABLE"),
                new Scenario(guardrail("app:null:1", request -> null), "GUARDRAIL_UNAVAILABLE")
        }) {
            RecordingRunContext context = new RecordingRunContext();
            AtomicInteger calls = new AtomicInteger();

            assertThatThrownBy(() -> callback(context, identity(context), scenario.guardrail(),
                    "raw-secret-canary", calls).call("{}"))
                    .isInstanceOfSatisfying(OutputRejectedException.class,
                            denied -> assertThat(denied.code()).isEqualTo("TOOL_OUTPUT_GUARDRAIL_DENIED"));

            assertThat(calls).hasValue(1);
            assertThat(context.types()).endsWith("TOOL_STARTED", "TOOL_OUTPUT_GUARDRAIL_EVALUATED")
                    .doesNotContain("TOOL_COMPLETED", "TOOL_FAILED");
            assertThat(guardrailEvidence(context)).containsEntry("decision", "DENY")
                    .containsEntry("reason", scenario.reason());
            assertThat(context.events.toString()).doesNotContain("raw-secret-canary");
        }
    }

    @Test
    void decisionsAndProviderIdentityCannotLeakArbitraryText() {
        assertThatThrownBy(() -> ToolOutputGuardrail.Decision.deny("secret-canary denied"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ToolOutputGuardrail.Decision(ToolOutputGuardrail.Outcome.REDACT,
                "REDACTED", null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(ToolOutputGuardrail.Decision.redact("REDACTED", "raw-secret-canary").toString())
                .doesNotContain("raw-secret-canary");

        RecordingRunContext context = new RecordingRunContext();
        assertThatThrownBy(() -> gate(context, identity(context), guardrail("BAD ID",
                request -> ToolOutputGuardrail.Decision.allow("ALLOWED"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static RecordingToolCallback callback(RecordingRunContext context, InvocationIdentity identity,
            ToolOutputGuardrail guardrail, String result, AtomicInteger calls) {
        ToolCallback delegate = new ToolCallback() {
            private final ToolDefinition definition = ToolDefinition.builder().name("ledger")
                    .description("ledger test").inputSchema("{\"type\":\"object\"}").build();

            @Override public ToolDefinition getToolDefinition() { return definition; }

            @Override public String call(String input) {
                calls.incrementAndGet();
                return result;
            }
        };
        ToolPolicyGate policy = new ToolPolicyGate(context.pins.workspaceId(), identity, Optional.of(POLICY));
        return new RecordingToolCallback(delegate, new DurableRunRecorder(context, 64), policy,
                ToolInputSchema.compile(delegate.getToolDefinition().inputSchema()),
                ToolInputGuardrailGate.legacyCompatibility(), gate(context, identity, guardrail));
    }

    private static ToolOutputGuardrailGate gate(RecordingRunContext context, InvocationIdentity identity,
            ToolOutputGuardrail guardrail) {
        return new ToolOutputGuardrailGate(guardrail, context.pins, identity, Optional.of(POLICY), "ledger",
                "core:ledger:1", Optional.of(EFFECT));
    }

    private static ToolOutputGuardrail guardrail(String id,
            java.util.function.Function<ToolOutputGuardrail.Request, ToolOutputGuardrail.Decision> evaluator) {
        return new ToolOutputGuardrail() {
            @Override public String id() { return id; }
            @Override public Decision evaluate(Request request) { return evaluator.apply(request); }
        };
    }

    private static InvocationIdentity identity(RecordingRunContext context) {
        UUID user = UUID.randomUUID();
        return new InvocationIdentity(UUID.randomUUID(), context.pins.workspaceId(), user, UUID.randomUUID(), user);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> guardrailEvidence(RecordingRunContext context) {
        return (Map<String, Object>) context.events.stream()
                .filter(event -> event.type().name().equals("TOOL_OUTPUT_GUARDRAIL_EVALUATED"))
                .findFirst().orElseThrow().payload();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> completionEvidence(RecordingRunContext context) {
        return (Map<String, Object>) context.events.stream()
                .filter(event -> event.type().name().equals("TOOL_COMPLETED"))
                .findFirst().orElseThrow().payload();
    }
}
