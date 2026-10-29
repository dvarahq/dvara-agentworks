package com.dvarahq.agentworks.runplane.execution;

import com.dvarahq.agentworks.domain.run.ClaimQuarantine;
import com.dvarahq.agentworks.domain.run.DurableRunStore;

import java.util.Optional;
import java.util.UUID;

/**
 * A base for test stores that wrap a real store's transactions and claims: its claim quarantine (#181) is the wrapped
 * store's, so a wrapper's failing claims are quarantined where that store's claim query honors them.
 */
abstract class QuarantineDelegating implements DurableRunStore {
    private final DurableRunStore quarantines;

    QuarantineDelegating(DurableRunStore quarantines) {
        this.quarantines = quarantines;
    }

    @Override
    public boolean keepsClaimQuarantine() {
        return quarantines.keepsClaimQuarantine();
    }

    @Override
    public Optional<ClaimQuarantine> quarantine(UUID runId, ClaimQuarantine.Request request) {
        return quarantines.quarantine(runId, request);
    }
}
