package com.dvarahq.agentworks.runplane.execution;

import com.dvarahq.agentworks.domain.lifecycle.state.LifecycleRows.ReleaseRow;
import com.dvarahq.agentworks.domain.run.RunLimits;
import com.dvarahq.agentworks.gateway.PinnedModelRoles;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads release-captured AgentSpec limits and applies immutable operator ceilings. Older retained manifests keep the
 * configured ceilings that existed before manifest version 7. A release may tighten a ceiling but can never relax an
 * operator ceiling. AgentSpec {@code maxSteps} maps to the runtime's model/decision-step counter.
 */
public final class ManifestRunLimitsPolicy implements RunLimitsPolicy {
    private static final Set<String> FIELDS = Set.of("maxSteps", "maxToolCalls", "maxRuntime", "maxCostUsd");
    private final JsonMapper json = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private final RunLimits ceilings;

    public ManifestRunLimitsPolicy(RunLimits ceilings) {
        this.ceilings = Objects.requireNonNull(ceilings, "ceilings");
    }

    @Override
    public RunLimits limitsFor(ReleaseRow release) {
        Objects.requireNonNull(release, "release");
        JsonNode manifest;
        try {
            manifest = json.readTree(release.manifestJson());
        } catch (JacksonException malformed) {
            throw new RunEngineException("INVALID_MANIFEST", "the release manifest is not valid JSON", malformed);
        }
        if (!manifest.path("manifestVersion").isInt()) {
            throw new RunEngineException("INVALID_MANIFEST", "the release manifest has no integer version");
        }
        if (manifest.path("manifestVersion").intValue() < PinnedModelRoles.ADMISSION_LIMITS_MANIFEST_VERSION) {
            return ceilings;
        }
        JsonNode authored = manifest.get("limits");
        if (authored == null || !authored.isObject() || !FIELDS.containsAll(Set.copyOf(authored.propertyNames()))) {
            throw new RunEngineException("INVALID_MANIFEST", "release limits have an unknown field or are not an object");
        }
        Long steps = integer(authored, "maxSteps");
        Long tools = integer(authored, "maxToolCalls");
        Long runtime = authored.has("maxRuntime") ? duration(text(authored, "maxRuntime")) : null;
        BigDecimal cost = decimal(authored, "maxCostUsd");
        return new RunLimits(min(ceilings.maxTotalTokens(), null), min(ceilings.maxModelCalls(), steps),
                min(ceilings.maxToolCalls(), tools), min(ceilings.maxRuntimeMillis(), runtime),
                min(ceilings.maxCostUsd(), cost));
    }

    private static Long integer(JsonNode node, String field) {
        if (!node.has(field)) return null;
        JsonNode value = node.get(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) {
            throw new RunEngineException("INVALID_MANIFEST", "release limit " + field + " is a non-negative integer");
        }
        return value.longValue();
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        if (!node.has(field)) return null;
        JsonNode value = node.get(field);
        if (!value.isNumber() || value.decimalValue().signum() < 0) {
            throw new RunEngineException("INVALID_MANIFEST", "release limit " + field + " is non-negative");
        }
        return value.decimalValue();
    }

    private static String text(JsonNode node, String field) {
        if (!node.get(field).isString()) {
            throw new RunEngineException("INVALID_MANIFEST", "release limit " + field + " is a duration");
        }
        return node.get(field).stringValue();
    }

    static long duration(String value) {
        try {
            int split = 0;
            while (split < value.length() && Character.isDigit(value.charAt(split))) split++;
            long amount = Long.parseLong(value.substring(0, split));
            String unit = value.substring(split);
            return switch (unit) {
                case "ms" -> amount;
                case "s" -> Math.multiplyExact(amount, 1_000L);
                case "m" -> Math.multiplyExact(amount, 60_000L);
                case "h" -> Math.multiplyExact(amount, 3_600_000L);
                default -> throw new IllegalArgumentException();
            };
        } catch (RuntimeException malformed) {
            throw new RunEngineException("INVALID_MANIFEST", "release maxRuntime is not a supported duration");
        }
    }

    private static Long min(Long left, Long right) {
        if (left == null) return right;
        if (right == null) return left;
        return Math.min(left, right);
    }

    private static BigDecimal min(BigDecimal left, BigDecimal right) {
        if (left == null) return right;
        if (right == null) return left;
        return left.min(right);
    }
}
