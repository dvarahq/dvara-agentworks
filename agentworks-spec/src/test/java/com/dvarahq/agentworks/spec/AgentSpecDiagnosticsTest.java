package com.dvarahq.agentworks.spec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.stream.Stream;

import static com.dvarahq.agentworks.spec.DiagnosticCode.DUPLICATE_ENTRY;
import static com.dvarahq.agentworks.spec.DiagnosticCode.FORBIDDEN_FIELD;
import static com.dvarahq.agentworks.spec.DiagnosticCode.INVALID_VALUE;
import static com.dvarahq.agentworks.spec.DiagnosticCode.OUT_OF_RANGE;
import static com.dvarahq.agentworks.spec.DiagnosticCode.REQUIRED_FIELD_MISSING;
import static com.dvarahq.agentworks.spec.DiagnosticCode.TYPE_MISMATCH;
import static com.dvarahq.agentworks.spec.DiagnosticCode.UNKNOWN_FIELD;
import static com.dvarahq.agentworks.spec.DiagnosticCode.UNSUPPORTED_KIND;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/** Invalid shapes yield typed diagnostics addressed by JSON Pointer and AgentSpec section. */
class AgentSpecDiagnosticsTest {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final Object REMOVE = new Object();
    private final AgentSpecCodec codec = AgentSpecCodec.create();

