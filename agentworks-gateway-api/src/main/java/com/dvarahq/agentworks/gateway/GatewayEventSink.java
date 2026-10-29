package com.dvarahq.agentworks.gateway;

/**
 * Receives gateway events synchronously on the invoking thread.
 *
 * <p>Degradation events are recorded before provider contact: if the sink throws there, the invocation aborts with
 * the sink's exception and no provider request is sent, so a degradation is never applied without evidence. Completion
 * and failure events are recorded after the outcome is known; a sink failure there is suppressed so evidence recording
 * cannot turn a completed model turn into an apparent failure that a caller might retry.
 */
@FunctionalInterface
public interface GatewayEventSink {
    void record(GatewayEvent event);
}
