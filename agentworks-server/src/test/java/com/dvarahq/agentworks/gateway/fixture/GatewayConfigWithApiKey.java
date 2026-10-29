package com.dvarahq.agentworks.gateway.fixture;

/** Deliberate violation: gateway configuration holding raw key material. */
public record GatewayConfigWithApiKey(String baseUrl, String apiKey) {
}
