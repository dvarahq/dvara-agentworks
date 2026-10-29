package com.dvarahq.agentworks.gateway.conformance;

import com.dvarahq.agentworks.credential.CredentialAuditEvent;
import com.dvarahq.agentworks.credential.CredentialAuthorization;
import com.dvarahq.agentworks.credential.CredentialProvider;
import com.dvarahq.agentworks.credential.CredentialResolutionException;
import com.dvarahq.agentworks.credential.ResolvedCredential;
import com.dvarahq.agentworks.domain.lifecycle.AgentReleaseId;
import com.dvarahq.agentworks.domain.lifecycle.AgentRunId;
import com.dvarahq.agentworks.domain.security.CredentialRef;
import com.dvarahq.agentworks.domain.security.InvocationIdentity;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import com.dvarahq.agentworks.gateway.AdapterInvocation;
import com.dvarahq.agentworks.gateway.AdapterResult;
import com.dvarahq.agentworks.gateway.AiGatewayAdapter;
import com.dvarahq.agentworks.gateway.CancellationSignal;
import com.dvarahq.agentworks.gateway.CapabilityPolicy;
import com.dvarahq.agentworks.gateway.ChunkSink;
import com.dvarahq.agentworks.gateway.ConfiguredGateway;
import com.dvarahq.agentworks.gateway.DegradedBehavior;
import com.dvarahq.agentworks.gateway.FinishReason;
import com.dvarahq.agentworks.gateway.GatewayCapability;
import com.dvarahq.agentworks.gateway.GatewayDescriptor;
import com.dvarahq.agentworks.gateway.GatewayErrorCode;
import com.dvarahq.agentworks.gateway.GatewayEvent;
import com.dvarahq.agentworks.gateway.GatewayException;
import com.dvarahq.agentworks.gateway.GatewayRegistry;
import com.dvarahq.agentworks.gateway.InvocationCorrelation;
import com.dvarahq.agentworks.gateway.ModelChunk;
import com.dvarahq.agentworks.gateway.ModelGateway;
import com.dvarahq.agentworks.gateway.ModelInvocation;
import com.dvarahq.agentworks.gateway.ModelMessage;
import com.dvarahq.agentworks.gateway.ModelRequest;
import com.dvarahq.agentworks.gateway.ModelResolutionRequest;
import com.dvarahq.agentworks.gateway.ModelResponse;
import com.dvarahq.agentworks.gateway.ModelRole;
import com.dvarahq.agentworks.gateway.ModelRoleBinding;
import com.dvarahq.agentworks.gateway.ModelUsage;
import com.dvarahq.agentworks.gateway.PinnedModel;
import com.dvarahq.agentworks.gateway.PinnedModelRoles;
import com.dvarahq.agentworks.gateway.ResolvedModel;
import com.dvarahq.agentworks.gateway.ResponseFormat;
import com.dvarahq.agentworks.gateway.ServedModel;
import com.dvarahq.agentworks.gateway.ToolCall;
import com.dvarahq.agentworks.gateway.ToolDefinition;
import com.dvarahq.agentworks.gateway.fake.DeterministicFakeGatewayAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * The adapter conformance suite (ADR-009 validation evidence). Every {@link AiGatewayAdapter} runs it through
 * {@link ModelGateway}, the same path production uses, against a deterministic provider supplied by a
 * {@link ConformanceHarness}. It proves alias resolution and pinning, ordered streaming, usage and served-identity
 * reporting, tool calls, structured output, capability negotiation and explicit degradation, cancellation, deadlines,
 * typed provider failures, just-in-time credential handling, tenant refusal, and redaction. No test needs a network
 * provider or a paid credential.
 */
public abstract class AiGatewayAdapterConformanceSuite {

    protected static final String CREDENTIAL = "conformance-credential-7c1e-do-not-log";
    protected static final String SENSITIVE_PROMPT = "SENSITIVE-PROMPT-TEXT-4411";
    protected static final Map<String, String> CATALOG = Map.of("standard", "provider-model-1");
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final ModelUsage USAGE = ModelUsage.reported(11, 7, 18L, 3L, 2L);

