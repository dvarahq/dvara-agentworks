package com.dvarahq.agentworks.agent.springai;

import com.dvarahq.agentworks.domain.run.RunEventType;
import com.dvarahq.agentworks.context.CompiledContext;
import com.dvarahq.agentworks.context.ContextDecision;
import com.dvarahq.agentworks.gateway.GatewayException;
import com.dvarahq.agentworks.gateway.ModelResponse;
import com.dvarahq.agentworks.gateway.ModelUsage;
import com.dvarahq.agentworks.gateway.PinnedModel;
import com.dvarahq.agentworks.runplane.execution.DurableRunEngine.EventDraft;
import com.dvarahq.agentworks.runplane.execution.RunContext;
import com.dvarahq.agentworks.runplane.execution.RunEngineException;
import com.dvarahq.agentworks.runplane.execution.RunEventCodec;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Writes one continuation's normalized model and tool events through its {@link RunContext}, so every event is a
 * durable run event under the worker's fenced lease. Each event row carries the run, release, deployment, revision,
 * cohort, and trace identity; the payload adds the pinned model identity and a span shared by one model call.
 *
 * <p>Model and tool events can originate on different threads (the model stream's virtual thread; Reactor runs
 * streamed tool calls on its own scheduler), and the lease holder's write token is ordered, so all writes are
 * serialized here, each one holding {@link #writes} for its whole duration. Prompts and tool arguments are recorded
 * as digests and sizes, never as text; model output is recorded as content, because it is the run's result and
 * reconnectable streaming (#35) replays it. A write failure (for example {@code STALE_LEASE} after a cancellation, or
 * {@code LIMIT_EXCEEDED}) propagates, wrapped in {@link RecordingFailedException}, and stops the continuation.
 *
 * <p>Batches never split a UTF-16 surrogate pair. Streamed text that could never be stored (NUL, or an unpaired
 * surrogate) ends the exchange with {@code OUTPUT_INVALID} before it is written.
 *
 * <p>{@link #close(Duration)} ends the recorder: it waits, bounded, for a write already in progress and refuses every
 * later write, so no write under the lease can race the continuation's own final outcome.
 *
 * <p>This object belongs to exactly one continuation; the executor that creates it keeps no reference to it.
 */
final class DurableRunRecorder {
    private final RunContext context;
    private final int chunkBatchChars;
    private final ReentrantLock writes = new ReentrantLock();
    private volatile boolean closed;
    // Guarded by writes.
    private final StringBuilder pending = new StringBuilder();
    private long pendingFrom = -1;
    private long pendingTo = -1;
    private String pendingStep;
    private String pendingSpan;
    /** The stream so far ends on a high surrogate, whose low half must start the next delta. */
    private boolean awaitingLow;

    DurableRunRecorder(RunContext context, int chunkBatchChars) {
        this.context = Objects.requireNonNull(context, "context");
        this.chunkBatchChars = chunkBatchChars;
    }

    RunContext context() {
        return context;
    }

    /** How {@link #close(Duration)} ended. */
    enum Drain {
        /** No write was in progress, or it finished in time. */
        DRAINED,
        /** A write was still in progress at the timeout. */
        WRITE_IN_PROGRESS,
        /** This thread was interrupted while it waited. */
        INTERRUPTED
    }

    /**
     * Stops recording. It waits at most {@code timeout} for a write in progress to finish; from then on every write is
     * refused. Anything but {@link Drain#DRAINED} means the caller must treat its claim as lost.
     */
    Drain close(Duration timeout) {
        closed = true;
        try {
            if (!writes.tryLock(timeout.toMillis(), TimeUnit.MILLISECONDS)) return Drain.WRITE_IN_PROGRESS;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Drain.INTERRUPTED;
        }
        writes.unlock();
        return Drain.DRAINED;
    }

    void modelRequested(String stepId, String spanId, PinnedModel model, int round, int messages, int tools,
            String responseFormat, String requestDigest) {
        write(() -> {
            Map<String, Object> payload = model(stepId, model);
            payload.put("round", round);
            payload.put("messages", messages);
            payload.put("tools", tools);
            payload.put("responseFormat", responseFormat);
            payload.put("requestDigest", requestDigest);
            // The call is charged before the provider is contacted, so the engine refuses a call over the run's pinned
            // model-call limit (LIMIT_EXCEEDED) instead of paying for it first; a cancelled call stays counted.
            payload.put("usage", Map.of("modelCalls", 1));
            emit(RunEventType.MODEL_REQUESTED, payload, spanId);
        });
    }

    /**
     * Buffers one streamed delta. A full batch, another step, or a gap in sequence is flushed as one chunk event, so
     * the stored chunks concatenate to the streamed content in order. A batch never ends between the two halves of a
     * surrogate pair (it may exceed the batch size by one character to keep a pair whole). An empty delta carries
     * nothing and is skipped.
     */
    void chunk(String stepId, String spanId, long sequence, String delta) {
        write(() -> {
            if (delta.isEmpty()) return;
            if (pendingStep != null && (!pendingStep.equals(stepId) || sequence != pendingTo + 1)) doFlush();
            storable(delta);
            int offset = 0;
            while (offset < delta.length()) {
                if (pendingStep == null) {
                    pendingStep = stepId;
                    pendingSpan = spanId;
                    pendingFrom = sequence;
                }
                int take = Math.min(delta.length() - offset, Math.max(1, chunkBatchChars - pending.length()));
                if (Character.isHighSurrogate(delta.charAt(offset + take - 1)) && offset + take < delta.length()) {
                    // Keep the pair together: take its low half too.
                    take++;
                }
                pending.append(delta, offset, offset + take);
                pendingTo = sequence;
                offset += take;
                // A pair may also straddle two deltas: never flush on a trailing high surrogate.
                if (pending.length() >= chunkBatchChars && !Character.isHighSurrogate(pending.charAt(pending.length() - 1))) {
                    doFlush();
                }
            }
        });
    }

    /**
     * Rejects text that could never be stored: NUL, or a surrogate without its pair. A pair may straddle two deltas,
     * so whether the stream ended on a high surrogate carries over to the next delta.
     */
    private void storable(String delta) {
        boolean awaiting = awaitingLow;
        for (int index = 0; index < delta.length(); index++) {
            char c = delta.charAt(index);
            boolean valid;
            if (c == '\u0000') {
                valid = false;
            } else if (awaiting) {
                valid = Character.isLowSurrogate(c);
                awaiting = false;
            } else {
                valid = !Character.isLowSurrogate(c);
                awaiting = Character.isHighSurrogate(c);
            }
            if (!valid) {
                throw new OutputRejectedException("OUTPUT_INVALID",
                        "the model output contains NUL or an unpaired surrogate, which cannot be stored");
            }
        }
        awaitingLow = awaiting;
    }

    void flush() {
        write(this::doFlush);
    }

    /** Records the complete content-free context decision set before the model can be contacted. */
    void contextCompiled(CompiledContext compiled) {
        write(() -> {
            emit(RunEventType.CONTEXT_BUILD_STARTED, Map.of("compiler", "basic-v1"), null);
            for (ContextDecision decision : compiled.decisions()) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("itemId", decision.itemId());
                payload.put("itemType", decision.type().name());
                payload.put("action", decision.action().name());
                payload.put("reason", decision.reason());
                payload.put("estimatedTokens", decision.estimatedTokens());
                payload.put("sourceType", decision.provenance().sourceType());
                payload.put("sourceId", decision.provenance().sourceId());
                payload.put("contentDigest", decision.provenance().contentDigest());
                payload.put("trust", decision.provenance().trust());
                payload.put("classification", decision.classification().name());
                RunEventType type = decision.action() == ContextDecision.Action.INCLUDED
                        ? RunEventType.CONTEXT_ITEM_INCLUDED : RunEventType.CONTEXT_ITEM_EXCLUDED;
                emit(type, payload, null);
            }
            if (compiled.compacted()) {
                emit(RunEventType.CONTEXT_COMPACTED, Map.of("strategy", "sliding-window"), null);
            }
            emit(RunEventType.CONTEXT_CACHE_LAYOUT_RECORDED, Map.of("stableCacheHit", compiled.stableCacheHit(),
                    "stablePrefixDigest", RunEventCodec.sha256(compiled.stablePrefix()),
                    "dynamicDigest", RunEventCodec.sha256(compiled.dynamicContent())), null);
            emit(RunEventType.CONTEXT_COMPILED, Map.of("estimatedTokens", compiled.estimatedTokens(),
                    "decisionCount", compiled.decisions().size(), "compacted", compiled.compacted()), null);
        });
    }

    void modelResponded(String stepId, String spanId, PinnedModel model, ModelResponse response) {
        write(() -> {
            flushTerminal();
            PinnedModel servedRoute = new PinnedModel(model.workspace(), model.release(), response.pinned(),
                    model.resilience());
            Map<String, Object> payload = model(stepId, servedRoute);
            payload.put("requestedGatewayId", model.identity().gatewayId());
            payload.put("requestedProviderModel", model.identity().providerModel());
            payload.put("fallback", !model.identity().equals(response.pinned()));
            payload.put("finishReason", response.finishReason().name());
            payload.put("servedModel", response.served().model());
            payload.put("chunks", response.chunks());
            // The digest and length describe the text the chunks store, so they verify against the chunks (#181): half
            // a surrogate pair left at the end is dropped from both, exactly as flushTerminal drops it from the chunks.
            String stored = storedText(response.content());
            payload.put("contentLength", stored.length());
            payload.put("contentDigest", RunEventCodec.sha256(stored));
            List<String> toolCalls = new ArrayList<>();
            response.toolCalls().forEach(call -> toolCalls.add(call.name()));
            payload.put("toolCalls", toolCalls);
            // Provider call identities are safe invocation provenance. Arguments remain represented only by the
            // separately recorded digest and are never copied into durable model-response evidence.
            payload.put("providerToolCallIds", response.toolCalls().stream().map(call -> call.id()).toList());
            payload.put("degradations", response.degradations().stream().map(Enum::name).toList());
            payload.put("usage", usage(response.usage()));
            payload.put("usageStatus", response.usage().status().name());
            Map<String, Object> usageDetails = new LinkedHashMap<>();
            if (response.usage().totalTokens() != null) usageDetails.put("totalTokens", response.usage().totalTokens());
            if (response.usage().cachedInputTokens() != null) {
                usageDetails.put("cachedInputTokens", response.usage().cachedInputTokens());
            }
            if (response.usage().reasoningTokens() != null) {
                usageDetails.put("reasoningTokens", response.usage().reasoningTokens());
            }
            if (response.usage().toolTokens() != null) usageDetails.put("toolTokens", response.usage().toolTokens());
            if (response.usage().cost() != null) usageDetails.put("cost", Map.of(
                    "amount", response.usage().cost().amount().toPlainString(),
                    "currency", response.usage().cost().currency(), "catalog", response.usage().cost().catalog(),
                    "version", response.usage().cost().version()));
            payload.put("usageDetails", usageDetails);
            emit(RunEventType.MODEL_RESPONSE_RECEIVED, payload, spanId);
        });
    }

    void modelFailed(String stepId, String spanId, PinnedModel model, GatewayException failure) {
        write(() -> {
            flushTerminal();
            Map<String, Object> payload = model(stepId, model);
            payload.put("code", failure.code().name());
            emit(RunEventType.MODEL_FAILED, payload, spanId);
        });
    }

    void toolRequested(String stepId, String name, String argumentsDigest) {
        write(() -> {
            doFlush();
            emit(RunEventType.TOOL_REQUESTED, Map.of("stepId", stepId, "name", name, "argumentsDigest", argumentsDigest,
                    "usage", Map.of("toolCalls", 1)), null);
        });
    }

    void toolStarted(String stepId, String name) {
        write(() -> emit(RunEventType.TOOL_STARTED, Map.of("stepId", stepId, "name", name), null));
    }

    void toolInputSchemaValidated(String stepId, String name, ToolInputSchema.Decision decision) {
        write(() -> emit(RunEventType.TOOL_INPUT_SCHEMA_VALIDATED, Map.of("stepId", stepId, "name", name,
                "decision", decision.verdict().name(), "reason", decision.reason(),
                "violations", decision.violations(), "schemaHash", decision.schemaHash()), null));
    }

    void toolPolicyEvaluated(String stepId, String name, ToolPolicyGate.Decision decision) {
        write(() -> {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("stepId", stepId);
            payload.put("name", name);
            payload.put("decision", decision.verdict().name());
            payload.put("reason", decision.reason());
            payload.put("intent", decision.intent());
            payload.put("autonomyProfile", decision.autonomyProfile());
            decision.rememberedPermission().ifPresent(permission -> {
                Map<String, Object> evidence = new LinkedHashMap<>();
                evidence.put("decision", permission.verdict());
                evidence.put("reason", permission.reason());
                permission.ruleId().ifPresent(id -> evidence.put("ruleId", id.toString()));
                permission.ruleVersion().ifPresent(version -> evidence.put("ruleVersion", version));
                evidence.put("capability", permission.capability());
                evidence.put("resource", permission.effect().resource());
                evidence.put("action", permission.effect().action());
                evidence.put("risk", permission.effect().risk().name());
                payload.put("rememberedPermission", evidence);
            });
            emit(RunEventType.TOOL_POLICY_EVALUATED, payload, null);
        });
    }

    void toolInputGuardrailEvaluated(String stepId, String name, ToolInputGuardrailGate.Decision decision) {
        write(() -> emit(RunEventType.TOOL_INPUT_GUARDRAIL_EVALUATED, Map.of("stepId", stepId, "name", name,
                "decision", decision.outcome().name(), "reason", decision.reason(),
                "guardrail", decision.guardrail(), "risk", decision.risk()), null));
    }

    void toolOutputGuardrailEvaluated(String stepId, String name, ToolOutputGuardrailGate.Decision decision) {
        write(() -> emit(RunEventType.TOOL_OUTPUT_GUARDRAIL_EVALUATED, Map.of("stepId", stepId, "name", name,
                "decision", decision.outcome().name(), "reason", decision.reason(),
                "guardrail", decision.guardrail(), "risk", decision.risk()), null));
    }

    void toolCompleted(String stepId, String name, String result) {
        write(() -> emit(RunEventType.TOOL_COMPLETED, Map.of("stepId", stepId, "name", name, "resultLength",
                result.length(), "resultDigest", RunEventCodec.sha256(result)), null));
    }

    void toolCompleted(String stepId, String name, String result, String effectKey, boolean replayed) {
        write(() -> emit(RunEventType.TOOL_COMPLETED, Map.of("stepId", stepId, "name", name, "resultLength",
                result.length(), "resultDigest", RunEventCodec.sha256(result), "effectKey", effectKey,
                "effectReplayed", replayed), null));
    }

    void toolFailed(String stepId, String name, Throwable failure) {
        write(() -> emit(RunEventType.TOOL_FAILED, Map.of("stepId", stepId, "name", name, "error",
                failure.getClass().getName()), null));
    }

    /**
     * Pauses the run at this safe boundary if an operator's request is still pending (#183): under the write lock, so
     * it is ordered with every stream write of this continuation, after flushing buffered chunks. Its failures are
     * marked as this recorder's own, like every other write, so they leave the run for recovery.
     */
    boolean pauseIfRequested() {
        if (!context.pauseRequested()) return false;
        java.util.concurrent.atomic.AtomicBoolean paused = new java.util.concurrent.atomic.AtomicBoolean();
        write(() -> {
            doFlush();
            try {
                paused.set(context.pauseIfRequested());
            } catch (RunEngineException | org.springframework.dao.DataAccessException
                     | org.springframework.transaction.TransactionException
                     | com.dvarahq.agentworks.storage.lifecycle.LifecycleRetryExhaustedException failure) {
                throw new RecordingFailedException(failure);
            }
        });
        return paused.get();
    }

    /** Runs one write under the lock; after {@link #close(Duration)} every write is refused. */
    private void write(Runnable write) {
        writes.lock();
        try {
            if (closed) {
                throw new RecordingFailedException(new RunEngineException("STALE_LEASE",
                        "the continuation stopped recording"));
            }
            write.run();
        } finally {
            writes.unlock();
        }
    }

    /**
     * Flushes at the end of a model call. Half a surrogate pair left at the end can never be completed, so it is dropped
     * from the stored chunks (the output check reports the unpaired half as {@code OUTPUT_INVALID}), and the buffered
     * chunks and the call's final event are always recorded. Guarded by {@link #writes}.
     */
    private void flushTerminal() {
        if (pendingStep != null && Character.isHighSurrogate(pending.charAt(pending.length() - 1))) {
            pending.setLength(pending.length() - 1);
            if (pending.isEmpty()) {
                pendingStep = null;
                pendingSpan = null;
            }
        }
        awaitingLow = false;
        doFlush();
    }

    /** {@code content} without half a surrogate pair at its end, which the stored chunks never carry. */
    static String storedText(String content) {
        return !content.isEmpty() && Character.isHighSurrogate(content.charAt(content.length() - 1))
                ? content.substring(0, content.length() - 1) : content;
    }

    /** Guarded by {@link #writes}. */
    private void doFlush() {
        if (pendingStep == null) return;
        if (Character.isHighSurrogate(pending.charAt(pending.length() - 1))) {
            // Its low half can no longer arrive in this batch: the step or stream ended on half a pair.
            throw new OutputRejectedException("OUTPUT_INVALID",
                    "the model output ends with an unpaired surrogate, which cannot be stored");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("stepId", pendingStep);
        payload.put("fromSequence", pendingFrom);
        payload.put("toSequence", pendingTo);
        payload.put("delta", pending.toString());
        String span = pendingSpan;
        pending.setLength(0);
        pendingStep = null;
        pendingSpan = null;
        emit(RunEventType.MODEL_STREAM_CHUNK, payload, span);
    }

    private static Map<String, Object> model(String stepId, PinnedModel model) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("stepId", stepId);
        payload.put("role", model.identity().role().manifestKey());
        payload.put("alias", model.identity().alias());
        payload.put("gatewayId", model.identity().gatewayId());
        payload.put("gatewayType", model.identity().gatewayType());
        payload.put("providerModel", model.identity().providerModel());
        return payload;
    }

    /** The tokens a response reported; the call itself was charged when it was requested. */
    private static Map<String, Object> usage(ModelUsage usage) {
        Map<String, Object> delta = new LinkedHashMap<>();
        if (usage.inputTokens() != null) delta.put("inputTokens", usage.inputTokens());
        if (usage.outputTokens() != null) delta.put("outputTokens", usage.outputTokens());
        if (usage.cost() != null && "USD".equalsIgnoreCase(usage.cost().currency())) {
            delta.put("estimatedCostUsd", usage.cost().amount().toPlainString());
        }
        return delta;
    }

    /**
     * The only call into the engine. Its engine, database, and transaction failures are marked as this recorder's own
     * (#181), so the executor can tell them from the same exception types thrown by a tool.
     */
    private void emit(RunEventType type, Map<String, Object> payload, String spanId) {
        try {
            context.emit(List.of(new EventDraft(type, payload, spanId)));
        } catch (RunEngineException | org.springframework.dao.DataAccessException
                 | org.springframework.transaction.TransactionException
                 | com.dvarahq.agentworks.storage.lifecycle.LifecycleRetryExhaustedException failure) {
            throw new RecordingFailedException(failure);
        }
    }
}
