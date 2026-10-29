package com.dvarahq.agentworks.interaction;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Durable interaction state and the cohort transition observed when it was admitted. */
public record HumanInteractionRecord(HumanInteractionRequest request, long cohortTransitionVersion,
        HumanInteractionStatus status, Optional<HumanInteractionResponse> response, Optional<String> resolvedBy,
        Optional<Instant> resolvedAt) {
    public HumanInteractionRecord {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(status, "status");
        response = Objects.requireNonNull(response, "response");
        resolvedBy = Objects.requireNonNull(resolvedBy, "resolvedBy");
        resolvedAt = Objects.requireNonNull(resolvedAt, "resolvedAt");
        if (cohortTransitionVersion < 1) {
            throw new IllegalArgumentException("cohort transition version must be positive");
        }
        if (status == HumanInteractionStatus.OPEN
                && (response.isPresent() || resolvedBy.isPresent() || resolvedAt.isPresent())) {
            throw new IllegalArgumentException("an open interaction has no outcome");
        }
        if (status.terminal() && (resolvedBy.isEmpty() || resolvedAt.isEmpty())) {
            throw new IllegalArgumentException("a terminal interaction records its actor and time");
        }
        if ((status == HumanInteractionStatus.RESPONDED) != response.isPresent()) {
            throw new IllegalArgumentException("a response exists exactly for RESPONDED");
        }
        response.ifPresent(value -> {
            if (!request.accepts(value)) throw new IllegalArgumentException("response does not match request");
        });
    }
}
