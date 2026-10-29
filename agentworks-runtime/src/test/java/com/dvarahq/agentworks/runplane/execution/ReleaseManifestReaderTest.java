package com.dvarahq.agentworks.runplane.execution;

import com.dvarahq.agentworks.domain.lifecycle.AgentReleaseId;
import com.dvarahq.agentworks.domain.security.AutonomyProfile;
import com.dvarahq.agentworks.domain.security.ExecutionIntent;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import com.dvarahq.agentworks.gateway.CapabilityPolicy;
import com.dvarahq.agentworks.gateway.ConfiguredGateway;
import com.dvarahq.agentworks.gateway.GatewayErrorCode;
import com.dvarahq.agentworks.gateway.GatewayException;
import com.dvarahq.agentworks.gateway.GatewayRegistry;
import com.dvarahq.agentworks.gateway.ModelRole;
import com.dvarahq.agentworks.gateway.ModelRoleBinding;
import com.dvarahq.agentworks.gateway.PinnedModelRoles;
import com.dvarahq.agentworks.gateway.fake.DeterministicFakeGatewayAdapter;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static com.dvarahq.agentworks.runplane.execution.DurableRunEngineTest.assertCode;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The runtime manifest reader delegates model roles to the gateway's strict reader, so for every model-section
 * variant it accepts exactly what {@link PinnedModelRoles} accepts. Version 2 through 6 model entries are produced by
 * #33's own resolution path ({@code GatewayRegistry.resolve} and {@code toManifestEntry}), not copied by hand; version
 * 3 adds strict exact capability pins.
 */
class ReleaseManifestReaderTest {
    private static final JsonMapper JSON = JsonMapper.builder().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();
    private final ReleaseManifestReader reader = new ReleaseManifestReader();
    private final WorkspaceId workspace = new WorkspaceId(UUID.randomUUID());
    private final UUID release = UUID.randomUUID();

    private ReleaseManifestReader.RunManifest read(String manifest) {
        return reader.read(workspace, release, manifest);
    }

    @Test
    void readsAVersionOneManifestWhoseRolesTheGatewayRefusesToInvoke() {
        UUID version = UUID.randomUUID();
        var manifest = read(EngineHarness.manifest(version));

        assertThat(manifest.manifestVersion()).isEqualTo(1);
        assertThat(manifest.versionId()).isEqualTo(version);
        assertThat(manifest.executorType()).isEqualTo("spring-ai");
        assertThat(manifest.capabilityPacks()).containsExactly("software-development");
        assertThat(manifest.sandboxProvider()).isNull();
        assertThat(manifest.models().identities()).isEmpty();
        assertThatCode(() -> manifest.models().role(ModelRole.REASONING), GatewayErrorCode.MODEL_ROLE_UNRESOLVED);
    }

    @Test
    void readsTheVersionTwoManifestThatReleaseResolutionWrites() {
        UUID version = UUID.randomUUID();
        var manifest = read(version2(version, "coding-model-2026-09-01"));

        assertThat(manifest.manifestVersion()).isEqualTo(2);
        var pinned = manifest.models().role(ModelRole.REASONING);
        assertThat(pinned.identity().providerModel()).isEqualTo("coding-model-2026-09-01");
        assertThat(pinned.release()).isEqualTo(new AgentReleaseId(release));
        assertThat(pinned.workspace()).isEqualTo(workspace);
    }

    @Test
    void readsVersionThreeExactCapabilityPinsAndRefusesMalformedEvidence() {
        UUID version = UUID.randomUUID();
        String manifestJson = version3(version);
        var manifest = read(manifestJson);

        assertThat(manifest.manifestVersion()).isEqualTo(3);
        assertThat(manifest.capabilityPacks()).containsExactly("software-development:1.0.0");
        assertThat(manifest.pinnedCapabilities()).singleElement().satisfies(pack -> {
            assertThat(pack.id()).isEqualTo("software-development");
            assertThat(pack.version()).isEqualTo("1.0.0");
            assertThat(pack.tools()).singleElement().satisfies(tool -> {
                assertThat(tool.id().value()).isEqualTo("core:code:1.2.3");
                assertThat(tool.schemaHash()).isEqualTo("sha256:" + "a".repeat(64));
            });
        });
        assertCode(() -> read(manifestJson.replace("sha256:" + "a".repeat(64), "sha256:no")),
                "INVALID_MANIFEST");
        assertCode(() -> read(manifestJson.replace("\"schemaHash\"", "\"unexpected\"")), "INVALID_MANIFEST");
    }

