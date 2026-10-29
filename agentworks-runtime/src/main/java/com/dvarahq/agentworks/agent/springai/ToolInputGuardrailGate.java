package com.dvarahq.agentworks.agent.springai;

import com.dvarahq.agentworks.capability.ToolEffect;
import com.dvarahq.agentworks.domain.run.RunPins;
import com.dvarahq.agentworks.domain.security.ExecutionPolicy;
import com.dvarahq.agentworks.domain.security.InvocationIdentity;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/** Binds one selected tool's immutable run facts to the configured input guardrail. */
final class ToolInputGuardrailGate {
    record Decision(ToolInputGuardrail.Outcome outcome, String reason, String guardrail, String risk) {
        Decision {
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(guardrail, "guardrail");
            Objects.requireNonNull(risk, "risk");
        }

        boolean allowed() {
            return outcome == ToolInputGuardrail.Outcome.ALLOW;
        }
    }

    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9._:-]{0,127}");
    private static final String UNPINNED = "UNPINNED";

    private final ToolInputGuardrail guardrail;
    private final String guardrailId;
    private final RunPins pins;
    private final InvocationIdentity identity;
    private final Optional<ExecutionPolicy> policy;
    private final String toolName;
    private final String capability;
    private final Optional<ToolEffect> effect;

    private ToolInputGuardrailGate() {
        this.guardrail = null;
        this.guardrailId = "core:legacy-compatibility:1";
        this.pins = null;
        this.identity = null;
        this.policy = Optional.empty();
        this.toolName = UNPINNED;
        this.capability = UNPINNED;
        this.effect = Optional.empty();
    }

    static ToolInputGuardrailGate legacyCompatibility() {
        return new ToolInputGuardrailGate();
    }

    ToolInputGuardrailGate(ToolInputGuardrail guardrail, RunPins pins, InvocationIdentity identity,
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

    Decision evaluate(String arguments) {
        if (effect.isEmpty() || policy.isEmpty()) {
            return new Decision(ToolInputGuardrail.Outcome.ALLOW, "LEGACY_MANIFEST_COMPATIBILITY",
                    guardrailId, UNPINNED);
        }
        ToolInputGuardrail.Decision decision;
        try {
            decision = Objects.requireNonNull(guardrail.evaluate(new ToolInputGuardrail.Request(pins.id(),
                    pins.workspaceId(), pins.releaseId(), pins.environmentId(), identity.userId(),
                    identity.delegatedSubjectId(), toolName, capability, effect.get(), policy.get(), arguments)),
                    "guardrail decision");
        } catch (RuntimeException unavailable) {
            return new Decision(ToolInputGuardrail.Outcome.DENY, "GUARDRAIL_UNAVAILABLE", guardrailId,
                    effect.get().risk().name());
        }
        return new Decision(decision.outcome(), decision.reason(), guardrailId, effect.get().risk().name());
    }
}
