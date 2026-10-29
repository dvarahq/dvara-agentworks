package com.dvarahq.agentworks.operation;

import java.util.Map;
import java.util.Objects;

/** Terminal provider result. Details may contain customer data and are excluded from diagnostics. */
public record DurableOperationResult(DurableOperationStatus status, String code, Map<String, Object> output) {
    public DurableOperationResult {
        Objects.requireNonNull(status, "status");
        if (!status.terminal()) throw new IllegalArgumentException("an operation result must be terminal");
        if (code == null || code.isBlank() || code.length() > 128) {
            throw new IllegalArgumentException("result code must be 1 to 128 characters");
        }
        output = Map.copyOf(Objects.requireNonNull(output, "output"));
    }

    @Override public String toString() {
        return "DurableOperationResult[status=" + status + ", code=" + code + ", output=<redacted>]";
    }
}
