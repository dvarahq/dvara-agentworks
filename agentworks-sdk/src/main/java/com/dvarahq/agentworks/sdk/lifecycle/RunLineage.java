package com.dvarahq.agentworks.sdk.lifecycle;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Authority-issued root/continuation facts. Clients may observe but never assert this record during admission. */
public record RunLineage(UUID runId, UUID rootRunId, UUID parentRunId, Kind kind) {
    public enum Kind { ROOT, CHILD, RETRY, COMPENSATION, WAKEUP, EXISTING_RUN_DELIVERY }
    public RunLineage {
        Objects.requireNonNull(runId); Objects.requireNonNull(rootRunId); Objects.requireNonNull(kind);
        if (kind == Kind.ROOT && (!runId.equals(rootRunId) || parentRunId != null)) {
            throw new IllegalArgumentException("root lineage must be self-rooted without a parent");
        }
        if (kind != Kind.ROOT && parentRunId == null) throw new IllegalArgumentException("continuation requires parent");
    }
    public Optional<UUID> parent() { return Optional.ofNullable(parentRunId); }
}
