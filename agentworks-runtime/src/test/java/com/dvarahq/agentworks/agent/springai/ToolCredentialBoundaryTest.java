package com.dvarahq.agentworks.agent.springai;

import com.dvarahq.agentworks.capability.ToolId;
import com.dvarahq.agentworks.credential.CredentialAuditEvent;
import com.dvarahq.agentworks.credential.CredentialAuthorization;
import com.dvarahq.agentworks.credential.CredentialProvider;
import com.dvarahq.agentworks.credential.CredentialUseRequest;
import com.dvarahq.agentworks.credential.JitCredentialToolInvoker;
import com.dvarahq.agentworks.credential.ResolvedCredential;
import com.dvarahq.agentworks.credential.conformance.DeterministicFakeCredentialProvider;
import com.dvarahq.agentworks.domain.security.AutonomyProfile;
import com.dvarahq.agentworks.domain.security.CredentialRef;
import com.dvarahq.agentworks.domain.security.ExecutionIntent;
import com.dvarahq.agentworks.domain.security.ExecutionPolicy;
import com.dvarahq.agentworks.domain.security.InvocationIdentity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolCredentialBoundaryTest {
    private static final Instant NOW = Instant.parse("2026-10-08T12:00:00Z");
    private static final CredentialRef REFERENCE = new CredentialRef("vault://workspaces/demo/calendar");
    private static final String SECRET_A = "credential-A-secret-canary";
    private static final String SECRET_B = "credential-B-secret-canary";
    private static final ExecutionPolicy POLICY =
            new ExecutionPolicy(ExecutionIntent.EXECUTE, AutonomyProfile.SUPERVISED);

    private DeterministicFakeCredentialProvider provider;
    private List<CredentialAuditEvent> audit;
    private JitCredentialToolInvoker invoker;
    private AtomicReference<CredentialUseRequest> requested;

    @BeforeEach
    void setUp() {
        provider = new DeterministicFakeCredentialProvider();
        audit = new ArrayList<>();
        requested = new AtomicReference<>();
        CredentialProvider observing = new CredentialProvider() {
            @Override
            public CredentialAuthorization authorize(CredentialUseRequest request, Instant now) {
                requested.set(request);
                return provider.authorize(request, now);
            }
            @Override
            public CredentialAuthorization authorize(CredentialRef reference, InvocationIdentity identity,
                    Instant now) {
                return provider.authorize(reference, identity, now);
            }
            @Override
            public ResolvedCredential resolve(CredentialAuthorization authorization, Instant now) {
                return provider.resolve(authorization, now);
            }
        };
        invoker = new JitCredentialToolInvoker(observing, audit::add, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void tearDown() {
        provider.close();
    }

    @Test
    void resolvesAfterAllPreUseGatesAndZeroizesImmediatelyAfterTheCallback() {
        RecordingRunContext context = new RecordingRunContext();
        InvocationIdentity identity = identity(context);
        provider.put(REFERENCE, identity, SECRET_A, NOW.plusSeconds(60));
        AtomicReference<char[]> observed = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        CredentialedToolCallback delegate = callback((input, toolContext, material) -> {
            calls.incrementAndGet();
            observed.set(material);
            assertThat(new String(material)).isEqualTo(SECRET_A);
            return "calendar-ok";
        });

        String result = recording(context, identity, delegate).call("{\"value\":\"meeting\"}");

        assertThat(result).isEqualTo("calendar-ok");
        assertThat(calls).hasValue(1);
        assertThat(provider.timeline()).containsExactly(
                DeterministicFakeCredentialProvider.Operation.AUTHORIZE,
                DeterministicFakeCredentialProvider.Operation.RESOLVE);
        assertThat(observed.get()).containsOnly('\0');
        assertThat(requested.get().agentId()).contains(context.pins.definitionId());
        assertThat(context.types()).containsExactly("TOOL_REQUESTED", "TOOL_INPUT_SCHEMA_VALIDATED",
                "TOOL_POLICY_EVALUATED", "TOOL_INPUT_GUARDRAIL_EVALUATED", "TOOL_STARTED",
                "TOOL_OUTPUT_GUARDRAIL_EVALUATED", "TOOL_COMPLETED");
        assertThat(audit).singleElement().satisfies(event -> assertThat(event.outcome()).isEqualTo("SUCCEEDED"));
        assertThat(context.events.toString() + audit).doesNotContain(SECRET_A, SECRET_B);
    }

    @Test
    void rotationIsObservedOnTheNextCallWithoutChangingTheBinding() {
        RecordingRunContext context = new RecordingRunContext();
        InvocationIdentity identity = identity(context);
        List<String> observed = new ArrayList<>();
        CredentialedToolCallback delegate = callback((input, toolContext, material) -> {
            observed.add(new String(material));
            return "ok";
        });
        RecordingToolCallback callback = recording(context, identity, delegate);

        provider.put(REFERENCE, identity, SECRET_A, NOW.plusSeconds(60));
        callback.call("{\"value\":\"first\"}");
        provider.put(REFERENCE, identity, SECRET_B, NOW.plusSeconds(120));
        callback.call("{\"value\":\"second\"}");

        assertThat(observed).containsExactly(SECRET_A, SECRET_B);
        assertThat(provider.resolveCalls()).isEqualTo(2);
        assertThat(context.events.toString() + audit).doesNotContain(SECRET_A, SECRET_B);
    }

    @Test
    void providerOutageFailsClosedWithFixedEvidenceAndSuppressesTheTool() {
        RecordingRunContext context = new RecordingRunContext();
        InvocationIdentity identity = identity(context);
        provider.put(REFERENCE, identity, SECRET_A, NOW.plusSeconds(60));
        provider.available(false);
        AtomicInteger calls = new AtomicInteger();
        CredentialedToolCallback delegate = callback((input, toolContext, material) -> {
            calls.incrementAndGet();
            return "must-not-run";
        });

        assertThatThrownBy(() -> recording(context, identity, delegate).call("{\"value\":\"meeting\"}"))
                .isInstanceOfSatisfying(OutputRejectedException.class,
                        failure -> assertThat(failure.code()).isEqualTo("TOOL_CREDENTIAL_UNAVAILABLE"))
                .hasMessage("the tool credential could not be authorized or resolved");

        assertThat(calls).hasValue(0);
        assertThat(provider.resolveCalls()).isZero();
        assertThat(context.types()).endsWith("TOOL_STARTED", "TOOL_FAILED")
                .doesNotContain("TOOL_COMPLETED", "TOOL_OUTPUT_GUARDRAIL_EVALUATED");
        assertThat(context.events.toString() + audit).doesNotContain(SECRET_A, "credential provider is unavailable");
    }

    @Test
    void invalidInputStopsBeforeCredentialAuthorization() {
        RecordingRunContext context = new RecordingRunContext();
        InvocationIdentity identity = identity(context);
        provider.put(REFERENCE, identity, SECRET_A, NOW.plusSeconds(60));
        CredentialedToolCallback delegate = callback((input, toolContext, material) -> "must-not-run");

        assertThatThrownBy(() -> recording(context, identity, delegate).call("{}"))
                .isInstanceOfSatisfying(OutputRejectedException.class,
                        failure -> assertThat(failure.code()).isEqualTo("TOOL_INPUT_INVALID"));

        assertThat(provider.timeline()).isEmpty();
        assertThat(provider.resolveCalls()).isZero();
        assertThat(context.types()).containsExactly("TOOL_REQUESTED", "TOOL_INPUT_SCHEMA_VALIDATED");
    }

    @Test
    void directInvocationIsRefusedAndCallbackFailuresKeepTheirIdentity() {
        RecordingRunContext context = new RecordingRunContext();
        InvocationIdentity identity = identity(context);
        provider.put(REFERENCE, identity, SECRET_A, NOW.plusSeconds(60));
        IllegalArgumentException marker = new IllegalArgumentException("callback marker");
        CredentialedToolCallback delegate = callback((input, toolContext, material) -> { throw marker; });

        assertThatThrownBy(() -> delegate.call("{}"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("JIT invocation");
        assertThatThrownBy(() -> recording(context, identity, delegate).call("{\"value\":\"meeting\"}"))
                .isSameAs(marker);
        assertThat(context.types()).endsWith("TOOL_STARTED", "TOOL_FAILED");
    }

    @Test
    void capabilityBindingsRequireAnExplicitValidSlotExactlyForCredentialedCallbacks() {
        ToolId id = new ToolId("core", "calendar", "1.0.0");
        CredentialedToolCallback credentialed = callback((input, context, material) -> "ok");
        org.springframework.ai.tool.ToolCallback ordinary = new org.springframework.ai.tool.ToolCallback() {
            @Override public ToolDefinition getToolDefinition() { return credentialed.getToolDefinition(); }
            @Override public String call(String input) { return "ok"; }
        };

        assertThatThrownBy(() -> new CapabilityToolCallback(id, credentialed))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CapabilityToolCallback(id, ordinary, "calendar.primary"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CapabilityToolCallback(id, credentialed, "bad name"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new CapabilityToolCallback(id, credentialed, "calendar.primary").credentialName())
                .contains("calendar.primary");
    }

    private RecordingToolCallback recording(RecordingRunContext context, InvocationIdentity identity,
            CredentialedToolCallback delegate) {
        ToolPolicyGate policy = new ToolPolicyGate(context.pins.workspaceId(), identity, Optional.of(POLICY));
        ToolCredentialBinding binding = new ToolCredentialBinding(invoker, REFERENCE, identity,
                context.pins.definitionId());
        return new RecordingToolCallback(delegate, new DurableRunRecorder(context, 64), policy,
                ToolInputSchema.compile(delegate.getToolDefinition().inputSchema()),
                ToolInputGuardrailGate.legacyCompatibility(), ToolOutputGuardrailGate.legacyCompatibility(), binding);
    }

    private static CredentialedToolCallback callback(CredentialCall call) {
        return new CredentialedToolCallback() {
            private final ToolDefinition definition = ToolDefinition.builder().name("calendar")
                    .description("calendar test")
                    .inputSchema("""
                            {"type":"object","properties":{"value":{"type":"string"}},
                             "required":["value"],"additionalProperties":false}
                            """).build();

            @Override public ToolDefinition getToolDefinition() { return definition; }

            @Override
            public String call(String input, ToolContext context, char[] credentialMaterial) {
                return call.invoke(input, context, credentialMaterial);
            }
        };
    }

    private static InvocationIdentity identity(RecordingRunContext context) {
        UUID user = UUID.randomUUID();
        return new InvocationIdentity(UUID.randomUUID(), context.pins.workspaceId(), user, UUID.randomUUID(), user);
    }

    @FunctionalInterface
    private interface CredentialCall {
        String invoke(String input, ToolContext context, char[] material);
    }
}
