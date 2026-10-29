package com.dvarahq.agentworks.operation;

import java.util.Optional;

/** Provider SPI for long external work. start and cancel must be idempotent for the supplied operation identity. */
public interface DurableOperationProvider {
    String id();
    DurableOperationHandle start(DurableOperationRequest request);
    ProviderStatus status(DurableOperationHandle handle);
    Optional<DurableOperationResult> result(DurableOperationHandle handle);
    void cancel(DurableOperationHandle handle);

    record ProviderStatus(State state, Integer progressPercent, String code) {
        public enum State { RUNNING, SUCCEEDED, FAILED, CANCELLED, UNKNOWN }

        public ProviderStatus {
            java.util.Objects.requireNonNull(state, "state");
            if (progressPercent != null && (progressPercent < 0 || progressPercent > 100)) {
                throw new IllegalArgumentException("progressPercent must be between 0 and 100");
            }
            if (code != null && (code.isBlank() || code.length() > 128)) {
                throw new IllegalArgumentException("code must be 1 to 128 characters");
            }
        }
    }
}