    @Test
    void readsVersionFourExecutionPolicyAndKeepsLegacyEvidenceExplicitlyAbsent() {
        String manifestJson = version4(UUID.randomUUID(), "ANALYZE", "SUPERVISED");

        var manifest = read(manifestJson);

        assertThat(manifest.manifestVersion()).isEqualTo(4);
        assertThat(manifest.executionPolicy()).get().satisfies(policy -> {
            assertThat(policy.initialIntent()).isEqualTo(ExecutionIntent.ANALYZE);
            assertThat(policy.autonomyProfile()).isEqualTo(AutonomyProfile.SUPERVISED);
        });
        assertThat(read(EngineHarness.manifest(UUID.randomUUID())).executionPolicy()).isEmpty();
        assertThat(read(version2(UUID.randomUUID(), "model")).executionPolicy()).isEmpty();
        assertThat(read(version3(UUID.randomUUID())).executionPolicy()).isEmpty();
    }

    @Test
    void versionFourExecutionPolicyIsAClosedKnownShape() {
        String manifest = version4(UUID.randomUUID(), "EXECUTE", "STRICT");

        assertCode(() -> read(manifest.replace("\"EXECUTE\"", "\"DELETE\"")), "INVALID_MANIFEST");
        assertCode(() -> read(manifest.replace("\"STRICT\"", "\"UNBOUNDED\"")), "INVALID_MANIFEST");
        assertCode(() -> read(manifest.replace("\"autonomyProfile\":\"STRICT\"",
                "\"autonomyProfile\":\"STRICT\",\"extra\":true")), "INVALID_MANIFEST");
        assertCode(() -> read(manifest.replace(",\"initialIntent\":\"EXECUTE\"", "")), "INVALID_MANIFEST");
        assertCode(() -> read(manifest.replaceFirst(",\"execution\":\\{[^}]*}", "")), "INVALID_MANIFEST");
    }

    @Test
    void readsVersionFiveEffectPinsAndRefusesMissingOrUnknownEvidence() {
        String manifestJson = version5(UUID.randomUUID());

        var manifest = read(manifestJson);

        assertThat(manifest.manifestVersion()).isEqualTo(5);
        assertThat(manifest.pinnedCapabilities()).singleElement().satisfies(pack ->
                assertThat(pack.tools()).singleElement().satisfies(tool -> assertThat(tool.effect()).get().satisfies(effect -> {
                    assertThat(effect.resource()).isEqualTo("source-code");
                    assertThat(effect.action()).isEqualTo("write");
                    assertThat(effect.risk().name()).isEqualTo("HIGH");
                })));
        assertThat(read(version4(UUID.randomUUID(), "EXECUTE", "STRICT")).pinnedCapabilities().getFirst()
                .tools().getFirst().effect()).isEmpty();
        assertCode(() -> read(manifestJson.replace(",\"risk\":\"HIGH\"", "")), "INVALID_MANIFEST");
        assertCode(() -> read(manifestJson.replace("\"HIGH\"", "\"UNKNOWN\"")), "INVALID_MANIFEST");
        assertCode(() -> read(manifestJson.replace("\"risk\":\"HIGH\"", "\"risk\":\"HIGH\",\"extra\":true")),
                "INVALID_MANIFEST");
    }

