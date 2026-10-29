package com.dvarahq.agentworks.controlplane.lifecycle;

import com.dvarahq.agentworks.controlplane.evidence.ReleaseEvidence.EvidenceKind;
import com.dvarahq.agentworks.controlplane.evidence.ReleaseEvidence.EvidenceReference;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import com.dvarahq.agentworks.spec.AgentSpecValidationException;
import com.dvarahq.agentworks.spec.SpecFormat;
import com.dvarahq.agentworks.storage.lifecycle.Digests;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Immutable releases with resolved-identity manifests and audited aliases on PostgreSQL. */
class ReleaseLifecyclePostgresTest {
    private LifecycleFixture fixture;
    private WorkspaceId workspace;

    @BeforeEach
    void setUp() {
        fixture = LifecycleFixture.postgres();
        workspace = fixture.workspace();
    }

    @Test
    void manifestRecordsExactVersionIdentityAndNeverClaimsUnresolvedModels() {
        UUID version = fixture.version(workspace, "agent", "1.0.0");
        var release = fixture.releases.createRelease(workspace, version, "agent-1.0.0");

        String manifest = fixture.releases.manifest(workspace, release.id());
        JsonNode tree = JsonMapper.builder().build().readTree(manifest);

        assertThat(Digests.prefixed(manifest)).isEqualTo(release.manifestDigest());
        assertThat(tree.at("/agent/versionId").asString()).isEqualTo(version.toString());
        assertThat(tree.at("/agent/specDigest").asString()).startsWith("sha256:");
        assertThat(tree.at("/manifestVersion").intValue()).isEqualTo(ReleaseService.MANIFEST_VERSION);
        assertThat(tree.at("/context/maximumTokens").intValue()).isEqualTo(8192);
        assertThat(tree.at("/context/loadingPolicy").asString()).isEqualTo("ON_DEMAND");
        assertThat(tree.at("/context/cacheAware").booleanValue()).isTrue();
        assertThat(tree.at("/context/policyHash").asString()).matches("sha256:[0-9a-f]{64}");
        assertThat(tree.at("/execution/initialIntent").asString()).isEqualTo("OBSERVE");
        assertThat(tree.at("/execution/autonomyProfile").asString()).isEqualTo("READ_ONLY");
        assertThat(tree.at("/models/reasoning/resolution").asString()).isEqualTo("RESOLVED");
        assertThat(tree.at("/models/reasoning/alias").asString()).isEqualTo("coding-premium");
        assertThat(tree.at("/models/reasoning/model").asString()).isEqualTo("coding-model-2026-09-01");
        assertThat(tree.at("/models/reasoning/gatewayType").asString()).isEqualTo("deterministic-fake");
        assertThat(tree.at("/models/reasoning/capabilityPolicy/TOOL_CALLS").asString()).isEqualTo("REQUIRED");
        assertThat(manifest).doesNotContain("credential").doesNotContain("http");
        assertThat(tree.at("/capabilityPacks/0/id").asString()).isEqualTo("software-development");
        assertThat(tree.at("/capabilityPacks/0/version").asString()).isEqualTo("1.0.0");
        assertThat(tree.at("/capabilityPacks/0/tools/0/namespace").asString()).isEqualTo("core");
        assertThat(tree.at("/capabilityPacks/0/tools/0/name").asString()).isEqualTo("software-development");
        assertThat(tree.at("/capabilityPacks/0/tools/0/version").asString()).isEqualTo("1.0.0");
        assertThat(tree.at("/capabilityPacks/0/tools/0/schemaHash").asString()).startsWith("sha256:");
        assertThat(tree.at("/capabilityPacks/0/tools/0/effect/resource").asString()).isEqualTo("source-code");
        assertThat(tree.at("/capabilityPacks/0/tools/0/effect/action").asString()).isEqualTo("write");
        assertThat(tree.at("/capabilityPacks/0/tools/0/effect/risk").asString()).isEqualTo("HIGH");
        assertThat(release.definitionId()).isNotNull();
    }

