package com.dvarahq.agentworks.a2a;

import com.dvarahq.agentworks.domain.external.*;
import com.dvarahq.agentworks.domain.run.RunPins;
import com.dvarahq.agentworks.runplane.execution.DurableRunEngine.EffectOutcome;
import com.dvarahq.agentworks.runplane.execution.RunContext;
import com.dvarahq.agentworks.runplane.execution.RunHandler;
import com.dvarahq.agentworks.runplane.execution.RunOutcome;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Durable runtime binding for a pinned external executor. The whole remote invocation is one modeled effect with the
 * engine-supplied stable external key. Its validated event transcript and executor descriptor are committed in the
 * effect result and terminal run output; an unknown transport outcome remains in doubt and is never silently retried
 * under a new route or executor.
 */
public final class RemoteRunHandler implements RunHandler {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final ExternalExecutorPort executor;
    private final ExternalInteractionHandler interactions;

    public RemoteRunHandler(ExternalExecutorPort executor, ExternalInteractionHandler interactions) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.interactions = Objects.requireNonNull(interactions, "interactions");
    }

    @Override
    public RunOutcome execute(RunContext context) {
        Objects.requireNonNull(context, "context");
        ExternalExecutorDescriptor pinned = context.manifest().externalExecutor()
                .orElseThrow(() -> new ExternalProtocolException(ExternalProtocolError.NEGOTIATION_MISMATCH,
                        "release has no pinned external executor"));
        Set<ExternalCapability> required = context.manifest().requiredCapabilities().stream().map(capability -> {
            try { return ExternalCapability.valueOf(capability); }
            catch (IllegalArgumentException unknown) { throw new ExternalProtocolException(
                    ExternalProtocolError.NEGOTIATION_MISMATCH, "release requires an unsupported external capability"); }
        }).collect(Collectors.toUnmodifiableSet());
        ExternalProtocolRequirements requirements = new ExternalProtocolRequirements(pinned.protocolVersion(),
                pinned.schemaVersion(), pinned.assurance(), pinned.accessMode(), required);
        ExternalExecutorDescriptor negotiated = executor.negotiate(requirements);
        if (!pinned.equals(negotiated)) {
            throw new ExternalProtocolException(ExternalProtocolError.NEGOTIATION_MISMATCH,
                    "executor identity or provenance differs from the pinned release");
        }
        RunPins pins = context.pins();
        var invoker = context.invoker().orElseThrow(() -> new ExternalProtocolException(
                ExternalProtocolError.CORRELATION_MISMATCH, "external execution requires a recorded invoker"));
        if (!invoker.workspaceId().equals(pins.workspaceId()) || pins.routingGenerationId() == null) {
            throw new ExternalProtocolException(ExternalProtocolError.CORRELATION_MISMATCH,
                    "run has incomplete or mismatched external-executor pins");
        }
        Map<String, Object> fingerprint = new LinkedHashMap<>();
        fingerprint.put("runId", pins.id());
        fingerprint.put("releaseId", pins.releaseId());
        fingerprint.put("deploymentRevisionId", pins.revisionId());
        fingerprint.put("cohortId", pins.cohortId());
        fingerprint.put("routingGenerationId", pins.routingGenerationId());
        fingerprint.put("executor", pinned);
        fingerprint.put("input", context.input());
        EffectOutcome outcome = context.effect("external-executor", fingerprint, (externalKey, ignored) -> {
            ExternalInvocationContext invocation = new ExternalInvocationContext(invoker.organizationId(),
                    pins.workspaceId(), pins.id(), pins.releaseId(), pins.revisionId(), pins.cohortId().toString(),
                    pins.routingGenerationId(), pins.traceId(), externalKey);
            ExternalInvocationRequest request = new ExternalInvocationRequest(invocation, requirements, pinned,
                    JSON.writeValueAsString(context.input()));
            ExternalInvocationResult result = executor.execute(request, interactions);
            if (!result.executor().equals(pinned)) throw new ExternalProtocolException(
                    ExternalProtocolError.NEGOTIATION_MISMATCH, "execution result differs from pinned executor");
            ExternalEventValidator validator = new ExternalEventValidator(invocation, requirements);
            result.events().forEach(validator::accept);
            if (!validator.terminal()) throw new ExternalProtocolException(ExternalProtocolError.MALFORMED_ENVELOPE,
                    "external execution ended without a terminal event");
            return JSON.valueToTree(result);
        });
        if (outcome.failed()) return RunOutcome.failed("EXTERNAL_EXECUTOR_FAILED", outcome.failure());
        try {
            ExternalInvocationResult result = JSON.treeToValue(outcome.result(), ExternalInvocationResult.class);
            return RunOutcome.completed(result);
        } catch (JacksonException malformed) {
            return RunOutcome.failed("EXTERNAL_RESULT_INVALID", "committed external result is unreadable");
        }
    }
}