    @Test
    void readsVersionSixCompleteSandboxPinsAndRefusesMutableOrMalformedShapes() {
        String manifestJson = version6(UUID.randomUUID());

        var manifest = read(manifestJson);

        assertThat(manifest.manifestVersion()).isEqualTo(6);
        assertThat(manifest.sandboxProvider()).isEqualTo("docker");
        assertThat(manifest.sandboxConfiguration()).get().satisfies(sandbox -> {
            assertThat(sandbox.image()).contains("example/sandbox@sha256:" + "b".repeat(64));
            assertThat(sandbox.cpu()).isEqualByComparingTo("2");
            assertThat(sandbox.memory()).isEqualTo("1Gi");
            assertThat(sandbox.pidLimit()).isEqualTo(128);
            assertThat(sandbox.networkPolicy()).isEqualTo("NONE");
            assertThat(sandbox.maxRuntime()).isEqualTo("5m");
        });
        assertCode(() -> read(manifestJson.replace(",\"pidLimit\":128", "")), "INVALID_MANIFEST");
        assertCode(() -> read(manifestJson.replace("\"networkPolicy\":\"NONE\"",
                "\"networkPolicy\":\"NONE\",\"mounts\":[]")), "INVALID_MANIFEST");
    }

    @Test
    void readsVersionEightExternalExecutorProvenanceAndRejectsFalseDurability() {
        String manifestJson = version8(UUID.randomUUID(), "INSTRUMENTED", false);

        var manifest = read(manifestJson);

        assertThat(manifest.manifestVersion()).isEqualTo(8);
        assertThat(manifest.externalExecutor()).get().satisfies(executor -> {
            assertThat(executor.executorId()).isEqualTo("remote-python");
            assertThat(executor.artifactDigest()).isEqualTo("sha256:" + "c".repeat(64));
            assertThat(executor.dependencyLockDigest()).isEqualTo("sha256:" + "d".repeat(64));
            assertThat(executor.assurance().name()).isEqualTo("INSTRUMENTED");
            assertThat(executor.accessMode().name()).isEqualTo("DVARAOS_MEDIATED");
        });
        assertCode(() -> read(version8(UUID.randomUUID(), "DURABLE", false)), "INVALID_MANIFEST");
        assertCode(() -> read(manifestJson.replace("\"artifactDigest\"", "\"unknown\"")), "INVALID_MANIFEST");
    }

    @Test
    void readsVersionEightLocalExecutorWithoutExternalProvenance() {
        var manifest = read(version8Local(UUID.randomUUID()));

        assertThat(manifest.manifestVersion()).isEqualTo(8);
        assertThat(manifest.executorType()).isEqualTo("spring-ai");
        assertThat(manifest.externalExecutor()).isEmpty();
    }

    @Test
    void readsVersionNineImmutableSkillEvidenceAndRefusesUnknownOrDuplicatePins() {
        String manifestJson = version9(UUID.randomUUID());

        var manifest = read(manifestJson);

        assertThat(manifest.manifestVersion()).isEqualTo(9);
        assertThat(manifest.skills()).containsExactly("secure-review");
        assertThat(manifest.pinnedSkills()).singleElement().satisfies(skill -> {
            assertThat(skill.sourceKind().name()).isEqualTo("GIT");
            assertThat(skill.resolvedRef()).isEqualTo("a".repeat(40));
            assertThat(skill.contentDigest()).isEqualTo("sha256:" + "e".repeat(64));
            assertThat(skill.loadingPolicy().name()).isEqualTo("CONTEXT_TRIGGERED");
            assertThat(skill.contextTriggers()).containsExactly("security-review");
        });
        assertCode(() -> read(manifestJson.replace("\"contentDigest\"", "\"unknown\"")), "INVALID_MANIFEST");
        assertCode(() -> read(manifestJson.replace("\"skills\":[", "\"skills\":["
                + skillJson() + ",")), "INVALID_MANIFEST");
    }

    @Test
    void readsVersionTenPinnedContextPolicyAndRefusesUnknownOrMalformedPolicy() {
        String manifestJson = version10(UUID.randomUUID());

        var manifest = read(manifestJson);

        assertThat(manifest.manifestVersion()).isEqualTo(10);
        assertThat(manifest.contextPolicy()).get().satisfies(policy -> {
            assertThat(policy.maximumTokens()).isEqualTo(8192);
            assertThat(policy.loadingPolicy()).isEqualTo("ON_DEMAND");
            assertThat(policy.compactionStrategy()).isEqualTo("SLIDING_WINDOW");
            assertThat(policy.policyHash()).isEqualTo("sha256:" + "f".repeat(64));
        });
        assertCode(() -> read(manifestJson.replace("\"maximumTokens\":8192", "\"maximumTokens\":0")),
                "INVALID_MANIFEST");
        assertCode(() -> read(manifestJson.replace("\"policyHash\"", "\"unknown\"")), "INVALID_MANIFEST");
    }

