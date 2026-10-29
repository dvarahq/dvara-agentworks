package com.dvarahq.agentworks.spec;

import java.util.Comparator;
import java.util.Objects;

/**
 * One actionable validation failure. {@code path} is an RFC 6901 JSON Pointer into the authored document; the
 * message never echoes authored values.
 */
public record SpecDiagnostic(DiagnosticCode code, SpecSection section, String path, String message) {
    static final Comparator<SpecDiagnostic> ORDER = Comparator.comparing(SpecDiagnostic::path)
            .thenComparing(SpecDiagnostic::code).thenComparing(SpecDiagnostic::message);

    public SpecDiagnostic {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(section, "section");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(message, "message");
    }

    public static SpecDiagnostic at(DiagnosticCode code, String path, String message) {
        return new SpecDiagnostic(code, SpecSection.of(path), path, message);
    }
}