    static Stream<Arguments> invalidShapes() {
        return Stream.of(
                // executor
                invalid("/spec/executor", REMOVE, REQUIRED_FIELD_MISSING, SpecSection.EXECUTOR, "/spec/executor"),
                invalid("/spec/executor", "spring-ai", TYPE_MISMATCH, SpecSection.EXECUTOR, "/spec/executor"),
                invalid("/spec/executor/type", REMOVE, REQUIRED_FIELD_MISSING, SpecSection.EXECUTOR, "/spec/executor/type"),
                invalid("/spec/executor/type", "Spring_AI", INVALID_VALUE, SpecSection.EXECUTOR, "/spec/executor/type"),
                invalid("/spec/executor/type", 42, TYPE_MISMATCH, SpecSection.EXECUTOR, "/spec/executor/type"),
                invalid("/spec/executor/requiredCapabilities", array("TELEPATHY"), INVALID_VALUE,
                        SpecSection.EXECUTOR, "/spec/executor/requiredCapabilities/0"),
                invalid("/spec/executor/requiredCapabilities", array("CANCELLATION", "CANCELLATION"), DUPLICATE_ENTRY,
                        SpecSection.EXECUTOR, "/spec/executor/requiredCapabilities"),
                invalid("/spec/executor/langgraphGraph", "module:graph", UNKNOWN_FIELD, SpecSection.EXECUTOR,
                        "/spec/executor/langgraphGraph"),
                // models
                invalid("/spec/models", MAPPER.createObjectNode(), OUT_OF_RANGE, SpecSection.MODELS, "/spec/models"),
                invalid("/spec/models/reasoning/model", REMOVE, REQUIRED_FIELD_MISSING, SpecSection.MODELS,
                        "/spec/models/reasoning/model"),
                invalid("/spec/models/reasoning/gateway", "Open AI", INVALID_VALUE, SpecSection.MODELS,
                        "/spec/models/reasoning/gateway"),
                invalid("/spec/models/reasoning/model", "", OUT_OF_RANGE, SpecSection.MODELS,
                        "/spec/models/reasoning/model"),
                invalid("/spec/models/planner", MAPPER.createObjectNode().put("gateway", "direct").put("model", "m"),
                        UNKNOWN_FIELD, SpecSection.MODELS, "/spec/models/planner"),
                invalid("/spec/models/reasoning/apiKey", "sk-live-000000", UNKNOWN_FIELD, SpecSection.MODELS,
                        "/spec/models/reasoning/apiKey"),
                // context
                invalid("/spec/context/maxTokens", 0, OUT_OF_RANGE, SpecSection.CONTEXT, "/spec/context/maxTokens"),
                invalid("/spec/context/maxTokens", "lots", TYPE_MISMATCH, SpecSection.CONTEXT, "/spec/context/maxTokens"),
                invalid("/spec/context/maxTokens", 1.5, TYPE_MISMATCH, SpecSection.CONTEXT, "/spec/context/maxTokens"),
                invalid("/spec/context/loadingPolicy", "LAZY", INVALID_VALUE, SpecSection.CONTEXT,
                        "/spec/context/loadingPolicy"),
                invalid("/spec/context/compaction/threshold", 1.5, OUT_OF_RANGE, SpecSection.CONTEXT,
                        "/spec/context/compaction/threshold"),
                invalid("/spec/context/compaction/threshold", 0, OUT_OF_RANGE, SpecSection.CONTEXT,
                        "/spec/context/compaction/threshold"),
                invalid("/spec/context/compaction/strategy", "none", INVALID_VALUE, SpecSection.CONTEXT,
                        "/spec/context/compaction/strategy"),
                // capabilities and skills
                invalid("/spec/capabilityPacks", "software-development", TYPE_MISMATCH, SpecSection.CAPABILITIES,
                        "/spec/capabilityPacks"),
                invalid("/spec/capabilityPacks", array("software-development", "software-development"),
                        DUPLICATE_ENTRY, SpecSection.CAPABILITIES, "/spec/capabilityPacks"),
                invalid("/spec/skills", array("java", "Java Skill"), INVALID_VALUE, SpecSection.CAPABILITIES,
                        "/spec/skills/1"),
                // sandbox
                invalid("/spec/sandbox/provider", REMOVE, REQUIRED_FIELD_MISSING, SpecSection.SANDBOX,
                        "/spec/sandbox/provider"),
                invalid("/spec/sandbox/provisioning", "always", INVALID_VALUE, SpecSection.SANDBOX,
                        "/spec/sandbox/provisioning"),
                invalid("/spec/sandbox/image", "eclipse-temurin:25", INVALID_VALUE, SpecSection.SANDBOX,
                        "/spec/sandbox/image"),
                invalid("/spec/sandbox/cpu", 0, OUT_OF_RANGE, SpecSection.SANDBOX, "/spec/sandbox/cpu"),
                invalid("/spec/sandbox/cpu", 0.0001, INVALID_VALUE, SpecSection.SANDBOX, "/spec/sandbox/cpu"),
                invalid("/spec/sandbox/memory", "8GB", INVALID_VALUE, SpecSection.SANDBOX, "/spec/sandbox/memory"),
                invalid("/spec/sandbox/memory", "512Ki", OUT_OF_RANGE, SpecSection.SANDBOX, "/spec/sandbox/memory"),
                invalid("/spec/sandbox/memory", "2Ti", OUT_OF_RANGE, SpecSection.SANDBOX, "/spec/sandbox/memory"),
                invalid("/spec/sandbox/hostPath", "/var/run", UNKNOWN_FIELD, SpecSection.SANDBOX,
                        "/spec/sandbox/hostPath"),
                // limits
                invalid("/spec/limits/maxSteps", 0, OUT_OF_RANGE, SpecSection.LIMITS, "/spec/limits/maxSteps"),
                invalid("/spec/limits/maxSteps", "100", TYPE_MISMATCH, SpecSection.LIMITS, "/spec/limits/maxSteps"),
                invalid("/spec/limits/maxToolCalls", -1, OUT_OF_RANGE, SpecSection.LIMITS, "/spec/limits/maxToolCalls"),
                invalid("/spec/limits/maxRuntime", "30 minutes", INVALID_VALUE, SpecSection.LIMITS,
                        "/spec/limits/maxRuntime"),
                invalid("/spec/limits/maxRuntime", "200h", OUT_OF_RANGE, SpecSection.LIMITS, "/spec/limits/maxRuntime"),
                invalid("/spec/limits/maxCostUsd", -1, OUT_OF_RANGE, SpecSection.LIMITS, "/spec/limits/maxCostUsd"),
                invalid("/spec/limits/maxCostUsd", 0.00001, INVALID_VALUE, SpecSection.LIMITS,
                        "/spec/limits/maxCostUsd"),
                // metadata and document
                invalid("/metadata/name", REMOVE, REQUIRED_FIELD_MISSING, SpecSection.METADATA, "/metadata/name"),
                invalid("/metadata/name", "Software_Engineer", INVALID_VALUE, SpecSection.METADATA, "/metadata/name"),
                invalid("/metadata/version", "1.0", INVALID_VALUE, SpecSection.METADATA, "/metadata/version"),
                invalid("/metadata/version", 1.0, TYPE_MISMATCH, SpecSection.METADATA, "/metadata/version"),
                invalid("/kind", "Deployment", UNSUPPORTED_KIND, SpecSection.DOCUMENT, "/kind"),
                invalid("/spec/memory/working/enabled", "yes", TYPE_MISMATCH, SpecSection.MEMORY,
                        "/spec/memory/working/enabled"),
                invalid("/spec/execution/autonomyProfile", "UNRESTRICTED", INVALID_VALUE, SpecSection.EXECUTION,
                        "/spec/execution/autonomyProfile"),
                invalid("/spec/instructions/system", 7, TYPE_MISMATCH, SpecSection.INSTRUCTIONS,
                        "/spec/instructions/system"));
    }

    static Stream<Arguments> separateArtifacts() {
        return Stream.of("/environment", "/spec/environment", "/spec/environments", "/spec/release",
                "/spec/deployment", "/spec/promotion", "/spec/promotionPolicy", "/spec/placement",
                "/spec/exposure", "/spec/exposures", "/spec/aliases", "/spec/sandbox/placement",
                "/metadata/workspaceId", "/metadata/organization", "/spec/tenant").map(Arguments::of);
    }

