package com.dvarahq.agentworks.agent.springai;

import com.dvarahq.agentworks.capability.ToolEffect;
import com.dvarahq.agentworks.domain.run.RunPins;
import com.dvarahq.agentworks.domain.security.InvocationIdentity;
import com.dvarahq.agentworks.interaction.HumanInteractionAssignment;
import com.dvarahq.agentworks.interaction.HumanInteractionDraft;
import com.dvarahq.agentworks.interaction.HumanInteractionPrompt;
import com.dvarahq.agentworks.interaction.HumanInteractionRecord;
import com.dvarahq.agentworks.interaction.HumanInteractionRequest;
import com.dvarahq.agentworks.interaction.HumanInteractionResponse;
import com.dvarahq.agentworks.interaction.HumanInteractionStatus;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** Maps an input-guardrail step-up decision to one durable, replayable user approval. */
final class ToolInteractionBinding {
    enum Outcome { APPROVED, DENIED, REQUIRED }

    record Result(Outcome outcome, HumanInteractionDraft draft, String reason) {
        Result {
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(reason, "reason");
            if ((outcome == Outcome.REQUIRED) != (draft != null)) {
                throw new IllegalArgumentException("only a required interaction carries a draft");
            }
        }
    }

    static final Duration DEFAULT_TIMEOUT = Duration.ofHours(24);

    private final RunPins pins;
    private final InvocationIdentity identity;
    private final String toolName;
    private final String capability;
    private final ToolEffect effect;
    private final ToolInteractionAuthority authority;
    private final String keyPrefix;
    private final AtomicInteger sequence = new AtomicInteger();

    ToolInteractionBinding(RunPins pins, InvocationIdentity identity, String toolName, String capability,
            ToolEffect effect, ToolInteractionAuthority authority) {
        this.pins = Objects.requireNonNull(pins, "pins");
        this.identity = Objects.requireNonNull(identity, "identity");
        this.toolName = Objects.requireNonNull(toolName, "toolName");
        this.capability = Objects.requireNonNull(capability, "capability");
        this.effect = Objects.requireNonNull(effect, "effect");
        this.authority = Objects.requireNonNull(authority, "authority");
        this.keyPrefix = "tool-review-" + digest(toolName + "\n" + capability).substring(0, 24);
    }

    Result resolve(String guardrail, String reason) {
        String key = keyPrefix + "-" + sequence.incrementAndGet();
        HumanInteractionDraft draft = draft(key, guardrail, reason);
        Optional<HumanInteractionRecord> existing = authority.find(pins.workspaceId(), pins.id(), key);
        if (existing.isEmpty() || existing.get().status() == HumanInteractionStatus.OPEN) {
            return new Result(Outcome.REQUIRED, draft, "TOOL_APPROVAL_REQUIRED");
        }
        HumanInteractionRecord interaction = existing.orElseThrow();
        if (!matches(draft, interaction.request())) {
            return new Result(Outcome.DENIED, null, "TOOL_APPROVAL_IDENTITY_MISMATCH");
        }
        if (interaction.status() == HumanInteractionStatus.RESPONDED
                && interaction.response().orElseThrow() instanceof HumanInteractionResponse.Approval approval) {
            return new Result(approval.approved() ? Outcome.APPROVED : Outcome.DENIED, null,
                    approval.approved() ? "TOOL_APPROVAL_GRANTED" : "TOOL_APPROVAL_REJECTED");
        }
        return new Result(Outcome.DENIED, null, "TOOL_APPROVAL_" + interaction.status().name());
    }

    private HumanInteractionDraft draft(String key, String guardrail, String reason) {
        UUID id = UUID.nameUUIDFromBytes((pins.id() + "\n" + key).getBytes(StandardCharsets.UTF_8));
        HumanInteractionPrompt.Approval prompt = new HumanInteractionPrompt.Approval("Tool approval required",
                "Approve tool " + toolName + " for capability " + capability + "?",
                "Guardrail " + guardrail + " requested " + reason + "; effect " + effect.action() + " on "
                        + effect.resource() + " has " + effect.risk().name() + " risk.");
        return new HumanInteractionDraft(id, pins.workspaceId(), pins.id(), pins.releaseId(), pins.deploymentId(),
                pins.revisionId(), pins.cohortId(), key, key, prompt,
                HumanInteractionAssignment.user(pins.workspaceId(), identity.userId()), DEFAULT_TIMEOUT);
    }

    private static boolean matches(HumanInteractionDraft expected, HumanInteractionRequest actual) {
        return actual.id().equals(expected.id())
                && actual.workspaceId().equals(expected.workspaceId())
                && actual.runId().equals(expected.runId())
                && actual.releaseId().equals(expected.releaseId())
                && actual.deploymentId().equals(expected.deploymentId())
                && actual.revisionId().equals(expected.revisionId())
                && actual.cohortId().equals(expected.cohortId())
                && actual.correlationId().equals(expected.correlationId())
                && actual.idempotencyKey().equals(expected.idempotencyKey())
                && actual.prompt().equals(expected.prompt())
                && actual.assignment().equals(expected.assignment())
                && actual.deadline().equals(actual.createdAt().plus(expected.timeout()));
    }

    private static String digest(String value) {
        return com.dvarahq.agentworks.runplane.execution.RunEventCodec.sha256(value)
                .substring("sha256:".length());
    }
}
