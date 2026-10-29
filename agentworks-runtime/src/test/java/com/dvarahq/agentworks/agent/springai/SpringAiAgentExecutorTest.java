package com.dvarahq.agentworks.agent.springai;

import com.dvarahq.agentworks.capability.CapabilityDisclosure;
import com.dvarahq.agentworks.capability.CapabilityResolution;
import com.dvarahq.agentworks.capability.CapabilityResolutionRequest;
import com.dvarahq.agentworks.capability.ToolEffect;
import com.dvarahq.agentworks.capability.ToolId;
import com.dvarahq.agentworks.capability.ToolType;
import com.dvarahq.agentworks.domain.run.PinnedAgentVersion;
import com.dvarahq.agentworks.domain.run.RunInvoker;
import com.dvarahq.agentworks.domain.security.CredentialRef;
import com.dvarahq.agentworks.domain.security.PermissionRisk;
import com.dvarahq.agentworks.domain.security.RememberedPermissionEvaluator;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import com.dvarahq.agentworks.gateway.CapabilityPolicy;
import com.dvarahq.agentworks.gateway.ConfiguredGateway;
import com.dvarahq.agentworks.gateway.GatewayRegistry;
import com.dvarahq.agentworks.gateway.ModelGateway;
import com.dvarahq.agentworks.gateway.ModelRole;
import com.dvarahq.agentworks.gateway.ModelRoleBinding;
import com.dvarahq.agentworks.gateway.ModelMessage;
import com.dvarahq.agentworks.gateway.ToolCall;
import com.dvarahq.agentworks.gateway.fake.DeterministicFakeGatewayAdapter;
import com.dvarahq.agentworks.gateway.fake.FakeReply;
import com.dvarahq.agentworks.gateway.ModelUsage;
import com.dvarahq.agentworks.context.BasicContextCompiler;
import com.dvarahq.agentworks.context.ContextCompilationCache;
import com.dvarahq.agentworks.context.SlidingWindowCompactionStrategy;
import com.dvarahq.agentworks.memory.AgentMemoryStore;
import com.dvarahq.agentworks.memory.MemoryActor;
import com.dvarahq.agentworks.memory.MemoryKind;
import com.dvarahq.agentworks.memory.MemoryQuery;
import com.dvarahq.agentworks.memory.MemoryRecord;
import com.dvarahq.agentworks.memory.MemoryScope;
import com.dvarahq.agentworks.memory.MemoryWrite;
import com.dvarahq.agentworks.runplane.execution.ReleaseManifestReader;
import com.dvarahq.agentworks.runplane.execution.ResolvedRunCapabilities;
import com.dvarahq.agentworks.runplane.execution.RunOutcome;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The production Spring AI executor's default telemetry (P0-S1-004, issue #34). */
class SpringAiAgentExecutorTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** A continuation of a version 2 release pinned to a deterministic fake gateway. */
    private static RecordingRunContext continuation(String prompt) {
        return continuation(prompt, 2, List.of());
    }

    private static RecordingRunContext continuation(String prompt, int manifestVersion,
            List<Map<String, Object>> capabilityPacks) {
        RecordingRunContext context = new RecordingRunContext();
        var adapter = DeterministicFakeGatewayAdapter.builder("direct").model("coding-premium", "model-a").build();
        Map<String, Object> models = new TreeMap<>();
        GatewayRegistry.of(List.of(new ConfiguredGateway(adapter, CapabilityPolicy.DEFAULT, null)))
                .resolve(List.of(new ModelRoleBinding(ModelRole.REASONING, "direct", "coding-premium")))
                .forEach((role, identity) -> models.put(role.manifestKey(), identity.toManifestEntry()));
        Map<String, Object> manifest = new TreeMap<>();
        manifest.put("manifestVersion", manifestVersion);
        manifest.put("agent", Map.of("definitionId", UUID.randomUUID().toString(), "definitionName", "agent",
                "specApiVersion", "dvarahq.com/v1alpha1", "specDigest", "sha256:" + "a".repeat(64), "version", "1.0.0",
                "versionId", context.pins.versionId().toString()));
        manifest.put("capabilityPacks", capabilityPacks);
        manifest.put("executor", Map.of("requiredCapabilities", List.of(), "type", "spring-ai"));
        if (manifestVersion >= 4) {
            manifest.put("execution", Map.of("initialIntent", "EXECUTE", "autonomyProfile", "SUPERVISED"));
        }
        if (manifestVersion >= 7) manifest.put("limits", Map.of());
        if (manifestVersion >= 10) {
            manifest.put("context", Map.of("maximumTokens", 8192, "loadingPolicy", "ON_DEMAND",
                    "cacheAware", true, "policyHash", "sha256:" + "f".repeat(64), "compaction",
                    Map.of("enabled", true, "strategy", "SLIDING_WINDOW", "threshold", 0.8)));
        }
        if (manifestVersion >= 11) {
            manifest.put("memory", Map.ofEntries(Map.entry("conversationEnabled", true),
                    Map.entry("conversationRetention", "P30D"), Map.entry("conversationItems", 64),
                    Map.entry("workingEnabled", true), Map.entry("workingRetention", "PT24H"),
                    Map.entry("workingItems", 32), Map.entry("semanticEnabled", true),
                    Map.entry("knowledgeItems", 16), Map.entry("knowledgeTokens", 4096),
                    Map.entry("knowledgeTimeout", "PT0.05S"), Map.entry("policyHash", "sha256:" + "b".repeat(64))));
        }
        manifest.put("models", models);
        manifest.put("sandbox", Map.of());
        manifest.put("skills", List.of());
        context.manifest = new ReleaseManifestReader().read(new WorkspaceId(context.pins.workspaceId()),
                context.pins.releaseId(), JSON.writeValueAsString(manifest));
        context.invoker = new RunInvoker(UUID.randomUUID(), context.pins.workspaceId(), UUID.randomUUID(),
                UUID.randomUUID(), "session");
        context.version = new PinnedAgentVersion(context.pins.versionId(),
                "{\"spec\":{\"instructions\":{\"system\":\"secret-instructions\"}}}", "sha256:" + "a".repeat(64));
        context.input = JSON.valueToTree(Map.of("prompt", prompt));
        return context;
    }

    private static SpringAiAgentExecutor executor(ObservationRegistry registry) {
        var adapter = DeterministicFakeGatewayAdapter.builder("direct").model("coding-premium", "model-a").build();
        var gateway = new ModelGateway(GatewayRegistry.of(List.of(new ConfiguredGateway(adapter, CapabilityPolicy.DEFAULT,
                null))), null, null, event -> { }, Clock.systemUTC());
        return new SpringAiAgentExecutor(gateway, () -> new org.springframework.ai.tool.ToolCallback[0], registry,
                new SpringAiExecutorSettings(Duration.ofSeconds(10), 64, 4));
    }

    @Test
    void emitsObservationWithoutCapturingPromptOrSecretInDefaultTelemetry() throws Exception {
        RecordingObservationHandler handler = new RecordingObservationHandler();
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(handler);
        RecordingRunContext context = continuation("password=should-not-appear");

        RunOutcome outcome = executor(registry).execute(context);

        assertThat(outcome).isInstanceOf(RunOutcome.Completed.class);
        assertThat(handler.observationNames()).contains("agentworks.run.execute");
        assertThat(handler.telemetry()).contains("agentworks.trace.id=" + context.pins.traceId())
                .contains("agentworks.run.id=" + context.pins.id())
                .contains("agentworks.release.id=" + context.pins.releaseId())
                .contains("agentworks.deployment.id=" + context.pins.deploymentId())
                .contains("agentworks.revision.id=" + context.pins.revisionId())
                .contains("agentworks.cohort.id=" + context.pins.cohortId())
                .contains("agentworks.executor=spring-ai");
        assertThat(handler.telemetry()).doesNotContain("should-not-appear").doesNotContain("password=")
                .doesNotContain("secret-instructions");
    }

    @Test
    void theExecutorKeepsNoStateBetweenContinuations() throws Exception {
        SpringAiAgentExecutor executor = executor(ObservationRegistry.NOOP);
        RecordingRunContext first = continuation("first prompt");
        RecordingRunContext second = continuation("second prompt");

        RunOutcome one = executor.execute(first);
        RunOutcome two = executor.execute(second);

        assertThat(one).isEqualTo(RunOutcome.completed(Map.of("type", "text", "content", "echo(model-a): first prompt")));
        assertThat(two).isEqualTo(RunOutcome.completed(Map.of("type", "text", "content", "echo(model-a): second prompt")));
        assertThat(first.events).noneMatch(event -> event.payload().toString().contains("second"));
        assertThat(first.checkpoints).hasSize(1);
        assertThat(second.checkpoints).hasSize(1);
    }

    @Test
    void versionTenCompilesContextAndRecordsOnlyContentFreeDebugEvidence() throws Exception {
        RecordingRunContext context = continuation("private-user-input", 10, List.of());
        var request = new CapabilityResolutionRequest(List.of(), Set.of(), Set.of(), Set.of(), Set.of(), 64, 64);
        var resolution = new CapabilityResolution("capability-resolver/v1", List.of(), List.of(), 0, 0);
        context.resolvedCapabilities = new ResolvedRunCapabilities(request, resolution,
                new CapabilityDisclosure(List.of(), List.of()));

        RunOutcome outcome = executor(ObservationRegistry.NOOP).execute(context);

        assertThat(outcome).isInstanceOf(RunOutcome.Completed.class);
        assertThat(context.types()).containsSubsequence("CONTEXT_BUILD_STARTED", "CONTEXT_ITEM_INCLUDED",
                "CONTEXT_ITEM_INCLUDED", "CONTEXT_CACHE_LAYOUT_RECORDED", "CONTEXT_COMPILED", "MODEL_REQUESTED");
        assertThat(context.events.stream().filter(event -> event.type().category().name().equals("CONTEXT"))
                .map(event -> event.payload().toString()).toList().toString())
                .contains("contentDigest", "release-pinned", "estimatedTokens")
                .doesNotContain("private-user-input", "secret-instructions");
    }

    @Test
    void versionElevenLoadsMemoryAndFinalizesCheckpointedExchangeOnRecovery() throws Exception {
        RecordingRunContext context = continuation("new question", 11, List.of());
        var request = new CapabilityResolutionRequest(List.of(), Set.of(), Set.of(), Set.of(), Set.of(), 64, 64);
        context.resolvedCapabilities = new ResolvedRunCapabilities(request,
                new CapabilityResolution("capability-resolver/v1", List.of(), List.of(), 0, 0),
                new CapabilityDisclosure(List.of(), List.of()));
        UUID session = UUID.randomUUID();
        AtomicInteger commits = new AtomicInteger();
        List<MemoryWrite> committed = new ArrayList<>();
        AgentMemoryStore store = new AgentMemoryStore() {
            @Override public List<MemoryRecord> read(MemoryActor actor, MemoryQuery query) {
                if (query.kind() == MemoryKind.WORKING) return List.of();
                return List.of(new MemoryRecord(UUID.randomUUID(), MemoryKind.CONVERSATION, query.scope(),
                        context.pins.releaseId(), context.pins.revisionId(), "user", Optional.of("old memory"),
                        "sha256:" + "c".repeat(64), java.time.Instant.now(), java.time.Instant.now().plusSeconds(60)));
            }
            @Override public List<MemoryRecord> commit(MemoryActor actor, List<MemoryWrite> writes) {
                commits.incrementAndGet();
                committed.addAll(writes);
                return List.of();
            }
            @Override public int delete(MemoryActor actor, MemoryKind kind, MemoryScope scope) { return 0; }
            @Override public int expire(UUID workspaceId, java.time.Instant cutoff, int limit) { return 0; }
            @Override public long revision(MemoryKind kind, MemoryScope scope) { return 7; }
            @Override public Optional<UUID> logicalSessionId(UUID workspaceId, UUID runId) { return Optional.of(session); }
        };
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicReference<com.dvarahq.agentworks.gateway.AdapterInvocation> seen = new AtomicReference<>();
        var adapter = DeterministicFakeGatewayAdapter.builder("direct").model("coding-premium", "model-a")
                .script(invocation -> {
                    modelCalls.incrementAndGet();
                    seen.set(invocation);
                    return FakeReply.text(List.of("answer"), ModelUsage.UNREPORTED);
                }).build();
        var gateway = new ModelGateway(GatewayRegistry.of(List.of(new ConfiguredGateway(adapter,
                CapabilityPolicy.DEFAULT, null))), null, null, event -> { }, Clock.systemUTC());
        SpringAiAgentExecutor executor = new SpringAiAgentExecutor(gateway, () -> new ToolCallback[0],
                CapabilityToolCallbackProvider.from(), ObservationRegistry.NOOP,
                new SpringAiExecutorSettings(Duration.ofSeconds(10), 64, 4), null,
                ToolInputGuardrail.validatedInput(), ToolOutputGuardrail.validatedOutput(), null, null, null,
                new BasicContextCompiler(new ContextCompilationCache(16), new SlidingWindowCompactionStrategy()),
                store, List.of());

        assertThat(executor.execute(context)).isInstanceOf(RunOutcome.Completed.class);
        assertThat(seen.get().request().messages()).extracting(ModelMessage::content)
                .anyMatch(content -> content.contains("old memory"));
        assertThat(committed).extracting(MemoryWrite::kind)
                .containsExactlyInAnyOrder(MemoryKind.CONVERSATION, MemoryKind.CONVERSATION,
                        MemoryKind.WORKING, MemoryKind.WORKING);
        context.checkpointState = JSON.valueToTree(context.checkpoints.getFirst());

        assertThat(executor.execute(context)).isInstanceOf(RunOutcome.Completed.class);
        assertThat(modelCalls).hasValue(1);
        assertThat(commits).hasValue(2);
    }

    @Test
    void versionElevenBoundsKnowledgeProviderTime() throws Exception {
        RecordingRunContext context = continuation("bounded query", 11, List.of());
        var request = new CapabilityResolutionRequest(List.of(), Set.of(), Set.of(), Set.of(), Set.of(), 64, 64);
        context.resolvedCapabilities = new ResolvedRunCapabilities(request,
                new CapabilityResolution("capability-resolver/v1", List.of(), List.of(), 0, 0),
                new CapabilityDisclosure(List.of(), List.of()));
        var adapter = DeterministicFakeGatewayAdapter.builder("direct").model("coding-premium", "model-a").build();
        var gateway = new ModelGateway(GatewayRegistry.of(List.of(new ConfiguredGateway(adapter,
                CapabilityPolicy.DEFAULT, null))), null, null, event -> { }, Clock.systemUTC());
        com.dvarahq.agentworks.memory.KnowledgeProvider slow = new com.dvarahq.agentworks.memory.KnowledgeProvider() {
            @Override public String id() { return "slow"; }
            @Override public List<com.dvarahq.agentworks.context.ContextItem> retrieve(
                    com.dvarahq.agentworks.memory.KnowledgeQuery query,
                    com.dvarahq.agentworks.memory.RetrievalCancellation cancellation) {
                try {
                    Thread.sleep(1_000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                return List.of();
            }
        };
        SpringAiAgentExecutor executor = new SpringAiAgentExecutor(gateway, () -> new ToolCallback[0],
                CapabilityToolCallbackProvider.from(), ObservationRegistry.NOOP,
                new SpringAiExecutorSettings(Duration.ofSeconds(10), 64, 4), null,
                ToolInputGuardrail.validatedInput(), ToolOutputGuardrail.validatedOutput(), null, null, null,
                new BasicContextCompiler(new ContextCompilationCache(16), new SlidingWindowCompactionStrategy()),
                null, List.of(slow));

        assertThat(executor.execute(context)).isInstanceOfSatisfying(RunOutcome.Failed.class,
                failure -> assertThat(failure.code()).isEqualTo("KNOWLEDGE_TIMEOUT"));
    }

    @Test
    void aVersionThreeRunOffersOnlyExactDetailBindingsAndKeepsSummarySchemasOutOfContext() throws Exception {
        String schema = "{\"type\":\"object\"}";
        String hash = com.dvarahq.agentworks.capability.AgentTool.digest(schema);
        ToolId selectedId = new ToolId("core", "selected", "1.0.0");
        ToolId hiddenId = new ToolId("core", "hidden", "1.0.0");
        RecordingRunContext context = continuation("use selected", 3, List.of(Map.of("id", "pack", "version", "1",
                "tools", List.of(Map.of("namespace", selectedId.namespace(), "name", selectedId.name(),
                                "version", selectedId.version(), "schemaHash", hash),
                        Map.of("namespace", hiddenId.namespace(), "name", hiddenId.name(),
                                "version", hiddenId.version(), "schemaHash", hash)))));
        var request = new CapabilityResolutionRequest(List.of("pack:1"), Set.of("selected"), Set.of(), Set.of(),
                Set.of(selectedId), 64, 64);
        var resolution = new CapabilityResolution("capability-resolver/v1",
                List.of(new CapabilityResolution.PackDecision("pack:1", true,
                        CapabilityResolution.PackReason.INCLUDED)),
                List.of(new CapabilityResolution.Decision(selectedId, "pack:1", CapabilityResolution.Disclosure.DETAIL,
                                CapabilityResolution.Reason.INCLUDED, 1, hash),
                        new CapabilityResolution.Decision(hiddenId, "pack:1", CapabilityResolution.Disclosure.SUMMARY,
                                CapabilityResolution.Reason.NOT_TRIGGERED, 0, hash)), 16, 16);
        context.resolvedCapabilities = new ResolvedRunCapabilities(request, resolution,
                new CapabilityDisclosure(List.of(new CapabilityDisclosure.Summary(hiddenId, ToolType.JAVA,
                        "hidden summary", hash)), List.of(new CapabilityDisclosure.Detail(selectedId, ToolType.JAVA,
                                "selected summary", "selected detail", schema, hash))));
        AtomicReference<com.dvarahq.agentworks.gateway.AdapterInvocation> seen = new AtomicReference<>();
        var adapter = DeterministicFakeGatewayAdapter.builder("direct").model("coding-premium", "model-a")
                .script(invocation -> {
                    seen.set(invocation);
                    return FakeReply.text(List.of("done"), ModelUsage.UNREPORTED);
                }).build();
        var gateway = new ModelGateway(GatewayRegistry.of(List.of(new ConfiguredGateway(adapter,
                CapabilityPolicy.DEFAULT, null))), null, null, event -> { }, Clock.systemUTC());
        ToolCallback selected = callback("selected_callback", schema);
        ToolCallback hidden = callback("hidden_callback", schema);
        SpringAiAgentExecutor executor = new SpringAiAgentExecutor(gateway,
                () -> new ToolCallback[] {hidden}, CapabilityToolCallbackProvider.from(
                        new CapabilityToolCallback(selectedId, selected), new CapabilityToolCallback(hiddenId, hidden)),
                ObservationRegistry.NOOP, new SpringAiExecutorSettings(Duration.ofSeconds(10), 64, 4));

        RunOutcome outcome = executor.execute(context);

        assertThat(outcome).isInstanceOf(RunOutcome.Completed.class);
        assertThat(seen.get().request().tools()).extracting(tool -> tool.name()).containsExactly("selected_callback");
        assertThat(seen.get().request().messages()).extracting(message -> message.content())
                .anyMatch(content -> content.contains("hidden summary"));
        assertThat(seen.get().request().messages()).extracting(message -> message.content())
                .noneMatch(content -> content.contains(schema) || content.contains("hidden_callback"));

        RecordingRunContext delegated = continuation("use selected", 3, List.of(Map.of("id", "pack", "version", "1",
                "tools", List.of(Map.of("namespace", selectedId.namespace(), "name", selectedId.name(),
                                "version", selectedId.version(), "schemaHash", hash),
                        Map.of("namespace", hiddenId.namespace(), "name", hiddenId.name(),
                                "version", hiddenId.version(), "schemaHash", hash)))));
        delegated.input = JSON.valueToTree(Map.of("prompt", "use selected",
                "allowedTools", List.of(selectedId.value())));
        delegated.resolvedCapabilities = new ResolvedRunCapabilities(request,
                new CapabilityResolution("capability-resolver/v1", resolution.packs(), List.of(
                        new CapabilityResolution.Decision(selectedId, "pack:1", CapabilityResolution.Disclosure.DETAIL,
                                CapabilityResolution.Reason.INCLUDED, 1, hash),
                        new CapabilityResolution.Decision(hiddenId, "pack:1", CapabilityResolution.Disclosure.DETAIL,
                                CapabilityResolution.Reason.INCLUDED, 1, hash)), 16, 16),
                new CapabilityDisclosure(List.of(), List.of(
                        new CapabilityDisclosure.Detail(selectedId, ToolType.JAVA, "selected", "selected", schema, hash),
                        new CapabilityDisclosure.Detail(hiddenId, ToolType.JAVA, "hidden", "hidden", schema, hash))));
        assertThat(executor.execute(delegated)).isInstanceOf(RunOutcome.Completed.class);
        assertThat(seen.get().request().tools()).extracting(tool -> tool.name()).containsExactly("selected_callback");

        SpringAiAgentExecutor missing = new SpringAiAgentExecutor(gateway, () -> new ToolCallback[] {hidden},
                CapabilityToolCallbackProvider.from(), ObservationRegistry.NOOP,
                new SpringAiExecutorSettings(Duration.ofSeconds(10), 64, 4));
        assertThat(missing.execute(context)).isInstanceOfSatisfying(RunOutcome.Failed.class,
                failure -> assertThat(failure.code()).isEqualTo("CAPABILITY_BINDING_UNAVAILABLE"));
        SpringAiAgentExecutor drifting = new SpringAiAgentExecutor(gateway, () -> new ToolCallback[] {hidden},
                CapabilityToolCallbackProvider.from(new CapabilityToolCallback(selectedId,
                        callback("selected_callback", "{\"type\":\"string\"}"))), ObservationRegistry.NOOP,
                new SpringAiExecutorSettings(Duration.ofSeconds(10), 64, 4));
        assertThat(drifting.execute(context)).isInstanceOfSatisfying(RunOutcome.Failed.class,
                failure -> assertThat(failure.code()).isEqualTo("CAPABILITY_BINDING_SCHEMA_DRIFT"));
    }

    @Test
    void agentAsToolBlocksThenReplaysExactChildOutputThroughTheOrdinaryPipeline() throws Exception {
        AgentAsToolCallback callback = new AgentAsToolCallback();
        String schema = callback.getToolDefinition().inputSchema();
        String hash = com.dvarahq.agentworks.capability.AgentTool.digest(schema);
        ToolId id = new ToolId("agentworks", "delegate-agent", "1.0.0");
        RecordingRunContext context = continuation("delegate this", 3, List.of(Map.of("id", "agents", "version", "1",
                "tools", List.of(Map.of("namespace", id.namespace(), "name", id.name(), "version", id.version(),
                        "schemaHash", hash)))));
        var request = new CapabilityResolutionRequest(List.of("agents:1"), Set.of("delegate"), Set.of(), Set.of(),
                Set.of(id), 64, 64);
        context.resolvedCapabilities = new ResolvedRunCapabilities(request,
                new CapabilityResolution("capability-resolver/v1",
                        List.of(new CapabilityResolution.PackDecision("agents:1", true,
                                CapabilityResolution.PackReason.INCLUDED)),
                        List.of(new CapabilityResolution.Decision(id, "agents:1",
                                CapabilityResolution.Disclosure.DETAIL, CapabilityResolution.Reason.INCLUDED, 1, hash)),
                        8, 8),
                new CapabilityDisclosure(List.of(), List.of(new CapabilityDisclosure.Detail(id, ToolType.JAVA,
                        "delegate", "delegate", schema, hash))));
        UUID relationshipId = UUID.randomUUID(), childId = UUID.randomUUID(), deploymentId = UUID.randomUUID(),
                releaseId = UUID.randomUUID();
        AtomicInteger childAllocations = new AtomicInteger();
        AtomicReference<com.dvarahq.agentworks.child.ChildRunRequest> acceptedChild = new AtomicReference<>();
        List<com.dvarahq.agentworks.child.ChildRunRequest> childRequests = new ArrayList<>();
        context.childInvoker = childRequest -> {
            childRequests.add(childRequest);
            if (acceptedChild.compareAndSet(null, childRequest)) {
                childAllocations.incrementAndGet();
                return new com.dvarahq.agentworks.runplane.execution.AgentAsTool.Result.Blocked(
                        relationshipId, childId);
            }
            assertThat(childRequest.idempotencyKey()).isEqualTo(acceptedChild.get().idempotencyKey());
            assertThat(childRequest).isEqualTo(acceptedChild.get());
            return new com.dvarahq.agentworks.runplane.execution.AgentAsTool.Result.Completed(
                    relationshipId, childId, JSON.valueToTree(Map.of("answer", 42)));
        };
        String arguments = JSON.writeValueAsString(Map.of("targetDeploymentId", deploymentId.toString(),
                "expectedReleaseId", releaseId.toString(), "task", "bounded subtask", "allowedTools", List.of(),
                "budget", Map.of("maxTokens", 100, "maxModelCalls", 2, "maxToolCalls", 2,
                        "maxRuntimeMillis", 1000, "maxCostUsd", "1.000000"),
                "failurePolicy", "RETURN_OUTCOME"));
        AtomicInteger providerCalls = new AtomicInteger();
        var adapter = DeterministicFakeGatewayAdapter.builder("direct").model("coding-premium", "model-a")
                .script(invocation -> {
                    Optional<ModelMessage> tool = invocation.request().messages().stream()
                            .filter(message -> message.role() == ModelMessage.Role.TOOL).findFirst();
                    if (tool.isPresent()) {
                        assertThat(tool.orElseThrow().toolCallId()).isEqualTo("delegate-provider-2");
                        assertThat(tool.orElseThrow().content()).isEqualTo("{\"answer\":42}");
                        return FakeReply.text(List.of("parent completed"), ModelUsage.UNREPORTED);
                    }
                    String providerCallId = "delegate-provider-" + providerCalls.incrementAndGet();
                    return FakeReply.text(List.of(), ModelUsage.UNREPORTED)
                            .withToolCalls(List.of(new ToolCall(providerCallId, "delegate_agent", arguments)));
                }).build();
        var gateway = new ModelGateway(GatewayRegistry.of(List.of(new ConfiguredGateway(adapter,
                CapabilityPolicy.DEFAULT, null))), null, null, event -> { }, Clock.systemUTC());
        SpringAiAgentExecutor executor = new SpringAiAgentExecutor(gateway, () -> new ToolCallback[0],
                CapabilityToolCallbackProvider.from(new CapabilityToolCallback(id, callback)),
                ObservationRegistry.NOOP, new SpringAiExecutorSettings(Duration.ofSeconds(10), 64, 4));

        assertThat(executor.execute(context)).isInstanceOfSatisfying(RunOutcome.Delegated.class,
                delegated -> assertThat(delegated.detail()).isEqualTo(Map.of("relationshipId", relationshipId.toString(),
                        "childRunId", childId.toString())));
        assertThat(executor.execute(context)).isInstanceOfSatisfying(RunOutcome.Completed.class,
                completed -> assertThat(completed.output()).isEqualTo(
                        Map.of("type", "text", "content", "parent completed")));
        assertThat(childAllocations).hasValue(1);
        assertThat(providerCalls).hasValue(2);
        assertThat(childRequests).hasSize(2).allSatisfy(childRequest ->
                assertThat(childRequest.idempotencyKey())
                        .isEqualTo("child:" + context.pins.id() + ":1:0")
                        .doesNotContain("delegate-provider"));
        String durableModelEvidence = context.events.stream()
                .filter(event -> event.type() == com.dvarahq.agentworks.domain.run.RunEventType.MODEL_RESPONSE_RECEIVED)
                .map(event -> event.payload().toString()).toList().toString();
        assertThat(durableModelEvidence)
                .contains("delegate-provider-1", "delegate-provider-2")
                .doesNotContain("bounded subtask", deploymentId.toString(), releaseId.toString());
        assertThat(schema).doesNotContain("idempotencyKey");
        assertThat(context.types()).containsSubsequence("TOOL_REQUESTED", "TOOL_INPUT_SCHEMA_VALIDATED",
                "TOOL_POLICY_EVALUATED", "TOOL_INPUT_GUARDRAIL_EVALUATED", "TOOL_STARTED",
                "TOOL_OUTPUT_GUARDRAIL_EVALUATED", "TOOL_COMPLETED");
    }

    @Test
    void delegatedLegacyReleaseReceivesOnlyExplicitlyAllowedTools() throws Exception {
        RecordingRunContext context = continuation("legacy delegated child", 2, List.of());
        context.input = JSON.valueToTree(Map.of("prompt", "legacy delegated child",
                "allowedTools", List.of("selected_callback")));
        AtomicReference<com.dvarahq.agentworks.gateway.ModelRequest> seen = new AtomicReference<>();
        var adapter = DeterministicFakeGatewayAdapter.builder("direct").model("coding-premium", "model-a")
                .script(invocation -> {
                    seen.set(invocation.request());
                    return FakeReply.text(List.of("done"), ModelUsage.UNREPORTED);
                }).build();
        var gateway = new ModelGateway(GatewayRegistry.of(List.of(new ConfiguredGateway(adapter,
                CapabilityPolicy.DEFAULT, null))), null, null, event -> { }, Clock.systemUTC());
        ToolCallback selected = callback("selected_callback", "{\"type\":\"object\"}");
        ToolCallback excluded = callback("excluded_callback", "{\"type\":\"object\"}");
        SpringAiAgentExecutor executor = new SpringAiAgentExecutor(gateway,
                () -> new ToolCallback[] {selected, excluded}, ObservationRegistry.NOOP,
                new SpringAiExecutorSettings(Duration.ofSeconds(10), 64, 4));

        assertThat(executor.execute(context)).isInstanceOf(RunOutcome.Completed.class);
        assertThat(seen.get().tools()).extracting(tool -> tool.name()).containsExactly("selected_callback");
    }

    @Test
    void terminalChildFailureReturnsTypedOutcomeOrFailsParentPerImmutablePolicy() throws Exception {
        RecordingRunContext context = continuation("resume parent");
        UUID relationship = UUID.randomUUID(), child = UUID.randomUUID();
        context.childInvoker = ignored -> new com.dvarahq.agentworks.runplane.execution.AgentAsTool.Result.Failed(
                relationship, child, "CANCELLED", "OPERATOR_CANCELLED");
        ToolCallback callback = new AgentAsToolCallback().bind(new ToolRunScope(context));
        ToolContext callIdentity = ToolCallIdentityRegistry.withIdentity(null,
                new ToolCallIdentityRegistry.Identity("child:" + context.pins.id() + ":1:0", "provider-call-9"));
        Map<String, Object> base = new java.util.LinkedHashMap<>();
        base.put("targetDeploymentId", UUID.randomUUID().toString());
        base.put("expectedReleaseId", UUID.randomUUID().toString());
        base.put("task", "bounded subtask");
        base.put("budget", Map.of("maxTokens", 100, "maxModelCalls", 2, "maxToolCalls", 2,
                "maxRuntimeMillis", 1000, "maxCostUsd", "1.000000"));
        base.put("failurePolicy", "RETURN_OUTCOME");

        tools.jackson.databind.JsonNode outcome = JSON.readTree(
                callback.call(JSON.writeValueAsString(base), callIdentity));
        assertThat(outcome.path("status").asString()).isEqualTo("CANCELLED");
        assertThat(outcome.path("code").asString()).isEqualTo("OPERATOR_CANCELLED");
        base.put("failurePolicy", "FAIL_PARENT");
        assertThatThrownBy(() -> callback.call(JSON.writeValueAsString(base), callIdentity))
                .isInstanceOfSatisfying(ToolAdapterException.class,
                        failure -> assertThat(failure.code()).isEqualTo("CHILD_RUN_CANCELLED"));
    }

    @Test
    void aVersionFiveEffectUsesTheDurableBoundaryAndPassesItsStableKeyToTheTool() throws Exception {
        verifyScopedDurableEffect(false);
        verifyScopedDurableEffect(true);
    }

    private void verifyScopedDurableEffect(boolean cleanupFails) throws Exception {
        String schema = "{\"type\":\"object\",\"additionalProperties\":false}";
        String hash = com.dvarahq.agentworks.capability.AgentTool.digest(schema);
        ToolId id = new ToolId("core", "ledger", "1.0.0");
        ToolEffect effect = new ToolEffect("ledger", "write", PermissionRisk.HIGH);
        RecordingRunContext context = continuation("write the ledger", 5, List.of(Map.of("id", "pack", "version", "1",
                "tools", List.of(Map.of("namespace", id.namespace(), "name", id.name(), "version", id.version(),
                        "schemaHash", hash, "effect", Map.of("resource", effect.resource(), "action", effect.action(),
                                "risk", effect.risk().name()))))));
        var request = new CapabilityResolutionRequest(List.of("pack:1"), Set.of("ledger"), Set.of(), Set.of(),
                Set.of(id), 64, 64);
        var resolution = new CapabilityResolution("capability-resolver/v1",
                List.of(new CapabilityResolution.PackDecision("pack:1", true,
                        CapabilityResolution.PackReason.INCLUDED)),
                List.of(new CapabilityResolution.Decision(id, "pack:1", CapabilityResolution.Disclosure.DETAIL,
                        CapabilityResolution.Reason.INCLUDED, 1, hash)), 8, 8);
        context.resolvedCapabilities = new ResolvedRunCapabilities(request, resolution,
                new CapabilityDisclosure(List.of(), List.of(new CapabilityDisclosure.Detail(id, ToolType.JAVA,
                        "ledger", "ledger detail", schema, hash))));
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> idempotencyKey = new AtomicReference<>();
        AtomicInteger acquired = new AtomicInteger(), closed = new AtomicInteger(), bound = new AtomicInteger();
        ToolCallback callback = new ToolCallback() {
            private final ToolDefinition definition = ToolDefinition.builder().name("ledger_callback")
                    .description("ledger").inputSchema(schema).build();
            @Override public ToolDefinition getToolDefinition() { return definition; }
            @Override public String call(String input) { throw new AssertionError("ToolContext is required"); }
            @Override public String call(String input, ToolContext toolContext) {
                calls.incrementAndGet();
                idempotencyKey.set(ToolEffectContext.idempotencyKey(toolContext).orElseThrow());
                return "written";
            }
        };
        var adapter = DeterministicFakeGatewayAdapter.builder("direct").model("coding-premium", "model-a")
                .script(invocation -> {
                    boolean answered = invocation.request().messages().stream()
                            .anyMatch(message -> message.role() == ModelMessage.Role.TOOL);
                    if (!answered) {
                        return FakeReply.text(List.of(), ModelUsage.UNREPORTED)
                                .withToolCalls(List.of(new ToolCall("call-1", "ledger_callback", "{}")));
                    }
                    return FakeReply.text(List.of("done"), ModelUsage.UNREPORTED);
                }).build();
        var gateway = new ModelGateway(GatewayRegistry.of(List.of(new ConfiguredGateway(adapter,
                CapabilityPolicy.DEFAULT, null))), null, null, event -> { }, Clock.systemUTC());
        ToolPermissionAuthority permissions = ignored -> new RememberedPermissionEvaluator.Decision(
                RememberedPermissionEvaluator.Verdict.ALLOW, RememberedPermissionEvaluator.Reason.MATCHED_ALLOW,
                java.util.Optional.of(UUID.randomUUID()), java.util.Optional.of(1L));
        RunBoundToolCallback scoped = new RunBoundToolCallback() {
            @Override public ToolDefinition getToolDefinition() { return callback.getToolDefinition(); }
            @Override public String call(String input) { throw new AssertionError("unbound"); }
            @Override public ToolCallback bind(ToolRunScope scope) {
                bound.incrementAndGet();
                return new ToolCallback() {
                    @Override public ToolDefinition getToolDefinition() { return callback.getToolDefinition(); }
                    @Override public String call(String input) { throw new AssertionError("context required"); }
                    @Override public String call(String input, ToolContext toolContext) {
                        scope.resource(this, () -> {
                            acquired.incrementAndGet();
                            return () -> {
                                closed.incrementAndGet();
                                if (cleanupFails) throw new Exception("secret-cleanup-canary");
                            };
                        });
                        return callback.call(input, toolContext);
                    }
                };
            }
        };
        SpringAiAgentExecutor executor = new SpringAiAgentExecutor(gateway, () -> new ToolCallback[0],
                CapabilityToolCallbackProvider.from(new CapabilityToolCallback(id, scoped)),
                ObservationRegistry.NOOP, new SpringAiExecutorSettings(Duration.ofSeconds(10), 64, 4), permissions);

        RunOutcome outcome = executor.execute(context);

        if (cleanupFails) {
            assertThat(outcome).isEqualTo(RunOutcome.failed("TOOL_RESOURCE_CLEANUP_FAILED", "tool adapter refused the operation"));
            assertThat(context.events.toString()).doesNotContain("secret-cleanup-canary");
        } else {
            assertThat(outcome).isEqualTo(RunOutcome.completed(Map.of("type", "text", "content", "done")));
        }
        assertThat(calls).hasValue(1);
        assertThat(context.effectInvocations).hasValue(1);
        assertThat(idempotencyKey.get()).hasSize(64).matches("[0-9a-f]{64}")
                .isEqualTo(context.externalEffectKeys.getFirst());
        assertThat(context.effectRequests.toString()).contains("core:ledger:1.0.0", "ledger", "write", "HIGH")
                .doesNotContain("write the ledger");
        assertThat(context.events.stream().filter(event -> event.type().name().equals("TOOL_COMPLETED"))
                .map(event -> event.payload().toString())).singleElement()
                .satisfies(payload -> assertThat(payload).contains("effectKey", "effectReplayed=false"));
        assertThat(bound).hasValue(1);
        assertThat(acquired).hasValue(1);
        assertThat(closed).hasValue(1);
        // A new continuation binds a fresh scope, but committed effects never acquire or invoke resources again.
        assertThat(executor.execute(context)).isInstanceOf(RunOutcome.Completed.class);
        assertThat(bound).hasValue(2);
        assertThat(acquired).hasValue(1);
        assertThat(closed).hasValue(1);
        assertThat(calls).hasValue(1);
    }

    @Test
    void aVersionFiveGuardrailStepUpReturnsASecretFreeDurableInteractionBeforeTheEffect() throws Exception {
        String schema = "{\"type\":\"object\",\"additionalProperties\":false}";
        String hash = com.dvarahq.agentworks.capability.AgentTool.digest(schema);
        ToolId id = new ToolId("core", "ledger", "1.0.0");
        ToolEffect effect = new ToolEffect("ledger", "write", PermissionRisk.HIGH);
        RecordingRunContext context = continuation("write the ledger", 5, List.of(Map.of("id", "pack", "version", "1",
                "tools", List.of(Map.of("namespace", id.namespace(), "name", id.name(), "version", id.version(),
                        "schemaHash", hash, "effect", Map.of("resource", effect.resource(), "action", effect.action(),
                                "risk", effect.risk().name()))))));
        var request = new CapabilityResolutionRequest(List.of("pack:1"), Set.of("ledger"), Set.of(), Set.of(),
                Set.of(id), 64, 64);
        var resolution = new CapabilityResolution("capability-resolver/v1",
                List.of(new CapabilityResolution.PackDecision("pack:1", true,
                        CapabilityResolution.PackReason.INCLUDED)),
                List.of(new CapabilityResolution.Decision(id, "pack:1", CapabilityResolution.Disclosure.DETAIL,
                        CapabilityResolution.Reason.INCLUDED, 1, hash)), 8, 8);
        context.resolvedCapabilities = new ResolvedRunCapabilities(request, resolution,
                new CapabilityDisclosure(List.of(), List.of(new CapabilityDisclosure.Detail(id, ToolType.JAVA,
                        "ledger", "ledger detail", schema, hash))));
        AtomicInteger calls = new AtomicInteger();
        ToolCallback callback = new ToolCallback() {
            private final ToolDefinition definition = ToolDefinition.builder().name("ledger_callback")
                    .description("ledger").inputSchema(schema).build();
            @Override public ToolDefinition getToolDefinition() { return definition; }
            @Override public String call(String input) { calls.incrementAndGet(); return "written"; }
        };
        var adapter = DeterministicFakeGatewayAdapter.builder("direct").model("coding-premium", "model-a")
                .script(invocation -> FakeReply.text(List.of(), ModelUsage.UNREPORTED)
                        .withToolCalls(List.of(new ToolCall("call-1", "ledger_callback", "{}"))))
                .build();
        var gateway = new ModelGateway(GatewayRegistry.of(List.of(new ConfiguredGateway(adapter,
                CapabilityPolicy.DEFAULT, null))), null, null, event -> { }, Clock.systemUTC());
        ToolPermissionAuthority permissions = ignored -> new RememberedPermissionEvaluator.Decision(
                RememberedPermissionEvaluator.Verdict.ALLOW, RememberedPermissionEvaluator.Reason.MATCHED_ALLOW,
                java.util.Optional.of(UUID.randomUUID()), java.util.Optional.of(1L));
        ToolInputGuardrail guardrail = new ToolInputGuardrail() {
            @Override public String id() { return "app:approval:1"; }
            @Override public Decision evaluate(Request ignored) {
                return new Decision(Outcome.INTERACTION_REQUIRED, "REVIEW_REQUIRED");
            }
        };
        SpringAiAgentExecutor executor = new SpringAiAgentExecutor(gateway, () -> new ToolCallback[0],
                CapabilityToolCallbackProvider.from(new CapabilityToolCallback(id, callback)),
                ObservationRegistry.NOOP, new SpringAiExecutorSettings(Duration.ofSeconds(10), 64, 4), permissions,
                guardrail, ToolOutputGuardrail.validatedOutput(), null,
                (workspaceId, runId, idempotencyKey) -> java.util.Optional.empty());

        RunOutcome outcome = executor.execute(context);

        assertThat(outcome).isInstanceOfSatisfying(RunOutcome.Interaction.class, interaction -> {
            assertThat(interaction.draft().runId()).isEqualTo(context.pins.id());
            assertThat(interaction.draft().assignment().principalId()).contains(context.invoker.subjectId());
            assertThat(interaction.draft().toString()).doesNotContain("write the ledger", "secret-instructions");
        });
        assertThat(calls).hasValue(0);
        assertThat(context.effectInvocations).hasValue(0);
        assertThat(context.types()).contains("TOOL_INPUT_GUARDRAIL_EVALUATED").doesNotContain("TOOL_STARTED");
    }

    @Test
    void credentialedCapabilitiesRequireBothThePinnedReferenceAndJitAuthority() throws Exception {
        String schema = "{\"type\":\"object\"}";
        ToolId id = new ToolId("core", "calendar", "1.0.0");
        CredentialedToolCallback callback = new CredentialedToolCallback() {
            private final ToolDefinition definition = ToolDefinition.builder().name("calendar_callback")
                    .description("calendar").inputSchema(schema).build();
            @Override public ToolDefinition getToolDefinition() { return definition; }
            @Override public String call(String input, ToolContext context, char[] credentialMaterial) { return "ok"; }
        };
        var adapter = DeterministicFakeGatewayAdapter.builder("direct").model("coding-premium", "model-a").build();
        var gateway = new ModelGateway(GatewayRegistry.of(List.of(new ConfiguredGateway(adapter,
                CapabilityPolicy.DEFAULT, null))), null, null, event -> { }, Clock.systemUTC());
        SpringAiAgentExecutor executor = new SpringAiAgentExecutor(gateway, () -> new ToolCallback[0],
                CapabilityToolCallbackProvider.from(new CapabilityToolCallback(id, callback, "calendar.primary")),
                ObservationRegistry.NOOP, new SpringAiExecutorSettings(Duration.ofSeconds(10), 64, 4));

        RecordingRunContext unbound = credentialContinuation(id, schema);
        assertThat(executor.execute(unbound)).isInstanceOfSatisfying(RunOutcome.Failed.class,
                failure -> assertThat(failure.code()).isEqualTo("CREDENTIAL_REFERENCE_REQUIRED"));

        RecordingRunContext noAuthority = credentialContinuation(id, schema);
        noAuthority.credentialReferences = Map.of("calendar.primary",
                new CredentialRef("vault://workspaces/demo/calendar"));
        assertThat(executor.execute(noAuthority)).isInstanceOfSatisfying(RunOutcome.Failed.class,
                failure -> assertThat(failure.code()).isEqualTo("CREDENTIAL_PROVIDER_UNAVAILABLE"));
    }

    private static RecordingRunContext credentialContinuation(ToolId id, String schema) {
        String hash = com.dvarahq.agentworks.capability.AgentTool.digest(schema);
        RecordingRunContext context = continuation("use calendar", 3, List.of(Map.of("id", "pack", "version", "1",
                "tools", List.of(Map.of("namespace", id.namespace(), "name", id.name(), "version", id.version(),
                        "schemaHash", hash)))));
        var request = new CapabilityResolutionRequest(List.of("pack:1"), Set.of("calendar"), Set.of(), Set.of(),
                Set.of(id), 64, 64);
        var resolution = new CapabilityResolution("capability-resolver/v1",
                List.of(new CapabilityResolution.PackDecision("pack:1", true,
                        CapabilityResolution.PackReason.INCLUDED)),
                List.of(new CapabilityResolution.Decision(id, "pack:1", CapabilityResolution.Disclosure.DETAIL,
                        CapabilityResolution.Reason.INCLUDED, 1, hash)), 8, 8);
        context.resolvedCapabilities = new ResolvedRunCapabilities(request, resolution,
                new CapabilityDisclosure(List.of(), List.of(new CapabilityDisclosure.Detail(id, ToolType.JAVA,
                        "calendar", "calendar detail", schema, hash))));
        return context;
    }

    private static ToolCallback callback(String name, String schema) {
        return new ToolCallback() {
            private final ToolDefinition definition = ToolDefinition.builder().name(name).description(name)
                    .inputSchema(schema).build();
            @Override public ToolDefinition getToolDefinition() { return definition; }
            @Override public String call(String input) { return "ok"; }
        };
    }

    /** Issue #210: even an unexpected recorder-close failure ends the drain span and gives it that failure. */
    @Test
    void aDrainOperationFailureStopsAndErrorsItsObservation() {
        ObservationRegistry registry = ObservationRegistry.create();
        var stopped = new java.util.concurrent.atomic.AtomicBoolean();
        var recorded = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        registry.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
            @Override
            public void onStop(Observation.Context context) {
                if ("agentworks.run.drain".equals(context.getName())) {
                    stopped.set(true);
                    recorded.set(context.getError());
                }
            }

            @Override
            public boolean supportsContext(Observation.Context context) {
                return true;
            }
        });
        Observation drain = Observation.createNotStarted("agentworks.run.drain", registry).start();
        var failure = new IllegalStateException("close failed");

        assertThatThrownBy(() -> SpringAiAgentExecutor.observeDrain(drain, () -> {
            throw failure;
        })).isSameAs(failure);
        assertThat(stopped).isTrue();
        assertThat(recorded).hasValue(failure);
    }

    // Review L1: a checkpoint without a recognized phase is not the restart boundary, and never breaks execution.
    @Test
    void aCheckpointWithoutAPhaseIsNotTheRestartBoundary() throws Exception {
        RecordingRunContext context = continuation("resume me");
        context.checkpointState = JSON.valueToTree(Map.of("progress", 1));

        RunOutcome outcome = executor(ObservationRegistry.NOOP).execute(context);

        assertThat(outcome).isEqualTo(RunOutcome.completed(Map.of("type", "text", "content", "echo(model-a): resume me")));
    }

    private static final class RecordingObservationHandler implements ObservationHandler<Observation.Context> {
        private final List<String> observationNames = new ArrayList<>();
        private String telemetry = "";

        @Override
        public synchronized void onStop(Observation.Context context) {
            observationNames.add(context.getName());
            telemetry += context.getLowCardinalityKeyValues() + " " + context.getHighCardinalityKeyValues();
        }

        synchronized List<String> observationNames() { return List.copyOf(observationNames); }
        synchronized String telemetry() { return telemetry; }

        @Override
        public boolean supportsContext(Observation.Context context) {
            return true;
        }
    }
}
