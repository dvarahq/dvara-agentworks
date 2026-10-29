package com.dvarahq.agentworks.evaluation;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.JsonNodeFactory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import static com.dvarahq.agentworks.evaluation.EvaluationContracts.*;

/** Executes only deterministic, bounded evaluators over already-captured observations. */
public final class EvaluatorEngine {
    private static final int MAX_TEXT = 65_536;
    private final Map<String, CustomEvaluator> custom;

    public EvaluatorEngine(List<CustomEvaluator> customEvaluators) {
        Map<String, CustomEvaluator> registered = new HashMap<>();
        for (CustomEvaluator evaluator : List.copyOf(Objects.requireNonNullElse(customEvaluators, List.of()))) {
            String identity = Objects.requireNonNull(evaluator.identity(), "custom evaluator identity");
            if (!identity.matches("[A-Za-z0-9_.-]{1,128}") || registered.putIfAbsent(identity, evaluator) != null) {
                throw new IllegalArgumentException("custom evaluator identities must be unique and stable");
            }
        }
        custom = Map.copyOf(registered);
    }

    public Outcome evaluate(Evaluator evaluator, Case evaluationCase, Observation observation) {
        Objects.requireNonNull(evaluator, "evaluator"); Objects.requireNonNull(evaluationCase, "case");
        Objects.requireNonNull(observation, "observation");
        if (!evaluationCase.id().equals(observation.caseId())) throw new EvaluationException("CASE_MISMATCH", "observation case does not match");
        return switch (evaluator.type()) {
            case EXACT -> exact(evaluationCase, observation);
            case REGEX -> regex(evaluator.configuration(), observation);
            case JSON_SCHEMA -> schema(evaluator.configuration(), observation);
            case TOOL_TRACE -> toolTrace(evaluator.configuration(), observation);
            case POLICY -> booleanOutcome(observation.policyPassed(), "POLICY_PASSED", "POLICY_FAILED", object());
            case LATENCY -> threshold(observation.latencyMillis(), requiredLong(evaluator.configuration(), "maximumMillis"),
                    "LATENCY_WITHIN_LIMIT", "LATENCY_EXCEEDED", "observedMillis");
            case COST -> cost(evaluator.configuration(), observation);
            case CUSTOM_JAVA -> custom(evaluator.configuration(), evaluationCase, observation);
        };
    }

    /** Validates configuration before an evaluator identity is made immutable. */
    public void validate(EvaluatorType type, JsonNode configuration) {
        Objects.requireNonNull(type, "type"); Objects.requireNonNull(configuration, "configuration");
        switch (type) {
            case EXACT, POLICY -> requireObject(configuration);
            case REGEX -> safePattern(requiredText(configuration, "pattern"));
            case JSON_SCHEMA -> compileSchema(required(configuration, "schema"));
            case TOOL_TRACE -> { stringSet(configuration, "required"); stringSet(configuration, "forbidden"); }
            case LATENCY -> requiredLong(configuration, "maximumMillis");
            case COST -> requiredDecimal(configuration, "maximumUsd");
            case CUSTOM_JAVA -> {
                String identity = requiredText(configuration, "identity");
                if (!custom.containsKey(identity)) throw new EvaluationException("CUSTOM_EVALUATOR_UNAVAILABLE", "custom evaluator is not registered");
            }
        }
    }

    private static Outcome exact(Case evaluationCase, Observation observation) {
        boolean matched = evaluationCase.expected().equals(observation.output());
        ObjectNode evidence = object();
        evidence.put("expectedDigest", EvaluationDigests.json(evaluationCase.expected()));
        evidence.put("actualDigest", EvaluationDigests.json(observation.output()));
        return booleanOutcome(matched, "EXACT_MATCH", "EXACT_MISMATCH", evidence);
    }

    private static Outcome regex(JsonNode config, Observation observation) {
        String actual = text(observation.output());
        if (actual.length() > MAX_TEXT) return booleanOutcome(false, "REGEX_MATCH", "OUTPUT_TOO_LARGE", object());
        Pattern pattern = safePattern(requiredText(config, "pattern"));
        boolean matched = pattern.matcher(actual).matches();
        ObjectNode evidence = object(); evidence.put("patternDigest", EvaluationDigests.text(pattern.pattern()));
        return booleanOutcome(matched, "REGEX_MATCH", "REGEX_MISMATCH", evidence);
    }

    private static Outcome schema(JsonNode config, Observation observation) {
        Schema schema = compileSchema(required(config, "schema"));
        List<com.networknt.schema.Error> errors = schema.validate(observation.output());
        ObjectNode evidence = object(); evidence.put("violations", errors.size());
        return booleanOutcome(errors.isEmpty(), "JSON_SCHEMA_MATCH", "JSON_SCHEMA_VIOLATION", evidence);
    }

    private static Outcome toolTrace(JsonNode config, Observation observation) {
        Set<String> required = stringSet(config, "required");
        Set<String> forbidden = stringSet(config, "forbidden");
        Set<String> actual = new HashSet<>(observation.toolTrace());
        List<String> missing = required.stream().filter(tool -> !actual.contains(tool)).sorted().toList();
        List<String> denied = forbidden.stream().filter(actual::contains).sorted().toList();
        ObjectNode evidence = object(); evidence.put("observedCount", observation.toolTrace().size());
        evidence.put("missingCount", missing.size()); evidence.put("forbiddenCount", denied.size());
        return booleanOutcome(missing.isEmpty() && denied.isEmpty(), "TOOL_TRACE_MATCH", "TOOL_TRACE_MISMATCH", evidence);
    }

