package com.dvarahq.agentworks.observability;

import com.dvarahq.agentworks.api.web.ApiProblems;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = DiagnosticsController.class)
@Order(ApiProblems.SPECIFIC_ORDER)
public final class DiagnosticsExceptionHandler {
    @ExceptionHandler(SupportBundleService.InvalidTraceIdException.class)
    ProblemDetail invalid(SupportBundleService.InvalidTraceIdException invalid) {
        return ApiProblems.problem(HttpStatus.BAD_REQUEST, "INVALID_TRACE_ID", invalid.getMessage());
    }

    @ExceptionHandler(SupportBundleService.SupportBundleTooLargeException.class)
    ProblemDetail tooLarge(SupportBundleService.SupportBundleTooLargeException tooLarge) {
        return ApiProblems.problem(HttpStatus.CONTENT_TOO_LARGE, "SUPPORT_BUNDLE_TOO_LARGE",
                "The support bundle exceeds the configured " + tooLarge.maximum() + " byte maximum.");
    }
}
