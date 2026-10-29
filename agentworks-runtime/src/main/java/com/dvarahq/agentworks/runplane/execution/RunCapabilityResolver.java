package com.dvarahq.agentworks.runplane.execution;

import com.dvarahq.agentworks.capability.CapabilityDisclosure;
import com.dvarahq.agentworks.capability.AgentTool;
import com.dvarahq.agentworks.capability.CapabilityPack;
import com.dvarahq.agentworks.capability.CapabilityResolution;
import com.dvarahq.agentworks.capability.CapabilityResolutionRequest;
import com.dvarahq.agentworks.capability.CapabilityRuntimeCatalog;
import com.dvarahq.agentworks.capability.DeterministicCapabilityResolver;
import com.dvarahq.agentworks.capability.PinnedCapabilityPack;
import com.dvarahq.agentworks.capability.ToolId;
import com.dvarahq.agentworks.capability.ToolSummary;
import com.dvarahq.agentworks.domain.run.RunEventType;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import com.dvarahq.agentworks.storage.TenantTransactionTemplate;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reconstructs a version-3 release's capability decision for one continuation and records every captured input and
 * include/exclude explanation. Catalog reads use one short tenant-bound Spring JDBC transaction; the resulting event
 * uses the run engine's existing fenced transaction. No distributed transaction is required: recovery repeats both
 * deterministic operations if the process stops between them.
 */
public final class RunCapabilityResolver {
    private static final Pattern TERM = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private static final int MAX_INTENT_TERMS = 256;
    private static final int MAX_DETAIL_TRIGGERS = 200;

    public record Settings(Set<String> compatibility, int summaryBudgetTokens, int detailBudgetTokens) {
        public static final Settings DEFAULTS = new Settings(Set.of("runtime-v1"), 2_048, 8_192);

        public Settings {
            compatibility = Set.copyOf(Objects.requireNonNull(compatibility, "compatibility"));
            if (compatibility.stream().anyMatch(value -> value == null || value.isBlank() || value.length() > 128)) {
                throw new IllegalArgumentException("invalid runtime compatibility fact");
            }
            if (summaryBudgetTokens < 0 || detailBudgetTokens < 0) {
                throw new IllegalArgumentException("capability token budgets cannot be negative");
            }
        }
    }

    private final TenantTransactionTemplate transactions;
    private final CapabilityRuntimeCatalog catalog;
    private final DeterministicCapabilityResolver resolver;
    private final Settings settings;

