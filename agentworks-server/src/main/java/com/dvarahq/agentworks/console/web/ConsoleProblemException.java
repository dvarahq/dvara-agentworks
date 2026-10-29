package com.dvarahq.agentworks.console.web;

import java.util.Objects;

/** An API call made by the Console was refused; {@link #problem()} is the API's own answer. */
public final class ConsoleProblemException extends RuntimeException {
    private final ConsoleProblems.ProblemView problem;

    public ConsoleProblemException(ConsoleProblems.ProblemView problem) {
        super(Objects.requireNonNull(problem, "problem").code(), null, false, false);
        this.problem = problem;
    }

    public ConsoleProblems.ProblemView problem() {
        return problem;
    }
}
