package com.dvarahq.agentworks.gateway.conformance;

import com.dvarahq.agentworks.gateway.GatewayErrorCode;
import com.dvarahq.agentworks.gateway.ModelUsage;
import com.dvarahq.agentworks.gateway.ToolCall;

import java.time.Duration;
import java.util.List;

/**
 * Provider behavior a {@link ConformanceHarness} must reproduce behind its adapter, described without any wire format.
 *
 * @param usage usage the provider reports, or {@code null} for none
 * @param servedModel the serving model the provider reports, or {@code null} to echo the requested model
 * @param fingerprint the backend fingerprint the provider reports, or {@code null}
 * @param chunkDelay pause before each content delta
 */
public record ProviderScript(List<String> chunks, List<ToolCall> toolCalls, ModelUsage usage, String servedModel,
        String fingerprint, Duration chunkDelay, Failure failure) {

    /** Provider misbehavior, each with the code every adapter must map it to. */
    public enum Failure {
        NONE(null),
        AUTHENTICATION(GatewayErrorCode.PROVIDER_AUTHENTICATION_FAILED),
        RATE_LIMITED(GatewayErrorCode.PROVIDER_RATE_LIMITED),
        UNAVAILABLE(GatewayErrorCode.PROVIDER_UNAVAILABLE),
        REJECTED(GatewayErrorCode.PROVIDER_REJECTED_REQUEST),
        MALFORMED(GatewayErrorCode.INVALID_PROVIDER_RESPONSE),
        TRUNCATED_STREAM(GatewayErrorCode.INVALID_PROVIDER_RESPONSE),
        /** Accept the request and never answer. */
        HANG(null);

        private final GatewayErrorCode expected;

        Failure(GatewayErrorCode expected) {
            this.expected = expected;
        }

        public GatewayErrorCode expected() {
            return expected;
        }
    }

    public ProviderScript {
        chunks = List.copyOf(chunks);
        toolCalls = List.copyOf(toolCalls);
        chunkDelay = chunkDelay == null ? Duration.ZERO : chunkDelay;
        failure = failure == null ? Failure.NONE : failure;
    }

    public static ProviderScript text(List<String> chunks, ModelUsage usage) {
        return new ProviderScript(chunks, List.of(), usage, null, null, Duration.ZERO, Failure.NONE);
    }

    public static ProviderScript failing(Failure failure) {
        return new ProviderScript(List.of(), List.of(), null, null, null, Duration.ZERO, failure);
    }

    public ProviderScript withToolCalls(List<ToolCall> calls) {
        return new ProviderScript(chunks, calls, usage, servedModel, fingerprint, chunkDelay, failure);
    }

    public ProviderScript withChunkDelay(Duration delay) {
        return new ProviderScript(chunks, toolCalls, usage, servedModel, fingerprint, delay, failure);
    }

    public ProviderScript servedBy(String model, String systemFingerprint) {
        return new ProviderScript(chunks, toolCalls, usage, model, systemFingerprint, chunkDelay, failure);
    }

    public String content() {
        return String.join("", chunks);
    }
}
