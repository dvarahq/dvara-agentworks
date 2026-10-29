package com.dvarahq.agentworks.domain.run;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The durable ledger entry of one modeled external effect, keyed by run and effect key. The intent is recorded as
 * {@link State#PENDING} before the effect is invoked and becomes {@link State#COMMITTED} or {@link State#FAILED}
 * only after an outcome is known. A pending effect whose outcome is unknown is in doubt: it is retried only with
 * the same external idempotency key, or resolved explicitly as {@link State#ABANDONED}; it is never reported as
 * rolled back.
 */
public record RunEffect(UUID id, UUID workspaceId, UUID runId, UUID cohortId, String effectKey, String requestDigest,
        State state, int attempts, String resultJson, String resolution, String leaseOwner, long leaseFence,
        Instant startedAt, Instant completedAt) {
    public enum State {
        PENDING, COMMITTED, FAILED, ABANDONED;

        public boolean terminal() {
            return this != PENDING;
        }
    }

    public RunEffect {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(effectKey, "effectKey");
        Objects.requireNonNull(requestDigest, "requestDigest");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(startedAt, "startedAt");
    }

    public RunEffect transition(State next, int nextAttempts, String result, String nextResolution, String owner,
            long fence, Instant completed) {
        return new RunEffect(id, workspaceId, runId, cohortId, effectKey, requestDigest, next, nextAttempts, result,
                nextResolution, owner, fence, startedAt, completed);
    }
}
