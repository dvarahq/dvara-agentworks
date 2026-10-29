package com.dvarahq.agentworks.spec;

import com.dvarahq.agentworks.spec.model.AgentSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Additive schema evolution is allowed within an apiVersion; unknown or breaking versions are rejected. */
class AgentSpecCompatibilityTest {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private final AgentSpecCodec codec = AgentSpecCodec.create();

    @Test
    void installationSupportsExactlyTheImplementedApiVersion() {
        assertThat(AgentSpecCodec.supportedApiVersions()).containsExactly(AgentSpec.API_VERSION_V1ALPHA1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"dvarahq.com/v1alpha2", "dvarahq.com/v1beta1", "dvarahq.com/v1", "dvarahq.com/v2",
            "dvaraos.io/v1alpha1", "example.com/v1alpha1", "dvarahq.com/V1ALPHA1",
            "dvarahq.com/v1alpha1 ", "v1alpha1", ""})
    void unknownOrBreakingApiVersionIsRejectedWithoutPartialInterpretation(String apiVersion) throws IOException {
        ObjectNode document = AgentSpecDiagnosticsTest.golden();
        document.put("apiVersion", apiVersion);
        document.put("unexpected", true);

        assertThatThrownBy(() -> codec.read(MAPPER.writeValueAsBytes(document), SpecFormat.JSON))
                .isInstanceOfSatisfying(AgentSpecValidationException.class, exception ->
                        assertThat(exception.diagnostics()).singleElement().satisfies(diagnostic -> {
                            assertThat(diagnostic.code()).isEqualTo(DiagnosticCode.UNSUPPORTED_API_VERSION);
                            assertThat(diagnostic.path()).isEqualTo("/apiVersion");
                        }));
    }

    @Test
    void bundledSchemaIsAnAdditiveEvolutionOfTheFrozenV1alpha1Baseline() throws IOException {
        assertThat(SchemaEvolutionChecker.breakingChanges(baseline(), current())).isEmpty();
    }

    @Test
    void definitionNameRuleMatchesTheSchemaNameRule() throws IOException {
        assertThat(current().at("/$defs/name/pattern").asString()).isEqualTo(AgentSpecCodec.AGENT_NAME_PATTERN);
        assertThat(current().at("/$defs/name/maxLength").asInt()).isEqualTo(63);
        assertThat(AgentSpecCodec.isValidAgentName("refund-agent")).isTrue();
        assertThat(AgentSpecCodec.isValidAgentName("Refund_Agent")).isFalse();
        assertThat(AgentSpecCodec.isValidAgentName("a".repeat(64))).isFalse();
        assertThat(AgentSpecCodec.isValidAgentName(null)).isFalse();
    }

    @Test
    void evolutionCheckerAcceptsAdditiveChanges() throws IOException {
        JsonNode evolved = evolve(schema -> {
            ObjectNode execution = (ObjectNode) schema.at("/$defs/execution/properties");
            execution.putObject("maxParallelTools").put("type", "integer").put("minimum", 1);
            ((ArrayNode) schema.at("/$defs/sandbox/properties/provisioning/enum")).add("eager");
            ((ObjectNode) schema.at("/$defs/limits/properties/maxSteps")).put("maximum", 2_000_000);
        });

        assertThat(SchemaEvolutionChecker.breakingChanges(baseline(), evolved)).isEmpty();
    }

