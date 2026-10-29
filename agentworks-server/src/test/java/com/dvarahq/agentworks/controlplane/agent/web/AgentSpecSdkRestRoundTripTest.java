package com.dvarahq.agentworks.controlplane.agent.web;

import com.dvarahq.agentworks.DvaraAgentWorksApplication;
import com.dvarahq.agentworks.spec.AgentSpecCodec;
import com.dvarahq.agentworks.spec.CanonicalAgentSpec;
import com.dvarahq.agentworks.spec.SpecFormat;
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
import com.dvarahq.agentworks.spec.model.ExecutorCapability;
import com.dvarahq.agentworks.spec.model.ExecutorSpec;
import com.dvarahq.agentworks.spec.model.InstructionsSpec;
import com.dvarahq.agentworks.spec.model.LimitsSpec;
import com.dvarahq.agentworks.spec.model.MemorySpec;
import com.dvarahq.agentworks.spec.model.MemoryTier;
import com.dvarahq.agentworks.spec.model.ModelBinding;
import com.dvarahq.agentworks.spec.model.ModelRoles;
import com.dvarahq.agentworks.spec.model.SandboxProvisioning;
import com.dvarahq.agentworks.spec.model.SandboxSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Java SDK values survive REST publication and retrieval without semantic loss (ADR-021, master section 13). */
@SpringBootTest(classes = DvaraAgentWorksApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestWorkspaces.class)
class AgentSpecSdkRestRoundTripTest {
    private final AgentSpecCodec codec = AgentSpecCodec.create();

    @LocalServerPort
    int port;
    private AgentApiClient api;

    @BeforeEach
    void setUp() {
        api = new AgentApiClient(port);
    }

    /**
     * Exercises every v1alpha1 field, including optional roles, tiers, and capabilities absent from the golden
     * vector. The threshold uses the full 15 significant digits, and the test client parses decimals as doubles like an
     * ordinary JSON or JavaScript client.
     */
    static AgentSpec everyField(String name) {
        return AgentSpec.v1alpha1(new AgentMetadata(name, "platform", "2.3.4-rc.1+build.7", "Ωmega agent — ünïcode"),
                new AgentSpecBody(
                        new ExecutorSpec("python-container", List.of(ExecutorCapability.EVENT_STREAMING,
                                ExecutorCapability.CHECKPOINT_RESTORE, ExecutorCapability.USAGE_REPORTING)),
                        new ModelRoles(new ModelBinding("openai-compatible", "org/model:v1@2026"),
                                new ModelBinding("direct", "small"), new ModelBinding("direct", "summary"),
                                new ModelBinding("direct", "memory"), new ModelBinding("direct", "router"),
                                new ModelBinding("direct", "guard"), new ModelBinding("direct", "judge")),
                        new InstructionsSpec("Line one\n\ttabbed \"quoted\" \\ backslash\nemoji 🚀\n"),
                        new ExecutionSpec(ExecutionIntent.OBSERVE, AutonomyProfile.READ_ONLY),
                        new ContextSpec(1, ContextLoadingPolicy.NEVER_INLINE, false,
                                new CompactionSpec(false, new BigDecimal("0.123456789012345"), CompactionStrategy.ARTIFACT_AWARE)),
                        List.of("pack.one", "pack-two"),
                        List.of(),
                        new SandboxSpec("kubernetes", SandboxProvisioning.DEMAND,
                                "registry.example/agent@sha256:" + "a".repeat(64), new BigDecimal("0.250"), "512Mi"),
                        new MemorySpec(new MemoryTier(false), new MemoryTier(true), new MemoryTier(true),
                                new MemoryTier(false)),
                        new LimitsSpec(1_000_000, 0, "250ms", new BigDecimal("0.0001"))));
    }

    @ParameterizedTest
    @EnumSource(SpecFormat.class)
    void sdkSpecificationRoundTripsThroughRestWithoutSemanticLoss(SpecFormat format) {
        String name = AgentDefinitionApiTest.uniqueName();
        CanonicalAgentSpec authored = codec.canonicalize(everyField(name));
        String definition = api.post("/api/v1/agents", "application/json", "{\"name\":\"" + name + "\"}", null)
                .body().path("id").asString();
        String body = format == SpecFormat.YAML ? codec.toYaml(authored.spec()) : authored.canonicalJson();

        var published = api.post("/api/v1/agents/" + definition + "/versions",
                format == SpecFormat.YAML ? "application/yaml" : "application/json", body, null);
        var fetched = api.get("/api/v1/agents/" + definition + "/versions/2.3.4-rc.1+build.7", null);
        CanonicalAgentSpec returned = codec.read(AgentApiClient.JSON.writeValueAsBytes(fetched.body().get("spec")),
                SpecFormat.JSON);

        assertThat(published.status()).isEqualTo(201);
        assertThat(fetched.status()).isEqualTo(200);
        assertThat(returned.spec()).isEqualTo(authored.spec());
        assertThat(returned.digest()).isEqualTo(authored.digest())
                .isEqualTo(published.body().path("digest").asString())
                .isEqualTo(fetched.body().path("digest").asString());
        assertThat(returned.canonicalJson()).isEqualTo(authored.canonicalJson());
    }
}