    @Test
    void manifestPinsTheAuthoredExecutionIntentAndAutonomyProfile() {
        var definition = fixture.agents.createDefinition(workspace, "governed-agent", null);
        String yaml = """
                apiVersion: dvarahq.com/v1alpha1
                kind: Agent
                metadata: {name: governed-agent, version: 1.0.0}
                spec:
                  executor: {type: spring-ai}
                  models:
                    reasoning: {gateway: direct, model: coding-premium}
                  capabilityPacks: [software-development]
                  execution:
                    initialIntent: EXECUTE
                    autonomyProfile: SUPERVISED
                """;
        UUID version = fixture.agents.publishVersion(workspace, definition.id(),
                yaml.getBytes(StandardCharsets.UTF_8), SpecFormat.YAML).version().id().value();

        var release = fixture.releases.createRelease(workspace, version, "governed-agent-1.0.0");
        JsonNode manifest = JsonMapper.builder().build().readTree(
                fixture.releases.manifest(workspace, release.id()));

        assertThat(manifest.at("/execution/initialIntent").asString()).isEqualTo("EXECUTE");
        assertThat(manifest.at("/execution/autonomyProfile").asString()).isEqualTo("SUPERVISED");
    }

    @Test
    void manifestPinsVerifiedSkillSourceAndProgressiveLoadingEvidence() {
        var definition = fixture.agents.createDefinition(workspace, "skilled-agent", null);
        String yaml = """
                apiVersion: dvarahq.com/v1alpha1
                kind: Agent
                metadata: {name: skilled-agent, version: 1.0.0}
                spec:
                  executor: {type: spring-ai}
                  models:
                    reasoning: {gateway: direct, model: coding-premium}
                  skills: [safe-research]
                """;
        UUID version = fixture.agents.publishVersion(workspace, definition.id(),
                yaml.getBytes(StandardCharsets.UTF_8), SpecFormat.YAML).version().id().value();
        byte[] markdown = "# Safe research".getBytes(StandardCharsets.UTF_8);
        String digest = com.dvarahq.agentworks.capability.SecureSkillResolver.canonicalDigest(
                Map.of("SKILL.md", markdown));
        var skill = new com.dvarahq.agentworks.capability.ResolvedSkill("safe-research",
                com.dvarahq.agentworks.capability.SkillSourceKind.GIT,
                URI.create("git+https://skills.example/research.git"), "a".repeat(40), "a".repeat(40), digest,
                "sha256:" + "b".repeat(64), "release-trust@1", "skill/v1",
                com.dvarahq.agentworks.capability.SkillLifecycle.ACTIVE,
                com.dvarahq.agentworks.capability.SkillLoadingPolicy.CONTEXT_TRIGGERED, Set.of("research"),
                "Apache-2.0", "git:skills.example/research@" + "a".repeat(40), Map.of("SKILL.md", markdown));
        var releases = fixture.releasesResolvingWith(fixture.gateways,
                (scope, id) -> id.equals("safe-research") ? List.of(skill) : List.of());

        var release = releases.createRelease(workspace, version, "skilled-agent-1.0.0");
        JsonNode pinned = JsonMapper.builder().build().readTree(releases.manifest(workspace, release.id()))
                .at("/skills/0");

        assertThat(pinned.path("id").asString()).isEqualTo("safe-research");
        assertThat(pinned.path("sourceKind").asString()).isEqualTo("GIT");
        assertThat(pinned.path("resolvedRef").asString()).isEqualTo("a".repeat(40));
        assertThat(pinned.path("contentDigest").asString()).isEqualTo(digest);
        assertThat(pinned.path("signatureDigest").asString()).isEqualTo("sha256:" + "b".repeat(64));
        assertThat(pinned.path("loadingPolicy").asString()).isEqualTo("CONTEXT_TRIGGERED");
        assertThat(pinned.at("/contextTriggers/0").asString()).isEqualTo("research");
    }

    @Test
    void releaseRefusesAnUnresolvedSkillWithoutCreatingPartialState() {
        var definition = fixture.agents.createDefinition(workspace, "missing-skill-agent", null);
        String yaml = """
                apiVersion: dvarahq.com/v1alpha1
                kind: Agent
                metadata: {name: missing-skill-agent, version: 1.0.0}
                spec:
                  executor: {type: spring-ai}
                  models:
                    reasoning: {gateway: direct, model: coding-premium}
                  skills: [missing]
                """;
        UUID version = fixture.agents.publishVersion(workspace, definition.id(),
                yaml.getBytes(StandardCharsets.UTF_8), SpecFormat.YAML).version().id().value();

        assertThatThrownBy(() -> fixture.releases.createRelease(workspace, version, "missing-skill-agent-1.0.0"))
                .isInstanceOfSatisfying(LifecycleConflictException.class,
                        failure -> assertThat(failure.code()).isEqualTo("SKILL_UNAVAILABLE"));
        assertThat(fixture.releases.listReleases(workspace)).isEmpty();
    }

