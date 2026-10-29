package com.dvarahq.agentworks.spec;

import com.dvarahq.agentworks.spec.model.AgentMetadata;
import com.dvarahq.agentworks.spec.model.AgentSpec;
import com.dvarahq.agentworks.spec.model.AgentSpecBody;
import com.dvarahq.agentworks.spec.model.AutonomyProfile;
import com.dvarahq.agentworks.spec.model.CompactionSpec;
import com.dvarahq.agentworks.spec.model.CompactionStrategy;
import com.dvarahq.agentworks.spec.model.ContextLoadingPolicy;
import com.dvarahq.agentworks.spec.model.ContextSpec;
import com.dvarahq.agentworks.spec.model.ExecutionIntent;
import com.dvarahq.agentworks.spec.model.ExecutionSpec;
import com.dvarahq.agentworks.spec.model.ExecutorSpec;
import com.dvarahq.agentworks.spec.model.InstructionsSpec;
import com.dvarahq.agentworks.spec.model.LimitsSpec;
import com.dvarahq.agentworks.spec.model.MemorySpec;
import com.dvarahq.agentworks.spec.model.MemoryTier;
import com.dvarahq.agentworks.spec.model.ModelBinding;
import com.dvarahq.agentworks.spec.model.ModelRoles;
import com.dvarahq.agentworks.spec.model.SandboxProvisioning;
import com.dvarahq.agentworks.spec.model.SandboxSpec;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Java SDK values are the same canonical AgentSpec as YAML and JSON authoring. */
class AgentSpecSdkTest {
    private final AgentSpecCodec codec = AgentSpecCodec.create();

    static AgentSpec softwareEngineer() {
        return AgentSpec.v1alpha1(
                new AgentMetadata("software-engineer", "default", "1.0.0", null),
                new AgentSpecBody(
                        ExecutorSpec.of("spring-ai"),
                        new ModelRoles(new ModelBinding("direct", "coding-premium"),
                                new ModelBinding("direct", "economical-small"), null, null, null, null,
                                new ModelBinding("direct", "judge-model")),
                        new InstructionsSpec("You are a senior software engineer.\n"
                                + "Inspect repository state before proposing changes.\n"),
                        new ExecutionSpec(ExecutionIntent.ANALYZE, AutonomyProfile.SUPERVISED),
                        new ContextSpec(100_000, ContextLoadingPolicy.ON_DEMAND, true,
                                new CompactionSpec(true, new BigDecimal("0.80"), CompactionStrategy.HIERARCHICAL)),
                        List.of("software-development"),
                        List.of("spring-boot", "java"),
                        new SandboxSpec("docker", SandboxProvisioning.DEMAND, "eclipse-temurin@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", new BigDecimal("4"),
                                "8Gi"),
                        new MemorySpec(new MemoryTier(true), new MemoryTier(true), null, null),
                        new LimitsSpec(100, 60, "30m", new BigDecimal("10.00"))));
    }

    @Test
    void sdkBuiltSpecMatchesTheGoldenYamlAndJsonDigest() throws IOException {
        CanonicalAgentSpec fromSdk = codec.canonicalize(softwareEngineer());

        assertThat(fromSdk.digest()).isEqualTo(new String(
                AgentSpecGoldenVectorTest.resource("software-engineer.digest"), StandardCharsets.UTF_8).strip());
        assertThat(fromSdk.spec()).isEqualTo(
                codec.read(AgentSpecGoldenVectorTest.resource("software-engineer.yaml"), SpecFormat.YAML).spec());
    }

    @Test
    void numericallyEqualValuesAreOneSemanticValue() {
        assertThat(new LimitsSpec(1, 1, "1s", new BigDecimal("10.00")))
                .isEqualTo(new LimitsSpec(1, 1, "1s", new BigDecimal("1E+1")));
        assertThat(new SandboxSpec("docker", null, null, new BigDecimal("4.000"), null).cpu()).isEqualTo("4");
    }

    @Test
    void sdkValuesAreHeldToTheSameNumericBoundsAsAuthoredDocuments() {
        AgentSpec base = softwareEngineer();
        var body = base.spec();
        AgentSpec excessive = new AgentSpec(base.apiVersion(), base.kind(), base.metadata(), new AgentSpecBody(
                body.executor(), body.models(), body.instructions(), body.execution(),
                new ContextSpec(100_000, null, null, new CompactionSpec(true,
                        new BigDecimal("0.1234567890123456789012345"), CompactionStrategy.SUMMARY)),
                body.capabilityPacks(), body.skills(), body.sandbox(), body.memory(), body.limits()));

        assertThatThrownBy(() -> codec.canonicalize(excessive)).isInstanceOfSatisfying(
                AgentSpecValidationException.class, exception -> assertThat(exception.diagnostics())
                        .extracting(SpecDiagnostic::code).containsExactly(DiagnosticCode.OUT_OF_RANGE));
    }

    @Test
    void sdkValuesAreImmutableSnapshots() {
        List<String> skills = new ArrayList<>(List.of("java"));
        AgentSpecBody body = new AgentSpecBody(ExecutorSpec.of("spring-ai"), null, null, null, null, null, skills,
                null, null, null);
        skills.add("mutated-after-construction");

        assertThat(body.skills()).containsExactly("java");
        assertThatThrownBy(() -> body.skills().add("x")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void sdkRejectsLifecycleAndDeploymentConcernsByConstruction() {
        assertThat(AgentSpecBody.class.getRecordComponents()).extracting(component -> component.getName())
                .doesNotContain("environment", "release", "deployment", "promotion", "placement", "exposure",
                        "workspaceId", "organizationId");
    }
}
