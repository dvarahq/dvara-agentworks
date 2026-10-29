package com.dvarahq.agentworks.spec;

import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Verifies that a schema revision only evolves an API version additively: every document valid under the baseline
 * must stay valid and keep its meaning. Anything else requires a new {@code apiVersion}.
 */
final class SchemaEvolutionChecker {
    private static final List<String> LOWER_BOUNDS = List.of("minimum", "exclusiveMinimum", "minLength", "minItems",
            "minProperties");
    private static final List<String> UPPER_BOUNDS = List.of("maximum", "exclusiveMaximum", "maxLength", "maxItems",
            "maxProperties");
    private static final List<String> EXACT = List.of("type", "const", "pattern", "additionalProperties");
    /** Keywords compared structurally above, or annotations that never affect validity. */
    private static final Set<String> UNDERSTOOD = Set.of("type", "const", "pattern", "additionalProperties", "enum",
            "required", "properties", "items", "uniqueItems", "minimum", "exclusiveMinimum", "minLength", "minItems",
            "minProperties", "maximum", "exclusiveMaximum", "maxLength", "maxItems", "maxProperties", "$ref", "$defs",
            "$id", "$schema", "title", "description", "default", "examples");

    private final JsonNode baselineRoot;
    private final JsonNode candidateRoot;
    private final List<String> violations = new ArrayList<>();

    private SchemaEvolutionChecker(JsonNode baselineRoot, JsonNode candidateRoot) {
        this.baselineRoot = baselineRoot;
        this.candidateRoot = candidateRoot;
    }

    static List<String> breakingChanges(JsonNode baseline, JsonNode candidate) {
        var checker = new SchemaEvolutionChecker(baseline, candidate);
        checker.compare(baseline, candidate, "#");
        return List.copyOf(checker.violations);
    }

    private void compare(JsonNode baseline, JsonNode candidate, String location) {
        baseline = resolve(baselineRoot, baseline);
        candidate = resolve(candidateRoot, candidate);
        if (candidate == null) {
            violations.add(location + ": removed");
            return;
        }
        for (String keyword : EXACT) {
            if (baseline.has(keyword) && !baseline.get(keyword).equals(candidate.get(keyword))) {
                violations.add(location + ": changed " + keyword);
            }
            if (!baseline.has(keyword) && candidate.has(keyword)) violations.add(location + ": added " + keyword);
        }
        if (baseline.has("enum")) {
            Set<JsonNode> allowed = new HashSet<>();
            candidate.path("enum").values().forEach(allowed::add);
            baseline.get("enum").values().forEach(value -> {
                if (!allowed.contains(value)) violations.add(location + ": enum dropped " + value);
            });
        } else if (candidate.has("enum")) {
            violations.add(location + ": added enum");
        }
        Set<String> previouslyRequired = new HashSet<>();
        baseline.path("required").values().forEach(value -> previouslyRequired.add(value.asString()));
        candidate.path("required").values().forEach(value -> {
            if (!previouslyRequired.contains(value.asString())) {
                violations.add(location + ": newly required " + value.asString());
            }
        });
        for (String bound : LOWER_BOUNDS) checkBound(baseline, candidate, location, bound, true);
        for (String bound : UPPER_BOUNDS) checkBound(baseline, candidate, location, bound, false);
        if (!baseline.path("uniqueItems").asBoolean(false) && candidate.path("uniqueItems").asBoolean(false)) {
            violations.add(location + ": added uniqueItems");
        }
        // Any other keyword (multipleOf, not, allOf, if/then, format, propertyNames, ...) can reject previously
        // valid documents, so adding or changing one is treated as breaking.
        for (String keyword : candidate.propertyNames()) {
            if (!UNDERSTOOD.contains(keyword) && !candidate.get(keyword).equals(baseline.get(keyword))) {
                violations.add(location + ": added or changed unrecognized keyword " + keyword);
            }
        }
        for (Map.Entry<String, JsonNode> property : baseline.path("properties").properties()) {
            compare(property.getValue(), candidate.path("properties").get(property.getKey()),
                    location + "/properties/" + property.getKey());
        }
        if (baseline.has("items")) compare(baseline.get("items"), candidate.get("items"), location + "/items");
    }

    private void checkBound(JsonNode baseline, JsonNode candidate, String location, String bound, boolean lower) {
        if (!candidate.has(bound)) return;
        if (!baseline.has(bound)) {
            violations.add(location + ": added " + bound);
            return;
        }
        int comparison = candidate.get(bound).decimalValue().compareTo(baseline.get(bound).decimalValue());
        if (lower ? comparison > 0 : comparison < 0) violations.add(location + ": tightened " + bound);
    }

    private static JsonNode resolve(JsonNode root, JsonNode node) {
        JsonNode current = node;
        for (int hops = 0; current != null && current.has("$ref") && hops < 16; hops++) {
            String reference = current.get("$ref").asString();
            if (!reference.startsWith("#")) throw new IllegalArgumentException("external $ref " + reference);
            current = root.at(reference.substring(1));
            if (current.isMissingNode()) return null;
        }
        return current;
    }
}