    @Test
    void manifestPinsCompleteExternalExecutorProvenance() {
        var definition = fixture.agents.createDefinition(workspace, "remote-agent", null);
        String yaml = """
                apiVersion: dvarahq.com/v1alpha1
                kind: Agent
                metadata: {name: remote-agent, version: 1.0.0}
                spec:
                  executor:
                    type: remote-http
                    requiredCapabilities: [EVENT_STREAMING, TOOL_MEDIATION]
                    external:
                      executorId: remote-python
                      executorVersion: 1.2.3
                      framework: langgraph
                      runtime: python-3.13
                      artifactDigest: sha256:%s
                      dependencyLockDigest: sha256:%s
                      protocolVersion: dvara-agentworks-executor/v1
                      schemaVersion: 2026-10-14
                      assurance: INSTRUMENTED
                      accessMode: DVARAOS_MEDIATED
                      capabilities: [EVENT_STREAMING, TOOL_MEDIATION]
                      checkpointRecoveryProven: false
                  models:
                    reasoning: {gateway: direct, model: coding-premium}
                """.formatted("c".repeat(64), "d".repeat(64));
        UUID version = fixture.agents.publishVersion(workspace, definition.id(),
                yaml.getBytes(StandardCharsets.UTF_8), SpecFormat.YAML).version().id().value();

        var release = fixture.releases.createRelease(workspace, version, "remote-agent-1.0.0");
        JsonNode external = JsonMapper.builder().build().readTree(
                fixture.releases.manifest(workspace, release.id())).at("/executor/external");

        assertThat(external.path("executorId").asString()).isEqualTo("remote-python");
        assertThat(external.path("artifactDigest").asString()).isEqualTo("sha256:" + "c".repeat(64));
        assertThat(external.path("dependencyLockDigest").asString()).isEqualTo("sha256:" + "d".repeat(64));
        assertThat(external.path("accessMode").asString()).isEqualTo("DVARAOS_MEDIATED");
        assertThat(external.path("capabilities")).hasSize(2);
    }

    @Test
    void manifestPinsTheCompleteBoundedSandboxRequest() {
        var definition = fixture.agents.createDefinition(workspace, "sandboxed-agent", null);
        String yaml = """
                apiVersion: dvarahq.com/v1alpha1
                kind: Agent
                metadata: {name: sandboxed-agent, version: 1.0.0}
                spec:
                  executor: {type: spring-ai}
                  models:
                    reasoning: {gateway: direct, model: coding-premium}
                  sandbox:
                    provider: docker
                    provisioning: demand
                    image: example/sandbox@sha256:%s
                    cpu: 2.5
                    memory: 768Mi
                  limits:
                    maxRuntime: 90s
                """.formatted("b".repeat(64));
        UUID version = fixture.agents.publishVersion(workspace, definition.id(),
                yaml.getBytes(StandardCharsets.UTF_8), SpecFormat.YAML).version().id().value();

        var release = fixture.releases.createRelease(workspace, version, "sandboxed-agent-1.0.0");
        JsonNode sandbox = JsonMapper.builder().build().readTree(
                fixture.releases.manifest(workspace, release.id())).path("sandbox");

        assertThat(sandbox.path("provider").asString()).isEqualTo("docker");
        assertThat(sandbox.path("provisioning").asString()).isEqualTo("demand");
        assertThat(sandbox.path("image").asString()).isEqualTo("example/sandbox@sha256:" + "b".repeat(64));
        assertThat(sandbox.path("cpu").decimalValue()).isEqualByComparingTo("2.5");
        assertThat(sandbox.path("memory").asString()).isEqualTo("768Mi");
        assertThat(sandbox.path("pidLimit").intValue()).isEqualTo(128);
        assertThat(sandbox.path("networkPolicy").asString()).isEqualTo("NONE");
        assertThat(sandbox.path("maxRuntime").asString()).isEqualTo("90s");
    }

