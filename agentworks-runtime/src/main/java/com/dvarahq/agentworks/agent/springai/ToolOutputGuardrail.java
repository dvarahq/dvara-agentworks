package com.dvarahq.agentworks.agent.springai;

import com.dvarahq.agentworks.capability.ToolEffect;
import com.dvarahq.agentworks.domain.security.ExecutionPolicy;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Evaluates a tool result before it is returned to the model. Implementations may inspect the raw result in memory,
 * but must not retain or log it. The runtime persists only normalized decision evidence and the digest of the
 * effective result after any redaction.
 */
public interface ToolOutputGuardrail {
    enum Outcome { ALLOW, REDACT, DENY }

    record Decision(Outcome outcome, String reason, String replacement) {
        private static final Pattern REASON = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

        public Decision {
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(reason, "reason");
            if (!REASON.matcher(reason).matches()) {
                throw new IllegalArgumentException("guardrail reason must be a stable uppercase code");
            }
            if (outcome == Outcome.REDACT && replacement == null) {
                throw new IllegalArgumentException("a redaction decision requires replacement output");
            }
            if (outcome != Outcome.REDACT && replacement != null) {
                throw new IllegalArgumentException("only a redaction decision may carry replacement output");
            }
        }

        public static Decision allow(String reason) {
            return new Decision(Outcome.ALLOW, reason, null);
        }

        public static Decision redact(String reason, String replacement) {
            return new Decision(Outcome.REDACT, reason, replacement);
        }

        public static Decision deny(String reason) {
            return new Decision(Outcome.DENY, reason, null);
        }

        @Override
        public String toString() {
            return "Decision[outcome=" + outcome + ", reason=" + reason + ", replacement=<redacted>]";
        }
    }

    record Request(UUID runId, UUID workspaceId, UUID releaseId, UUID environmentId, UUID actorId,
            UUID delegatedSubjectId, String toolName, String capability, ToolEffect effect,
            ExecutionPolicy executionPolicy, String result) {
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
            result = result == null ? "" : result;
        }

        @Override
        public String toString() {
            return "Request[runId=" + runId + ", workspaceId=" + workspaceId + ", releaseId=" + releaseId
                    + ", environmentId=" + environmentId + ", actorId=" + actorId + ", delegatedSubjectId="
                    + delegatedSubjectId + ", toolName=" + toolName + ", capability=" + capability + ", effect="
                    + effect + ", executionPolicy=" + executionPolicy + ", result=<redacted>]";
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

    /** Core's explicit compatibility policy: successful tool output is allowed without an application-specific rule. */
    static ToolOutputGuardrail validatedOutput() {
        return ValidatedOutput.INSTANCE;
    }

    enum ValidatedOutput implements ToolOutputGuardrail {
        INSTANCE;

        @Override
        public String id() {
            return "core:validated-output:1";
        }

        @Override
        public Decision evaluate(Request request) {
            Objects.requireNonNull(request, "request");
            return Decision.allow("VALIDATED_OUTPUT_ALLOWED");
        }
    }
}
