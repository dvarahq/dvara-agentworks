package com.dvarahq.agentworks.runplane.execution;

import com.dvarahq.agentworks.capability.AgentTool;
import com.dvarahq.agentworks.capability.CapabilityPack;
import com.dvarahq.agentworks.capability.CapabilityRuntimeCatalog;
import com.dvarahq.agentworks.capability.DeterministicCapabilityResolver;
import com.dvarahq.agentworks.capability.PinnedCapabilityPack;
import com.dvarahq.agentworks.capability.ToolId;
import com.dvarahq.agentworks.capability.ToolSummary;
import com.dvarahq.agentworks.capability.ToolType;
import com.dvarahq.agentworks.domain.run.RunEventType;
import com.dvarahq.agentworks.domain.run.RunPins;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import com.dvarahq.agentworks.storage.TenantTransactionTemplate;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RunCapabilityResolverTest {
    private static final String SCHEMA = "{\"type\":\"object\"}";
    private static final ToolId SEARCH = new ToolId("core", "search", "1.0.0");
    private static final UUID WORKSPACE = UUID.randomUUID();

    @Test
    void capturesReproducibleSummaryFirstDecisionBeforeTheExecutorRuns() throws Exception {
        ToolSummary tool = tool(AgentTool.Availability.ACTIVE, AgentTool.digest(SCHEMA));
        PinnedCapabilityPack pinned = pinned(tool.schemaHash());
        RunContext context = context(3, List.of(pinned), "Please search the web");
        RunCapabilityResolver resolver = resolver(new CapabilityRuntimeCatalog.Snapshot(
                List.of(new CapabilityPack("research", "1", CapabilityPack.State.ACTIVE, List.of(SEARCH))),
                List.of(tool)));

        Optional<ResolvedRunCapabilities> resolved = resolver.resolve(context);

        assertThat(resolved).isPresent();
        assertThat(resolved.orElseThrow().disclosure().summaries()).singleElement()
                .extracting(summary -> summary.id()).isEqualTo(SEARCH);
        assertThat(resolved.orElseThrow().disclosure().details()).isEmpty();
        @SuppressWarnings({"rawtypes", "unchecked"})
        ArgumentCaptor<List<DurableRunEngine.EventDraft>> emitted = (ArgumentCaptor) ArgumentCaptor.forClass(List.class);
        verify(context).emit(emitted.capture());
        var events = emitted.getValue();
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo(RunEventType.CAPABILITY_RESOLVED);
            var payload = RunEventCodec.standard().mapper().readTree(
                    RunEventCodec.standard().canonicalPayload(event.payload()));
            assertThat(payload.path("resolverVersion").stringValue()).isEqualTo("capability-resolver/v1");
            assertThat(payload.at("/request/intentTerms").toString()).contains("search", "web");
            assertThat(payload.at("/request/detailTriggers").isEmpty()).isTrue();
            assertThat(payload.at("/catalogTools/0/schemaHash").stringValue()).isEqualTo(tool.schemaHash());
            assertThat(payload.at("/decisions/0/disclosure").stringValue()).isEqualTo("SUMMARY");
            assertThat(payload.at("/decisions/0/reason").stringValue()).isEqualTo("NOT_TRIGGERED");
            assertThat(payload.toString()).doesNotContain(SCHEMA);
        });

        AtomicReference<RunContext> seen = new AtomicReference<>();
        RunOutcome outcome = new CapabilityResolvingRunHandler(candidate -> {
            seen.set(candidate);
            return RunOutcome.completed(null);
        }, resolver).execute(context);
        assertThat(outcome).isInstanceOf(RunOutcome.Completed.class);
        assertThat(seen.get().resolvedCapabilities()).isPresent();
    }

    @Test
    void disabledCapabilitiesAreExplainedAndNeverEnterModelContext() {
        ToolSummary tool = tool(AgentTool.Availability.DISABLED, AgentTool.digest(SCHEMA));
        PinnedCapabilityPack pinned = pinned(tool.schemaHash());
        RunContext context = context(3, List.of(pinned), "search");
        RunCapabilityResolver resolver = resolver(new CapabilityRuntimeCatalog.Snapshot(
                List.of(new CapabilityPack("research", "1", CapabilityPack.State.ACTIVE, List.of(SEARCH))),
                List.of(tool)));

        ResolvedRunCapabilities resolved = resolver.resolve(context).orElseThrow();

        assertThat(resolved.disclosure().summaries()).isEmpty();
        assertThat(resolved.disclosure().details()).isEmpty();
        assertThat(resolved.resolution().decisions()).singleElement()
                .extracting(decision -> decision.reason()).isEqualTo(
                        com.dvarahq.agentworks.capability.CapabilityResolution.Reason.TOOL_DISABLED);
    }

    @Test
    void anExactDurableDetailTriggerLoadsOnlyThatSchemaAfterTheSummaryDecision() throws Exception {
        AgentTool detail = new AgentTool(SEARCH, ToolType.MCP, "search the web", "Detailed search", SCHEMA,
                AgentTool.digest(SCHEMA), effect(), Set.of("web"), Set.of("runtime-v1"), 8, 24,
                AgentTool.Availability.ACTIVE);
        PinnedCapabilityPack pinned = pinned(detail.schemaHash());
        RunContext context = context(3, List.of(pinned), JsonMapper.builder().build().readTree("""
                {"prompt":"Please search the web","capabilityDetails":["core:search:1.0.0"]}
                """));
        CapabilityRuntimeCatalog catalog = new CapabilityRuntimeCatalog() {
            @Override public Snapshot snapshot(WorkspaceId workspace, List<PinnedCapabilityPack> evidence) {
                return new Snapshot(List.of(new CapabilityPack("research", "1", CapabilityPack.State.ACTIVE,
                        List.of(SEARCH))), List.of(detail.metadata()));
            }
            @Override public List<AgentTool> details(WorkspaceId workspace, List<ToolId> selected) {
                if (selected.isEmpty()) return List.of();
                assertThat(selected).containsExactly(SEARCH);
                return List.of(detail);
            }
        };
        RunCapabilityResolver resolver = resolver(catalog);

        ResolvedRunCapabilities resolved = resolver.resolve(context).orElseThrow();

        assertThat(resolved.disclosure().summaries()).isEmpty();
        assertThat(resolved.disclosure().details()).singleElement().satisfies(selected -> {
            assertThat(selected.id()).isEqualTo(SEARCH);
            assertThat(selected.inputSchema()).isEqualTo(SCHEMA);
        });
        @SuppressWarnings({"rawtypes", "unchecked"})
        ArgumentCaptor<List<DurableRunEngine.EventDraft>> emitted =
                (ArgumentCaptor) ArgumentCaptor.forClass(List.class);
        verify(context, times(2)).emit(emitted.capture());
        var finalPayload = RunEventCodec.standard().mapper().readTree(RunEventCodec.standard()
                .canonicalPayload(emitted.getAllValues().getLast().getFirst().payload()));
        assertThat(finalPayload.at("/request/detailTriggers/0").stringValue()).isEqualTo(SEARCH.value());
        assertThat(finalPayload.at("/decisions/0/disclosure").stringValue()).isEqualTo("DETAIL");
        assertThat(finalPayload.toString()).doesNotContain(SCHEMA);
    }

    @Test
    void schemaDriftFailsBeforeAnyResolutionEventAndLegacyManifestsAreLeftUntouched() {
        RunContext drift = context(3, List.of(pinned("sha256:" + "0".repeat(64))), "search");
        RunCapabilityResolver resolver = resolver(new CapabilityRuntimeCatalog.Snapshot(
                List.of(new CapabilityPack("research", "1", CapabilityPack.State.ACTIVE, List.of(SEARCH))),
                List.of(tool(AgentTool.Availability.ACTIVE, AgentTool.digest(SCHEMA)))));

        assertThatThrownBy(() -> resolver.resolve(drift)).isInstanceOf(RunEngineException.class)
                .extracting(failure -> ((RunEngineException) failure).code()).isEqualTo("CAPABILITY_SCHEMA_DRIFT");
        verify(drift, never()).emit(any());

        RunContext legacy = context(2, List.of(), "search");
        assertThat(resolver.resolve(legacy)).isEmpty();
        verify(legacy, never()).emit(any());
    }

    @Test
    void versionFiveEffectDriftFailsBeforeAnyResolutionEvent() {
        var different = new com.dvarahq.agentworks.capability.ToolEffect("other-resource", "read",
                com.dvarahq.agentworks.domain.security.PermissionRisk.LOW);
        PinnedCapabilityPack pack = new PinnedCapabilityPack("research", "1", List.of(
                new PinnedCapabilityPack.PinnedTool(SEARCH, AgentTool.digest(SCHEMA), different)));
        RunContext drift = context(5, List.of(pack), "search");
        RunCapabilityResolver resolver = resolver(new CapabilityRuntimeCatalog.Snapshot(
                List.of(new CapabilityPack("research", "1", CapabilityPack.State.ACTIVE, List.of(SEARCH))),
                List.of(tool(AgentTool.Availability.ACTIVE, AgentTool.digest(SCHEMA)))));

        assertThatThrownBy(() -> resolver.resolve(drift)).isInstanceOf(RunEngineException.class)
                .extracting(failure -> ((RunEngineException) failure).code()).isEqualTo("CAPABILITY_EFFECT_DRIFT");
        verify(drift, never()).emit(any());
    }

    @Test
    void malformedOrUnpinnedDetailRequestsFailBeforeCatalogReadsOrEvents() throws Exception {
        PinnedCapabilityPack pinned = pinned(AgentTool.digest(SCHEMA));
        RunCapabilityResolver resolver = resolver(new CapabilityRuntimeCatalog.Snapshot(List.of(), List.of()));
        RunContext malformed = context(3, List.of(pinned), JsonMapper.builder().build().readTree("""
                {"prompt":"search","capabilityDetails":["not-an-exact-identity"]}
                """));
        RunContext unpinned = context(3, List.of(pinned), JsonMapper.builder().build().readTree("""
                {"prompt":"search","capabilityDetails":["core:other:1.0.0"]}
                """));

        assertThatThrownBy(() -> resolver.resolve(malformed)).isInstanceOfSatisfying(RunEngineException.class,
                failure -> assertThat(failure.code()).isEqualTo("INVALID_CAPABILITY_DETAILS"));
        assertThatThrownBy(() -> resolver.resolve(unpinned)).isInstanceOfSatisfying(RunEngineException.class,
                failure -> assertThat(failure.code()).isEqualTo("CAPABILITY_DETAIL_NOT_PINNED"));
        verify(malformed, never()).emit(any());
        verify(unpinned, never()).emit(any());
    }

    private static RunCapabilityResolver resolver(CapabilityRuntimeCatalog.Snapshot snapshot) {
        return resolver((workspace, pinned) -> snapshot);
    }

    private static RunCapabilityResolver resolver(CapabilityRuntimeCatalog catalog) {
        TenantTransactionTemplate transactions = mock(TenantTransactionTemplate.class);
        when(transactions.execute(eq(new WorkspaceId(WORKSPACE)), any())).thenAnswer(invocation ->
                ((Supplier<?>) invocation.getArgument(1)).get());
        return new RunCapabilityResolver(transactions, catalog, new DeterministicCapabilityResolver(),
                RunCapabilityResolver.Settings.DEFAULTS);
    }

    private static RunContext context(int version, List<PinnedCapabilityPack> pinned, String prompt) {
        return context(version, pinned, JsonMapper.builder().build().valueToTree(prompt));
    }

    private static RunContext context(int version, List<PinnedCapabilityPack> pinned, tools.jackson.databind.JsonNode input) {
        RunContext context = mock(RunContext.class);
        RunPins pins = mock(RunPins.class);
        when(pins.workspaceId()).thenReturn(WORKSPACE);
        when(context.pins()).thenReturn(pins);
        when(context.input()).thenReturn(input);
        ReleaseManifestReader.RunManifest manifest = mock(ReleaseManifestReader.RunManifest.class);
        when(manifest.manifestVersion()).thenReturn(version);
        when(manifest.pinnedCapabilities()).thenReturn(pinned);
        when(context.manifest()).thenReturn(manifest);
        return context;
    }

    private static ToolSummary tool(AgentTool.Availability state, String hash) {
        return new ToolSummary(SEARCH, ToolType.MCP, "search the web", hash, effect(), Set.of("web"), Set.of("runtime-v1"),
                8, 24, state);
    }

    private static PinnedCapabilityPack pinned(String hash) {
        return new PinnedCapabilityPack("research", "1", List.of(new PinnedCapabilityPack.PinnedTool(SEARCH, hash)));
    }

    private static com.dvarahq.agentworks.capability.ToolEffect effect() {
        return new com.dvarahq.agentworks.capability.ToolEffect("web", "read",
                com.dvarahq.agentworks.domain.security.PermissionRisk.LOW);
    }
}