    @Test
    void releaseRefusesUnavailableAmbiguousAndInactiveCapabilityDependencies() {
        var registry = new com.dvarahq.agentworks.storage.capability.JdbcCapabilityRegistry(fixture.jdbc);
        var tool = new com.dvarahq.agentworks.capability.ToolId("core", "software-development", "1.0.0");

        UUID unavailableVersion = fixture.version(workspace, "unavailable-agent", "1.0.0");
        fixture.tenants.executeWithoutResult(workspace, () -> registry.transitionPack(workspace,
                "software-development", "1.0.0", com.dvarahq.agentworks.capability.CapabilityPack.State.DISABLED,
                java.time.Instant.parse("2026-01-02T00:00:00Z")));
        assertThatThrownBy(() -> fixture.releases.createRelease(workspace, unavailableVersion, "unavailable"))
                .isInstanceOfSatisfying(LifecycleConflictException.class,
                        failure -> assertThat(failure.code()).isEqualTo("CAPABILITY_PACK_UNAVAILABLE"));

        fixture.tenants.executeWithoutResult(workspace, () -> {
            registry.transitionPack(workspace, "software-development", "1.0.0",
                    com.dvarahq.agentworks.capability.CapabilityPack.State.ACTIVE,
                    java.time.Instant.parse("2026-01-03T00:00:00Z"));
            registry.insertPack(workspace, new com.dvarahq.agentworks.capability.CapabilityPack(
                    "software-development", "2.0.0", com.dvarahq.agentworks.capability.CapabilityPack.State.ACTIVE,
                    List.of(tool)), java.time.Instant.parse("2026-01-03T00:00:00Z"));
        });
        UUID ambiguousVersion = fixture.version(workspace, "ambiguous-agent", "1.0.0");
        assertThatThrownBy(() -> fixture.releases.createRelease(workspace, ambiguousVersion, "ambiguous"))
                .isInstanceOfSatisfying(LifecycleConflictException.class,
                        failure -> assertThat(failure.code()).isEqualTo("CAPABILITY_PACK_AMBIGUOUS"));

        fixture.tenants.executeWithoutResult(workspace, () -> {
            registry.transitionPack(workspace, "software-development", "2.0.0",
                    com.dvarahq.agentworks.capability.CapabilityPack.State.DISABLED,
                    java.time.Instant.parse("2026-01-04T00:00:00Z"));
            registry.transitionTool(workspace, tool, com.dvarahq.agentworks.capability.AgentTool.Availability.DISABLED,
                    java.time.Instant.parse("2026-01-04T00:00:00Z"));
        });
        UUID inactiveToolVersion = fixture.version(workspace, "inactive-tool-agent", "1.0.0");
        assertThatThrownBy(() -> fixture.releases.createRelease(workspace, inactiveToolVersion, "inactive-tool"))
                .isInstanceOfSatisfying(LifecycleConflictException.class,
                        failure -> assertThat(failure.code()).isEqualTo("CAPABILITY_TOOL_UNAVAILABLE"));
        assertThat(fixture.releases.listReleases(workspace)).isEmpty();
    }

    @Test
    void releasesPinResolvedModelsSoCatalogChangesOnlyAffectNewReleases() {
        UUID v1 = fixture.version(workspace, "agent", "1.0.0");
        var pinned = fixture.releases.createRelease(workspace, v1, "agent-1.0.0");
        String pinnedManifest = fixture.releases.manifest(workspace, pinned.id());
        ReleaseService repointed = fixture.releasesResolvingWith(LifecycleFixture.gateways("coding-model-2026-12-01"));

        UUID v2 = fixture.version(workspace, "agent", "2.0.0");
        var next = repointed.createRelease(workspace, v2, "agent-2.0.0");
        var replay = repointed.createRelease(workspace, v1, "agent-1.0.0");
        var replayWithoutGateways = fixture.releasesResolvingWith(com.dvarahq.agentworks.gateway.GatewayRegistry.empty())
                .createRelease(workspace, v1, "agent-1.0.0");

        assertThat(model(workspace, pinned.id(), fixture.releases.manifest(workspace, pinned.id())))
                .isEqualTo("coding-model-2026-09-01");
        assertThat(fixture.releases.manifest(workspace, pinned.id())).isEqualTo(pinnedManifest);
        assertThat(model(workspace, next.id(), repointed.manifest(workspace, next.id())))
                .isEqualTo("coding-model-2026-12-01");
        assertThat(replay).isEqualTo(pinned);
        assertThat(replayWithoutGateways).isEqualTo(pinned);
        assertThat(fixture.releases.manifest(workspace, pinned.id())).isEqualTo(pinnedManifest);
    }

