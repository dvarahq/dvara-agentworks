package com.dvarahq.agentworks.architecture.fixture.core;

import com.dvarahq.agentworks.gateway.dvarahq.fixture.DvaraHqAdapterStandIn;

/** Deliberate violation: Core code referencing the optional DvaraHQ adapter. */
public final class CoreDependingOnDvaraHqAdapter {

    private final DvaraHqAdapterStandIn adapter;

    public CoreDependingOnDvaraHqAdapter(DvaraHqAdapterStandIn adapter) {
        this.adapter = adapter;
    }
}