    @Test
    void readsVersionElevenPinnedMemoryPolicyAndRefusesUnknownOrMalformedPolicy() {
        String manifestJson = version11(UUID.randomUUID());

        var manifest = read(manifestJson);

        assertThat(manifest.manifestVersion()).isEqualTo(11);
        assertThat(manifest.memoryPolicy()).get().satisfies(policy -> {
            assertThat(policy.conversationEnabled()).isTrue();
            assertThat(policy.conversationRetention()).isEqualTo(java.time.Duration.ofDays(30));
            assertThat(policy.workingRetention()).isEqualTo(java.time.Duration.ofHours(24));
            assertThat(policy.semanticEnabled()).isFalse();
            assertThat(policy.knowledgeTokens()).isEqualTo(4096);
        });
        assertCode(() -> read(manifestJson.replace("\"conversationItems\":64", "\"conversationItems\":0")),
                "INVALID_MANIFEST");
        assertCode(() -> read(manifestJson.replace("\"knowledgeTimeout\"", "\"unknown\"")), "INVALID_MANIFEST");
    }

    @Test
    void acceptsExactlyTheModelSectionsTheGatewayReaderAccepts() {
        String v2 = version2(UUID.randomUUID(), "coding-model-2026-09-01");
        String v1 = EngineHarness.manifest(UUID.randomUUID());
        List<String> variants = new ArrayList<>(List.of(v1, v2));
        variants.add(v2.replace("\"RESOLVED\"", "\"UNRESOLVED_ALIAS\""));
        variants.add(v2.replace("\"protocol\":", "\"endpoint\":\"https://x\",\"protocol\":"));
        variants.add(v2.replaceFirst("\"adapterVersion\":\"[^\"]*\",", ""));
        variants.add(v2.replace("\"REQUIRED\"", "\"ALWAYS\""));
        variants.add(v2.replace("\"manifestVersion\":2", "\"manifestVersion\":1"));
        variants.add(v1.replace("\"manifestVersion\":1", "\"manifestVersion\":2"));
        variants.add(v1.replace("UNRESOLVED_ALIAS", "RESOLVED"));
        variants.add(v1.replace("\"reasoning\"", "\"poetry\""));
        variants.add(v1.replace("\"alias\":\"coding-premium\",", "\"alias\":\"coding-premium\",\"model\":\"x\","));
        variants.add(v1.replace("\"gateway\":\"direct\"", "\"gateway\":\"\""));
        for (String variant : variants) {
            boolean gatewayAccepts;
            try {
                PinnedModelRoles.fromReleaseManifest(workspace, new AgentReleaseId(release), variant);
                gatewayAccepts = true;
            } catch (GatewayException refused) {
                gatewayAccepts = false;
            }
            boolean readerAccepts;
            try {
                read(variant);
                readerAccepts = true;
            } catch (RunEngineException refused) {
                assertThat(refused.code()).isIn("INVALID_MANIFEST", "UNSUPPORTED_MANIFEST_VERSION");
                readerAccepts = false;
            }
            assertThat(readerAccepts).as(variant).isEqualTo(gatewayAccepts);
        }
        assertThat(variants.subList(2, variants.size())).allSatisfy(variant ->
                assertCode(() -> read(variant), "INVALID_MANIFEST"));
    }

    @Test
    void refusesUnsupportedVersionsAndUnknownOrMissingNonModelFields() {
        String manifest = EngineHarness.manifest(UUID.randomUUID());

        assertCode(() -> read(manifest.replace("\"manifestVersion\":1", "\"manifestVersion\":14")),
                "UNSUPPORTED_MANIFEST_VERSION");
        assertCode(() -> read(manifest.replace("\"skills\":[]", "\"skills\":[],\"abom\":{}")), "INVALID_MANIFEST");
        assertCode(() -> read(manifest.replace(",\"skills\":[]", "")), "INVALID_MANIFEST");
        assertCode(() -> read(manifest.replace("\"type\":\"spring-ai\"", "\"type\":\"spring-ai\",\"extra\":1")),
                "INVALID_MANIFEST");
        assertCode(() -> read(manifest.replace("\"sandbox\":{}", "\"sandbox\":{\"image\":\"x\"}")), "INVALID_MANIFEST");
        assertCode(() -> read(manifest.replace("\"manifestVersion\":1", "\"manifestVersion\":\"1\"")),
                "INVALID_MANIFEST");
        assertCode(() -> read("not json"), "INVALID_MANIFEST");
    }

