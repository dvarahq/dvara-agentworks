package com.dvarahq.agentworks.agent.springai;

import com.dvarahq.agentworks.runplane.execution.RunEventCodec;
import com.dvarahq.agentworks.runplane.execution.DurableRunEngine.EffectFailedException;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.HexFormat;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Wraps an advertised tool for one continuation, so each execution is recorded as durable, normalized tool evidence
 * ({@code TOOL_REQUESTED} carrying the tool-call usage, {@code TOOL_STARTED}, then {@code TOOL_COMPLETED} or
 * {@code TOOL_FAILED}) before and after the tool runs. Normalized input-schema, policy, input-guardrail, and
 * output-guardrail decisions are recorded at their execution boundaries. Arguments and effective results are recorded
 * as digests and sizes. Effectful release-pinned callbacks also execute through the durable operation ledger; the
 * ledger supplies a stable downstream idempotency key and replays a committed redacted result without invoking the
 * callback or resolving credentials again. A
 * write that fails (for example because the run was cancelled or exceeded its tool-call limit) stops the tool before it
 * runs. Human interaction and sandboxing remain in #39 and #40.
 */
final class RecordingToolCallback implements ToolCallback {
    private final ToolCallback delegate;
    private final DurableRunRecorder recorder;
    private final ToolPolicyGate policy;
    private final ToolInputSchema inputSchema;
    private final ToolInputGuardrailGate inputGuardrail;
    private final ToolOutputGuardrailGate outputGuardrail;
    private final ToolCredentialBinding credential;
    private final ToolEffectBinding effect;
    private final ToolInteractionBinding interaction;
    private final ToolResultArtifactBinding artifact;
    private final ToolCallIdentityRegistry toolCallIdentities;

    RecordingToolCallback(ToolCallback delegate, DurableRunRecorder recorder, ToolPolicyGate policy) {
        this(delegate, recorder, policy, inputSchema(delegate), ToolInputGuardrailGate.legacyCompatibility(),
                ToolOutputGuardrailGate.legacyCompatibility());
    }

    RecordingToolCallback(ToolCallback delegate, DurableRunRecorder recorder, ToolPolicyGate policy,
            ToolInputSchema inputSchema) {
        this(delegate, recorder, policy, inputSchema, ToolInputGuardrailGate.legacyCompatibility(),
                ToolOutputGuardrailGate.legacyCompatibility());
    }

    RecordingToolCallback(ToolCallback delegate, DurableRunRecorder recorder, ToolPolicyGate policy,
            ToolInputSchema inputSchema, ToolInputGuardrailGate inputGuardrail) {
        this(delegate, recorder, policy, inputSchema, inputGuardrail, ToolOutputGuardrailGate.legacyCompatibility());
    }

    RecordingToolCallback(ToolCallback delegate, DurableRunRecorder recorder, ToolPolicyGate policy,
            ToolInputSchema inputSchema, ToolInputGuardrailGate inputGuardrail,
            ToolOutputGuardrailGate outputGuardrail) {
        this(delegate, recorder, policy, inputSchema, inputGuardrail, outputGuardrail, null);
    }

    RecordingToolCallback(ToolCallback delegate, DurableRunRecorder recorder, ToolPolicyGate policy,
            ToolInputSchema inputSchema, ToolInputGuardrailGate inputGuardrail,
            ToolOutputGuardrailGate outputGuardrail, ToolCredentialBinding credential) {
        this(delegate, recorder, policy, inputSchema, inputGuardrail, outputGuardrail, credential, null);
    }

    RecordingToolCallback(ToolCallback delegate, DurableRunRecorder recorder, ToolPolicyGate policy,
            ToolInputSchema inputSchema, ToolInputGuardrailGate inputGuardrail,
            ToolOutputGuardrailGate outputGuardrail, ToolCredentialBinding credential, ToolEffectBinding effect) {
        this(delegate, recorder, policy, inputSchema, inputGuardrail, outputGuardrail, credential, effect, null);
    }

    RecordingToolCallback(ToolCallback delegate, DurableRunRecorder recorder, ToolPolicyGate policy,
            ToolInputSchema inputSchema, ToolInputGuardrailGate inputGuardrail,
            ToolOutputGuardrailGate outputGuardrail, ToolCredentialBinding credential, ToolEffectBinding effect,
            ToolInteractionBinding interaction) {
        this(delegate, recorder, policy, inputSchema, inputGuardrail, outputGuardrail, credential, effect, interaction,
                null);
    }