    public RunCapabilityResolver(TenantTransactionTemplate transactions, CapabilityRuntimeCatalog catalog,
            DeterministicCapabilityResolver resolver, Settings settings) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    /** Versions 1 and 2 have no exact tool evidence and therefore cannot be reconstructed safely. */
    public Optional<ResolvedRunCapabilities> resolve(RunContext context) {
        Objects.requireNonNull(context, "context");
        List<PinnedCapabilityPack> pinned = context.manifest().pinnedCapabilities();
        if (context.manifest().manifestVersion() < 3) return Optional.empty();
        WorkspaceId workspace = new WorkspaceId(context.pins().workspaceId());
        Set<String> intent = intentTerms(context.input());
        Set<ToolId> triggers = detailTriggers(context.input());
        Set<ToolId> releaseTools = pinned.stream().flatMap(pack -> pack.tools().stream())
                .map(PinnedCapabilityPack.PinnedTool::id).collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!releaseTools.containsAll(triggers)) {
            throw new RunEngineException("CAPABILITY_DETAIL_NOT_PINNED",
                    "a requested capability detail is not pinned by the release");
        }
        Attempt initial = attempt(workspace, pinned, request(pinned, intent, Set.of()));
        emit(context, initial);
        if (triggers.isEmpty()) return Optional.of(initial.result());
        Attempt detailed = attempt(workspace, pinned, request(pinned, intent, triggers));
        emit(context, detailed);
        return Optional.of(detailed.result());
    }

    private CapabilityResolutionRequest request(List<PinnedCapabilityPack> pinned, Set<String> intent,
            Set<ToolId> detailTriggers) {
        return new CapabilityResolutionRequest(pinned.stream().map(PinnedCapabilityPack::identity).toList(), intent,
                Set.of(), settings.compatibility(), detailTriggers, settings.summaryBudgetTokens(),
                settings.detailBudgetTokens());
    }

    private Attempt attempt(WorkspaceId workspace, List<PinnedCapabilityPack> pinned,
            CapabilityResolutionRequest request) {
        return transactions.execute(workspace, () -> {
            CapabilityRuntimeCatalog.Snapshot snapshot = catalog.snapshot(workspace, pinned);
            verifyReleaseEvidence(pinned, snapshot.tools());
            CapabilityResolution resolution = resolver.resolve(request, snapshot.packs(), snapshot.tools());
            List<ToolId> selected = resolution.decisions().stream()
                    .filter(decision -> decision.disclosure() == CapabilityResolution.Disclosure.DETAIL)
                    .map(CapabilityResolution.Decision::tool).toList();
            List<AgentTool> details = selected.isEmpty() ? List.of() : catalog.details(workspace, selected);
            CapabilityDisclosure disclosure;
            try {
                disclosure = CapabilityDisclosure.from(resolution, snapshot.tools(), details);
            } catch (IllegalArgumentException | NullPointerException drift) {
                throw new RunEngineException("CAPABILITY_SCHEMA_DRIFT",
                        "selected capability detail no longer matches release evidence");
            }
            return new Attempt(snapshot, new ResolvedRunCapabilities(request, resolution, disclosure));
        });
    }

    private static void emit(RunContext context, Attempt attempt) {
        context.emit(List.of(DurableRunEngine.EventDraft.of(RunEventType.CAPABILITY_RESOLVED,
                event(attempt.result().request(), attempt.snapshot(), attempt.result().resolution()))));
    }

    private static void verifyReleaseEvidence(List<PinnedCapabilityPack> pinned, List<ToolSummary> tools) {
        Map<ToolId, PinnedCapabilityPack.PinnedTool> expected = new LinkedHashMap<>();
        for (PinnedCapabilityPack pack : pinned) {
            for (PinnedCapabilityPack.PinnedTool tool : pack.tools()) expected.putIfAbsent(tool.id(), tool);
        }
        for (ToolSummary tool : tools) {
            PinnedCapabilityPack.PinnedTool evidence = expected.get(tool.id());
            if (evidence != null && !evidence.schemaHash().equals(tool.schemaHash())) {
                throw new RunEngineException("CAPABILITY_SCHEMA_DRIFT",
                        "tool schema no longer matches release evidence for " + tool.id().value());
            }
            if (evidence != null && evidence.effect().isPresent() && !evidence.effect().get().equals(tool.effect())) {
                throw new RunEngineException("CAPABILITY_EFFECT_DRIFT",
                        "tool effect no longer matches release evidence for " + tool.id().value());
            }
        }
    }

    private static ResolutionEvent event(CapabilityResolutionRequest request,
            CapabilityRuntimeCatalog.Snapshot snapshot, CapabilityResolution resolution) {
        List<PackInput> packs = snapshot.packs().stream().sorted(Comparator.comparing(CapabilityPack::identity))
                .map(pack -> new PackInput(pack.identity(), pack.state().name(),
                        pack.tools().stream().map(ToolId::value).sorted().toList())).toList();
        List<ToolInput> tools = snapshot.tools().stream().sorted(Comparator.comparing(ToolSummary::id))
                .map(tool -> new ToolInput(tool.id().value(), tool.type().name(), tool.summary(), tool.schemaHash(),
                        tool.tags().stream().sorted().toList(), tool.compatibility().stream().sorted().toList(),
                        tool.summaryTokens(), tool.detailTokens(), tool.availability().name())).toList();
        RequestInput input = new RequestInput(request.packIds(), request.intentTerms().stream().sorted().toList(),
                request.policyDenied().stream().map(ToolId::value).sorted().toList(),
                request.compatibility().stream().sorted().toList(),
                request.detailTriggers().stream().map(ToolId::value).sorted().toList(), request.summaryBudgetTokens(),
                request.detailBudgetTokens());
        return new ResolutionEvent(resolution.resolverVersion(), input, packs, tools, resolution.packs(),
                resolution.decisions(), resolution.summaryTokens(), resolution.detailTokens());
    }

    static Set<String> intentTerms(JsonNode input) {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        collect(input, terms);
        return Set.copyOf(terms);
    }

    static Set<ToolId> detailTriggers(JsonNode input) {
        if (input == null || !input.isObject() || !input.has("capabilityDetails")) return Set.of();
        JsonNode requested = input.get("capabilityDetails");
        if (!requested.isArray() || requested.size() > MAX_DETAIL_TRIGGERS) {
            throw invalidDetails();
        }
        LinkedHashSet<ToolId> triggers = new LinkedHashSet<>();
        try {
            for (JsonNode value : requested.values()) {
                if (!value.isString()) throw invalidDetails();
                String[] parts = value.stringValue().split(":", -1);
                if (parts.length != 3 || !triggers.add(new ToolId(parts[0], parts[1], parts[2]))) {
                    throw invalidDetails();
                }
            }
        } catch (IllegalArgumentException invalid) {
            throw invalidDetails();
        }
        return Set.copyOf(triggers);
    }

    private static RunEngineException invalidDetails() {
        return new RunEngineException("INVALID_CAPABILITY_DETAILS",
                "capabilityDetails must contain at most 200 unique exact namespace:name:version identities");
    }

    private static void collect(JsonNode node, LinkedHashSet<String> terms) {
        if (node == null || terms.size() >= MAX_INTENT_TERMS) return;
        if (node.isString()) {
            Matcher matcher = TERM.matcher(node.stringValue().toLowerCase(java.util.Locale.ROOT));
            while (matcher.find() && terms.size() < MAX_INTENT_TERMS) terms.add(matcher.group());
        } else if (node.isArray()) {
            for (JsonNode item : node.values()) collect(item, terms);
        } else if (node.isObject()) {
            List<String> names = new ArrayList<>(node.propertyNames());
            names.sort(null);
            for (String name : names) collect(node.get(name), terms);
        }
    }

    private record RequestInput(List<String> packIds, List<String> intentTerms, List<String> policyDenied,
            List<String> compatibility, List<String> detailTriggers, int summaryBudgetTokens, int detailBudgetTokens) {}
    private record PackInput(String identity, String state, List<String> tools) {}
    private record ToolInput(String identity, String type, String summary, String schemaHash, List<String> tags,
            List<String> compatibility, int summaryTokens, int detailTokens, String state) {}
    private record ResolutionEvent(String resolverVersion, RequestInput request, List<PackInput> catalogPacks,
            List<ToolInput> catalogTools, List<CapabilityResolution.PackDecision> packs,
            List<CapabilityResolution.Decision> decisions, int summaryTokens, int detailTokens) {}
    private record Attempt(CapabilityRuntimeCatalog.Snapshot snapshot, ResolvedRunCapabilities result) {}
}
