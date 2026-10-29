package com.dvarahq.agentworks.a2a;

import java.util.Map;

public record A2aOutboundResponse(int status, Map<String, String> headers, byte[] body) {
    public A2aOutboundResponse {
        headers = Map.copyOf(headers);
        body = body.clone();
    }

    @Override public byte[] body() { return body.clone(); }
}
