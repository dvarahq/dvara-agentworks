package com.dvarahq.agentworks.spec;

import com.dvarahq.agentworks.spec.model.AgentSpec;
import com.dvarahq.agentworks.spec.model.CompactionSpec;
import com.dvarahq.agentworks.spec.model.CompactionStrategy;
import com.dvarahq.agentworks.spec.model.LimitsSpec;
import com.dvarahq.agentworks.spec.model.SandboxSpec;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Cross-field and unit rules that JSON Schema cannot express precisely. */
final class SemanticRules {
    static final BigInteger MAX_RUNTIME_SECONDS = BigInteger.valueOf(7L * 24 * 60 * 60);
    static final BigInteger MIN_SANDBOX_MEMORY = BigInteger.ONE.shiftLeft(20);
    static final BigInteger MAX_SANDBOX_MEMORY = BigInteger.ONE.shiftLeft(40);
    private static final Map<String, BigInteger> MEMORY_UNITS = Map.of(
            "Ki", BigInteger.ONE.shiftLeft(10), "Mi", BigInteger.ONE.shiftLeft(20),
            "Gi", BigInteger.ONE.shiftLeft(30), "Ti", BigInteger.ONE.shiftLeft(40));
    private static final Pattern DOCKER_DIGEST_IMAGE =
            Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._/:+-]*@sha256:[a-f0-9]{64}$");

    private SemanticRules() {}

    static List<SpecDiagnostic> check(AgentSpec spec) {
        List<SpecDiagnostic> diagnostics = new ArrayList<>();
        var body = spec.spec();
        if (body.limits() != null) limits(body.limits(), diagnostics);
        if (body.sandbox() != null) sandbox(body.sandbox(), diagnostics);
        if (body.context() != null && body.context().compaction() != null) {
            compaction(body.context().compaction(), diagnostics);
        }
        return diagnostics;
    }

    private static void limits(LimitsSpec limits, List<SpecDiagnostic> diagnostics) {
        if (limits.maxRuntime() != null) {
            String value = limits.maxRuntime();
            int unitStart = value.endsWith("ms") ? value.length() - 2 : value.length() - 1;
            BigInteger amount = new BigInteger(value.substring(0, unitStart));
            BigInteger millis = switch (value.substring(unitStart)) {
                case "ms" -> amount;
                case "s" -> amount.multiply(BigInteger.valueOf(1_000));
                case "m" -> amount.multiply(BigInteger.valueOf(60_000));
                default -> amount.multiply(BigInteger.valueOf(3_600_000));
            };
            if (millis.compareTo(MAX_RUNTIME_SECONDS.multiply(BigInteger.valueOf(1_000))) > 0) {
                diagnostics.add(SpecDiagnostic.at(DiagnosticCode.OUT_OF_RANGE, "/spec/limits/maxRuntime",
                        "must not exceed 168h"));
            }
        }
        if (scale(limits.maxCostUsd()) > 4) {
            diagnostics.add(SpecDiagnostic.at(DiagnosticCode.INVALID_VALUE, "/spec/limits/maxCostUsd",
                    "must have at most 4 decimal places"));
        }
    }

    private static void sandbox(SandboxSpec sandbox, List<SpecDiagnostic> diagnostics) {
        if ("docker".equals(sandbox.provider())
                && (sandbox.image() == null || !DOCKER_DIGEST_IMAGE.matcher(sandbox.image()).matches())) {
            diagnostics.add(SpecDiagnostic.at(DiagnosticCode.INVALID_VALUE, "/spec/sandbox/image",
                    "docker images must be pinned by sha256 digest"));
        }
        if (sandbox.memory() != null) {
            String value = sandbox.memory();
            String unit = value.length() > 2 ? value.substring(value.length() - 2) : "";
            BigInteger multiplier = MEMORY_UNITS.get(unit);
            BigInteger bytes = multiplier == null ? new BigInteger(value)
                    : new BigInteger(value.substring(0, value.length() - 2)).multiply(multiplier);
            if (bytes.compareTo(MIN_SANDBOX_MEMORY) < 0 || bytes.compareTo(MAX_SANDBOX_MEMORY) > 0) {
                diagnostics.add(SpecDiagnostic.at(DiagnosticCode.OUT_OF_RANGE, "/spec/sandbox/memory",
                        "must be between 1Mi and 1Ti"));
            }
        }
        if (scale(sandbox.cpu()) > 3) {
            diagnostics.add(SpecDiagnostic.at(DiagnosticCode.INVALID_VALUE, "/spec/sandbox/cpu",
                    "must have at most 3 decimal places"));
        }
    }

    private static void compaction(CompactionSpec compaction, List<SpecDiagnostic> diagnostics) {
        if (Boolean.TRUE.equals(compaction.enabled()) && compaction.strategy() == CompactionStrategy.NONE) {
            diagnostics.add(SpecDiagnostic.at(DiagnosticCode.INVALID_VALUE, "/spec/context/compaction/strategy",
                    "enabled compaction requires a strategy other than none"));
        }
    }

    private static int scale(BigDecimal value) {
        return value == null ? 0 : value.stripTrailingZeros().scale();
    }
}
