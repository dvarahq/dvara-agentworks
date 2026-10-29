package com.dvarahq.agentworks.api.web;

import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * Keeps problem documents free of request content (issue #35). Spring fills an empty problem {@code instance} with
 * the request path, which would echo whatever the client put there; AgentWorks problems identify the error by
 * {@code type} and {@code code} alone, so the member is removed before the body is written.
 */
@RestControllerAdvice
public final class ProblemInstanceAdvice implements ResponseBodyAdvice<Object> {

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
            Class<? extends HttpMessageConverter<?>> selectedConverterType, ServerHttpRequest request,
            ServerHttpResponse response) {
        if (body instanceof ProblemDetail problem) problem.setInstance(null);
        return body;
    }
}
