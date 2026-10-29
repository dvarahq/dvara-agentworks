package com.dvarahq.agentworks.runplane.execution;

import com.dvarahq.agentworks.domain.run.RunBlockReason;
import com.dvarahq.agentworks.domain.run.RunEventType;
import com.dvarahq.agentworks.domain.run.RunLifecycle;
import com.dvarahq.agentworks.domain.run.RunStateMachine;
import com.dvarahq.agentworks.domain.run.RunSummary;
import com.dvarahq.agentworks.domain.run.RunUsage;
import com.dvarahq.agentworks.runplane.execution.RunEventCodec.DecodedEvent;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Rebuilds the run summary deterministically from canonical events. Event history is canonical; the persisted
 * summary must equal this fold, and any illegal transition or unexpected event order in the history is reported as
 * {@code CORRUPT_HISTORY} rather than repaired.
 *
 * <p>Payload conventions written by the engine: transition and checkpoint events carry the active runtime they
 * close as {@code runtimeMillis}; claims carry the new {@code fence}; any event may carry a non-negative
 * {@code usage} delta ({@code inputTokens}, {@code outputTokens}, {@code modelCalls}, {@code toolCalls},
 * {@code estimatedCostUsd}); a failed run carries its {@code code}.
 */
public final class RunProjection {
    public static final String CANCELLED_CODE = "CANCELLED";

    private RunProjection() {}

    public static RunSummary rebuild(List<DecodedEvent> history) {
        return rebuild(history, false);
    }

    /**
     * Rebuilds the summary; {@code admittedBeforeEngine} is true for a run admitted before issue #32, whose creation
     * facts live only on its row, so its history starts with the first transition instead of RUN_CREATED.
     */
    public static RunSummary rebuild(List<DecodedEvent> history, boolean admittedBeforeEngine) {
        RunLifecycle lifecycle = RunLifecycle.CREATED;
        RunBlockReason blockReason = null;
        String terminalCode = null;
        long sequence = 0;
        String head = null;
        long checkpoint = 0;
        long fence = 0;
        RunUsage usage = RunUsage.ZERO;
        Instant startedAt = null;
        Instant completedAt = null;
        for (DecodedEvent event : history) {
            ObjectNode payload = event.payload();
            RunEventType type = event.type();
            boolean first = sequence == 0;
            if (admittedBeforeEngine ? type == RunEventType.RUN_CREATED : first != (type == RunEventType.RUN_CREATED)) {
                throw corrupt(event, "a history starts with exactly one RUN_CREATED");
            }
            if (lifecycle.terminal() && type != RunEventType.OPERATION_RESOLVED) {
                throw corrupt(event, "a terminal run records only post-terminal evidence");
            }
            var target = RunStateMachine.target(type);
            if (target.isPresent()) {
                if (RunStateMachine.eventFor(lifecycle, target.get()).filter(type::equals).isEmpty()) {
                    throw corrupt(event, lifecycle + " cannot move to " + target.get() + " by " + type);
                }
                lifecycle = target.get();
                blockReason = lifecycle == RunLifecycle.BLOCKED ? RunBlockReason.valueOf(text(payload, "reason")) : null;
            } else if (type == RunEventType.RUN_RECOVERED && lifecycle != RunLifecycle.ACTIVE) {
                throw corrupt(event, "only an active run is recovered");
            }
            switch (type) {
                case RUN_ACTIVATED, RUN_RECOVERED -> {
                    fence = payload.path("fence").longValue();
                    if (startedAt == null) startedAt = event.raw().createdAt();
                }
                // A control write that revokes a lease (cancellation, failure, or an expired lease's pause, #183) advances
                // the fence and records it.
                case RUN_CANCELLED, RUN_FAILED, RUN_PAUSED -> fence = payload.path("fence").longValue(fence);
                case CHECKPOINT_CREATED -> checkpoint = payload.path("checkpointSequence").longValue();
                default -> { }
            }
            if (lifecycle.terminal() && completedAt == null) {
                completedAt = event.raw().createdAt();
                terminalCode = switch (lifecycle) {
                    case FAILED -> text(payload, "code");
                    case CANCELLED -> CANCELLED_CODE;
                    default -> null;
                };
            }
            // Runtime, fences, and checkpoint positions are engine facts; a worker payload field of the same name
            // is ordinary data and never changes the summary.
            long runtime = type.authorship() == RunEventType.Authorship.WORKER ? 0
                    : nonNegative(payload.path("runtimeMillis").longValue(0), "runtimeMillis");
            usage = usage.plus(usageDelta(payload)).plusRuntime(runtime);
            sequence = event.raw().sequence();
            head = event.raw().eventDigest();
        }
        return new RunSummary(lifecycle, blockReason, terminalCode, sequence, head, checkpoint, fence, usage, startedAt,
                completedAt);
    }

    /** The usage delta an event payload carries; absent counters are zero and negative counters are rejected. */
    public static RunUsage usageDelta(ObjectNode payload) {
        JsonNode usage = payload.get("usage");
        if (usage == null || usage.isNull()) return RunUsage.ZERO;
        if (!usage.isObject()) throw new RunEngineException("INVALID_PAYLOAD", "usage is an object");
        for (String name : usage.propertyNames()) {
            if (!List.of("inputTokens", "outputTokens", "modelCalls", "toolCalls", "estimatedCostUsd").contains(name)) {
                throw new RunEngineException("INVALID_PAYLOAD", "unknown usage counter " + name);
            }
        }
        BigDecimal cost;
        JsonNode costNode = usage.path("estimatedCostUsd");
        try {
            cost = costNode.isMissingNode() || costNode.isNull() ? BigDecimal.ZERO
                    : costNode.isString() ? new BigDecimal(costNode.stringValue()) : costNode.decimalValue();
        } catch (RuntimeException invalid) {
            throw new RunEngineException("INVALID_PAYLOAD", "estimatedCostUsd is a decimal", invalid);
        }
        try {
            return RunUsage.of(counter(usage, "inputTokens"), counter(usage, "outputTokens"), counter(usage, "modelCalls"),
                    counter(usage, "toolCalls"), 0, cost);
        } catch (IllegalArgumentException invalid) {
            throw new RunEngineException("INVALID_PAYLOAD", invalid.getMessage(), invalid);
        }
    }

    private static long counter(JsonNode usage, String name) {
        JsonNode value = usage.path(name);
        if (value.isMissingNode() || value.isNull()) return 0;
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new RunEngineException("INVALID_PAYLOAD", name + " is a whole number");
        }
        return nonNegative(value.longValue(), name);
    }

    private static long nonNegative(long value, String name) {
        if (value < 0) throw new RunEngineException("INVALID_PAYLOAD", name + " cannot be negative");
        return value;
    }

    private static String text(ObjectNode payload, String name) {
        JsonNode value = payload.get(name);
        if (value == null || !value.isString()) {
            throw new RunEngineException("CORRUPT_HISTORY", "the event payload lacks " + name);
        }
        return value.stringValue();
    }

    private static RunEngineException corrupt(DecodedEvent event, String message) {
        return new RunEngineException("CORRUPT_HISTORY", "event " + event.raw().sequence() + ": " + message);
    }
}
