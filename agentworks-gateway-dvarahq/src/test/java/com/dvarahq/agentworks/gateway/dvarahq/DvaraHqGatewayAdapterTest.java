package com.dvarahq.agentworks.gateway.dvarahq;

import com.dvarahq.agentworks.domain.lifecycle.AgentReleaseId;
import com.dvarahq.agentworks.domain.lifecycle.AgentRunId;
import com.dvarahq.agentworks.domain.security.InvocationIdentity;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import com.dvarahq.agentworks.gateway.*;
import com.dvarahq.agentworks.gateway.conformance.ProviderScript;
import com.dvarahq.agentworks.gateway.openaicompat.StubChatCompletionsServer;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.EnumSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

final class DvaraHqGatewayAdapterTest {
    @Test void advertisesOnlyConfiguredAndImplementedSurfaces() {
        var config = configured(EnumSet.of(GatewayCapability.STREAMING, GatewayCapability.MODEL_ROUTING,
                GatewayCapability.MODEL_FINGERPRINT, GatewayCapability.CONTINUITY_EVENTS), true, true);
        var adapter = new DvaraHqGatewayAdapter(config);

        assertThat(adapter.capabilities()).containsExactlyInAnyOrder(GatewayCapability.STREAMING,
                GatewayCapability.MODEL_ROUTING, GatewayCapability.MODEL_FINGERPRINT,
                GatewayCapability.CONTINUITY_EVENTS, GatewayCapability.MCP_PROXY, GatewayCapability.A2A_PROXY);
        assertThat(adapter.capabilities()).doesNotContain(GatewayCapability.DELEGATED_IDENTITY,
                GatewayCapability.COST_ACCOUNTING, GatewayCapability.EVIDENCE_ANCHOR);
        assertThat(adapter.resolveModel(new ModelResolutionRequest(ModelRole.REASONING, "standard")))
                .isEqualTo(new ResolvedModel("governed-route", ResolvedModel.ResolutionSource.GATEWAY_ALIAS_CATALOG,
                        "fp-pinned"));
    }

    @Test void rejectsOverclaimedCapabilitiesAndUnsafeEndpointShapes() {
        assertThatIllegalArgumentException().isThrownBy(() -> configured(
                EnumSet.of(GatewayCapability.COST_ACCOUNTING), false, false));
        var base = DvaraHqGatewayConfig.of("dvarahq", URI.create("http://127.0.0.1:8080/v1"),
                Map.of("standard", "route"));
        assertThatIllegalArgumentException().isThrownBy(() -> new DvaraHqGatewayConfig(base.gatewayId(),
                base.modelBaseUri(), base.models(), base.modelCapabilities(),
                Optional.of(URI.create("https://gateway.example/mcp?secret=x")), Optional.empty(),
                base.connectTimeout(), base.maxModelResponseBytes(), base.maxModelStreamBytes()));
        assertThatIllegalArgumentException().isThrownBy(() -> new DvaraHqGatewayConfig(base.gatewayId(),
                base.modelBaseUri(), base.models(), EnumSet.of(GatewayCapability.MODEL_FINGERPRINT),
                Optional.empty(), Optional.empty(), base.connectTimeout(), base.maxModelResponseBytes(),
                base.maxModelStreamBytes()));
        assertThatIllegalArgumentException().isThrownBy(() -> new DvaraHqGatewayConfig(base.gatewayId(),
                base.modelBaseUri(), Map.of("standard",
                        new DvaraHqGatewayConfig.ModelRoute("route", "fp", true)),
                EnumSet.of(GatewayCapability.MODEL_METADATA), Optional.empty(), Optional.empty(),
                base.connectTimeout(), base.maxModelResponseBytes(), base.maxModelStreamBytes()));
    }