    private final WorkspaceId workspace = new WorkspaceId(UUID.randomUUID());
    private final AgentReleaseId release = new AgentReleaseId(UUID.randomUUID());
    private final InvocationIdentity identity = new InvocationIdentity(UUID.randomUUID(), workspace.value(),
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    private final List<GatewayEvent> events = new CopyOnWriteArrayList<>();
    private final List<CredentialAuditEvent> audits = new CopyOnWriteArrayList<>();
    private final RecordingCredentialProvider credentials = new RecordingCredentialProvider();
    protected ConformanceHarness harness;

    /** Creates a fresh harness and provider for one test. */
    protected abstract ConformanceHarness createHarness();

    @BeforeEach
    void startHarness() {
        harness = createHarness();
    }

    @AfterEach
    void stopHarness() {
        harness.close();
    }

    @Test
    void descriptorAndCapabilitiesAreStableAndSecretFree() {
        AiGatewayAdapter adapter = adapter(all());

        GatewayDescriptor descriptor = adapter.descriptor();
        assertThat(descriptor.gatewayId()).isEqualTo(ConformanceHarness.GATEWAY_ID);
        assertThat(adapter.type()).isEqualTo(descriptor.type());
        assertThat(adapter.capabilities()).containsAll(GatewayCapability.negotiatedCapabilities())
                .isEqualTo(adapter(all()).capabilities());
        assertThat(adapter.toString() + descriptor).doesNotContain(CREDENTIAL);
    }

    @Test
    void aliasesResolveOfflineThroughTheCatalogAndUnknownAliasesFail() {
        AiGatewayAdapter adapter = adapter(all());

        ResolvedModel resolved = adapter.resolveModel(new ModelResolutionRequest(ModelRole.REASONING, "standard"));
        GatewayException unknown = catchThrowableOfType(GatewayException.class,
                () -> adapter.resolveModel(new ModelResolutionRequest(ModelRole.REASONING, "missing-alias")));

        assertThat(resolved.providerModel()).isEqualTo("provider-model-1");
        assertThat(unknown.code()).isEqualTo(GatewayErrorCode.MODEL_ALIAS_UNKNOWN);
        assertThat(harness.providerRequests()).isZero();
        assertThat(credentials.authorizations).hasValue(0);
    }

    @Test
    void blockingCallReturnsContentUsageAndServedIdentity() {
        harness.script(ProviderScript.text(List.of("Hello", ", ", "world"), USAGE)
                .servedBy("provider-model-1-2026-09-01", "fp_conformance"));
        ModelGateway gateway = gateway(adapter(all()));

        ModelResponse response = gateway.call(invocation(pin(all()), prompt(), null));

        assertThat(response.content()).isEqualTo("Hello, world");
        assertThat(response.finishReason()).isEqualTo(FinishReason.STOP);
        assertThat(response.usage()).isEqualTo(USAGE);
        assertThat(response.served()).isEqualTo(new ServedModel("provider-model-1-2026-09-01", "fp_conformance",
                ServedModel.Source.PROVIDER_REPORTED));
        assertThat(response.pinned().providerModel()).isEqualTo("provider-model-1");
        assertThat(response.degradations()).isEmpty();
        assertThat(response.chunks()).isZero();
        assertThat(harness.lastRequest().model()).isEqualTo("provider-model-1");
        assertThat(harness.lastRequest().streamed()).isFalse();
        assertThat(events).filteredOn(GatewayEvent.ModelInvocationCompleted.class::isInstance).singleElement()
                .isInstanceOfSatisfying(GatewayEvent.ModelInvocationCompleted.class,
                completed -> {
                    assertThat(completed.usage()).isEqualTo(USAGE);
                    assertThat(completed.served().model()).isEqualTo("provider-model-1-2026-09-01");
                    assertThat(completed.scope().workspaceId()).isEqualTo(workspace.value());
                    assertThat(completed.scope().releaseId()).isEqualTo(release.value());
                });
    }

    @Test
    void streamDeliversOrderedChunksThatReassembleTheContent() {
        List<String> deltas = List.of("The ", "quick ", "brown ", "fox", ".");
        harness.script(ProviderScript.text(deltas, USAGE));
        List<ModelChunk> received = new ArrayList<>();

        ModelResponse response = gateway(adapter(all())).stream(invocation(pin(all()), prompt(), null), received::add);

        assertThat(received).extracting(ModelChunk::sequence).containsExactly(0L, 1L, 2L, 3L, 4L);
        assertThat(received).extracting(ModelChunk::delta).containsExactlyElementsOf(deltas);
        assertThat(response.content()).isEqualTo("The quick brown fox.");
        assertThat(response.chunks()).isEqualTo(5);
        assertThat(response.usage()).isEqualTo(USAGE);
        assertThat(harness.lastRequest().streamed()).isTrue();
    }

    @Test
    void blockingCallsReturnToolCalls() {
        ToolCall call = new ToolCall("call_1", "lookup", "{\"query\":\"weather\"}");
        harness.script(ProviderScript.text(List.of(), USAGE).withToolCalls(List.of(call)));

        ModelResponse response = gateway(adapter(all())).call(invocation(pin(all()), prompt().withTools(tools()), null));

        assertThat(response.toolCalls()).containsExactly(call);
        assertThat(response.finishReason()).isEqualTo(FinishReason.TOOL_CALLS);
        assertThat(harness.lastRequest().tools()).isEqualTo(1);
    }

    @Test
    void streamsReturnToolCalls() {
        ToolCall call = new ToolCall("call_7", "lookup", "{\"query\":\"forecast for tomorrow\"}");
        harness.script(ProviderScript.text(List.of("Checking"), USAGE).withToolCalls(List.of(call)));
        List<ModelChunk> received = new ArrayList<>();

        ModelResponse response = gateway(adapter(all()))
                .stream(invocation(pin(all()), prompt().withTools(tools()), null), received::add);

        assertThat(response.toolCalls()).containsExactly(call);
        assertThat(response.content()).isEqualTo("Checking");
        assertThat(received).extracting(ModelChunk::delta).containsExactly("Checking");
    }

    @Test
    void structuredOutputRequestsReachTheProvider() {
        harness.script(ProviderScript.text(List.of("{\"answer\":", "42}"), USAGE));
        ModelRequest request = prompt().withResponseFormat(ResponseFormat.jsonSchema("answer",
                "{\"type\":\"object\",\"properties\":{\"answer\":{\"type\":\"integer\"}},\"required\":[\"answer\"]}"));

        ModelResponse response = gateway(adapter(all())).call(invocation(pin(all()), request, null));

        assertThat(JsonMapper.builder().build().readTree(response.content()).path("answer").intValue()).isEqualTo(42);
        assertThat(harness.lastRequest().structuredOutput()).isTrue();
    }

    @Test
    void unsupportedRequiredCapabilityFailsBeforeCredentialResolutionOrProviderContact() {
        Set<GatewayCapability> advertised = without(GatewayCapability.TOOL_CALLS);
        ModelGateway gateway = gateway(adapter(advertised));

        GatewayException failure = catchThrowableOfType(GatewayException.class,
                () -> gateway.call(invocation(pin(advertised), prompt().withTools(tools()), null)));

        assertThat(failure.code()).isEqualTo(GatewayErrorCode.CAPABILITY_UNSUPPORTED);
        assertThat(failure.getMessage()).contains("TOOL_CALLS");
        assertThat(harness.providerRequests()).isZero();
        assertThat(credentials.authorizations).hasValue(0);
        assertThat(events).filteredOn(GatewayEvent.ModelInvocationFailed.class::isInstance).singleElement()
                .isInstanceOfSatisfying(GatewayEvent.ModelInvocationFailed.class, failed -> {
            assertThat(failed.code()).isEqualTo(GatewayErrorCode.CAPABILITY_UNSUPPORTED);
            assertThat(failed.providerContacted()).isFalse();
        });
    }

    @Test
    void degradedStreamingDeliversOneChunkAndEmitsAnEvent() {
        Set<GatewayCapability> advertised = without(GatewayCapability.STREAMING);
        harness.script(ProviderScript.text(List.of("one ", "two ", "three"), USAGE));
        List<ModelChunk> received = new ArrayList<>();

        ModelResponse response = gateway(adapter(advertised))
                .stream(invocation(pin(advertised), prompt(), null), received::add);

        assertThat(received).containsExactly(new ModelChunk(0, "one two three"));
        assertThat(response.degradations()).containsExactly(DegradedBehavior.SINGLE_CHUNK);
        assertThat(harness.lastRequest().streamed()).isFalse();
        assertThat(degradationEvents()).containsExactly(DegradedBehavior.SINGLE_CHUNK);
    }

    @Test
    void degradedUsageIsReportedAsUnreportedWithAnEvent() {
        Set<GatewayCapability> advertised = without(GatewayCapability.USAGE);
        harness.script(ProviderScript.text(List.of("ok"), USAGE));

        ModelResponse response = gateway(adapter(advertised)).call(invocation(pin(advertised), prompt(), null));

        assertThat(response.usage().status()).isEqualTo(ModelUsage.UsageStatus.ESTIMATED);
        assertThat(response.usage().totalTokens()).isPositive();
        assertThat(degradationEvents()).containsExactly(DegradedBehavior.USAGE_UNREPORTED);
    }

    @Test
    void degradedModelMetadataReportsOnlyThePinnedIdentity() {
        Set<GatewayCapability> advertised = without(GatewayCapability.MODEL_METADATA);
        harness.script(ProviderScript.text(List.of("ok"), USAGE).servedBy("something-else", "fp_other"));

        ModelResponse response = gateway(adapter(advertised)).call(invocation(pin(advertised), prompt(), null));

        assertThat(response.served()).isEqualTo(new ServedModel("provider-model-1", null, ServedModel.Source.PINNED));
        assertThat(degradationEvents()).containsExactly(DegradedBehavior.SERVED_IDENTITY_UNREPORTED);
    }

    @Test
    void degradedToolCallsAreWithheldFromTheProviderOnlyWhenPolicyPermits() {
        Set<GatewayCapability> advertised = without(GatewayCapability.TOOL_CALLS);
        harness.script(ProviderScript.text(List.of("no tools"), USAGE));
        ModelGateway gateway = gateway(adapter(advertised));
        CapabilityPolicy permissive = CapabilityPolicy.withOverrides(
                Map.of(GatewayCapability.TOOL_CALLS, com.dvarahq.agentworks.gateway.CapabilityMode.DEGRADE));

        ModelResponse response = gateway.call(invocation(pin(advertised, permissive), prompt().withTools(tools()), null));

        assertThat(response.content()).isEqualTo("no tools");
        assertThat(response.degradations()).containsExactly(DegradedBehavior.TOOLS_OMITTED);
        assertThat(harness.lastRequest().tools()).isZero();
    }

    @Test
    void cancellationStopsTheStreamAndDeliversNoFurtherChunks() {
        harness.script(ProviderScript.text(repeat("tick ", 40), USAGE).withChunkDelay(Duration.ofMillis(100)));
        CancellationSignal signal = CancellationSignal.create();
        List<ModelChunk> received = new CopyOnWriteArrayList<>();
        long started = System.nanoTime();

        GatewayException failure = catchThrowableOfType(GatewayException.class,
                () -> gateway(adapter(all())).stream(invocation(pin(all()), prompt(), signal), chunk -> {
                    received.add(chunk);
                    signal.cancel();
                }));

        assertThat(failure.code()).isEqualTo(GatewayErrorCode.CANCELLED);
        assertThat(received).hasSize(1);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
        assertThat(credentials.issuedAreClosed()).isTrue();
        assertThat(events).last().isInstanceOfSatisfying(GatewayEvent.ModelInvocationFailed.class,
                failed -> assertThat(failed.code()).isEqualTo(GatewayErrorCode.CANCELLED));
    }

    @Test
    void cancellationWithoutProviderSupportIsLocalAndEvented() {
        Set<GatewayCapability> advertised = without(GatewayCapability.CANCELLATION);
        harness.script(ProviderScript.text(repeat("tock ", 20), USAGE).withChunkDelay(Duration.ofMillis(50)));
        CancellationSignal signal = CancellationSignal.create();
        List<ModelChunk> received = new CopyOnWriteArrayList<>();

        GatewayException failure = catchThrowableOfType(GatewayException.class,
                () -> gateway(adapter(advertised)).stream(invocation(pin(advertised), prompt(), signal), chunk -> {
                    received.add(chunk);
                    signal.cancel();
                }));

        assertThat(failure.code()).isEqualTo(GatewayErrorCode.CANCELLED);
        assertThat(received).hasSize(1);
        assertThat(degradationEvents()).containsExactly(DegradedBehavior.LOCAL_CANCELLATION_ONLY);
    }

    @Test
    void anAlreadyCancelledInvocationNeverReachesTheProvider() {
        CancellationSignal signal = CancellationSignal.create();
        signal.cancel();

        GatewayException failure = catchThrowableOfType(GatewayException.class,
                () -> gateway(adapter(all())).call(invocation(pin(all()), prompt(), signal)));

        assertThat(failure.code()).isEqualTo(GatewayErrorCode.CANCELLED);
        assertThat(harness.providerRequests()).isZero();
        assertThat(credentials.authorizations).hasValue(0);
    }

    @Test
    void theDeadlineTimesOutAHungProvider() {
        harness.script(ProviderScript.failing(ProviderScript.Failure.HANG));
        long started = System.nanoTime();
        ModelInvocation invocation = new ModelInvocation(pin(all()), identity, correlation(), prompt(),
                Duration.ofMillis(300), null);

        GatewayException failure = catchThrowableOfType(GatewayException.class,
                () -> gateway(adapter(all())).stream(invocation, chunk -> { }));

        assertThat(failure.code()).isEqualTo(GatewayErrorCode.TIMEOUT);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
        assertThat(credentials.issuedAreClosed()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = ProviderScript.Failure.class, names = {"AUTHENTICATION", "RATE_LIMITED", "UNAVAILABLE",
            "REJECTED", "MALFORMED", "TRUNCATED_STREAM"})
    void providerFailuresMapToTypedCodesWithoutLeakingSecretsOrPrompts(ProviderScript.Failure mode) {
        harness.script(ProviderScript.failing(mode));
        ModelGateway gateway = gateway(adapter(all()));

        GatewayException failure = catchThrowableOfType(GatewayException.class,
                () -> gateway.stream(invocation(pin(all()), prompt(), null), chunk -> { }));

        if (mode == ProviderScript.Failure.TRUNCATED_STREAM) {
            // A fixture may detect truncation before emitting content (invalid response) or after it (partial stream).
            assertThat(failure.code()).isIn(GatewayErrorCode.INVALID_PROVIDER_RESPONSE,
                    GatewayErrorCode.PARTIAL_STREAM);
        } else {
            assertThat(failure.code()).isEqualTo(mode.expected());
        }
        assertThat(failure.getCause()).isNull();
        assertThat(events).last().isInstanceOfSatisfying(GatewayEvent.ModelInvocationFailed.class,
                failed -> assertThat(failed.providerContacted()).as("adapters must call ChunkSink.requestSent()")
                        .isTrue());
        assertThat(failure.getMessage() + events + audits).doesNotContain(CREDENTIAL).doesNotContain(SENSITIVE_PROMPT);
        assertThat(credentials.issuedAreClosed()).isTrue();
    }

    @Test
    void theCredentialIsResolvedJustInTimeAuditedPresentedAndZeroized() {
        harness.script(ProviderScript.text(List.of("ok"), USAGE));

        ModelResponse response = gateway(adapter(all())).call(invocation(pin(all()), prompt(), null));

        assertThat(credentials.authorizations).hasValue(1);
        assertThat(credentials.resolutions).hasValue(1);
        assertThat(harness.lastPresentedCredentialDigest())
                .isEqualTo(DeterministicFakeGatewayAdapter.digest(CREDENTIAL.toCharArray()));
        assertThat(audits).singleElement().satisfies(audit -> {
            assertThat(audit.action()).isEqualTo("MODEL_CREDENTIAL_USE");
            assertThat(audit.outcome()).isEqualTo("SUCCEEDED");
            assertThat(audit.workspaceId()).isEqualTo(workspace.value());
        });
        assertThat(credentials.issuedAreClosed()).isTrue();
        assertThat(response + events.toString() + audits).doesNotContain(CREDENTIAL).doesNotContain(SENSITIVE_PROMPT);
    }

    @Test
    void aDeniedCredentialFailsClosedWithoutProviderContactOrLeakingTheDenial() {
        credentials.deny = true;

        GatewayException failure = catchThrowableOfType(GatewayException.class,
                () -> gateway(adapter(all())).call(invocation(pin(all()), prompt(), null)));

        assertThat(failure.code()).isEqualTo(GatewayErrorCode.CREDENTIAL_UNAVAILABLE);
        assertThat(failure.getMessage()).doesNotContain(CREDENTIAL);
        assertThat(harness.providerRequests()).isZero();
        assertThat(audits).singleElement().satisfies(audit -> assertThat(audit.outcome()).isEqualTo("DENIED_OR_FAILED"));
        assertThat(events).last().isInstanceOfSatisfying(GatewayEvent.ModelInvocationFailed.class,
                failed -> assertThat(failed.providerContacted()).isFalse());
    }

    @Test
    void aSlowCredentialProviderCannotHoldTheCallerPastItsDeadline() {
        credentials.delay = Duration.ofSeconds(3);
        long started = System.nanoTime();
        ModelInvocation invocation = new ModelInvocation(pin(all()), identity, correlation(), prompt(),
                Duration.ofMillis(200), null);

        GatewayException failure = catchThrowableOfType(GatewayException.class,
                () -> gateway(adapter(all())).call(invocation));

        assertThat(failure.code()).isEqualTo(GatewayErrorCode.TIMEOUT);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(2_000));
        assertThat(audits).noneMatch(audit -> audit.outcome().equals("SUCCEEDED"));
        assertThat(harness.providerRequests()).isZero();
        assertThat(events).last().isInstanceOfSatisfying(GatewayEvent.ModelInvocationFailed.class,
                failed -> assertThat(failed.providerContacted()).isFalse());
    }

    @Test
    void toolHistoryCannotBeDegradedAwayEvenWhenPolicyPermitsOmittingTools() {
        Set<GatewayCapability> advertised = without(GatewayCapability.TOOL_CALLS);
        CapabilityPolicy permissive = CapabilityPolicy.withOverrides(
                Map.of(GatewayCapability.TOOL_CALLS, com.dvarahq.agentworks.gateway.CapabilityMode.DEGRADE));
        ModelRequest withHistory = ModelRequest.of(ModelMessage.user(SENSITIVE_PROMPT),
                ModelMessage.assistant(null, List.of(new ToolCall("call_1", "lookup", "{}"))),
                ModelMessage.tool("call_1", "result"));

        GatewayException failure = catchThrowableOfType(GatewayException.class, () -> gateway(adapter(advertised))
                .call(invocation(pin(advertised, permissive), withHistory, null)));

        assertThat(failure.code()).isEqualTo(GatewayErrorCode.CAPABILITY_UNSUPPORTED);
        assertThat(harness.providerRequests()).isZero();
        assertThat(degradationEvents()).isEmpty();
    }

    @Test
    void unsupportedStructuredOutputFailsByDefaultAndIsWithheldOnlyWhenPolicyPermits() {
        Set<GatewayCapability> advertised = without(GatewayCapability.STRUCTURED_OUTPUT);
        harness.script(ProviderScript.text(List.of("{\"answer\":42}"), USAGE));
        ModelRequest request = prompt().withResponseFormat(ResponseFormat.jsonSchema("answer", "{\"type\":\"object\"}"));
        CapabilityPolicy permissive = CapabilityPolicy.withOverrides(
                Map.of(GatewayCapability.STRUCTURED_OUTPUT, com.dvarahq.agentworks.gateway.CapabilityMode.DEGRADE));

        GatewayException required = catchThrowableOfType(GatewayException.class,
                () -> gateway(adapter(advertised)).call(invocation(pin(advertised), request, null)));
        int requestsAfterRefusal = harness.providerRequests();
        ModelResponse degraded = gateway(adapter(advertised)).call(invocation(pin(advertised, permissive), request, null));

        assertThat(required.code()).isEqualTo(GatewayErrorCode.CAPABILITY_UNSUPPORTED);
        assertThat(requestsAfterRefusal).isZero();
        assertThat(degraded.degradations()).containsExactly(DegradedBehavior.SCHEMA_NOT_ENFORCED);
        assertThat(harness.lastRequest().structuredOutput()).isFalse();
        assertThat(degradationEvents()).containsExactly(DegradedBehavior.SCHEMA_NOT_ENFORCED);
    }

    @Test
    void aPinnedModelDoesNotDriftWhenTheAliasCatalogChanges() {
        PinnedModel pinned = pin(all());
        AiGatewayAdapter repointed = harness.adapter(Map.of("standard", "provider-model-2"), all());
        GatewayRegistry changed = registry(repointed, CapabilityPolicy.DEFAULT);
        harness.script(ProviderScript.text(List.of("still pinned"), USAGE));
        ModelGateway gateway = new ModelGateway(changed, credentials, audits::add, events::add, Clock.systemUTC());

        gateway.call(invocation(pinned, prompt(), null));
        String firstTurn = harness.lastRequest().model();
        gateway.call(invocation(pinned, prompt(), null));

        assertThat(firstTurn).isEqualTo("provider-model-1");
        assertThat(harness.lastRequest().model()).isEqualTo("provider-model-1");
        assertThat(changed.resolve(List.of(binding())).get(ModelRole.REASONING).providerModel())
                .isEqualTo("provider-model-2");
    }

    @Test
    void aChangedGatewayIdentityIsRefusedBeforeProviderContact() {
        PinnedModel pinned = pin(all());
        ModelGateway gateway = new ModelGateway(registry(new OtherTypeAdapter(), CapabilityPolicy.DEFAULT), credentials,
                audits::add, events::add, Clock.systemUTC());

        GatewayException failure = catchThrowableOfType(GatewayException.class,
                () -> gateway.call(invocation(pinned, prompt(), null)));

        assertThat(failure.code()).isEqualTo(GatewayErrorCode.GATEWAY_IDENTITY_CHANGED);
        assertThat(harness.providerRequests()).isZero();
    }

    @Test
    void aPrincipalFromAnotherWorkspaceIsRefusedBeforeProviderContact() {
        InvocationIdentity stranger = new InvocationIdentity(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID());
        ModelInvocation invocation = new ModelInvocation(pin(all()), stranger, correlation(), prompt(), TIMEOUT, null);

        GatewayException failure = catchThrowableOfType(GatewayException.class,
                () -> gateway(adapter(all())).call(invocation));

        assertThat(failure.code()).isEqualTo(GatewayErrorCode.TENANT_MISMATCH);
        assertThat(harness.providerRequests()).isZero();
        assertThat(credentials.authorizations).hasValue(0);
    }

    @Test
    void aListenerFailureStopsTheStreamAndReachesTheCaller() {
        harness.script(ProviderScript.text(repeat("x", 10), USAGE));
        IllegalStateException listenerBug = new IllegalStateException("listener bug");

        assertThatThrownBy(() -> gateway(adapter(all())).stream(invocation(pin(all()), prompt(), null), chunk -> {
            throw listenerBug;
        })).isSameAs(listenerBug);
        assertThat(events).last().isInstanceOfSatisfying(GatewayEvent.ModelInvocationFailed.class,
                failed -> assertThat(failed.code()).isEqualTo(GatewayErrorCode.CANCELLED));
    }

    // ---- fixtures ------------------------------------------------------------------------------------------------

    protected Set<GatewayCapability> all() {
        return EnumSet.copyOf(harness.supportedCapabilities());
    }

    protected Set<GatewayCapability> without(GatewayCapability capability) {
        Set<GatewayCapability> advertised = all();
        advertised.remove(capability);
        return advertised;
    }

    private AiGatewayAdapter adapter(Set<GatewayCapability> advertised) {
        return harness.adapter(CATALOG, advertised);
    }

    private GatewayRegistry registry(AiGatewayAdapter adapter, CapabilityPolicy policy) {
        return GatewayRegistry.of(List.of(new ConfiguredGateway(adapter, policy, new CredentialRef("conformance-key"))));
    }

    private ModelGateway gateway(AiGatewayAdapter adapter) {
        return new ModelGateway(registry(adapter, CapabilityPolicy.DEFAULT), credentials, audits::add, events::add,
                Clock.systemUTC());
    }

    private static ModelRoleBinding binding() {
        return new ModelRoleBinding(ModelRole.REASONING, ConformanceHarness.GATEWAY_ID, "standard");
    }

    private PinnedModel pin(Set<GatewayCapability> advertised) {
        return pin(advertised, CapabilityPolicy.DEFAULT);
    }

    /** Resolves at the "release boundary" and reads the pin back through the stored-manifest reader. */
    private PinnedModel pin(Set<GatewayCapability> advertised, CapabilityPolicy policy) {
        var identities = registry(adapter(advertised), policy).resolve(List.of(binding()));
        String manifest = JsonMapper.builder().build().writeValueAsString(Map.of(
                "manifestVersion", PinnedModelRoles.RESOLVED_MANIFEST_VERSION,
                "models", Map.of("reasoning", identities.get(ModelRole.REASONING).toManifestEntry())));
        return PinnedModelRoles.fromReleaseManifest(workspace, release, manifest).role(ModelRole.REASONING);
    }

    private InvocationCorrelation correlation() {
        return new InvocationCorrelation(new AgentRunId(UUID.randomUUID()), "trace-conformance");
    }

    private ModelInvocation invocation(PinnedModel pinned, ModelRequest request, CancellationSignal signal) {
        return new ModelInvocation(pinned, identity, correlation(), request, TIMEOUT, signal);
    }

    private static ModelRequest prompt() {
        return ModelRequest.of(ModelMessage.system("You are a conformance test."), ModelMessage.user(SENSITIVE_PROMPT));
    }

    private static List<ToolDefinition> tools() {
        return List.of(new ToolDefinition("lookup", "Looks something up.",
                "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}}}"));
    }

    private static List<String> repeat(String delta, int times) {
        List<String> chunks = new ArrayList<>();
        for (int index = 0; index < times; index++) {
            chunks.add(delta);
        }
        return chunks;
    }

    private List<DegradedBehavior> degradationEvents() {
        return events.stream().filter(GatewayEvent.CapabilityDegraded.class::isInstance)
                .map(event -> ((GatewayEvent.CapabilityDegraded) event).behavior()).toList();
    }

    /** Issues the conformance credential; a denial message deliberately contains it to prove Core drops messages. */
    private static final class RecordingCredentialProvider implements CredentialProvider {
        final AtomicInteger authorizations = new AtomicInteger();
        final AtomicInteger resolutions = new AtomicInteger();
        final List<ResolvedCredential> issued = new CopyOnWriteArrayList<>();
        volatile boolean deny;
        volatile Duration delay = Duration.ZERO;

        @Override
        public CredentialAuthorization authorize(CredentialRef reference, InvocationIdentity who, Instant now) {
            authorizations.incrementAndGet();
            if (deny) {
                throw new CredentialResolutionException("denied while holding " + CREDENTIAL);
            }
            return new CredentialAuthorization(UUID.randomUUID(), reference, who);
        }

        @Override
        public ResolvedCredential resolve(CredentialAuthorization authorization, Instant now) {
            resolutions.incrementAndGet();
            try {
                Thread.sleep(delay);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            ResolvedCredential credential = new ResolvedCredential(CREDENTIAL.toCharArray(), now.plusSeconds(300));
            issued.add(credential);
            return credential;
        }

        boolean issuedAreClosed() {
            return issued.stream().allMatch(credential -> {
                try {
                    credential.use(Instant.now(), material -> material);
                    return false;
                } catch (IllegalStateException closed) {
                    return true;
                }
            });
        }
    }

    /** A different adapter type registered under the pinned gateway identifier. */
    private static final class OtherTypeAdapter implements AiGatewayAdapter {
        @Override
        public GatewayDescriptor descriptor() {
            return new GatewayDescriptor(ConformanceHarness.GATEWAY_ID, "other-type", "1", "other/1");
        }

        @Override
        public Set<GatewayCapability> capabilities() {
            return GatewayCapability.negotiatedCapabilities();
        }

        @Override
        public ResolvedModel resolveModel(ModelResolutionRequest request) {
            return new ResolvedModel("other", ResolvedModel.ResolutionSource.GATEWAY_ALIAS_CATALOG, null);
        }

        @Override
        public AdapterResult invoke(AdapterInvocation invocation, ChunkSink sink) {
            throw new AssertionError("a changed gateway identity must never be invoked");
        }
    }
}