    @Test
    void unresolvableModelRolesFailReleaseCreationAndCreateNothing() {
        UUID version = fixture.version(workspace, "agent", "1.0.0");
        ReleaseService unconfigured = fixture.releasesResolvingWith(
                com.dvarahq.agentworks.gateway.GatewayRegistry.empty());
        ReleaseService otherCatalog = fixture.releasesResolvingWith(com.dvarahq.agentworks.gateway.GatewayRegistry.of(
                List.of(new com.dvarahq.agentworks.gateway.ConfiguredGateway(
                        com.dvarahq.agentworks.gateway.fake.DeterministicFakeGatewayAdapter.builder("direct")
                                .model("economical-small", "small-1").build(),
                        com.dvarahq.agentworks.gateway.CapabilityPolicy.DEFAULT, null))));

        assertThatThrownBy(() -> unconfigured.createRelease(workspace, version, "r1"))
                .isInstanceOfSatisfying(LifecycleConflictException.class, conflict -> {
                    assertThat(conflict.code()).isEqualTo("GATEWAY_NOT_CONFIGURED");
                    assertThat(conflict.getMessage()).contains("reasoning").contains("direct");
                });
        assertThatThrownBy(() -> otherCatalog.createRelease(workspace, version, "r1"))
                .isInstanceOfSatisfying(LifecycleConflictException.class,
                        conflict -> assertThat(conflict.code()).isEqualTo("MODEL_ALIAS_UNKNOWN"));
        assertThat(fixture.releases.listReleases(workspace)).isEmpty();
    }

    // Review 2, finding 7: an adapter that breaks its contract yields a typed 409 conflict, not a 500.
    @Test
    void adapterContractViolationsDuringReleaseResolutionAreTypedConflicts() {
        UUID version = fixture.version(workspace, "agent", "1.0.0");
        var identity = com.dvarahq.agentworks.gateway.fake.DeterministicFakeGatewayAdapter.builder("direct")
                .model("coding-premium", "m").build();
        var broken = new com.dvarahq.agentworks.gateway.AiGatewayAdapter() {
            @Override
            public com.dvarahq.agentworks.gateway.GatewayDescriptor descriptor() {
                return identity.descriptor();
            }

            @Override
            public java.util.Set<com.dvarahq.agentworks.gateway.GatewayCapability> capabilities() {
                return null;
            }

            @Override
            public com.dvarahq.agentworks.gateway.ResolvedModel resolveModel(
                    com.dvarahq.agentworks.gateway.ModelResolutionRequest request) {
                return identity.resolveModel(request);
            }

            @Override
            public com.dvarahq.agentworks.gateway.AdapterResult invoke(
                    com.dvarahq.agentworks.gateway.AdapterInvocation invocation,
                    com.dvarahq.agentworks.gateway.ChunkSink sink) {
                throw new AssertionError("never invoked");
            }
        };
        ReleaseService releases = fixture.releasesResolvingWith(com.dvarahq.agentworks.gateway.GatewayRegistry.of(
                List.of(new com.dvarahq.agentworks.gateway.ConfiguredGateway(broken,
                        com.dvarahq.agentworks.gateway.CapabilityPolicy.DEFAULT, null))));

        assertThatThrownBy(() -> releases.createRelease(workspace, version, "r1"))
                .isInstanceOfSatisfying(LifecycleConflictException.class,
                        conflict -> assertThat(conflict.code()).isEqualTo("ADAPTER_FAILURE"));
        assertThat(fixture.releases.listReleases(workspace)).isEmpty();
    }

    private static String model(WorkspaceId workspace, UUID release, String manifest) {
        return com.dvarahq.agentworks.gateway.PinnedModelRoles.fromReleaseManifest(workspace,
                new com.dvarahq.agentworks.domain.lifecycle.AgentReleaseId(release), manifest)
                .role(com.dvarahq.agentworks.gateway.ModelRole.REASONING).identity().providerModel();
    }

    @Test
    void releaseNamesAreIdempotentForTheSameVersionAndConflictOtherwise() {
        UUID v1 = fixture.version(workspace, "agent", "1.0.0");
        UUID v2 = fixture.version(workspace, "agent", "2.0.0");
        var first = fixture.releases.createRelease(workspace, v1, "stable");

        assertThat(fixture.releases.createRelease(workspace, v1, "stable")).isEqualTo(first);
        assertThatThrownBy(() -> fixture.releases.createRelease(workspace, v2, "stable"))
                .isInstanceOfSatisfying(LifecycleConflictException.class,
                        conflict -> assertThat(conflict.code()).isEqualTo("RELEASE_NAME_EXISTS"));
        assertThatThrownBy(() -> fixture.releases.createRelease(workspace, v1, "bad name!"))
                .isInstanceOf(AgentSpecValidationException.class);
        assertThat(fixture.releases.listReleases(workspace)).hasSize(1);
    }

