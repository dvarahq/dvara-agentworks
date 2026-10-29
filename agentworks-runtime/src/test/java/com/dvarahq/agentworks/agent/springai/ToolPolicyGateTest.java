package com.dvarahq.agentworks.agent.springai;

import com.dvarahq.agentworks.capability.ToolEffect;
import com.dvarahq.agentworks.domain.run.RunEventType;
import com.dvarahq.agentworks.domain.security.AutonomyProfile;
import com.dvarahq.agentworks.domain.security.ExecutionIntent;
import com.dvarahq.agentworks.domain.security.ExecutionPolicy;
import com.dvarahq.agentworks.domain.security.InvocationIdentity;
import com.dvarahq.agentworks.domain.security.PermissionRisk;
import com.dvarahq.agentworks.domain.security.RememberedPermissionEvaluator;
import com.dvarahq.agentworks.domain.security.RememberedPermissionRequest;
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

/** Issue #39: the deterministic policy boundary before a tool callback or credential lookup. */
class ToolPolicyGateTest {

    @Test
    void anAllowedInvocationRecordsPolicyBetweenRequestAndStartWithoutRawArguments() {
        RecordingRunContext context = new RecordingRunContext();
        DurableRunRecorder recorder = new DurableRunRecorder(context, 64);
        AtomicInteger calls = new AtomicInteger();
        ToolCallback delegate = callback(calls);
        ToolPolicyGate gate = new ToolPolicyGate(context.pins.workspaceId(), identity(context.pins.workspaceId()),
                Optional.of(new ExecutionPolicy(ExecutionIntent.EXECUTE, AutonomyProfile.AUTONOMOUS)));

        String result = new RecordingToolCallback(delegate, recorder, gate).call("{\"token\":\"secret-canary\"}");

        assertThat(result).isEqualTo("ok");
        assertThat(calls).hasValue(1);
        assertThat(context.types()).containsExactly("TOOL_REQUESTED", "TOOL_INPUT_SCHEMA_VALIDATED",
                "TOOL_POLICY_EVALUATED", "TOOL_INPUT_GUARDRAIL_EVALUATED", "TOOL_STARTED",
                "TOOL_OUTPUT_GUARDRAIL_EVALUATED", "TOOL_COMPLETED");
        Map<String, Object> policy = policy(context);
        assertThat(policy).containsEntry("decision", "ALLOW").containsEntry("reason", "PINNED_POLICY_ALLOWED")
                .containsEntry("intent", "EXECUTE").containsEntry("autonomyProfile", "AUTONOMOUS");
        assertThat(context.events.toString()).doesNotContain("secret-canary");
    }

    @Test
    void everyAutonomyProfileLosesToAHardWorkspaceDenialBeforeTheCallback() {
        for (AutonomyProfile profile : AutonomyProfile.values()) {
            RecordingRunContext context = new RecordingRunContext();
            AtomicInteger calls = new AtomicInteger();
            ToolPolicyGate gate = new ToolPolicyGate(context.pins.workspaceId(), identity(UUID.randomUUID()),
                    Optional.of(new ExecutionPolicy(ExecutionIntent.EXECUTE, profile)));
            RecordingToolCallback callback = new RecordingToolCallback(callback(calls),
                    new DurableRunRecorder(context, 64), gate);

            assertThatThrownBy(() -> callback.call("{}")).isInstanceOfSatisfying(
                    OutputRejectedException.class, denied -> assertThat(denied.code()).isEqualTo("TOOL_POLICY_DENIED"));
            assertThat(calls).as(profile.name()).hasValue(0);
            assertThat(context.types()).as(profile.name()).containsExactly("TOOL_REQUESTED",
                    "TOOL_INPUT_SCHEMA_VALIDATED", "TOOL_POLICY_EVALUATED").doesNotContain("TOOL_STARTED");
            Map<String, Object> decision = policy(context);
            assertThat(decision).containsEntry("decision", "DENY").containsEntry("reason", "WORKSPACE_MISMATCH")
                    .containsEntry("autonomyProfile", profile.name());
        }
    }

