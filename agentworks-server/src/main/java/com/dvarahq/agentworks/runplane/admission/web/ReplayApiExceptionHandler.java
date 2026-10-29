package com.dvarahq.agentworks.runplane.admission.web;

import com.dvarahq.agentworks.replay.ReplayException;
import com.dvarahq.agentworks.gateway.GatewayException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

/** RFC 9457 typed replay refusals. */
@org.springframework.core.annotation.Order(com.dvarahq.agentworks.api.web.ApiProblems.SPECIFIC_ORDER)
@RestControllerAdvice(assignableTypes = ReplayController.class)
public final class ReplayApiExceptionHandler {
    @ExceptionHandler(ReplayException.class)
    ProblemDetail refused(ReplayException failure) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, failure.getMessage());
        problem.setTitle("Replay refused");
        problem.setType(URI.create("urn:dvara:problem:" + failure.code().toLowerCase(java.util.Locale.ROOT)
                .replace('_', '-')));
        problem.setProperty("code", failure.code());
        return problem;
    }

    @ExceptionHandler(GatewayException.class)
    ProblemDetail gateway(GatewayException failure) {
        String code = "GATEWAY_" + failure.code().name();
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, failure.detail());
        problem.setTitle("Model replay failed");
        problem.setType(URI.create("urn:dvara:problem:" + code.toLowerCase(java.util.Locale.ROOT)
                .replace('_', '-')));
        problem.setProperty("code", code);
        return problem;
    }
}
