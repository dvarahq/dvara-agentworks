package com.dvarahq.agentworks.gateway;

import java.util.List;
import java.util.Objects;

/**
 * An adapter's raw, normalized result. Core turns it into a {@link ModelResponse}.
 *
 * @param content the complete content; for a stream, exactly the concatenation of the emitted deltas
 * @param usage provider-reported usage, or {@code null} when not reported
 * @param servedModel the provider-reported serving model, or {@code null}
 * @param systemFingerprint the provider-reported fingerprint, or {@code null}
 * @param usageMalformed the provider sent a usage record that could not be normalized (then {@code usage} is
 *        {@code null}); Core degrades usage or fails with {@code INVALID_PROVIDER_RESPONSE} per the captured policy
 */
public record AdapterResult(String content, List<ToolCall> toolCalls, FinishReason finishReason, ModelUsage usage,
        String servedModel, String systemFingerprint, boolean usageMalformed) {

    public AdapterResult(String content, List<ToolCall> toolCalls, FinishReason finishReason, ModelUsage usage,
            String servedModel, String systemFingerprint) {
        this(content, toolCalls, finishReason, usage, servedModel, systemFingerprint, false);
    }

    public AdapterResult {
        if (usageMalformed && usage != null) {
            throw new IllegalArgumentException("malformed usage carries no usage record");
        }
        Objects.requireNonNull(content, "content");
        toolCalls = List.copyOf(Objects.requireNonNull(toolCalls, "toolCalls"));
        Objects.requireNonNull(finishReason, "finishReason");
    }

    @Override
    public String toString() {
        return "AdapterResult[content=<" + content.length() + " chars redacted>, toolCalls=" + toolCalls
                + ", finishReason=" + finishReason + ", usage=" + usage + ", servedModel=" + servedModel + "]";
    }
}
