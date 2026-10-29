package com.dvarahq.agentworks.agent.springai;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolInputSchemaTest {
    @Test
    void validInvalidAndMalformedArgumentsProduceOnlyNormalizedDecisions() {
        ToolInputSchema schema = ToolInputSchema.compile("{\"type\":\"object\",\"required\":[\"id\"],"
                + "\"properties\":{\"id\":{\"type\":\"integer\"}},\"additionalProperties\":false}");

        assertThat(schema.validate("{\"id\":7}")).satisfies(decision -> {
            assertThat(decision.allowed()).isTrue();
            assertThat(decision.reason()).isEqualTo("SCHEMA_MATCHED");
            assertThat(decision.violations()).isZero();
        });
        assertThat(schema.validate("{\"id\":\"secret-canary\"}")).satisfies(decision -> {
            assertThat(decision.allowed()).isFalse();
            assertThat(decision.reason()).isEqualTo("SCHEMA_VIOLATION");
            assertThat(decision.violations()).isOne();
            assertThat(decision.toString()).doesNotContain("secret-canary");
        });
        assertThat(schema.validate("{\"id\":")).satisfies(decision -> {
            assertThat(decision.allowed()).isFalse();
            assertThat(decision.reason()).isEqualTo("INVALID_JSON");
        });
    }

    @Test
    void unsafeOrUnusableSchemasFailBeforeTheToolIsAdvertised() {
        for (String schema : new String[] {
                "not-json",
                "[]",
                "{\"$ref\":\"https://attacker.invalid/schema\"}",
                "{\"pattern\":\"(a+)+\"}",
                "{\"$schema\":\"http://json-schema.org/draft-07/schema#\"}"
        }) {
            assertThatThrownBy(() -> ToolInputSchema.compile(schema))
                    .isInstanceOfSatisfying(ExecutorInput.InvalidInputException.class, invalid -> {
                        assertThat(invalid.code()).isEqualTo("TOOL_INPUT_SCHEMA_INVALID");
                        assertThat(invalid.getMessage()).doesNotContain(schema);
                    });
        }
    }
}
