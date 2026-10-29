package com.dvarahq.agentworks.a2a;

import java.util.Map;
import java.util.Objects;

public record A2aInboundRequest(String method, String path, Map<String, String> headers, byte[] body) {
    public A2aInboundRequest {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(path, "path");
        headers = Map.copyOf(Objects.requireNonNull(headers, "headers"));
        body = Objects.requireNonNull(body, "body").clone();
    }

    @Override public byte[] body() { return body.clone(); }
}
