package com.dvarahq.agentworks.exposure;

/** Provider adapter SPI. It maps transport data but owns no run, session, route, or release authority. */
public interface InteractionChannel {
    String type();
    InboundInteraction normalize(ChannelEnvelope envelope);
    DeliveryResult send(OutboundInteraction output);
}
