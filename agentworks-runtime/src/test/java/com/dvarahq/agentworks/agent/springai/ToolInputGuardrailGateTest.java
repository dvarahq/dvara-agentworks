package com.dvarahq.agentworks.agent.springai;

import com.dvarahq.agentworks.capability.ToolEffect;
import com.dvarahq.agentworks.domain.security.AutonomyProfile;
import com.dvarahq.agentworks.domain.security.ExecutionIntent;
import com.dvarahq.agentworks.domain.security.ExecutionPolicy;
import com.dvarahq.agentworks.domain.security.InvocationIdentity;
import com.dvarahq.agentworks.domain.security.PermissionRisk;
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

class ToolInputGuardrailGateTest {
    private static final ExecutionPolicy POLICY =
            new ExecutionPolicy(ExecutionIntent.EXECUTE, AutonomyProfile.SUPERVISED);
    private static final ToolEffect EFFECT = new ToolEffect("ledger", "write", PermissionRisk.HIGH);

    @Test
    void allowedDecisionReceivesExactFactsButPersistsOnlyNormalizedEvidence() {
        RecordingRunContext context = new RecordingRunContext();
        InvocationIdentity identity = identity(context);
        AtomicReference<ToolInputGuardrail.Request> observed = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        ToolInputGuardrail guardrail = guardrail("app:ledger-input:1", request -> {
            observed.set(request);
            return new ToolInputGuardrail.Decision(ToolInputGuardrail.Outcome.ALLOW, "LEDGER_INPUT_ALLOWED");
        });

        String result = callback(context, identity, guardrail, calls).call("{\"account\":\"secret-canary\"}");

        assertThat(result).isEqualTo("ok");
        assertThat(calls).hasValue(1);
        assertThat(observed.get()).satisfies(request -> {
            assertThat(request.runId()).isEqualTo(context.pins.id());
            assertThat(request.releaseId()).isEqualTo(context.pins.releaseId());
            assertThat(request.actorId()).isEqualTo(identity.userId());
            assertThat(request.capability()).isEqualTo("core:ledger:1");
            assertThat(request.effect()).isEqualTo(EFFECT);
            assertThat(request.arguments()).contains("secret-canary");
            assertThat(request.toString()).doesNotContain("secret-canary").contains("arguments=<redacted>");
        });
        assertThat(context.types()).containsExactly("TOOL_REQUESTED", "TOOL_INPUT_SCHEMA_VALIDATED",
                "TOOL_POLICY_EVALUATED", "TOOL_INPUT_GUARDRAIL_EVALUATED", "TOOL_STARTED",
                "TOOL_OUTPUT_GUARDRAIL_EVALUATED", "TOOL_COMPLETED");
        assertThat(guardrailEvidence(context)).containsEntry("decision", "ALLOW")
                .containsEntry("reason", "LEDGER_INPUT_ALLOWED").containsEntry("guardrail", "app:ledger-input:1")
                .containsEntry("risk", "HIGH");
        assertThat(context.events.toString()).doesNotContain("secret-canary", "account");
    }

