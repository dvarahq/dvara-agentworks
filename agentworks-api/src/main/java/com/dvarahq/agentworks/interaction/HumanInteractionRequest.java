package com.dvarahq.agentworks.interaction;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Immutable request envelope bound to the exact run pins that may resume after its terminal outcome. */
public record HumanInteractionRequest(UUID id, UUID workspaceId, UUID runId, UUID releaseId, UUID deploymentId,
        UUID revisionId, UUID cohortId, String correlationId, String idempotencyKey, HumanInteractionPrompt prompt,
        HumanInteractionAssignment assignment, Instant createdAt, Instant deadline) {
    private static final Pattern KEY = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$");

    public HumanInteractionRequest {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(releaseId, "releaseId");
        Objects.requireNonNull(deploymentId, "deploymentId");
        Objects.requireNonNull(revisionId, "revisionId");
        Objects.requireNonNull(cohortId, "cohortId");
        requireKey(correlationId, "correlationId");
        requireKey(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(prompt, "prompt");
        Objects.requireNonNull(assignment, "assignment");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(deadline, "deadline");
        if (!workspaceId.equals(assignment.workspaceId())) {
            throw new IllegalArgumentException("assignment workspace must match the interaction workspace");
        }
        if (!deadline.isAfter(createdAt)) throw new IllegalArgumentException("deadline must follow creation");
    }

    public boolean accepts(HumanInteractionResponse response) {
        if (response == null || prompt.kind() != response.kind()) return false;
        if (prompt instanceof HumanInteractionPrompt.Selection selection
                && response instanceof HumanInteractionResponse.Selection answer) {
            java.util.Set<String> allowed = selection.options().stream()
                    .map(HumanInteractionPrompt.Option::id).collect(java.util.stream.Collectors.toUnmodifiableSet());
            return answer.optionIds().size() >= selection.minimum()
                    && answer.optionIds().size() <= selection.maximum()
                    && allowed.containsAll(answer.optionIds());
        }
        return true;
    }

    private static void requireKey(String value, String name) {
        if (value == null || !KEY.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " is not a stable bounded identifier");
        }
    }
}