    @Test
    void evolutionCheckerRejectsEveryBreakingChangeCategory() throws IOException {
        assertBreaking(schema -> ((ArrayNode) schema.at("/$defs/spec/required")).add("models"), "newly required models");
        assertBreaking(schema -> ((ObjectNode) schema.at("/$defs/spec/properties")).remove("skills"), "removed");
        assertBreaking(schema -> ((ArrayNode) schema.at("/$defs/execution/properties/autonomyProfile/enum")).remove(0),
                "enum dropped");
        assertBreaking(schema -> ((ObjectNode) schema.at("/$defs/context/properties/maxTokens")).put("type", "string"),
                "changed type");
        assertBreaking(schema -> ((ObjectNode) schema.at("/$defs/limits/properties/maxSteps")).put("maximum", 10),
                "tightened maximum");
        assertBreaking(schema -> ((ObjectNode) schema.at("/$defs/name")).put("pattern", "^[a-z]+$"), "changed pattern");
        assertBreaking(schema -> ((ObjectNode) schema.at("/$defs/spec")).put("additionalProperties", true),
                "changed additionalProperties");
        assertBreaking(schema -> ((ObjectNode) schema.at("/$defs/instructions/properties/system")).put("minLength", 1),
                "added minLength");
        assertBreaking(schema -> ((ObjectNode) schema.at("/properties/apiVersion")).put("const", "dvarahq.com/v1alpha2"),
                "changed const");
        assertBreaking(schema -> ((ObjectNode) schema.at("/$defs/limits/properties/maxCostUsd")).put("multipleOf", 0.5),
                "unrecognized keyword multipleOf");
        assertBreaking(schema -> ((ObjectNode) schema.at("/$defs/metadata/properties/description")).put("format", "email"),
                "unrecognized keyword format");
        assertBreaking(schema -> ((ObjectNode) schema.at("/$defs/spec")).putArray("allOf").addObject()
                .putArray("required").add("limits"), "unrecognized keyword allOf");
    }

    @Test
    void absentOptionalFieldsStayAbsentSoAdditiveFieldsCannotChangeExistingDigests() throws IOException {
        CanonicalAgentSpec minimal = codec.read(AgentSpecGoldenVectorTest.resource("minimal-remote.yaml"),
                SpecFormat.YAML);

        JsonNode canonical = MAPPER.readTree(minimal.canonicalJson());
        assertThat(canonical.get("spec").propertyNames()).containsExactly("executor");
        assertThat(canonical.get("metadata").propertyNames()).containsExactly("name", "version");
        assertThat(minimal.digest()).isEqualTo(new String(AgentSpecGoldenVectorTest.resource("minimal-remote.digest"),
                java.nio.charset.StandardCharsets.UTF_8).strip());
    }

    @Test
    void fieldsFromANewerRevisionAreRejectedRatherThanSilentlyDropped() throws IOException {
        ObjectNode document = AgentSpecDiagnosticsTest.golden();
        ((ObjectNode) document.at("/spec/execution")).put("maxParallelTools", 4);

        assertThatThrownBy(() -> codec.read(MAPPER.writeValueAsBytes(document), SpecFormat.JSON))
                .isInstanceOfSatisfying(AgentSpecValidationException.class, exception ->
                        assertThat(exception.diagnostics()).singleElement().satisfies(diagnostic -> {
                            assertThat(diagnostic.code()).isEqualTo(DiagnosticCode.UNKNOWN_FIELD);
                            assertThat(diagnostic.path()).isEqualTo("/spec/execution/maxParallelTools");
                        }));
    }

    private void assertBreaking(Consumer<ObjectNode> change, String expected) throws IOException {
        assertThat(SchemaEvolutionChecker.breakingChanges(baseline(), evolve(change)))
                .anySatisfy(violation -> assertThat(violation).contains(expected));
    }

    private JsonNode evolve(Consumer<ObjectNode> change) throws IOException {
        ObjectNode schema = (ObjectNode) baseline().deepCopy();
        change.accept(schema);
        return schema;
    }

    private static JsonNode baseline() throws IOException {
        return MAPPER.readTree(AgentSpecGoldenVectorTest.resource("../compat/v1alpha1.baseline.schema.json"));
    }

    private static JsonNode current() throws IOException {
        try (InputStream input = AgentSpecCodec.schemaResource(AgentSpec.API_VERSION_V1ALPHA1)) {
            return MAPPER.readTree(Objects.requireNonNull(input));
        }
    }
}