    RecordingToolCallback(ToolCallback delegate, DurableRunRecorder recorder, ToolPolicyGate policy,
            ToolInputSchema inputSchema, ToolInputGuardrailGate inputGuardrail,
            ToolOutputGuardrailGate outputGuardrail, ToolCredentialBinding credential, ToolEffectBinding effect,
            ToolInteractionBinding interaction, ToolResultArtifactBinding artifact) {
        this(delegate, recorder, policy, inputSchema, inputGuardrail, outputGuardrail, credential, effect, interaction,
                artifact, null);
    }

    RecordingToolCallback(ToolCallback delegate, DurableRunRecorder recorder, ToolPolicyGate policy,
            ToolInputSchema inputSchema, ToolInputGuardrailGate inputGuardrail,
            ToolOutputGuardrailGate outputGuardrail, ToolCredentialBinding credential, ToolEffectBinding effect,
            ToolInteractionBinding interaction, ToolResultArtifactBinding artifact,
            ToolCallIdentityRegistry toolCallIdentities) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.recorder = Objects.requireNonNull(recorder, "recorder");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.inputSchema = Objects.requireNonNull(inputSchema, "inputSchema");
        this.inputGuardrail = Objects.requireNonNull(inputGuardrail, "inputGuardrail");
        this.outputGuardrail = Objects.requireNonNull(outputGuardrail, "outputGuardrail");
        this.credential = credential;
        this.effect = effect;
        this.interaction = interaction;
        this.artifact = artifact;
        this.toolCallIdentities = toolCallIdentities;
        if ((credential != null) != (delegate instanceof CredentialedToolCallback)) {
            throw new IllegalArgumentException("a credential binding requires exactly a CredentialedToolCallback");
        }
    }

    private static ToolInputSchema inputSchema(ToolCallback delegate) {
        return ToolInputSchema.compile(Objects.requireNonNull(delegate, "delegate").getToolDefinition().inputSchema());
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        String name = delegate.getToolDefinition().name();
        ToolCallIdentityRegistry.Identity identity = toolCallIdentities == null
                ? null : toolCallIdentities.claim(name, toolInput);
        ToolContext invocationContext = identity == null ? toolContext
                : ToolCallIdentityRegistry.withIdentity(toolContext, identity);
        String stepId = "tool-" + HexFormat.of().formatHex(randomBytes());
        recorder.toolRequested(stepId, name, RunEventCodec.sha256(toolInput == null ? "" : toolInput));
        ToolInputSchema.Decision schemaDecision = inputSchema.validate(toolInput);
        recorder.toolInputSchemaValidated(stepId, name, schemaDecision);
        if (!schemaDecision.allowed()) {
            throw new OutputRejectedException("TOOL_INPUT_INVALID",
                    "tool input validation denied " + name + ": " + schemaDecision.reason());
        }
        ToolPolicyGate.Decision decision = policy.evaluate();
        recorder.toolPolicyEvaluated(stepId, name, decision);
        if (!decision.allowed()) {
            throw new OutputRejectedException("TOOL_POLICY_DENIED",
                    "tool policy denied " + name + ": " + decision.reason());
        }
        ToolInputGuardrailGate.Decision guardrailDecision = inputGuardrail.evaluate(toolInput);
        recorder.toolInputGuardrailEvaluated(stepId, name, guardrailDecision);
        if (!guardrailDecision.allowed()) {
            if (guardrailDecision.outcome() == ToolInputGuardrail.Outcome.INTERACTION_REQUIRED
                    && interaction != null) {
                ToolInteractionBinding.Result resolution = interaction.resolve(guardrailDecision.guardrail(),
                        guardrailDecision.reason());
                if (resolution.outcome() == ToolInteractionBinding.Outcome.REQUIRED) {
                    throw new ToolInteractionRequiredException(resolution.draft());
                }
                if (resolution.outcome() == ToolInteractionBinding.Outcome.APPROVED) {
                    recorder.toolStarted(stepId, name);
                    return invokeApproved(toolInput, invocationContext, stepId, name);
                }
                throw new OutputRejectedException("TOOL_INTERACTION_DENIED",
                        "tool approval denied " + name + ": " + resolution.reason());
            }
            String code = guardrailDecision.outcome() == ToolInputGuardrail.Outcome.INTERACTION_REQUIRED
                    ? "TOOL_INTERACTION_REQUIRED" : "TOOL_INPUT_GUARDRAIL_DENIED";
            throw new OutputRejectedException(code,
                    "tool input guardrail stopped " + name + ": " + guardrailDecision.reason());
        }
        recorder.toolStarted(stepId, name);
        return invokeApproved(toolInput, invocationContext, stepId, name);
    }

    private String invokeApproved(String toolInput, ToolContext toolContext, String stepId, String name) {
        if (delegate instanceof ArtifactToolCallback && artifact == null) {
            var refusal = new ToolAdapterException("TOOL_ARTIFACT_STORE_REQUIRED");
            recordToolFailure(stepId, name, refusal);
            throw refusal;
        }
        if (effect == null) {
            String result;
            try {
                result = invoke(toolInput, toolContext);
            } catch (ChildRunBlockedException delegated) {
                throw delegated;
            } catch (RuntimeException | Error failure) {
                recordToolFailure(stepId, name, failure);
                throw failure;
            }
            result = guardOutput(stepId, name, result);
            result = offload(result);
            recorder.toolCompleted(stepId, name, result);
            return result;
        }

        ToolEffectBinding.Result effectResult;
        AtomicReference<ToolOutputGuardrailGate.Decision> evaluatedOutput = new AtomicReference<>();
        try {
            effectResult = effect.invoke(toolInput, toolContext, context -> {
                    String raw = invoke(toolInput, context);
                    ToolOutputGuardrailGate.Decision outputDecision = outputGuardrail.evaluate(raw);
                    evaluatedOutput.set(outputDecision);
                    if (!outputDecision.allowed()) {
                        throw new EffectFailedException("TOOL_OUTPUT_GUARDRAIL_DENIED");
                    }
                    String effective = outputDecision.effectiveResult();
                    return offload(effective);
                });
        } catch (RuntimeException | Error failure) {
            recordEvaluatedOutput(stepId, name, evaluatedOutput.get());
            recordToolFailure(stepId, name, failure);
            throw failure;
        }
        recordEvaluatedOutput(stepId, name, evaluatedOutput.get());
        if (effectResult.failed()) {
            throw new OutputRejectedException(stableEffectFailure(effectResult.failure()),
                    "the durable tool effect failed");
        }
        String result = effectResult.value();
        recorder.toolCompleted(stepId, name, result, effectResult.effectKey(), effectResult.replayed());
        return result;
    }

    private String offload(String result) {
        boolean required = delegate instanceof ArtifactToolCallback adapter && adapter.requiresArtifact(result);
        if (required && artifact == null) throw new ToolAdapterException("TOOL_ARTIFACT_STORE_REQUIRED");
        return artifact == null ? result : artifact.offload(result, required);
    }

    private void recordEvaluatedOutput(String stepId, String name, ToolOutputGuardrailGate.Decision decision) {
        // RunContext.effect advances the lease while its invoker runs. Evidence is therefore written only after that
        // method returns (or propagates an in-doubt outcome), when the worker context has installed the renewed lease.
        // A replay has no decision: the already-committed effective result bypasses the provider by design.
        if (decision != null) recorder.toolOutputGuardrailEvaluated(stepId, name, decision);
    }

    private void recordToolFailure(String stepId, String name, Throwable failure) {
        StackOverflowError overflow = overflow(failure);
        if (overflow != null) {
            // A stack overflow (raw, or wrapped by Spring AI's method callback) is recorded and becomes an ordinary
            // typed failure: an Error would bypass the stream's error handling, and Spring AI's tool-exception
            // processing would hand a wrapped one back to the model as text instead of ending the exchange.
            recorder.toolFailed(stepId, name, overflow);
            throw new OutputRejectedException("TOOL_FAILED", "tool " + name + " failed with "
                    + StackOverflowError.class.getName());
        }
        recorder.toolFailed(stepId, name, failure);
    }

    private String invoke(String input, ToolContext context) {
        return credential == null ? delegate.call(input, context)
                : credential.invoke((CredentialedToolCallback) delegate, input, context);
    }

    private String guardOutput(String stepId, String name, String raw) {
        ToolOutputGuardrailGate.Decision decision = outputGuardrail.evaluate(raw);
        recorder.toolOutputGuardrailEvaluated(stepId, name, decision);
        if (!decision.allowed()) {
            throw new OutputRejectedException("TOOL_OUTPUT_GUARDRAIL_DENIED",
                    "tool output guardrail denied " + name + ": " + decision.reason());
        }
        return decision.effectiveResult();
    }

    private static String stableEffectFailure(String failure) {
        return "TOOL_OUTPUT_GUARDRAIL_DENIED".equals(failure)
                ? "TOOL_OUTPUT_GUARDRAIL_DENIED" : "TOOL_EFFECT_FAILED";
    }

    private static StackOverflowError overflow(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof StackOverflowError overflow) return overflow;
        }
        return null;
    }

    private static byte[] randomBytes() {
        byte[] bytes = new byte[4];
        ThreadLocalRandom.current().nextBytes(bytes);
        return bytes;
    }
}
