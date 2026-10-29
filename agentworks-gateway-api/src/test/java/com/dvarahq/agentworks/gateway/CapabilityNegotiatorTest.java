package com.dvarahq.agentworks.gateway;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/** The capability matrix: every negotiated capability, supported or not, under REQUIRED and DEGRADE. */
class CapabilityNegotiatorTest {

    @ParameterizedTest
    @EnumSource(value = GatewayCapability.class, names = {"STREAMING", "TOOL_CALLS", "STRUCTURED_OUTPUT", "USAGE",
            "CANCELLATION", "MODEL_METADATA"})
    void everyNegotiatedCapabilityIsGrantedFailedOrDegradedExactlyAsThePolicyStates(GatewayCapability capability) {
        Set<GatewayCapability> advertisedWithout = EnumSet.complementOf(EnumSet.of(capability));

        var granted = CapabilityNegotiator.negotiate(Set.of(capability), EnumSet.allOf(GatewayCapability.class),
                CapabilityPolicy.DEFAULT);
        var required = catchThrowableOfType(GatewayException.class, () -> CapabilityNegotiator.negotiate(
                Set.of(capability), advertisedWithout, policy(capability, CapabilityMode.REQUIRED)));
        var degraded = CapabilityNegotiator.negotiate(Set.of(capability), advertisedWithout,
                policy(capability, CapabilityMode.DEGRADE));

        assertThat(granted.granted()).containsExactly(capability);
        assertThat(granted.degradations()).isEmpty();
        assertThat(required.code()).isEqualTo(GatewayErrorCode.CAPABILITY_UNSUPPORTED);
        assertThat(required.getMessage()).contains(capability.name());
        assertThat(degraded.granted()).isEmpty();
        assertThat(degraded.degradations()).containsExactly(DegradedBehavior.forCapability(capability));
    }

    @Test
    void unneededCapabilitiesAreNeitherGrantedNorFailed() {
        var outcome = CapabilityNegotiator.negotiate(Set.of(GatewayCapability.USAGE), Set.of(), CapabilityPolicy.DEFAULT);

        assertThat(outcome.granted()).isEmpty();
        assertThat(outcome.degradations()).containsExactly(DegradedBehavior.USAGE_UNREPORTED);
    }

    @Test
    void everyMissingRequiredCapabilityIsNamedInOneFailure() {
        var failure = catchThrowableOfType(GatewayException.class, () -> CapabilityNegotiator.negotiate(
                Set.of(GatewayCapability.TOOL_CALLS, GatewayCapability.STRUCTURED_OUTPUT, GatewayCapability.STREAMING),
                Set.of(), CapabilityPolicy.DEFAULT));

        assertThat(failure.getMessage()).contains("TOOL_CALLS").contains("STRUCTURED_OUTPUT")
                .doesNotContain("STREAMING");
    }

    @Test
    void defaultsRequireToolsAndStructuredOutputAndDegradeTheRest() {
        assertThat(CapabilityPolicy.DEFAULT.modes()).containsOnly(
                Map.entry(GatewayCapability.STREAMING, CapabilityMode.DEGRADE),
                Map.entry(GatewayCapability.TOOL_CALLS, CapabilityMode.REQUIRED),
                Map.entry(GatewayCapability.STRUCTURED_OUTPUT, CapabilityMode.REQUIRED),
                Map.entry(GatewayCapability.USAGE, CapabilityMode.DEGRADE),
                Map.entry(GatewayCapability.CANCELLATION, CapabilityMode.DEGRADE),
                Map.entry(GatewayCapability.MODEL_METADATA, CapabilityMode.DEGRADE));
    }

    @Test
    void policiesAreCompleteAndOnlyCoverNegotiatedCapabilities() {
        Map<GatewayCapability, CapabilityMode> partial = new EnumMap<>(GatewayCapability.class);
        partial.put(GatewayCapability.STREAMING, CapabilityMode.REQUIRED);

        assertThatThrownBy(() -> new CapabilityPolicy(partial)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CapabilityPolicy.withOverrides(Map.of(GatewayCapability.MCP_PROXY,
                CapabilityMode.DEGRADE))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CapabilityNegotiator.negotiate(Set.of(GatewayCapability.MCP_PROXY), Set.of(),
                CapabilityPolicy.DEFAULT)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requestsDeriveExactlyTheCapabilitiesTheyUse() {
        ModelRequest plain = ModelRequest.of(ModelMessage.user("hi"));
        ModelRequest tools = plain.withTools(java.util.List.of(new ToolDefinition("t", "d", "{}")));
        ModelRequest toolHistory = ModelRequest.of(ModelMessage.user("hi"),
                ModelMessage.assistant(null, java.util.List.of(new ToolCall("c", "t", "{}"))),
                ModelMessage.tool("c", "result"));
        ModelRequest structured = plain.withResponseFormat(ResponseFormat.jsonSchema("s", "{}"));
        Set<GatewayCapability> always = EnumSet.of(GatewayCapability.USAGE, GatewayCapability.CANCELLATION,
                GatewayCapability.MODEL_METADATA);

        assertThat(ModelGateway.needed(plain, false)).isEqualTo(always);
        assertThat(ModelGateway.needed(plain, true)).contains(GatewayCapability.STREAMING);
        assertThat(ModelGateway.needed(tools, false)).contains(GatewayCapability.TOOL_CALLS);
        assertThat(ModelGateway.needed(toolHistory, false)).contains(GatewayCapability.TOOL_CALLS);
        assertThat(ModelGateway.needed(structured, false)).contains(GatewayCapability.STRUCTURED_OUTPUT)
                .doesNotContain(GatewayCapability.TOOL_CALLS, GatewayCapability.STREAMING);
    }

    private static CapabilityPolicy policy(GatewayCapability capability, CapabilityMode mode) {
        return CapabilityPolicy.withOverrides(Map.of(capability, mode));
    }
}
