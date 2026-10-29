package com.dvarahq.agentworks.sdk;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Immutable invocation of one versioned API operation. */
public record ApiCall(ApiOperation operation, Map<String, String> pathParameters,
                      Map<String, String> queryParameters, JsonNode body,
                      String idempotencyKey, String ifMatch) {
    public ApiCall {
        Objects.requireNonNull(operation, "operation");
        pathParameters = Map.copyOf(pathParameters == null ? Map.of() : pathParameters);
        queryParameters = Map.copyOf(queryParameters == null ? Map.of() : queryParameters);
        body = body == null ? JsonNodeFactory.instance.nullNode() : body;
        rejectHeaderBreak(idempotencyKey);
        rejectHeaderBreak(ifMatch);
    }

    public static Builder operation(ApiOperation operation) { return new Builder(operation); }

    String pathAndQuery() {
        String path = operation.pathTemplate();
        for (Map.Entry<String, String> entry : pathParameters.entrySet()) {
            path = path.replace("{" + entry.getKey() + "}", encode(entry.getValue()));
        }
        if (path.indexOf('{') >= 0) throw new IllegalArgumentException("missing path parameter for " + operation);
        if (queryParameters.isEmpty()) return path;
        String query = queryParameters.entrySet().stream()
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .reduce((left, right) -> left + "&" + right).orElseThrow();
        return path + "?" + query;
    }

    private static String encode(String value) {
        Objects.requireNonNull(value, "parameter value");
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static void rejectHeaderBreak(String value) {
        if (value != null && (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0)) {
            throw new IllegalArgumentException("header value contains a line break");
        }
    }

    public static final class Builder {
        private final ApiOperation operation;
        private final Map<String, String> paths = new LinkedHashMap<>();
        private final Map<String, String> query = new LinkedHashMap<>();
        private JsonNode body;
        private String idempotencyKey;
        private String ifMatch;

        private Builder(ApiOperation operation) { this.operation = Objects.requireNonNull(operation, "operation"); }
        public Builder path(String name, Object value) { paths.put(name, Objects.toString(value)); return this; }
        public Builder query(String name, Object value) { if (value != null) query.put(name, Objects.toString(value)); return this; }
        public Builder body(JsonNode body) { this.body = body; return this; }
        public Builder idempotencyKey(String value) { this.idempotencyKey = value; return this; }
        public Builder ifMatch(String value) { this.ifMatch = value; return this; }
        public ApiCall build() { return new ApiCall(operation, paths, query, body, idempotencyKey, ifMatch); }
    }
}
