package com.dvarahq.agentworks.gateway;

/**
 * What the adapter may use for one invocation, derived from negotiation. A {@code false} flag means the capability
 * was not needed or was degraded; the adapter must not use it either way.
 *
 * @param stream stream through the {@link ChunkSink}
 * @param tools send the request's tool definitions and parse tool calls
 * @param structuredOutput send the request's JSON Schema response format
 * @param usage request and report provider usage
 * @param cancellation abort the provider request when the signal is cancelled
 */
public record InvocationPlan(boolean stream, boolean tools, boolean structuredOutput, boolean usage,
        boolean cancellation) {
}
