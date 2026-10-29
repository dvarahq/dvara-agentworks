package com.dvarahq.agentworks.controlplane.lifecycle;

import com.dvarahq.agentworks.spec.AgentSpecValidationException;
import com.dvarahq.agentworks.spec.DiagnosticCode;
import com.dvarahq.agentworks.spec.SpecDiagnostic;

import java.util.List;

/** Typed request validation shared by lifecycle services; rendered as 422 with JSON Pointer diagnostics. */
public final class LifecycleValidation {
    private LifecycleValidation() {}

    public static AgentSpecValidationException invalid(String path, String message) {
        return new AgentSpecValidationException(List.of(SpecDiagnostic.at(DiagnosticCode.INVALID_VALUE, path, message)));
    }
}