    @Test
    void releaseReplayRequiresTheSameEvidenceForManifestVersionTwelve() {
        UUID version = fixture.version(workspace, "evidenced-agent", "1.0.0");
        var original = List.of(new EvidenceReference(EvidenceKind.AGENT_TEST, "suite:release",
                Digests.prefixed("passing-suite")));
        var different = List.of(new EvidenceReference(EvidenceKind.AGENT_TEST, "suite:release",
                Digests.prefixed("different-suite")));

        var first = fixture.releases.createRelease(workspace, version, "evidenced", original);
        assertThat(fixture.releases.createRelease(workspace, version, "evidenced", original)).isEqualTo(first);
        assertThatThrownBy(() -> fixture.releases.createRelease(workspace, version, "evidenced", different))
                .isInstanceOfSatisfying(LifecycleConflictException.class,
                        conflict -> assertThat(conflict.code()).isEqualTo("RELEASE_EVIDENCE_MISMATCH"));
        assertThat(fixture.releases.listReleases(workspace)).containsExactly(first);
    }

    @Test
    void aliasesAreAuditedOptimisticPointersThatNeverSelectAnEnvironment() {
        var r1 = fixture.release(workspace, "agent", "1.0.0");
        var r2 = fixture.release(workspace, "agent", "2.0.0");

        var created = fixture.releases.setAlias(workspace, "latest", r1.id(), 0);
        var moved = fixture.releases.setAlias(workspace, "latest", r2.id(), created.version());

        assertThat(moved.version()).isEqualTo(2);
        assertThat(fixture.releases.getAlias(workspace, "latest").releaseId()).isEqualTo(r2.id());
        assertThat(fixture.releases.aliasHistory(workspace, "latest"))
                .extracting(row -> row.releaseId(), row -> row.previousReleaseId())
                .containsExactly(org.assertj.core.groups.Tuple.tuple(r1.id(), null),
                        org.assertj.core.groups.Tuple.tuple(r2.id(), r1.id()));
        assertThatThrownBy(() -> fixture.releases.setAlias(workspace, "latest", r1.id(), 1))
                .isInstanceOfSatisfying(LifecycleConflictException.class,
                        conflict -> assertThat(conflict.code()).isEqualTo("STALE_VERSION"));
        assertThat(fixture.count("select count(*) from agent_deployment where workspace_id = ?", workspace.value())).isZero();
    }

    @Test
    void concurrentAliasCreationHasOneWinnerAndTypedStaleOutcomes() throws Exception {
        var release = fixture.release(workspace, "agent", "1.0.0");

        List<Object> outcomes = PromotionLifecyclePostgresTest.race(6,
                index -> fixture.releases.setAlias(workspace, "stable", release.id(), 0));

        assertThat(outcomes).filteredOn(LifecycleModel.AliasView.class::isInstance).hasSize(1);
        assertThat(outcomes).filteredOn(LifecycleConflictException.class::isInstance).hasSize(5)
                .allSatisfy(outcome -> assertThat(((LifecycleConflictException) outcome).code()).isEqualTo("STALE_VERSION"));
        assertThat(fixture.releases.aliasHistory(workspace, "stable")).hasSize(1);
    }

    @Test
    void releasesAndVersionsAreInvisibleAcrossWorkspaces() {
        UUID version = fixture.version(workspace, "agent", "1.0.0");
        var release = fixture.releases.createRelease(workspace, version, "agent-1");
        WorkspaceId other = fixture.workspace();

        assertThatThrownBy(() -> fixture.releases.createRelease(other, version, "stolen"))
                .isInstanceOf(LifecycleNotFoundException.class);
        assertThatThrownBy(() -> fixture.releases.manifest(other, release.id())).isInstanceOf(LifecycleNotFoundException.class);
        assertThatThrownBy(() -> fixture.releases.setAlias(other, "latest", release.id(), 0))
                .isInstanceOf(LifecycleNotFoundException.class);
    }
}
