package com.dvarahq.agentworks.agent.springai;

import com.dvarahq.agentworks.capability.ToolEffect;
import com.dvarahq.agentworks.domain.security.AutonomyProfile;
import com.dvarahq.agentworks.domain.security.ExecutionIntent;
import com.dvarahq.agentworks.domain.security.ExecutionPolicy;
import com.dvarahq.agentworks.domain.security.InvocationIdentity;
import com.dvarahq.agentworks.domain.security.PermissionRisk;
import com.dvarahq.agentworks.interaction.HumanInteractionRecord;
import com.dvarahq.agentworks.interaction.HumanInteractionResponse;
import com.dvarahq.agentworks.interaction.HumanInteractionStatus;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class ToolInteractionBindingTest {
    private static final ToolEffect EFFECT = new ToolEffect("ledger", "write", PermissionRisk.HIGH);
    private static final ExecutionPolicy POLICY =
            new ExecutionPolicy(ExecutionIntent.EXECUTE, AutonomyProfile.SUPERVISED);

    @Test
    void approvalBlocksWithoutCallingTheToolThenAllowsTheRecoveredInvocation() {
        RecordingRunContext context = new RecordingRunContext();
        InvocationIdentity identity = identity(context);
        AtomicReference<HumanInteractionRecord> stored = new AtomicReference<>();
        ToolInteractionAuthority authority = (workspace, run, key) -> Optional.ofNullable(stored.get());
        AtomicInteger calls = new AtomicInteger();

        ToolInteractionRequiredException required = catchThrowableOfType(ToolInteractionRequiredException.class,
                () -> callback(context, identity, authority, calls).call("{\"secret\":\"input-canary\"}"));

        assertThat(calls).hasValue(0);
        assertThat(required.draft().workspaceId()).isEqualTo(context.pins.workspaceId());
        assertThat(required.draft().runId()).isEqualTo(context.pins.id());
        assertThat(required.draft().assignment().principalId()).contains(identity.userId());
        assertThat(required.draft().toString()).doesNotContain("input-canary");
        var request = required.draft().at(Instant.parse("2026-01-01T00:00:00Z"));
        stored.set(new HumanInteractionRecord(request, 1, HumanInteractionStatus.RESPONDED,
                Optional.of(new HumanInteractionResponse.Approval(true)), Optional.of("user:test"),
                Optional.of(Instant.parse("2026-01-01T00:01:00Z"))));

        assertThat(callback(context, identity, authority, calls).call("{\"secret\":\"input-canary\"}"))
                .isEqualTo("ok");
        assertThat(calls).hasValue(1);
        assertThat(context.events.toString()).doesNotContain("input-canary");
    }

    @Test
    void aRejectedApprovalFailsClosedBeforeToolOrCredentialWork() {
        RecordingRunContext context = new RecordingRunContext();
        InvocationIdentity identity = identity(context);
        AtomicReference<HumanInteractionRecord> stored = new AtomicReference<>();
        ToolInteractionAuthority authority = (workspace, run, key) -> Optional.ofNullable(stored.get());
        AtomicInteger calls = new AtomicInteger();
        ToolInteractionRequiredException required = catchThrowableOfType(ToolInteractionRequiredException.class,
                () -> callback(context, identity, authority, calls).call("{}"));
        var request = required.draft().at(Instant.parse("2026-01-01T00:00:00Z"));
        stored.set(new HumanInteractionRecord(request, 1, HumanInteractionStatus.RESPONDED,
                Optional.of(new HumanInteractionResponse.Approval(false)), Optional.of("user:test"),
                Optional.of(Instant.parse("2026-01-01T00:01:00Z"))));

        assertThatThrownBy(() -> callback(context, identity, authority, calls).call("{}"))
                .isInstanceOfSatisfying(OutputRejectedException.class,
                        failure -> assertThat(failure.code()).isEqualTo("TOOL_INTERACTION_DENIED"));
        assertThat(calls).hasValue(0);
    }

    private static RecordingToolCallback callback(RecordingRunContext context, InvocationIdentity identity,
            ToolInteractionAuthority authority, AtomicInteger calls) {
        ToolCallback delegate = new ToolCallback() {
            private final ToolDefinition definition = ToolDefinition.builder().name("ledger")
                    .description("ledger").inputSchema("{\"type\":\"object\"}").build();
            @Override public ToolDefinition getToolDefinition() { return definition; }
            @Override public String call(String input) { calls.incrementAndGet(); return "ok"; }
        };
        ToolInputGuardrail guardrail = new ToolInputGuardrail() {
            @Override public String id() { return "app:approval:1"; }
            @Override public Decision evaluate(Request request) {
                return new Decision(Outcome.INTERACTION_REQUIRED, "REVIEW_REQUIRED");
            }
        };
        return new RecordingToolCallback(delegate, new DurableRunRecorder(context, 64),
                new ToolPolicyGate(context.pins.workspaceId(), identity, Optional.of(POLICY)),
                ToolInputSchema.compile(delegate.getToolDefinition().inputSchema()),
                new ToolInputGuardrailGate(guardrail, context.pins, identity, Optional.of(POLICY), "ledger",
                        "core:ledger:1", Optional.of(EFFECT)),
                ToolOutputGuardrailGate.legacyCompatibility(), null, null,
                new ToolInteractionBinding(context.pins, identity, "ledger", "core:ledger:1", EFFECT, authority));
    }

    private static InvocationIdentity identity(RecordingRunContext context) {
        UUID user = UUID.randomUUID();
        return new InvocationIdentity(UUID.randomUUID(), context.pins.workspaceId(), user, UUID.randomUUID(), user);
    }
}
