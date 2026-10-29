package com.dvarahq.agentworks.agent.springai;

import com.dvarahq.agentworks.agent.springai.ExecutorInput.InvalidInputException;
import com.dvarahq.agentworks.gateway.ResponseFormat;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The executor's input contract and structured-output normalization (issue #34 AC5). */
class ExecutorInputTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static JsonNode json(String text) {
        return JSON.readTree(text);
    }

    private static void refused(String input, String code, String message) {
        assertThatThrownBy(() -> ExecutorInput.from(json(input))).isInstanceOfSatisfying(InvalidInputException.class,
                invalid -> {
                    assertThat(invalid.code()).isEqualTo(code);
                    assertThat(invalid.getMessage()).contains(message);
                });
    }

    @Test
    void textPromptAndArbitraryJsonInputsBecomeTheUserMessage() {
        assertThat(ExecutorInput.from(json("\"hello\"")).prompt()).isEqualTo("hello");
        assertThat(ExecutorInput.from(json("{\"prompt\":\"hi\"}")).prompt()).isEqualTo("hi");
        assertThat(ExecutorInput.from(json("{\"q\":\"hi\"}")).prompt()).isEqualTo("{\"q\":\"hi\"}");
        assertThat(ExecutorInput.from(json("{\"prompt\":\"hi\"}")).structured()).isEmpty();
        refused("null", "INVALID_INPUT", "no input");
        refused("\"   \"", "INVALID_INPUT", "blank");
        refused("{\"prompt\":\"  \"}", "INVALID_INPUT", "prompt");
        refused("{\"prompt\":\"x\",\"responseFormat\":{\"name\":\"bad name\",\"schema\":{}}}", "INVALID_INPUT", "name");
        refused("{\"prompt\":\"x\",\"responseFormat\":{\"name\":\"n\",\"schema\":\"text\"}}", "INVALID_INPUT", "schema");
    }

    @Test
    void aStructuredOutputIsValidatedAgainstItsSchemaWithoutEchoingTheOutput() {
        var structured = ExecutorInput.from(json("{\"prompt\":\"x\",\"responseFormat\":{\"name\":\"answer\","
                + "\"schema\":{\"type\":\"object\",\"required\":[\"answer\"],\"properties\":{\"answer\":"
                + "{\"type\":\"integer\"}}}}}")).structured().orElseThrow();

        assertThat(structured.responseFormat().kind()).isEqualTo(ResponseFormat.Kind.JSON_SCHEMA);
        assertThat(structured.responseFormat().strict()).isTrue();
        assertThat(structured.validate("{\"answer\": 7}").path("answer").intValue()).isEqualTo(7);
        for (String invalid : List.of("secret text", "{\"answer\": \"secret\"}", "{}", "")) {
            assertThatThrownBy(() -> structured.validate(invalid)).isInstanceOfSatisfying(InvalidInputException.class,
                    failure -> {
                        assertThat(failure.code()).isEqualTo("STRUCTURED_OUTPUT_INVALID");
                        assertThat(failure.getMessage()).doesNotContain("secret");
                    });
        }
    }

    @Test
    void aSchemaThatCouldLoadAResourceIsRefused() {
        String prefix = "{\"prompt\":\"x\",\"responseFormat\":{\"name\":\"n\",\"schema\":";
        refused(prefix + "{\"$ref\":\"https://example.com/s.json\"}}}", "INVALID_INPUT", "reference");
        refused(prefix + "{\"properties\":{\"a\":{\"$ref\":\"file:///etc/passwd\"}}}}}", "INVALID_INPUT", "reference");
        refused(prefix + "{\"$id\":\"https://example.com/s\"}}}", "INVALID_INPUT", "$id");
        refused(prefix + "{\"$schema\":\"http://json-schema.org/draft-07/schema#\"}}}", "INVALID_INPUT", "2020-12");
        refused(prefix + "{\"$dynamicRef\":\"https://example.com/x\"}}}", "INVALID_INPUT", "reference");
        // Regular expressions could backtrack without bound on model output; they are refused as keywords only.
        refused(prefix + "{\"type\":\"string\",\"pattern\":\"^(a+)+$\"}}}", "INVALID_INPUT", "pattern");
        refused(prefix + "{\"patternProperties\":{\"^x\":{}}}}}", "INVALID_INPUT", "patternProperties");
        refused(prefix + "{\"properties\":{\"re\":{\"type\":\"string\",\"format\":\"regex\"}}}}}", "INVALID_INPUT",
                "regex");
        refused(prefix + "{\"items\":{\"pattern\":\"x\"}}}}", "INVALID_INPUT", "pattern");
        assertThat(ExecutorInput.from(json(prefix + "{\"type\":\"object\",\"properties\":{\"pattern\":"
                + "{\"type\":\"string\"}}}}}")).structured().orElseThrow().validate("{\"pattern\":\"x\"}")
                .path("pattern").stringValue()).isEqualTo("x");
        // #34 review 3, item 2: a local $ref cannot reach an unchecked location; every value is walked.
        refused(prefix + "{\"$ref\":\"#/x\",\"x\":{\"type\":\"string\",\"pattern\":\"^(a+)+$\"}}}}", "INVALID_INPUT",
                "pattern");
        refused(prefix + "{\"$ref\":\"#/x/y\",\"x\":{\"y\":{\"format\":\"regex\"}}}}}", "INVALID_INPUT", "regex");
        refused(prefix + "{\"unknownKeyword\":{\"$ref\":\"https://example.com/s\"}}}}", "INVALID_INPUT", "reference");
        refused(prefix + "{\"$defs\":{\"a\":{\"$id\":\"https://example.com/a\"}}}}}", "INVALID_INPUT", "$id");
        // Local references are self-contained and allowed.
        assertThat(ExecutorInput.from(json(prefix + "{\"$defs\":{\"n\":{\"type\":\"integer\"}},\"$ref\":\"#/$defs/n\"}}}"))
                .structured().orElseThrow().validate("3").intValue()).isEqualTo(3);
    }
}