    /** A version 2 manifest with the model entry #33's release resolution writes for a {@code direct} gateway. */
    static String version2(UUID versionId, String model) {
        GatewayRegistry registry = GatewayRegistry.of(List.of(new ConfiguredGateway(DeterministicFakeGatewayAdapter
                .builder("direct").model("coding-premium", model).build(), CapabilityPolicy.DEFAULT, null)));
        Map<String, Object> models = new TreeMap<>();
        registry.resolve(List.of(new ModelRoleBinding(ModelRole.REASONING, "direct", "coding-premium")))
                .forEach((role, identity) -> models.put(role.manifestKey(), identity.toManifestEntry()));
        @SuppressWarnings("unchecked")
        Map<String, Object> manifest = JSON.readValue(EngineHarness.manifest(versionId), Map.class);
        manifest.put("manifestVersion", 2);
        manifest.put("models", models);
        return JSON.writeValueAsString(new TreeMap<>(manifest));
    }

    static String version3(UUID versionId) {
        @SuppressWarnings("unchecked")
        Map<String, Object> manifest = JSON.readValue(version2(versionId, "model"), Map.class);
        manifest.put("manifestVersion", 3);
        manifest.put("capabilityPacks", List.of(Map.of("id", "software-development", "version", "1.0.0",
                "tools", List.of(Map.of("namespace", "core", "name", "code", "version", "1.2.3",
                        "schemaHash", "sha256:" + "a".repeat(64))))));
        return JSON.writeValueAsString(new TreeMap<>(manifest));
    }

    static String version4(UUID versionId, String intent, String autonomy) {
        @SuppressWarnings("unchecked")
        Map<String, Object> manifest = JSON.readValue(version3(versionId), Map.class);
        manifest.put("manifestVersion", 4);
        manifest.put("execution", Map.of("initialIntent", intent, "autonomyProfile", autonomy));
        return JSON.writeValueAsString(new TreeMap<>(manifest));
    }

    static String version5(UUID versionId) {
        @SuppressWarnings("unchecked")
        Map<String, Object> manifest = JSON.readValue(version4(versionId, "EXECUTE", "STRICT"), Map.class);
        manifest.put("manifestVersion", 5);
        manifest.put("capabilityPacks", List.of(Map.of("id", "software-development", "version", "1.0.0",
                "tools", List.of(Map.of("namespace", "core", "name", "code", "version", "1.2.3",
                        "schemaHash", "sha256:" + "a".repeat(64), "effect",
                        Map.of("resource", "source-code", "action", "write", "risk", "HIGH"))))));
        return JSON.writeValueAsString(new TreeMap<>(manifest));
    }

    static String version6(UUID versionId) {
        @SuppressWarnings("unchecked")
        Map<String, Object> manifest = JSON.readValue(version5(versionId), Map.class);
        manifest.put("manifestVersion", 6);
        manifest.put("sandbox", Map.of("provider", "docker", "provisioning", "demand",
                "image", "example/sandbox@sha256:" + "b".repeat(64), "cpu", new java.math.BigDecimal("2.000"),
                "memory", "1Gi", "pidLimit", 128, "networkPolicy", "NONE", "maxRuntime", "5m"));
        return JSON.writeValueAsString(new TreeMap<>(manifest));
    }