    @Test
    void nonExecuteIntentAndUnsupportedDelegationFailBeforeTheCallback() {
        RecordingRunContext intentContext = new RecordingRunContext();
        AtomicInteger intentCalls = new AtomicInteger();
        ToolPolicyGate intentGate = new ToolPolicyGate(intentContext.pins.workspaceId(),
                identity(intentContext.pins.workspaceId()),
                Optional.of(new ExecutionPolicy(ExecutionIntent.PROPOSE, AutonomyProfile.AUTONOMOUS)));

        assertThatThrownBy(() -> new RecordingToolCallback(callback(intentCalls),
                new DurableRunRecorder(intentContext, 64), intentGate).call("{}"))
                .isInstanceOf(OutputRejectedException.class);
        assertThat(intentCalls).hasValue(0);
        assertReason(intentContext, "EXECUTION_INTENT_DENIED");

        RecordingRunContext delegatedContext = new RecordingRunContext();
        AtomicInteger delegatedCalls = new AtomicInteger();
        InvocationIdentity identity = identity(delegatedContext.pins.workspaceId());
        InvocationIdentity delegated = new InvocationIdentity(identity.organizationId(), identity.workspaceId(),
                identity.userId(), identity.sessionId(), UUID.randomUUID());
        ToolPolicyGate delegatedGate = new ToolPolicyGate(delegatedContext.pins.workspaceId(), delegated,
                Optional.of(new ExecutionPolicy(ExecutionIntent.EXECUTE, AutonomyProfile.AUTONOMOUS)));

        assertThatThrownBy(() -> new RecordingToolCallback(callback(delegatedCalls),
                new DurableRunRecorder(delegatedContext, 64), delegatedGate).call("{}"))
                .isInstanceOf(OutputRejectedException.class);
        assertThat(delegatedCalls).hasValue(0);
        assertReason(delegatedContext, "DELEGATED_SUBJECT_MISMATCH");
    }

    @Test
    void retainedLegacyManifestsReceiveAnExplicitCompatibilityDecision() {
        RecordingRunContext context = new RecordingRunContext();
        AtomicInteger calls = new AtomicInteger();
        ToolPolicyGate gate = new ToolPolicyGate(context.pins.workspaceId(), identity(context.pins.workspaceId()),
                Optional.empty());

        assertThat(new RecordingToolCallback(callback(calls), new DurableRunRecorder(context, 64), gate).call("{}"))
                .isEqualTo("ok");
        assertThat(calls).hasValue(1);
        assertReason(context, "LEGACY_MANIFEST_COMPATIBILITY");
    }

    @Test
    void pinnedEffectIsReReadAndAnExactRememberedPermissionAllowsTheCallback() {
        RecordingRunContext context = new RecordingRunContext();
        InvocationIdentity identity = identity(context.pins.workspaceId());
        AtomicReference<RememberedPermissionRequest> observed = new AtomicReference<>();
        UUID ruleId = UUID.randomUUID();
        ToolPolicyGate gate = new ToolPolicyGate(context.pins, identity,
                Optional.of(new ExecutionPolicy(ExecutionIntent.EXECUTE, AutonomyProfile.SUPERVISED)),
                "core:ledger:1.0.0", new ToolEffect("ledger", "write", PermissionRisk.HIGH), request -> {
                    observed.set(request);
                    return new RememberedPermissionEvaluator.Decision(RememberedPermissionEvaluator.Verdict.ALLOW,
                            RememberedPermissionEvaluator.Reason.MATCHED_ALLOW, Optional.of(ruleId), Optional.of(7L));
                });
        AtomicInteger calls = new AtomicInteger();

        assertThat(new RecordingToolCallback(callback(calls), new DurableRunRecorder(context, 64), gate).call("{}"))
                .isEqualTo("ok");

        assertThat(calls).hasValue(1);
        assertThat(observed.get()).satisfies(request -> {
            assertThat(request.organizationId()).isEqualTo(identity.organizationId());
            assertThat(request.workspaceId()).isEqualTo(context.pins.workspaceId());
            assertThat(request.actorId()).isEqualTo(identity.userId());
            assertThat(request.agentDefinitionId()).isEqualTo(context.pins.definitionId());
            assertThat(request.releaseId()).isEqualTo(context.pins.releaseId());
            assertThat(request.environmentId()).isEqualTo(context.pins.environmentId());
            assertThat(request.capability()).isEqualTo("core:ledger:1.0.0");
            assertThat(request.resource()).isEqualTo("ledger");
            assertThat(request.action()).isEqualTo("write");
            assertThat(request.risk()).isEqualTo(PermissionRisk.HIGH);
        });
        Map<String, Object> policy = policy(context);
        assertThat(policy).containsEntry("reason", "REMEMBERED_PERMISSION_MATCHED_ALLOW");
        @SuppressWarnings("unchecked")
        Map<String, Object> permission = (Map<String, Object>) policy.get("rememberedPermission");
        assertThat(permission).containsEntry("decision", "ALLOW").containsEntry("reason", "MATCHED_ALLOW")
                .containsEntry("ruleId", ruleId.toString()).containsEntry("ruleVersion", 7L)
                .containsEntry("capability", "core:ledger:1.0.0").containsEntry("resource", "ledger")
                .containsEntry("action", "write").containsEntry("risk", "HIGH");
    }

