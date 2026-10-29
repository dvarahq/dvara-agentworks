package com.dvarahq.agentworks.sdk;

import tools.jackson.databind.JsonNode;

/** RFC 9457 response surfaced without credentials or request content. */
public final class ApiProblemException extends RuntimeException {
    private final int status;
    private final String code;
    private final JsonNode problem;

    public ApiProblemException(int status, JsonNode problem) {
        super(message(status, problem));
        this.status = status;
        this.problem = problem;
        this.code = problem != null && problem.hasNonNull("code") ? problem.get("code").asString() : "HTTP_ERROR";
    }

    public int status() { return status; }
    public String code() { return code; }
    public JsonNode problem() { return problem; }

    private static String message(int status, JsonNode problem) {
        String code = problem != null && problem.hasNonNull("code") ? problem.get("code").asString() : "HTTP_ERROR";
        return "AgentWorks API refused the request: " + status + " " + code;
    }
}
