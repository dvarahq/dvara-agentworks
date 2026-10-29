package com.dvarahq.agentworks.spec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.stream.Stream;

import static com.dvarahq.agentworks.spec.DiagnosticCode.DOCUMENT_TOO_LARGE;
import static com.dvarahq.agentworks.spec.DiagnosticCode.INVALID_VALUE;
import static com.dvarahq.agentworks.spec.DiagnosticCode.MALFORMED_DOCUMENT;
import static com.dvarahq.agentworks.spec.DiagnosticCode.OUT_OF_RANGE;
import static com.dvarahq.agentworks.spec.DiagnosticCode.REQUIRED_FIELD_MISSING;
import static com.dvarahq.agentworks.spec.DiagnosticCode.TYPE_MISMATCH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/** Malformed, ambiguous, and resource-exhausting inputs fail closed with a typed document diagnostic. */
class AgentSpecMalformedInputTest {
    private static final String HEADER = "apiVersion: dvarahq.com/v1alpha1\nkind: Agent\nmetadata: {name: a, version: 1.0.0}\n";
    private final AgentSpecCodec codec = AgentSpecCodec.create();

    static Stream<Arguments> malformedYaml() {
        return Stream.of(
                Arguments.of("empty", "", MALFORMED_DOCUMENT),
                Arguments.of("implicit null document", "---\n", MALFORMED_DOCUMENT),
                Arguments.of("unterminated flow", "a: [", MALFORMED_DOCUMENT),
                Arguments.of("duplicate key", HEADER + "spec: {executor: {type: a}}\nspec: {executor: {type: b}}\n",
                        MALFORMED_DOCUMENT),
                Arguments.of("multiple documents", HEADER + "spec: {executor: {type: a}}\n---\nkind: Other\n",
                        MALFORMED_DOCUMENT),
                Arguments.of("anchored mapping", HEADER + "spec: &x {executor: {type: a}}\n", MALFORMED_DOCUMENT),
                Arguments.of("alias reinterpretation", HEADER
                        + "spec:\n  executor: &e {type: a}\n  sandbox: {provider: *e}\n", MALFORMED_DOCUMENT),
                Arguments.of("recursive alias", "a: &a [*a]\n", MALFORMED_DOCUMENT),
                Arguments.of("explicit custom tag", HEADER + "spec: {executor: {type: !custom a}}\n", MALFORMED_DOCUMENT),
                Arguments.of("binary tag", HEADER + "spec: {executor: {type: !!binary aGVsbG8=}}\n", MALFORMED_DOCUMENT),
                Arguments.of("complex key", HEADER + "? [a, b]\n: c\nspec: {executor: {type: a}}\n", MALFORMED_DOCUMENT),
                Arguments.of("not a number", HEADER + "spec: {executor: {type: a}, limits: {maxCostUsd: .nan}}\n",
                        MALFORMED_DOCUMENT),
                Arguments.of("infinity", HEADER + "spec: {executor: {type: a}, limits: {maxCostUsd: .inf}}\n",
                        MALFORMED_DOCUMENT),
                Arguments.of("sequence root", "[1, 2]", TYPE_MISMATCH),
                Arguments.of("scalar root", "just text", TYPE_MISMATCH),
                Arguments.of("hex integer is a string", HEADER + "spec: {executor: {type: a}, limits: {maxSteps: 0x10}}\n",
                        TYPE_MISMATCH),
                Arguments.of("YAML 1.1 boolean is a string", HEADER
                        + "spec: {executor: {type: a}, context: {cacheAware: yes}}\n", TYPE_MISMATCH),
                Arguments.of("tilde null", HEADER + "spec: {executor: {type: a}, instructions: {system: ~}}\n",
                        MALFORMED_DOCUMENT),
                Arguments.of("empty null", HEADER + "spec:\n  executor: {type: a}\n  instructions:\n    system:\n",
                        MALFORMED_DOCUMENT),
                Arguments.of("literal null", HEADER + "spec: {executor: {type: a}, context: {cacheAware: null}}\n",
                        MALFORMED_DOCUMENT),
                Arguments.of("quoted tilde is a string", HEADER
                        + "spec: {executor: {type: a}, context: {cacheAware: \"~\"}}\n", TYPE_MISMATCH),
                Arguments.of("missing apiVersion", "kind: Agent\n", REQUIRED_FIELD_MISSING),
                Arguments.of("non-string apiVersion", "apiVersion: 1\n", TYPE_MISMATCH));
    }