    @Test void providerOutageProducesTypedRedactedHealthFailure() {
        DvaraHqGatewayConfig config = configured(EnumSet.copyOf(GatewayCapability.negotiatedCapabilities()), false, false);
        var adapter = new DvaraHqGatewayAdapter(config);
        AdapterInvocation invocation = new AdapterInvocation("governed-route",
                ModelRequest.of(ModelMessage.user("prompt-secret-canary")),
                new InvocationPlan(false, true, false, true, true), noCredential(),
                CancellationSignal.create(), Duration.ofSeconds(1));

        // The network is deliberately absent: this verifies the adapter reports a typed, secret-free outage.
        GatewayException failure = catchThrowableOfType(GatewayException.class,
                () -> adapter.invoke(invocation, new ChunkSink() {
                    @Override public void requestSent() { }
                    @Override public void emit(String delta) { }
                }));
        assertThat(failure.code()).isEqualTo(GatewayErrorCode.PROVIDER_UNAVAILABLE);
        assertThat(adapter.health().status()).isEqualTo(DvaraHqHealth.Status.FAILED);
        assertThat(failure.getMessage() + adapter).doesNotContain("prompt-secret-canary");
    }

    @Test void immutableReleaseFingerprintSurvivesRemovedChangedAndDuplicateRuntimeAliases() {
        try (StubChatCompletionsServer server = new StubChatCompletionsServer()) {
            DvaraHqGatewayConfig base = DvaraHqGatewayConfig.of("dvarahq", server.baseUri(),
                    Map.of("standard", "governed-route"));
            DvaraHqGatewayConfig releaseConfig = new DvaraHqGatewayConfig(base.gatewayId(), base.modelBaseUri(),
                    Map.of("old-release-alias",
                            new DvaraHqGatewayConfig.ModelRoute("governed-route", "fp-release", true)),
                    EnumSet.of(GatewayCapability.MODEL_METADATA, GatewayCapability.MODEL_FINGERPRINT),
                    Optional.empty(), Optional.empty(), base.connectTimeout(), base.maxModelResponseBytes(),
                    base.maxModelStreamBytes());
            PinnedModel pin = pin(new DvaraHqGatewayAdapter(releaseConfig), "old-release-alias");

            // At runtime the old alias is gone and two current aliases disagree about the same provider model.
            DvaraHqGatewayConfig runtimeConfig = new DvaraHqGatewayConfig(base.gatewayId(), base.modelBaseUri(),
                    Map.of("replacement-a", new DvaraHqGatewayConfig.ModelRoute("governed-route", "fp-current-a", false),
                            "replacement-b", new DvaraHqGatewayConfig.ModelRoute("governed-route", "fp-current-b", false)),
                    EnumSet.of(GatewayCapability.MODEL_METADATA),
                    Optional.empty(), Optional.empty(), base.connectTimeout(), base.maxModelResponseBytes(),
                    base.maxModelStreamBytes());
            ModelGateway gateway = gateway(new DvaraHqGatewayAdapter(runtimeConfig));
            server.script(ProviderScript.text(List.of("same release"), null).servedBy("served", "fp-release"));
            assertThat(gateway.call(invocation(pin)).content()).isEqualTo("same release");

            server.script(ProviderScript.text(List.of("drift"), null).servedBy("served", "fp-current-a"));
            GatewayException mismatch = catchThrowableOfType(GatewayException.class,
                    () -> gateway.call(invocation(pin)));
            assertThat(mismatch.code()).isEqualTo(GatewayErrorCode.INVALID_PROVIDER_RESPONSE);
            assertThat(mismatch.getMessage()).doesNotContain("fp-current-a", "fp-release");
        }
    }