    @Test
    void denyInteractionAndProviderFailureAllStopBeforeTheCallback() {
        record Scenario(ToolInputGuardrail guardrail, String code, String decision, String reason) {}
        for (Scenario scenario : new Scenario[] {
                new Scenario(guardrail("app:deny:1", request -> new ToolInputGuardrail.Decision(
                        ToolInputGuardrail.Outcome.DENY, "CONTENT_DENIED")),
                        "TOOL_INPUT_GUARDRAIL_DENIED", "DENY", "CONTENT_DENIED"),
                new Scenario(guardrail("app:step-up:1", request -> new ToolInputGuardrail.Decision(
                        ToolInputGuardrail.Outcome.INTERACTION_REQUIRED, "REVIEW_REQUIRED")),
                        "TOOL_INTERACTION_REQUIRED", "INTERACTION_REQUIRED", "REVIEW_REQUIRED"),
                new Scenario(guardrail("app:unavailable:1", request -> {
                    throw new IllegalStateException("secret-canary provider outage");
                }), "TOOL_INPUT_GUARDRAIL_DENIED", "DENY", "GUARDRAIL_UNAVAILABLE")
        }) {
            RecordingRunContext context = new RecordingRunContext();
            AtomicInteger calls = new AtomicInteger();

            assertThatThrownBy(() -> callback(context, identity(context), scenario.guardrail(), calls)
                    .call("{\"account\":\"secret-canary\"}"))
                    .isInstanceOfSatisfying(OutputRejectedException.class,
                            denied -> assertThat(denied.code()).isEqualTo(scenario.code()));

            assertThat(calls).hasValue(0);
            assertThat(context.types()).containsExactly("TOOL_REQUESTED", "TOOL_INPUT_SCHEMA_VALIDATED",
                    "TOOL_POLICY_EVALUATED", "TOOL_INPUT_GUARDRAIL_EVALUATED").doesNotContain("TOOL_STARTED");
            assertThat(guardrailEvidence(context)).containsEntry("decision", scenario.decision())
                    .containsEntry("reason", scenario.reason()).containsEntry("risk", "HIGH");
            assertThat(context.events.toString()).doesNotContain("secret-canary", "account");
        }
    }

    @Test
    void providerIdentityAndReasonMustBeStableCodes() {
        RecordingRunContext context = new RecordingRunContext();
        assertThatThrownBy(() -> gate(context, identity(context), guardrail("BAD ID", request ->
                new ToolInputGuardrail.Decision(ToolInputGuardrail.Outcome.ALLOW, "ALLOWED"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ToolInputGuardrail.Decision(ToolInputGuardrail.Outcome.DENY,
                "secret-canary is forbidden")).isInstanceOf(IllegalArgumentException.class);

        AtomicInteger identityReads = new AtomicInteger();
        ToolInputGuardrail changing = new ToolInputGuardrail() {
            @Override public String id() {
                return identityReads.incrementAndGet() == 1 ? "app:stable:1" : "app:changed:2";
            }
            @Override public Decision evaluate(Request request) {
                return new Decision(Outcome.ALLOW, "ALLOWED");
            }
        };
        assertThat(gate(context, identity(context), changing).evaluate("{}").guardrail()).isEqualTo("app:stable:1");
        assertThat(identityReads).hasValue(1);
    }

    private static RecordingToolCallback callback(RecordingRunContext context, InvocationIdentity identity,
            ToolInputGuardrail guardrail, AtomicInteger calls) {
        ToolCallback delegate = new ToolCallback() {
            private final ToolDefinition definition = ToolDefinition.builder().name("ledger")
                    .description("ledger test").inputSchema("{\"type\":\"object\"}").build();

            @Override public ToolDefinition getToolDefinition() { return definition; }

            @Override public String call(String input) {
                calls.incrementAndGet();
                return "ok";
            }
        };
        ToolPolicyGate policy = new ToolPolicyGate(context.pins.workspaceId(), identity, Optional.of(POLICY));
        return new RecordingToolCallback(delegate, new DurableRunRecorder(context, 64), policy,
                ToolInputSchema.compile(delegate.getToolDefinition().inputSchema()), gate(context, identity, guardrail));
    }

    private static ToolInputGuardrailGate gate(RecordingRunContext context, InvocationIdentity identity,
            ToolInputGuardrail guardrail) {
        return new ToolInputGuardrailGate(guardrail, context.pins, identity, Optional.of(POLICY), "ledger",
                "core:ledger:1", Optional.of(EFFECT));
    }

    private static ToolInputGuardrail guardrail(String id,
            java.util.function.Function<ToolInputGuardrail.Request, ToolInputGuardrail.Decision> evaluator) {
        return new ToolInputGuardrail() {
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
                .filter(event -> event.type().name().equals("TOOL_INPUT_GUARDRAIL_EVALUATED"))
                .findFirst().orElseThrow().payload();
    }
}
