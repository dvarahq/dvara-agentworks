package com.dvarahq.agentworks.gateway;

import java.util.List;
import java.util.Objects;

/**
 * The normalized result of one model invocation. {@link #toString()} renders no content.
 *
 * @param content the complete text content; for a stream it equals the concatenation of all delivered chunks
 * @param pinned the release-pinned identity that was invoked
 * @param served the identity that served the invocation
 * @param degradations every degradation Core applied, also emitted as events
 * @param chunks the number of chunks delivered to the listener (0 for a blocking call)
 */
public record ModelResponse(String content, List<ToolCall> toolCalls, FinishReason finishReason, ModelUsage usage,
        ResolvedModelIdentity pinned, ServedModel served, List<DegradedBehavior> degradations, long chunks) {

    public ModelResponse {
        Objects.requireNonNull(content, "content");
        toolCalls = List.copyOf(Objects.requireNonNull(toolCalls, "toolCalls"));
        Objects.requireNonNull(finishReason, "finishReason");
        Objects.requireNonNull(usage, "usage");
        Objects.requireNonNull(pinned, "pinned");
        Objects.requireNonNull(served, "served");
        degradations = List.copyOf(Objects.requireNonNull(degradations, "degradations"));
    }

    @Override
    public String toString() {
        return "ModelResponse[content=<" + content.length() + " chars redacted>, toolCalls=" + toolCalls
                + ", finishReason=" + finishReason + ", usage=" + usage + ", model=" + pinned.providerModel()
                + ", served=" + served + ", degradations=" + degradations + ", chunks=" + chunks + "]";
    }
}
