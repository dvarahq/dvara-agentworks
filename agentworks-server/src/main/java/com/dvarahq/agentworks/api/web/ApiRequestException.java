package com.dvarahq.agentworks.api.web;

import org.springframework.http.HttpStatus;

import java.util.Map;
import java.util.Objects;

/**
 * A typed refusal raised by the web layer itself, for example a malformed cursor or a stream limit (issue #35).
 * {@link ApiFallbackExceptionHandler} renders it as a problem document with {@code code}, the fixed {@code detail},
 * any extra {@code properties}, and any response {@code headers} such as {@code Retry-After}.
 */
public final class ApiRequestException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final Map<String, Object> properties;
    private final Map<String, String> headers;

    public ApiRequestException(HttpStatus status, String code, String detail) {
        this(status, code, detail, Map.of(), Map.of());
    }

    public ApiRequestException(HttpStatus status, String code, String detail, Map<String, Object> properties,
            Map<String, String> headers) {
        super(Objects.requireNonNull(detail, "detail"), null, false, false);
        this.status = Objects.requireNonNull(status, "status");
        this.code = Objects.requireNonNull(code, "code");
        this.properties = Map.copyOf(properties);
        this.headers = Map.copyOf(headers);
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public Map<String, Object> properties() {
        return properties;
    }

    public Map<String, String> headers() {
        return headers;
    }
}
