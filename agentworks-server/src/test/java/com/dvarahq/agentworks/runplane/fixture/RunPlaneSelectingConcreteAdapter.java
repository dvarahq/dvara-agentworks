package com.dvarahq.agentworks.runplane.fixture;

import com.dvarahq.agentworks.gateway.openaicompat.OpenAiCompatibleGatewayAdapter;

/** Deliberate violation: run-plane code bypassing the gateway SPI to use a concrete adapter. */
public final class RunPlaneSelectingConcreteAdapter {

    private final OpenAiCompatibleGatewayAdapter adapter;

    public RunPlaneSelectingConcreteAdapter(OpenAiCompatibleGatewayAdapter adapter) {
        this.adapter = adapter;
    }
}