    @ParameterizedTest(name = "{0} = {1} -> {2}")
    @MethodSource("invalidShapes")
    void invalidShapeYieldsTypedFieldDiagnostic(String pointer, Object value, DiagnosticCode code, SpecSection section,
            String path) throws IOException {
        var exception = reject(mutate(pointer, value));

        assertThat(exception.diagnostics()).anySatisfy(diagnostic -> {
            assertThat(diagnostic.code()).isEqualTo(code);
            assertThat(diagnostic.section()).isEqualTo(section);
            assertThat(diagnostic.path()).isEqualTo(path);
            assertThat(diagnostic.message()).isNotBlank();
        });
        assertThat(exception.diagnostics()).allSatisfy(diagnostic ->
                assertThat(diagnostic.message()).doesNotContain("sk-live", "/var/run", "module:graph"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("separateArtifacts")
    void lifecycleDeploymentAndTenancyConfigurationIsRejected(String pointer) throws IOException {
        var exception = reject(mutate(pointer, MAPPER.createObjectNode().put("name", "production")));

        assertThat(exception.diagnostics()).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.code()).isEqualTo(FORBIDDEN_FIELD);
            assertThat(diagnostic.path()).isEqualTo(pointer);
        });
    }

    @Test
    void everyActionableFailureIsReportedTogetherInStableOrder() throws IOException {
        ObjectNode document = golden();
        at(document, "/spec/executor").put("type", "Bad Type");
        at(document, "/spec/limits").put("maxSteps", 0);
        at(document, "/spec/sandbox").remove("provider");
        at(document, "/spec/context").put("loadingPolicy", "LAZY");

        var first = reject(document);
        var second = reject(document);

        assertThat(first.diagnostics()).extracting(SpecDiagnostic::path).containsExactly(
                "/spec/context/loadingPolicy", "/spec/executor/type", "/spec/limits/maxSteps", "/spec/sandbox/provider");
        assertThat(first.diagnostics()).extracting(SpecDiagnostic::section).containsExactly(
                SpecSection.CONTEXT, SpecSection.EXECUTOR, SpecSection.LIMITS, SpecSection.SANDBOX);
        assertThat(second.diagnostics()).isEqualTo(first.diagnostics());
    }

    @Test
    void javaSdkValuesUseTheSameValidation() throws IOException {
        var golden = codec.read(AgentSpecGoldenVectorTest.resource("software-engineer.yaml"), SpecFormat.YAML).spec();
        var body = golden.spec();
        var invalid = new com.dvarahq.agentworks.spec.model.AgentSpec(golden.apiVersion(), golden.kind(),
                golden.metadata(), new com.dvarahq.agentworks.spec.model.AgentSpecBody(
                        new com.dvarahq.agentworks.spec.model.ExecutorSpec("Not Valid", null), body.models(),
                        body.instructions(), body.execution(), body.context(), body.capabilityPacks(), body.skills(),
                        body.sandbox(), body.memory(), body.limits()));

        assertThatThrownBy(() -> codec.canonicalize(invalid)).isInstanceOfSatisfying(AgentSpecValidationException.class,
                exception -> assertThat(exception.diagnostics()).extracting(SpecDiagnostic::path)
                        .containsExactly("/spec/executor/type"));
    }

    private AgentSpecValidationException reject(JsonNode document) {
        byte[] bytes = MAPPER.writeValueAsBytes(document);
        var exception = catchThrowableOfType(AgentSpecValidationException.class,
                () -> codec.read(bytes, SpecFormat.JSON));
        assertThat(exception).as("document must be rejected").isNotNull();
        return exception;
    }

    static ObjectNode golden() throws IOException {
        return (ObjectNode) MAPPER.readTree(AgentSpecGoldenVectorTest.resource("software-engineer.json"));
    }

    static ObjectNode mutate(String pointer, Object value) throws IOException {
        ObjectNode document = golden();
        int split = pointer.lastIndexOf('/');
        ObjectNode parent = at(document, pointer.substring(0, split));
        String field = pointer.substring(split + 1);
        if (value == REMOVE) parent.remove(field);
        else parent.set(field, MAPPER.valueToTree(value));
        return document;
    }

    private static ObjectNode at(ObjectNode document, String pointer) {
        return pointer.isEmpty() ? document : (ObjectNode) document.at(pointer);
    }

    private static ArrayNode array(String... values) {
        ArrayNode array = MAPPER.createArrayNode();
        for (String value : values) array.add(value);
        return array;
    }

    private static Arguments invalid(String pointer, Object value, DiagnosticCode code, SpecSection section, String path) {
        return Arguments.of(pointer, value, code, section, path);
    }
}
