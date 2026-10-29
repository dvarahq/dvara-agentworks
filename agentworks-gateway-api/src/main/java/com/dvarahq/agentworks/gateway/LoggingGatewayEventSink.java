package com.dvarahq.agentworks.gateway;

/**
 * The default event sink until the durable run event store records gateway events (#34): it logs each event's
 * identifiers, which are secret-free by construction. Degradations and failures log at INFO, completions at DEBUG.
 */
public final class LoggingGatewayEventSink implements GatewayEventSink {

    private static final System.Logger LOG = System.getLogger(LoggingGatewayEventSink.class.getName());

    @Override
    public void record(GatewayEvent event) {
        System.Logger.Level level = event instanceof GatewayEvent.ModelInvocationCompleted
                ? System.Logger.Level.DEBUG : System.Logger.Level.INFO;
        if (LOG.isLoggable(level)) {
            LOG.log(level, event.type() + " " + event);
        }
    }
}
