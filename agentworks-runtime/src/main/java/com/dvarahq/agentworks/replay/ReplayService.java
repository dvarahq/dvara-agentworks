package com.dvarahq.agentworks.replay;

import com.dvarahq.agentworks.domain.run.RunEffect;
import com.dvarahq.agentworks.domain.run.RunEventType;
import com.dvarahq.agentworks.domain.run.RunRecord;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import com.dvarahq.agentworks.runplane.execution.DurableRunEngine;
import com.dvarahq.agentworks.runplane.execution.RunEventCodec;
import com.dvarahq.agentworks.runplane.execution.RunEventCodec.DecodedEvent;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.NullNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Safe replay over verified immutable run history. Planning is read-only. EXACT returns only recorded state;
 * MODEL_REPLAY calls a model adapter only after compatibility succeeds, and never gives that adapter a live effect
 * channel. Missing redacted outcomes are incompatibilities, not permission to repeat an effect.
 */
public final class ReplayService {
    public static final String EFFECT_POLICY = "RECORDED_RESULTS_ONLY";

    public record Provenance(UUID sourceRunId, UUID sourceReleaseId, UUID sourceDeploymentId,
            UUID sourceRevisionId, UUID sourceCohortId, String sourceEventDigest, long sourceEventSequence) {}

    public record Substitution(String kind, String key, String status, String resultDigest) {}

    public record Diagnostic(String code, String message) {}

    public record ReplayPlan(UUID replayId, ReplayMode mode, Provenance provenance, String compatibility,
            String effectPolicy, boolean liveEffectsAllowed, List<Substitution> substitutions,
            List<Diagnostic> diagnostics, boolean executable, Instant plannedAt) {
        public ReplayPlan {
            substitutions = List.copyOf(substitutions);
            diagnostics = List.copyOf(diagnostics);
        }
    }

    public record ReplayResult(ReplayPlan plan, JsonNode output) {}

    /** Complete verified source supplied to a model-only adapter. */
    public record ModelInvocation(ReplayPlan plan, DurableRunEngine.ReplaySource source,
            Map<String, String> recordedResults) {
        public ModelInvocation {
            recordedResults = Map.copyOf(recordedResults);
        }
    }

    private record Collected(List<Substitution> substitutions, Map<String, String> results) {}

    private final DurableRunEngine engine;
    private final ReplayModelExecutor models;
    private final RecordedOutcomeReader outcomes;

    public ReplayService(DurableRunEngine engine, ReplayModelExecutor models) {
        this(engine, models, RecordedOutcomeReader.NONE);
    }