    static String version8(UUID versionId, String assurance, boolean recoveryProven) {
        @SuppressWarnings("unchecked")
        Map<String, Object> manifest = JSON.readValue(version6(versionId), Map.class);
        manifest.put("manifestVersion", 8);
        manifest.put("limits", Map.of());
        @SuppressWarnings("unchecked") Map<String, Object> executor = new TreeMap<>((Map<String, Object>) manifest.get("executor"));
        executor.put("external", Map.ofEntries(
                Map.entry("executorId", "remote-python"), Map.entry("executorVersion", "1.2.3"),
                Map.entry("framework", "langgraph"), Map.entry("runtime", "python-3.13"),
                Map.entry("artifactDigest", "sha256:" + "c".repeat(64)),
                Map.entry("dependencyLockDigest", "sha256:" + "d".repeat(64)),
                Map.entry("protocolVersion", "dvara-agentworks-executor/v1"),
                Map.entry("schemaVersion", "2026-10-14"), Map.entry("assurance", assurance),
                Map.entry("accessMode", "DVARAOS_MEDIATED"),
                Map.entry("capabilities", List.of("EVENT_STREAMING", "CHECKPOINT_EXPORT", "CHECKPOINT_RESTORE")),
                Map.entry("checkpointRecoveryProven", recoveryProven)));
        manifest.put("executor", executor);
        return JSON.writeValueAsString(new TreeMap<>(manifest));
    }

    static String version8Local(UUID versionId) {
        @SuppressWarnings("unchecked")
        Map<String, Object> manifest = JSON.readValue(version6(versionId), Map.class);
        manifest.put("manifestVersion", 8);
        manifest.put("limits", Map.of());
        return JSON.writeValueAsString(new TreeMap<>(manifest));
    }

    static String version9(UUID versionId) {
        @SuppressWarnings("unchecked")
        Map<String, Object> manifest = JSON.readValue(version8Local(versionId), Map.class);
        manifest.put("manifestVersion", 9);
        @SuppressWarnings("unchecked") Map<String, Object> skill = JSON.readValue(skillJson(), Map.class);
        manifest.put("skills", List.of(skill));
        return JSON.writeValueAsString(new TreeMap<>(manifest));
    }

    static String version10(UUID versionId) {
        @SuppressWarnings("unchecked")
        Map<String, Object> manifest = JSON.readValue(version9(versionId), Map.class);
        manifest.put("manifestVersion", 10);
        manifest.put("context", Map.of("maximumTokens", 8192, "loadingPolicy", "ON_DEMAND",
                "cacheAware", true, "policyHash", "sha256:" + "f".repeat(64), "compaction",
                Map.of("enabled", true, "strategy", "SLIDING_WINDOW", "threshold", 0.8)));
        return JSON.writeValueAsString(new TreeMap<>(manifest));
    }

    static String version11(UUID versionId) {
        @SuppressWarnings("unchecked")
        Map<String, Object> manifest = JSON.readValue(version10(versionId), Map.class);
        manifest.put("manifestVersion", 11);
        manifest.put("memory", Map.ofEntries(Map.entry("conversationEnabled", true),
                Map.entry("conversationRetention", "P30D"), Map.entry("conversationItems", 64),
                Map.entry("workingEnabled", true), Map.entry("workingRetention", "PT24H"),
                Map.entry("workingItems", 32), Map.entry("semanticEnabled", false),
                Map.entry("knowledgeItems", 16), Map.entry("knowledgeTokens", 4096),
                Map.entry("knowledgeTimeout", "PT5S"), Map.entry("policyHash", "sha256:" + "a".repeat(64))));
        return JSON.writeValueAsString(new TreeMap<>(manifest));
    }

    private static String skillJson() {
        return "{\"id\":\"secure-review\",\"sourceKind\":\"GIT\","
                + "\"sourceUri\":\"git+https://skills.example/review.git\","
                + "\"authoredRef\":\"" + "a".repeat(40) + "\",\"resolvedRef\":\"" + "a".repeat(40) + "\","
                + "\"contentDigest\":\"sha256:" + "e".repeat(64) + "\",\"trustPolicy\":\"signed@1\","
                + "\"manifestVersion\":\"1\",\"loadingPolicy\":\"CONTEXT_TRIGGERED\","
                + "\"contextTriggers\":[\"security-review\"],\"license\":\"Apache-2.0\","
                + "\"provenance\":\"commit:test\"}";
    }

    private static void assertThatCode(Runnable call, GatewayErrorCode code) {
        org.assertj.core.api.Assertions.assertThatThrownBy(call::run).isInstanceOfSatisfying(GatewayException.class,
                failure -> assertThat(failure.code()).isEqualTo(code));
    }
}