    @Test void publicObservationIntegrationReceivesRealReleaseScopedContinuityDrift() {
        try (StubChatCompletionsServer server = new StubChatCompletionsServer()) {
            DvaraHqGatewayConfig base = DvaraHqGatewayConfig.of("dvarahq", server.baseUri(),
                    Map.of("standard", "governed-route"));
            DvaraHqGatewayConfig config = new DvaraHqGatewayConfig(base.gatewayId(), base.modelBaseUri(),
                    Map.of("standard", new DvaraHqGatewayConfig.ModelRoute("governed-route", "fp-one", false)),
                    EnumSet.of(GatewayCapability.MODEL_METADATA, GatewayCapability.CONTINUITY_EVENTS),
                    Optional.empty(), Optional.empty(), base.connectTimeout(), base.maxModelResponseBytes(),
                    base.maxModelStreamBytes());
            var events = new ArrayList<DvaraHqObservation>();
            var adapter = new DvaraHqGatewayAdapter(config, events::add);
            PinnedModel pin = pin(adapter, "standard");
            ModelGateway gateway = gateway(adapter);

            server.script(ProviderScript.text(List.of("first"), null).servedBy("served", "fp-one"));
            gateway.call(invocation(pin));
            server.script(ProviderScript.text(List.of("second"), null).servedBy("served", "fp-two"));
            gateway.call(invocation(pin));

            assertThat(events).filteredOn(event -> event.type() == DvaraHqObservation.Type.MODEL_CONTINUITY_CHANGED)
                    .singleElement().satisfies(event -> {
                        assertThat(event.scope()).isNotNull();
                        assertThat(event.scope().workspaceId()).isEqualTo(pin.workspace().value());
                        assertThat(event.scope().releaseId()).isEqualTo(pin.release().value());
                        assertThat(event.scope().traceId()).isEqualTo("trace-observation");
                        assertThat(event.scope().role()).isEqualTo(ModelRole.REASONING);
                    });
        }
    }

    private static PinnedModel pin(AiGatewayAdapter adapter, String alias) {
        GatewayRegistry releaseRegistry = GatewayRegistry.of(List.of(
                new ConfiguredGateway(adapter, CapabilityPolicy.DEFAULT, null)));
        var workspace = new WorkspaceId(UUID.randomUUID());
        var identity = releaseRegistry.resolve(List.of(new ModelRoleBinding(ModelRole.REASONING, "dvarahq", alias)))
                .get(ModelRole.REASONING);
        return new PinnedModel(workspace, new AgentReleaseId(UUID.randomUUID()), identity);
    }

    private static ModelGateway gateway(AiGatewayAdapter adapter) {
        GatewayRegistry runtime = GatewayRegistry.of(List.of(
                new ConfiguredGateway(adapter, CapabilityPolicy.DEFAULT, null)));
        return new ModelGateway(runtime, null, null, ignored -> { }, java.time.Clock.systemUTC());
    }

    private static ModelInvocation invocation(PinnedModel pin) {
        UUID workspace = pin.workspace().value();
        InvocationIdentity identity = new InvocationIdentity(UUID.randomUUID(), workspace, UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID());
        return new ModelInvocation(pin, identity, new InvocationCorrelation(new AgentRunId(UUID.randomUUID()),
                "trace-observation"),
                ModelRequest.of(ModelMessage.user("input-canary")), Duration.ofSeconds(2), null);
    }

    private static AdapterInvocation invocation() {
        return new AdapterInvocation("governed-route", ModelRequest.of(ModelMessage.user("input-canary")),
                new InvocationPlan(false, false, false, false, false), noCredential(),
                CancellationSignal.create(), Duration.ofSeconds(2));
    }

    private static ChunkSink sink() {
        return new ChunkSink() {
            @Override public void requestSent() { }
            @Override public void emit(String delta) { }
        };
    }

    private static GatewayCredential noCredential() {
        return new GatewayCredential() {
            @Override public boolean present() { return false; }
            @Override public <T> T withMaterial(Function<char[], T> use) {
                throw new GatewayException(GatewayErrorCode.CREDENTIAL_UNAVAILABLE,
                        "No credential is configured");
            }
        };
    }

    private static DvaraHqGatewayConfig configured(java.util.Set<GatewayCapability> capabilities,
            boolean mcp, boolean a2a) {
        return new DvaraHqGatewayConfig("dvarahq", URI.create("http://127.0.0.1:9/v1"),
                Map.of("standard", new DvaraHqGatewayConfig.ModelRoute("governed-route", "fp-pinned",
                        capabilities.contains(GatewayCapability.MODEL_FINGERPRINT))),
                capabilities, mcp ? Optional.of(URI.create("http://127.0.0.1:9/mcp")) : Optional.empty(),
                a2a ? Optional.of(URI.create("http://127.0.0.1:9/a2a")) : Optional.empty(),
                Duration.ofMillis(100), 64 * 1024, 512 * 1024);
    }
}
