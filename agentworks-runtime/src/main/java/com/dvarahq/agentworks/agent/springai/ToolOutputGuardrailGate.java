package com.dvarahq.agentworks.agent.springai;

import com.dvarahq.agentworks.capability.ToolEffect;
import com.dvarahq.agentworks.domain.run.RunPins;
import com.dvarahq.agentworks.domain.security.ExecutionPolicy;
import com.dvarahq.agentworks.domain.security.InvocationIdentity;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/** Binds one selected tool's immutable run facts to the configured output guardrail. */
final class ToolOutputGuardrailGate {
    record Decision(ToolOutputGuardrail.Outcome outcome, String reason, String guardrail, String risk,
            String effectiveResult) {
        Decision {
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(guardrail, "guardrail");
            Objects.requireNonNull(risk, "risk");
            Objects.requireNonNull(effectiveResult, "effectiveResult");
        }

        boolean allowed() {
            return outcome != ToolOutputGuardrail.Outcome.DENY;
        }

        @Override
        public String toString() {
            return "Decision[outcome=" + outcome + ", reason=" + reason + ", guardrail=" + guardrail
                    + ", risk=" + risk + ", effectiveResult=<redacted>]";
        }
    }

    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9._:-]{0,127}");
    private static final String UNPINNED = "UNPINNED";

    private final ToolOutputGuardrail guardrail;
    private final String guardrailId;
    private final RunPins pins;
    private final InvocationIdentity identity;
    private final Optional<ExecutionPolicy> policy;
    private final String toolName;
    private final String capability;
    private final Optional<ToolEffect> effect;

    private ToolOutputGuardrailGate() {
        this.guardrail = null;
        this.guardrailId = "core:legacy-compatibility:1";
        this.pins = null;
        this.identity = null;
        this.policy = Optional.empty();
        this.toolName = UNPINNED;
        this.capability = UNPINNED;
        this.effect = Optional.empty();
    }

    static ToolOutputGuardrailGate legacyCompatibility() {
        return new ToolOutputGuardrailGate();
    }

    ToolOutputGuardrailGate(ToolOutputGuardrail guardrail, RunPins pins, InvocationIdentity identity,
            Optional<ExecutionPolicy> policy, String toolName, String capability, Optional<ToolEffect> effect) {
        this.guardrail = Objects.requireNonNull(guardrail, "guardrail");
        this.guardrailId = Objects.requireNonNull(guardrail.id(), "guardrail.id");
        if (!ID.matcher(guardrailId).matches()) {
            throw new IllegalArgumentException("guardrail id must be a stable lowercase identifier");
        }
        this.pins = Objects.requireNonNull(pins, "pins");
        this.identity = Objects.requireNonNull(identity, "identity");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.toolName = Objects.requireNonNull(toolName, "toolName");
        this.capability = Objects.requireNonNull(capability, "capability");
        this.effect = Objects.requireNonNull(effect, "effect");
    }

    Decision evaluate(String result) {
        String raw = result == null ? "" : result;
        if (effect.isEmpty() || policy.isEmpty()) {
            return new Decision(ToolOutputGuardrail.Outcome.ALLOW, "LEGACY_MANIFEST_COMPATIBILITY",
                    guardrailId, UNPINNED, raw);
        }
        try {
            ToolOutputGuardrail.Decision decision = Objects.requireNonNull(guardrail.evaluate(
                    new ToolOutputGuardrail.Request(pins.id(), pins.workspaceId(), pins.releaseId(),
                            pins.environmentId(), identity.userId(), identity.delegatedSubjectId(), toolName,
                            capability, effect.get(), policy.get(), raw)), "guardrail decision");
            String effective = decision.outcome() == ToolOutputGuardrail.Outcome.REDACT
                    ? decision.replacement() : raw;
            return new Decision(decision.outcome(), decision.reason(), guardrailId, effect.get().risk().name(),
                    effective);
        } catch (RuntimeException unavailable) {
            return new Decision(ToolOutputGuardrail.Outcome.DENY, "GUARDRAIL_UNAVAILABLE", guardrailId,
                    effect.get().risk().name(), "");
        }
    }
}
