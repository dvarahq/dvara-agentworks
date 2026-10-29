package com.dvarahq.agentworks.domain.run;

import java.util.Objects;
import java.util.UUID;

/** One persisted run: immutable pins, the reconstructible summary, and operational ownership. */
public record RunRecord(RunPins pins, RunSummary summary, RunControl control) {
    public RunRecord {
        Objects.requireNonNull(pins, "pins");
        Objects.requireNonNull(summary, "summary");
        Objects.requireNonNull(control, "control");
    }

    public UUID id() {
        return pins.id();
    }

    public RunLifecycle lifecycle() {
        return summary.lifecycle();
    }

    public RunRecord with(RunSummary next, RunControl nextControl) {
        return new RunRecord(pins, next, nextControl);
    }
}
