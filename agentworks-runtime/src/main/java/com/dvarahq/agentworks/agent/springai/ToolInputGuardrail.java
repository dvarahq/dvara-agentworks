package com.dvarahq.agentworks.agent.springai;

import com.dvarahq.agentworks.capability.ToolEffect;
import com.dvarahq.agentworks.domain.security.ExecutionPolicy;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Evaluates schema-valid tool arguments immediately before execution. Implementations may inspect arguments in
 * memory, but must not retain, log, or copy them into their decision. The runtime persists only normalized decision
 * evidence.
 */
public interface ToolInputGuardrail {
    enum Outcome { ALLOW, DENY, INTERACTION_REQUIRED }

    record Decision(Outcome outcome, String reason) {
        private static final Pattern REASON = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

        public Decision {
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(reason, "reason");
            if (!REASON.matcher(reason).matches()) {
                throw new IllegalArgumentException("guardrail reason must be a stable uppercase code");
            }
        }
    }

    record Request(UUID runId, UUID workspaceId, UUID releaseId, UUID environmentId, UUID actorId,
            UUID delegatedSubjectId, String toolName, String capability, ToolEffect effect,
            ExecutionPolicy executionPolicy, String arguments) {
        public Request {
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(workspaceId, "workspaceId");
            Objects.requireNonNull(releaseId, "releaseId");
            Objects.requireNonNull(environmentId, "environmentId");
            Objects.requireNonNull(actorId, "actorId");
            Objects.requireNonNull(delegatedSubjectId, "delegatedSubjectId");
            toolName = exact(toolName, "toolName");
            capability = exact(capability, "capability");
            Objects.requireNonNull(effect, "effect");
            Objects.requireNonNull(executionPolicy, "executionPolicy");
            arguments = arguments == null ? "" : arguments;
        }

        @Override
        public String toString() {
            return "Request[runId=" + runId + ", workspaceId=" + workspaceId + ", releaseId=" + releaseId
                    + ", environmentId=" + environmentId + ", actorId=" + actorId + ", delegatedSubjectId="
                    + delegatedSubjectId + ", toolName=" + toolName + ", capability=" + capability + ", effect="
                    + effect + ", executionPolicy=" + executionPolicy + ", arguments=<redacted>]";
        }

        private static String exact(String value, String name) {
            Objects.requireNonNull(value, name);
            if (value.isBlank() || !value.equals(value.trim())) {
                throw new IllegalArgumentException(name + " must be a non-blank exact value");
            }
            return value;
        }
    }

    /** Stable provider identity recorded in evidence. */
    String id();

    Decision evaluate(Request request);

    /** Core's explicit compatibility policy: schema-valid input is allowed without an application-specific rule. */
    static ToolInputGuardrail validatedInput() {
        return ValidatedInput.INSTANCE;
    }

    enum ValidatedInput implements ToolInputGuardrail {
        INSTANCE;

        @Override
        public String id() {
            return "core:validated-input:1";
        }

        @Override
        public Decision evaluate(Request request) {
            Objects.requireNonNull(request, "request");
            return new Decision(Outcome.ALLOW, "VALIDATED_INPUT_ALLOWED");
        }
    }
}