    @Test
    void everyUseRechecksPermissionAndARevocationStopsBeforeAnotherEffect() {
        RecordingRunContext context = new RecordingRunContext();
        AtomicInteger evaluations = new AtomicInteger();
        AtomicInteger calls = new AtomicInteger();
        ToolPolicyGate gate = new ToolPolicyGate(context.pins, identity(context.pins.workspaceId()),
                Optional.of(new ExecutionPolicy(ExecutionIntent.EXECUTE, AutonomyProfile.AUTONOMOUS)),
                "core:effect:1", new ToolEffect("record", "update", PermissionRisk.MEDIUM), request -> {
                    if (evaluations.incrementAndGet() == 1) {
                        return new RememberedPermissionEvaluator.Decision(RememberedPermissionEvaluator.Verdict.ALLOW,
                                RememberedPermissionEvaluator.Reason.MATCHED_ALLOW, Optional.of(UUID.randomUUID()),
                                Optional.of(1L));
                    }
                    return new RememberedPermissionEvaluator.Decision(RememberedPermissionEvaluator.Verdict.DENY,
                            RememberedPermissionEvaluator.Reason.REVOKED, Optional.of(UUID.randomUUID()),
                            Optional.of(2L));
                });
        RecordingToolCallback callback = new RecordingToolCallback(callback(calls),
                new DurableRunRecorder(context, 64), gate);

        assertThat(callback.call("{}")).isEqualTo("ok");
        assertThatThrownBy(() -> callback.call("{}")).isInstanceOfSatisfying(OutputRejectedException.class,
                denied -> assertThat(denied.code()).isEqualTo("TOOL_POLICY_DENIED"));

        assertThat(evaluations).hasValue(2);
        assertThat(calls).hasValue(1);
        assertReason(context, "REMEMBERED_PERMISSION_REVOKED");
        assertThat(context.types()).containsExactly("TOOL_REQUESTED", "TOOL_INPUT_SCHEMA_VALIDATED",
                "TOOL_POLICY_EVALUATED", "TOOL_INPUT_GUARDRAIL_EVALUATED", "TOOL_STARTED",
                "TOOL_OUTPUT_GUARDRAIL_EVALUATED", "TOOL_COMPLETED",
                "TOOL_REQUESTED", "TOOL_INPUT_SCHEMA_VALIDATED", "TOOL_POLICY_EVALUATED");
    }

    @Test
    void missingOrUnavailablePermissionAuthorityFailsClosed() {
        for (ToolPermissionAuthority authority : new ToolPermissionAuthority[] {null, request -> {
            throw new IllegalStateException("database unavailable");
        }}) {
            RecordingRunContext context = new RecordingRunContext();
            AtomicInteger calls = new AtomicInteger();
            ToolPolicyGate gate = new ToolPolicyGate(context.pins, identity(context.pins.workspaceId()),
                    Optional.of(new ExecutionPolicy(ExecutionIntent.EXECUTE, AutonomyProfile.AUTONOMOUS)),
                    "core:effect:1", new ToolEffect("record", "update", PermissionRisk.LOW), authority);

            assertThatThrownBy(() -> new RecordingToolCallback(callback(calls),
                    new DurableRunRecorder(context, 64), gate).call("{}"))
                    .isInstanceOf(OutputRejectedException.class);
            assertThat(calls).hasValue(0);
            assertReason(context, "REMEMBERED_PERMISSION_AUTHORITY_UNAVAILABLE");
        }
    }

