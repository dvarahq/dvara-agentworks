package com.dvarahq.agentworks.evaluation;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.dvarahq.agentworks.evaluation.EvaluationContracts.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvaluatorEngineTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final EvaluatorEngine engine = new EvaluatorEngine(List.of(new CustomEvaluator() {
        @Override public String identity() { return "example.score.v1"; }
        @Override public Outcome evaluate(Case evaluationCase, Observation observation, JsonNode configuration) {
            return new Outcome(true, new BigDecimal("0.75"), "CUSTOM_PASS", JSON.valueToTree(Map.of("raw", "secret-fixture")));
        }
    }));

    @Test
    void executesEveryDeterministicEvaluatorWithContentFreeEvidence() {
        Case item = new Case("case-1", json("{\"question\":\"q\"}"), json("{\"answer\":42}"), Map.of());
        Observation observation = new Observation("case-1", json("{\"answer\":42}"), List.of("search", "read"),
                true, 25, new BigDecimal("0.01"), "run:1", EvaluationDigests.text("source"));

        assertThat(evaluate(EvaluatorType.EXACT, "{}", item, observation).passed()).isTrue();
        assertThat(evaluate(EvaluatorType.REGEX, "{\"pattern\":\".*42.*\"}", item, observation).passed()).isTrue();
        assertThat(evaluate(EvaluatorType.JSON_SCHEMA, "{\"schema\":{\"type\":\"object\",\"required\":[\"answer\"]}}", item, observation).passed()).isTrue();
        assertThat(evaluate(EvaluatorType.TOOL_TRACE, "{\"required\":[\"search\"],\"forbidden\":[\"delete\"]}", item, observation).passed()).isTrue();
        assertThat(evaluate(EvaluatorType.POLICY, "{}", item, observation).passed()).isTrue();
        assertThat(evaluate(EvaluatorType.LATENCY, "{\"maximumMillis\":25}", item, observation).passed()).isTrue();
        assertThat(evaluate(EvaluatorType.COST, "{\"maximumUsd\":0.01}", item, observation).passed()).isTrue();
        Outcome custom = evaluate(EvaluatorType.CUSTOM_JAVA, "{\"identity\":\"example.score.v1\"}", item, observation);
        assertThat(custom.score()).isEqualByComparingTo("0.75");
        assertThat(custom.evidence().toString()).contains("customEvidenceDigest").doesNotContain("secret-fixture");
        assertThat(evaluate(EvaluatorType.EXACT, "{}", item, observation).evidence().toString())
                .contains("Digest").doesNotContain("answer");
    }

    @Test
    void rejectsRemoteSchemasUnsafeRegexAndUnregisteredJava() {
        assertThatThrownBy(() -> engine.validate(EvaluatorType.JSON_SCHEMA,
                json("{\"schema\":{\"$ref\":\"https://example.test/schema\"}}")))
                .isInstanceOf(EvaluationException.class).extracting("code").isEqualTo("EVALUATOR_CONFIGURATION_INVALID");
        assertThatThrownBy(() -> engine.validate(EvaluatorType.REGEX, json("{\"pattern\":\"(a+)+\"}")))
                .isInstanceOf(EvaluationException.class);
        assertThatThrownBy(() -> engine.validate(EvaluatorType.CUSTOM_JAVA, json("{\"identity\":\"unknown\"}")))
                .isInstanceOf(EvaluationException.class).extracting("code").isEqualTo("CUSTOM_EVALUATOR_UNAVAILABLE");
    }

    @Test
    void canonicalDigestsIgnoreJsonObjectFieldOrder() {
        assertThat(EvaluationDigests.json(json("{\"alpha\":1,\"nested\":{\"x\":2,\"y\":3}}")))
                .isEqualTo(EvaluationDigests.json(json("{\"nested\":{\"y\":3,\"x\":2},\"alpha\":1}")));
    }

    private Outcome evaluate(EvaluatorType type, String configuration, Case item, Observation observation) {
        JsonNode config = json(configuration); engine.validate(type, config);
        Evaluator evaluator = new Evaluator(UUID.randomUUID(), UUID.randomUUID(), type.name(), 1, type, config,
                EvaluationDigests.json(config), Instant.EPOCH);
        return engine.evaluate(evaluator, item, observation);
    }
    private static JsonNode json(String value) { return JSON.readTree(value); }
}
