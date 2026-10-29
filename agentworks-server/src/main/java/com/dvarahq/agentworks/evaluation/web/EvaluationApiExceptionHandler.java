package com.dvarahq.agentworks.evaluation.web;

import com.dvarahq.agentworks.evaluation.EvaluationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

/** RFC 9457 evaluation validation, scope, integrity, and lifecycle refusals. */
@org.springframework.core.annotation.Order(com.dvarahq.agentworks.api.web.ApiProblems.SPECIFIC_ORDER)
@RestControllerAdvice(assignableTypes = EvaluationController.class)
public final class EvaluationApiExceptionHandler {
    private static final Set<String> NOT_FOUND = Set.of("DATASET_NOT_FOUND", "EVALUATOR_NOT_FOUND",
            "EVALUATION_RUN_NOT_FOUND", "BASELINE_NOT_FOUND");

    @ExceptionHandler(EvaluationException.class)
    ProblemDetail evaluation(EvaluationException failure) {
        HttpStatus status = NOT_FOUND.contains(failure.code()) ? HttpStatus.NOT_FOUND
                : failure.code().endsWith("INVALID") || failure.code().contains("INCOMPLETE")
                || failure.code().contains("MISMATCH") || failure.code().startsWith("DUPLICATE")
                || failure.code().equals("CASE_TOO_LARGE") ? HttpStatus.BAD_REQUEST : HttpStatus.CONFLICT;
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, failure.getMessage());
        problem.setTitle("Evaluation refused");
        problem.setType(URI.create("urn:dvara:problem:" + failure.code().toLowerCase(Locale.ROOT).replace('_', '-')));
        problem.setProperty("code", failure.code()); return problem;
    }

    @ExceptionHandler({IllegalArgumentException.class, NullPointerException.class})
    ProblemDetail invalid(RuntimeException failure) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "The evaluation request is invalid.");
        problem.setTitle("Invalid evaluation request"); problem.setType(URI.create("urn:dvara:problem:invalid-evaluation-request"));
        problem.setProperty("code", "INVALID_EVALUATION_REQUEST"); return problem;
    }
}