    @Test
    void noMatchingPermissionDeniesAndHardIdentityFailureSkipsTheAuthority() {
        RecordingRunContext noMatch = new RecordingRunContext();
        AtomicInteger calls = new AtomicInteger();
        ToolPolicyGate noMatchGate = new ToolPolicyGate(noMatch.pins, identity(noMatch.pins.workspaceId()),
                Optional.of(new ExecutionPolicy(ExecutionIntent.EXECUTE, AutonomyProfile.SUPERVISED)),
                "core:effect:1", new ToolEffect("record", "read", PermissionRisk.LOW), request ->
                new RememberedPermissionEvaluator.Decision(RememberedPermissionEvaluator.Verdict.DENY,
                        RememberedPermissionEvaluator.Reason.NO_MATCH, Optional.empty(), Optional.empty()));

        assertThatThrownBy(() -> new RecordingToolCallback(callback(calls),
                new DurableRunRecorder(noMatch, 64), noMatchGate).call("{}"))
                .isInstanceOf(OutputRejectedException.class);
        assertThat(calls).hasValue(0);
        assertReason(noMatch, "REMEMBERED_PERMISSION_NO_MATCH");

        RecordingRunContext mismatch = new RecordingRunContext();
        AtomicInteger evaluations = new AtomicInteger();
        ToolPolicyGate mismatchGate = new ToolPolicyGate(mismatch.pins, identity(UUID.randomUUID()),
                Optional.of(new ExecutionPolicy(ExecutionIntent.EXECUTE, AutonomyProfile.AUTONOMOUS)),
                "core:effect:1", new ToolEffect("record", "read", PermissionRisk.LOW), request -> {
                    evaluations.incrementAndGet();
                    throw new AssertionError("hard denial must run first");
                });

        assertThatThrownBy(() -> new RecordingToolCallback(callback(calls),
                new DurableRunRecorder(mismatch, 64), mismatchGate).call("{}"))
                .isInstanceOf(OutputRejectedException.class);
        assertThat(evaluations).hasValue(0);
        assertReason(mismatch, "WORKSPACE_MISMATCH");
    }

    @Test
    void invalidInputStopsBeforePolicyAndTheCallbackWithoutPersistingArguments() {
        RecordingRunContext context = new RecordingRunContext();
        AtomicInteger evaluations = new AtomicInteger();
        AtomicInteger calls = new AtomicInteger();
        ToolPolicyGate gate = new ToolPolicyGate(context.pins, identity(context.pins.workspaceId()),
                Optional.of(new ExecutionPolicy(ExecutionIntent.EXECUTE, AutonomyProfile.AUTONOMOUS)),
                "core:effect:1", new ToolEffect("record", "update", PermissionRisk.LOW), request -> {
                    evaluations.incrementAndGet();
                    throw new AssertionError("input validation must run before policy");
                });
        ToolCallback delegate = callback(calls, "{\"type\":\"object\",\"required\":[\"account\"],"
                + "\"properties\":{\"account\":{\"type\":\"string\"}},\"additionalProperties\":false}");

        assertThatThrownBy(() -> new RecordingToolCallback(delegate, new DurableRunRecorder(context, 64), gate)
                .call("{\"account\":7,\"secret\":\"secret-canary\"}"))
                .isInstanceOfSatisfying(OutputRejectedException.class,
                        denied -> assertThat(denied.code()).isEqualTo("TOOL_INPUT_INVALID"));

        assertThat(evaluations).hasValue(0);
        assertThat(calls).hasValue(0);
        assertThat(context.types()).containsExactly("TOOL_REQUESTED", "TOOL_INPUT_SCHEMA_VALIDATED");
        @SuppressWarnings("unchecked")
        Map<String, Object> validation = (Map<String, Object>) context.events.getLast().payload();
        assertThat(validation).containsEntry("decision", "DENY").containsEntry("reason", "SCHEMA_VIOLATION")
                .hasEntrySatisfying("violations", count -> assertThat((Integer) count).isPositive());
        assertThat(context.events.toString()).doesNotContain("account", "secret-canary");
    }

    private static void assertReason(RecordingRunContext context, String reason) {
        assertThat(context.events.stream().filter(event -> event.type() == RunEventType.TOOL_POLICY_EVALUATED)
                .map(event -> String.valueOf(((Map<?, ?>) event.payload()).get("reason")))).contains(reason);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> policy(RecordingRunContext context) {
        return (Map<String, Object>) context.events.stream()
                .filter(event -> event.type() == RunEventType.TOOL_POLICY_EVALUATED).findFirst().orElseThrow().payload();
    }

    private static InvocationIdentity identity(UUID workspaceId) {
        UUID user = UUID.randomUUID();
        return new InvocationIdentity(UUID.randomUUID(), workspaceId, user, UUID.randomUUID(), user);
    }

    private static ToolCallback callback(AtomicInteger calls) {
        return callback(calls, "{\"type\":\"object\"}");
    }

    private static ToolCallback callback(AtomicInteger calls, String schema) {
        return new ToolCallback() {
            private final ToolDefinition definition = ToolDefinition.builder().name("effect")
                    .description("test effect").inputSchema(schema).build();

            @Override public ToolDefinition getToolDefinition() { return definition; }

            @Override public String call(String input) {
                calls.incrementAndGet();
                return "ok";
            }
        };
    }
}
