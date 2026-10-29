package com.dvarahq.agentworks.spec;

import java.util.List;

/** Rejected AgentSpec input. Carries every actionable diagnostic found at the failing stage. */
public final class AgentSpecValidationException extends RuntimeException {
    private final transient List<SpecDiagnostic> diagnostics;

    public AgentSpecValidationException(List<SpecDiagnostic> diagnostics) {
        super(summary(diagnostics));
        this.diagnostics = diagnostics.stream().sorted(SpecDiagnostic.ORDER).distinct().toList();
    }

    public List<SpecDiagnostic> diagnostics() {
        return diagnostics;
    }

    private static String summary(List<SpecDiagnostic> diagnostics) {
        if (diagnostics.isEmpty()) throw new IllegalArgumentException("diagnostics must not be empty");
        return "AgentSpec is invalid: " + diagnostics.size() + " diagnostic(s)";
    }
}