    public ReplayService(DurableRunEngine engine, ReplayModelExecutor models, RecordedOutcomeReader outcomes) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.models = models;
        this.outcomes = Objects.requireNonNull(outcomes, "outcomes");
    }

    public ReplayPlan plan(WorkspaceId workspace, UUID sourceRunId, ReplayMode mode) {
        DurableRunEngine.ReplaySource source = engine.replaySource(workspace, sourceRunId);
        return plan(source, mode, substitutions(source));
    }

    public ReplayResult replay(WorkspaceId workspace, UUID sourceRunId, ReplayMode mode) {
        DurableRunEngine.ReplaySource source = engine.replaySource(workspace, sourceRunId);
        Collected collected = substitutions(source);
        ReplayPlan plan = plan(source, mode, collected);
        if (!plan.executable()) {
            Diagnostic first = plan.diagnostics().getFirst();
            throw new ReplayException(first.code(), first.message());
        }
        JsonNode output;
        if (mode == ReplayMode.EXACT) {
            output = exactOutput(source.history());
        } else {
            if (models == null) {
                throw new ReplayException("MODEL_REPLAY_UNAVAILABLE",
                        "this node has no model replay executor; no model or effect was invoked");
            }
            output = Objects.requireNonNull(models.execute(new ModelInvocation(plan, source, collected.results())),
                    "model replay output");
        }
        return new ReplayResult(plan, output);
    }

    private ReplayPlan plan(DurableRunEngine.ReplaySource source, ReplayMode mode, Collected collected) {
        RunRecord run = source.run();
        var pins = run.pins();
        List<Substitution> substitutions = collected.substitutions();
        List<Diagnostic> diagnostics = new ArrayList<>();
        if (!run.lifecycle().terminal()) {
            diagnostics.add(new Diagnostic("SOURCE_RUN_NOT_TERMINAL", "replay requires a terminal source run"));
        }
        if (mode == ReplayMode.EXACT && terminalEvent(source.history()).isEmpty()) {
            diagnostics.add(new Diagnostic("RECORDED_OUTPUT_UNAVAILABLE",
                    "the source history has no recorded terminal output"));
        }
        if (mode == ReplayMode.MODEL_REPLAY) {
            if (source.invoker().isEmpty()) diagnostics.add(new Diagnostic("INVOKER_UNAVAILABLE",
                    "the source run has no recorded invocation identity"));
            if (source.agentVersion().isEmpty()) diagnostics.add(new Diagnostic("AGENT_VERSION_UNAVAILABLE",
                    "the source run's pinned AgentVersion is unavailable"));
            boolean unavailableContext = source.history().stream()
                    .filter(event -> event.type() == RunEventType.CONTEXT_ITEM_INCLUDED)
                    .map(event -> event.payload().path("itemType").asString())
                    .anyMatch(type -> !"SYSTEM_INSTRUCTION".equals(type) && !"USER_INPUT".equals(type));
            if (unavailableContext || !source.manifest().pinnedCapabilities().isEmpty()
                    || !source.manifest().pinnedSkills().isEmpty()) diagnostics.add(new Diagnostic(
                            "RECORDED_CONTEXT_UNAVAILABLE",
                            "the source used retained or compiled context whose content is unavailable"));
            substitutions.stream().filter(value -> "UNAVAILABLE".equals(value.status())).findFirst().ifPresent(value ->
                    diagnostics.add(new Diagnostic("RECORDED_RESULT_UNAVAILABLE", "recorded "
                            + value.kind().toLowerCase(java.util.Locale.ROOT) + " result '" + value.key()
                            + "' is redacted or expired; a live effect will not be used")));
            if (models == null) diagnostics.add(new Diagnostic("MODEL_REPLAY_UNAVAILABLE",
                    "this node has no model replay executor"));
            else diagnostics.addAll(models.diagnostics(source, substitutions));
        }
        Provenance provenance = new Provenance(run.id(), pins.releaseId(), pins.deploymentId(), pins.revisionId(),
                pins.cohortId(), run.summary().lastEventDigest(), run.summary().eventSequence());
        return new ReplayPlan(UUID.randomUUID(), mode, provenance, engine.compatibility(), EFFECT_POLICY, false,
                substitutions, diagnostics, diagnostics.isEmpty(), Instant.now());
    }

    private Collected substitutions(DurableRunEngine.ReplaySource source) {
        Map<String, RunEffect> effects = new LinkedHashMap<>();
        source.effects().forEach(effect -> effects.put(effect.effectKey(), effect));
        Map<String, Substitution> values = new LinkedHashMap<>();
        Map<String, String> results = new LinkedHashMap<>();
        for (DecodedEvent event : source.history()) {
            JsonNode payload = event.payload();
            switch (event.type()) {
                case TOOL_COMPLETED -> {
                    String key = payload.path("effectKey").asString(payload.path("stepId").asString("tool"));
                    RunEffect effect = effects.get(key);
                    String result = effect == null ? null : effect.resultJson();
                    if (result != null) results.put(materialKey("TOOL", key), result);
                    values.put(materialKey("TOOL", key), new Substitution("TOOL", key,
                            result == null ? "UNAVAILABLE" : "RECORDED", payload.path("resultDigest")
                                    .asString(result == null ? null : RunEventCodec.sha256(result))));
                }
                case HUMAN_INTERACTION_RESPONDED, AUTHORIZATION_GRANTED, AUTHORIZATION_FAILED,
                        AUTHORIZATION_REVOKED, HUMAN_INTERACTION_EXPIRED, HUMAN_INTERACTION_CANCELLED -> {
                    String key = payload.path("interactionId").asString("interaction");
                    WorkspaceId workspace = new WorkspaceId(source.run().pins().workspaceId());
                    Optional<String> result = uuid(key).flatMap(id -> outcomes.human(workspace, source.run().id(), id));
                    result.ifPresent(value -> results.put(materialKey("HUMAN", key), value));
                    values.put(materialKey("HUMAN", key), new Substitution("HUMAN", key,
                            result.isPresent() ? "RECORDED" : "UNAVAILABLE",
                            result.map(RunEventCodec::sha256).orElse(null)));
                }
                case OPERATION_COMPLETED, OPERATION_FAILED, OPERATION_CANCELLED, OPERATION_RESOLVED -> {
                    String key = payload.path("operationId").asString("operation");
                    WorkspaceId workspace = new WorkspaceId(source.run().pins().workspaceId());
                    Optional<String> result = uuid(key).flatMap(id -> outcomes.operation(workspace,
                            source.run().id(), id));
                    result.ifPresent(value -> results.put(materialKey("OPERATION", key), value));
                    values.put(materialKey("OPERATION", key), new Substitution("OPERATION", key,
                            result.isPresent() ? "RECORDED" : "UNAVAILABLE",
                            result.map(RunEventCodec::sha256).orElse(null)));
                }
                default -> { }
            }
        }
        return new Collected(List.copyOf(values.values()), Map.copyOf(results));
    }

    public static String materialKey(String kind, String key) {
        return kind + ":" + key;
    }

    private static Optional<UUID> uuid(String value) {
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }

    private static Optional<DecodedEvent> terminalEvent(List<DecodedEvent> history) {
        return history.stream().filter(event -> event.type() == RunEventType.RUN_SUCCEEDED
                || event.type() == RunEventType.RUN_FAILED || event.type() == RunEventType.RUN_CANCELLED)
                .reduce((a, b) -> b);
    }

    private static JsonNode exactOutput(List<DecodedEvent> history) {
        DecodedEvent event = terminalEvent(history).orElseThrow(() ->
                new ReplayException("RECORDED_OUTPUT_UNAVAILABLE", "the source history has no recorded terminal state"));
        if (event.type() == RunEventType.RUN_SUCCEEDED) {
            return Optional.ofNullable(event.payload().get("output")).orElse(NullNode.getInstance());
        }
        tools.jackson.databind.node.ObjectNode state = event.payload().deepCopy();
        state.put("sourceEventType", event.type().name());
        return state;
    }
}
