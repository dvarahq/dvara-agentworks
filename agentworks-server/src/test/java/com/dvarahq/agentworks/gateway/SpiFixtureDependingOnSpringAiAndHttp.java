package com.dvarahq.agentworks.gateway;

import org.springframework.ai.fixture.FakeChatModel;

import java.net.http.HttpClient;

/** Deliberate violation: a gateway SPI type that exposes a Spring AI type and a transport type. */
public final class SpiFixtureDependingOnSpringAiAndHttp {

    private final FakeChatModel chatModel;
    private final HttpClient client;

    public SpiFixtureDependingOnSpringAiAndHttp(FakeChatModel chatModel, HttpClient client) {
        this.chatModel = chatModel;
        this.client = client;
    }
}