    static Stream<Arguments> malformedJson() {
        return Stream.of(
                Arguments.of("trailing document", "{\"apiVersion\":\"dvarahq.com/v1alpha1\"} {}", MALFORMED_DOCUMENT),
                Arguments.of("duplicate key", "{\"apiVersion\":\"a\",\"apiVersion\":\"b\"}", MALFORMED_DOCUMENT),
                Arguments.of("NaN literal", "{\"apiVersion\": NaN}", MALFORMED_DOCUMENT),
                Arguments.of("NUL byte", "\u0000", MALFORMED_DOCUMENT),
                Arguments.of("comment", "{/* c */\"apiVersion\":\"dvarahq.com/v1alpha1\"}", MALFORMED_DOCUMENT),
                Arguments.of("single quotes", "{'apiVersion':'dvarahq.com/v1alpha1'}", MALFORMED_DOCUMENT),
                Arguments.of("truncated", "{\"apiVersion\":\"dvarahq.com/v1al", MALFORMED_DOCUMENT),
                Arguments.of("null root", "null", TYPE_MISMATCH),
                Arguments.of("string root", "\"Agent\"", TYPE_MISMATCH),
                Arguments.of("huge number", minimalJson("\"limits\":{\"maxSteps\":1e400}"), OUT_OF_RANGE),
                Arguments.of("unpaired high surrogate", minimalJson("\"instructions\":{\"system\":\"\\uD800\"}"),
                        INVALID_VALUE),
                Arguments.of("unpaired low surrogate", minimalJson("\"instructions\":{\"system\":\"a\\uDC00b\"}"),
                        INVALID_VALUE),
                // The JSON parser itself rejects an unpaired surrogate in a field name.
                Arguments.of("surrogate in field name", minimalJson("\"\\uD800\":1"), MALFORMED_DOCUMENT),
                Arguments.of("more digits than a double holds",
                        minimalJson("\"context\":{\"compaction\":{\"threshold\":0.1234567890123456}}"), OUT_OF_RANGE),
                Arguments.of("excessive precision", minimalJson("\"limits\":{\"maxCostUsd\":1e-400}"), OUT_OF_RANGE));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedYaml")
    void malformedYamlIsRejected(String name, String document, DiagnosticCode code) {
        assertRejected(document.getBytes(StandardCharsets.UTF_8), SpecFormat.YAML, code);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedJson")
    void malformedJsonIsRejected(String name, String document, DiagnosticCode code) {
        assertRejected(document.getBytes(StandardCharsets.UTF_8), SpecFormat.JSON, code);
    }

    @Test
    void validSurrogatePairsAreAcceptedAndDigestedAsUtf8() {
        byte[] document = minimalJson("\"instructions\":{\"system\":\"\\uD83D\\uDE80\"}")
                .getBytes(StandardCharsets.UTF_8);
        CanonicalAgentSpec spec = codec.read(document, SpecFormat.JSON);
        assertThat(spec.spec().spec().instructions().system()).isEqualTo("\uD83D\uDE80");
        assertThat(codec.read(spec.canonicalBytes(), SpecFormat.JSON)).isEqualTo(spec);
    }

    @Test
    void invalidUtf8IsRejected() {
        byte[] invalid = {'{', '"', 'a', '"', ':', '"', (byte) 0xC3, (byte) 0x28, '"', '}'};
        assertRejected(invalid, SpecFormat.JSON, MALFORMED_DOCUMENT);
        assertRejected(invalid, SpecFormat.YAML, MALFORMED_DOCUMENT);
    }

    @Test
    void oversizedDocumentIsRejectedBeforeParsing() {
        byte[] oversized = new byte[AgentSpecCodec.MAX_DOCUMENT_BYTES + 1];
        java.util.Arrays.fill(oversized, (byte) ' ');
        assertRejected(oversized, SpecFormat.JSON, DOCUMENT_TOO_LARGE);
        assertRejected(oversized, SpecFormat.YAML, DOCUMENT_TOO_LARGE);
    }

    @Test
    void excessiveNestingIsRejected() {
        String deep = "[".repeat(AgentSpecCodec.MAX_NESTING_DEPTH + 8) + "]".repeat(AgentSpecCodec.MAX_NESTING_DEPTH + 8);
        assertRejected(deep.getBytes(StandardCharsets.UTF_8), SpecFormat.JSON, DOCUMENT_TOO_LARGE);
        assertRejected(deep.getBytes(StandardCharsets.UTF_8), SpecFormat.YAML, DOCUMENT_TOO_LARGE);
    }

    @Test
    void adversarialIdentifiersAreRejectedInBoundedTime() {
        String hostile = "a" + "-".repeat(200_000) + "!";
        byte[] document = minimalJson("\"skills\":[\"" + hostile + "\"]").getBytes(StandardCharsets.UTF_8);
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> assertThatThrownBy(() ->
                codec.read(document, SpecFormat.JSON)).isInstanceOf(AgentSpecValidationException.class));
    }

    private void assertRejected(byte[] content, SpecFormat format, DiagnosticCode code) {
        assertThatThrownBy(() -> codec.read(content, format))
                .isInstanceOfSatisfying(AgentSpecValidationException.class, exception ->
                        assertThat(exception.diagnostics()).extracting(SpecDiagnostic::code).contains(code));
    }

    private static String minimalJson(String extraSpecFields) {
        return "{\"apiVersion\":\"dvarahq.com/v1alpha1\",\"kind\":\"Agent\",\"metadata\":{\"name\":\"a\",\"version\":\"1.0.0\"},"
                + "\"spec\":{\"executor\":{\"type\":\"a\"}," + extraSpecFields + "}}";
    }
}
