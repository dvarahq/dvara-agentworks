package com.dvarahq.agentworks.gateway.dvarahq;

@FunctionalInterface
public interface DvaraHqObservationSink {
    DvaraHqObservationSink NOOP = ignored -> { };
    void accept(DvaraHqObservation observation);
}