    private static Outcome cost(JsonNode config, Observation observation) {
        BigDecimal maximum = requiredDecimal(config, "maximumUsd");
        ObjectNode evidence = object(); evidence.put("observedUsd", observation.costUsd()); evidence.put("maximumUsd", maximum);
        return booleanOutcome(observation.costUsd().compareTo(maximum) <= 0, "COST_WITHIN_LIMIT", "COST_EXCEEDED", evidence);
    }

    private Outcome custom(JsonNode config, Case evaluationCase, Observation observation) {
        String identity = requiredText(config, "identity");
        CustomEvaluator evaluator = custom.get(identity);
        if (evaluator == null) throw new EvaluationException("CUSTOM_EVALUATOR_UNAVAILABLE", "custom evaluator is not registered");
        Outcome outcome = Objects.requireNonNull(evaluator.evaluate(evaluationCase, observation, config), "custom evaluator outcome");
        ObjectNode evidence = object();
        evidence.put("customEvaluatorIdentity", identity);
        evidence.put("customEvidenceDigest", EvaluationDigests.json(outcome.evidence()));
        return new Outcome(outcome.passed(), outcome.score(), outcome.code(), evidence);
    }

    private static Outcome threshold(long actual, long maximum, String pass, String fail, String field) {
        ObjectNode evidence = object(); evidence.put(field, actual); evidence.put("maximum", maximum);
        return booleanOutcome(actual <= maximum, pass, fail, evidence);
    }

    private static Outcome booleanOutcome(boolean passed, String passCode, String failCode, JsonNode evidence) {
        return new Outcome(passed, passed ? BigDecimal.ONE : BigDecimal.ZERO, passed ? passCode : failCode, evidence);
    }

    private static Pattern safePattern(String value) {
        if (value.length() > 256) throw invalid("regex pattern is too long");
        boolean inClass = false; boolean previousQuantifier = false; boolean hasAtom = false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '[') { if (inClass) throw invalid("nested character classes are not supported"); inClass = true; hasAtom = true; previousQuantifier = false; continue; }
            if (character == ']') { if (!inClass) throw invalid("unmatched character class"); inClass = false; continue; }
            if (!inClass && (character == '(' || character == ')' || character == '{' || character == '}' || character == '|' || character == '\\')) {
                throw invalid("regex uses an unsafe or unsupported construct");
            }
            if (!inClass && (character == '*' || character == '+' || character == '?')) {
                if (!hasAtom || previousQuantifier) throw invalid("regex quantifier is invalid");
                previousQuantifier = true; continue;
            }
            if (!inClass && character != '^' && character != '$') hasAtom = true;
            previousQuantifier = false;
        }
        if (inClass) throw invalid("unclosed character class");
        try { return Pattern.compile(value); } catch (PatternSyntaxException malformed) { throw invalid("regex pattern is invalid"); }
    }

    private static Schema compileSchema(JsonNode document) {
        if (!document.isObject()) throw invalid("schema must be an object");
        selfContained(document);
        try {
            return SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12, builder -> builder.schemaLoader(
                    loader -> loader.fetchRemoteResources(false).block(iri -> true))).getSchema(document);
        } catch (RuntimeException malformed) { throw invalid("schema is invalid"); }
    }

    private static void selfContained(JsonNode node) {
        if (node.isArray()) { for (JsonNode child : node.values()) selfContained(child); return; }
        if (!node.isObject()) return;
        for (Map.Entry<String, JsonNode> field : node.properties()) {
            if ((field.getKey().equals("$id") || field.getKey().equals("pattern") || field.getKey().equals("patternProperties"))
                    && !field.getValue().isNull()) throw invalid("schema contains an unsafe keyword");
            if ((field.getKey().equals("$ref") || field.getKey().equals("$dynamicRef")) && field.getValue().isString()
                    && !field.getValue().stringValue().startsWith("#")) throw invalid("remote schema references are forbidden");
            selfContained(field.getValue());
        }
    }

    private static Set<String> stringSet(JsonNode config, String name) {
        JsonNode node = config.get(name);
        if (node == null || node.isNull()) return Set.of();
        if (!node.isArray() || node.size() > 256) throw invalid(name + " must be a bounded array");
        Set<String> values = new HashSet<>();
        for (JsonNode value : node.values()) {
            if (!value.isString() || value.stringValue().isBlank() || value.stringValue().length() > 256) throw invalid(name + " contains an invalid value");
            values.add(value.stringValue());
        }
        return Set.copyOf(values);
    }

    private static long requiredLong(JsonNode config, String name) {
        JsonNode node = required(config, name);
        if (!node.isIntegralNumber() || node.longValue() < 0) throw invalid(name + " must be a non-negative integer");
        return node.longValue();
    }
    private static BigDecimal requiredDecimal(JsonNode config, String name) {
        JsonNode node = required(config, name);
        if (!node.isNumber() || node.decimalValue().signum() < 0) throw invalid(name + " must be a non-negative number");
        return node.decimalValue();
    }
    private static String requiredText(JsonNode config, String name) {
        JsonNode node = required(config, name);
        if (!node.isString() || node.stringValue().isBlank()) throw invalid(name + " must be a non-empty string");
        return node.stringValue();
    }
    private static JsonNode required(JsonNode config, String name) {
        requireObject(config); JsonNode node = config.get(name);
        if (node == null || node.isNull()) throw invalid(name + " is required"); return node;
    }
    private static void requireObject(JsonNode config) { if (!config.isObject()) throw invalid("configuration must be an object"); }
    private static String text(JsonNode node) { return node.isString() ? node.stringValue() : node.toString(); }
    private static ObjectNode object() { return JsonNodeFactory.instance.objectNode(); }
    private static EvaluationException invalid(String message) { return new EvaluationException("EVALUATOR_CONFIGURATION_INVALID", message); }
}
