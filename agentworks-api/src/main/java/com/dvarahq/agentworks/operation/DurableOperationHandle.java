package com.dvarahq.agentworks.operation;

import java.util.Objects;

/** Opaque provider identity; it contains no credential or callback secret. */
public record DurableOperationHandle(String provider, String externalId) {
    public DurableOperationHandle {
        if (provider == null || provider.isBlank() || provider.length() > 128) {
            throw new IllegalArgumentException("provider must be 1 to 128 characters");
        }
        if (externalId == null || externalId.isBlank() || externalId.length() > 512) {
            throw new IllegalArgumentException("externalId must be 1 to 512 characters");
        }
    }
}
