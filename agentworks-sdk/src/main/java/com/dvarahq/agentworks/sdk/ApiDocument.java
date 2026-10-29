package com.dvarahq.agentworks.sdk;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Successful typed transport envelope; resource payloads remain forward-compatible JSON. */
public record ApiDocument(int status, JsonNode body, Map<String, List<String>> headers) {
    public ApiDocument {
        if (status < 200 || status >= 300) throw new IllegalArgumentException("status must be successful");
        headers = Map.copyOf(headers);
    }

    public Optional<String> etag() { return first("etag"); }
    public Optional<URI> location() { return first("location").map(URI::create); }
    public Optional<String> nextCursor() {
        return body != null && body.hasNonNull("nextCursor") ? Optional.of(body.get("nextCursor").asString()) : Optional.empty();
    }

    public <T> T bodyAs(Class<T> type) {
        return JsonMapper.builder().build().treeToValue(body, type);
    }

    private Optional<String> first(String name) {
        return headers.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .flatMap(entry -> entry.getValue().stream()).findFirst();
    }
}
