package com.dvarahq.agentworks.gateway.fake;

import com.dvarahq.agentworks.gateway.FinishReason;
import com.dvarahq.agentworks.gateway.GatewayErrorCode;
import com.dvarahq.agentworks.gateway.ModelUsage;
import com.dvarahq.agentworks.gateway.ToolCall;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * A scripted provider answer for {@link DeterministicFakeGatewayAdapter}.
 *
 * @param chunks content deltas in order; the content is their concatenation
 * @param usage the usage the fake provider reports, or {@code null} to report none
 * @param servedModel the served model the fake reports, or {@code null} to report the invoked model
 * @param chunkDelay pause before each chunk
 * @param failure a typed provider failure raised after the credential is presented, or {@code null}
 * @param hang wait (up to {@link DeterministicFakeGatewayAdapter#HANG_LIMIT}) instead of answering
 */
public record FakeReply(List<String> chunks, List<ToolCall> toolCalls, FinishReason finishReason, ModelUsage usage,
        String servedModel, String systemFingerprint, Duration chunkDelay, GatewayErrorCode failure, boolean hang) {

    public FakeReply {
        chunks = List.copyOf(Objects.requireNonNull(chunks, "chunks"));
        toolCalls = List.copyOf(Objects.requireNonNull(toolCalls, "toolCalls"));
        Objects.requireNonNull(finishReason, "finishReason");
        chunkDelay = chunkDelay == null ? Duration.ZERO : chunkDelay;
        if (failure != null && failure.beforeProviderContact()) {
            throw new IllegalArgumentException(failure + " is raised by Core, not by a provider");
        }
    }

    public static FakeReply text(List<String> chunks, ModelUsage usage) {
        return new FakeReply(chunks, List.of(), FinishReason.STOP, usage, null, null, Duration.ZERO, null, false);
    }

    public static FakeReply failing(GatewayErrorCode failure) {
        return new FakeReply(List.of(), List.of(), FinishReason.OTHER, null, null, null, Duration.ZERO, failure, false);
    }

    public static FakeReply hanging() {
        return new FakeReply(List.of(), List.of(), FinishReason.OTHER, null, null, null, Duration.ZERO, null, true);
    }

    public FakeReply withToolCalls(List<ToolCall> calls) {
        return new FakeReply(chunks, calls, FinishReason.TOOL_CALLS, usage, servedModel, systemFingerprint,
                chunkDelay, failure, hang);
    }

    public FakeReply withChunkDelay(Duration delay) {
        return new FakeReply(chunks, toolCalls, finishReason, usage, servedModel, systemFingerprint, delay, failure,
                hang);
    }

    public FakeReply servedBy(String model, String fingerprint) {
        return new FakeReply(chunks, toolCalls, finishReason, usage, model, fingerprint, chunkDelay, failure, hang);
    }

    public String content() {
        return String.join("", chunks);
    }

    @Override
    public String toString() {
        return "FakeReply[chunks=" + chunks.size() + ", toolCalls=" + toolCalls.size() + ", failure=" + failure
                + ", hang=" + hang + "]";
    }
}
