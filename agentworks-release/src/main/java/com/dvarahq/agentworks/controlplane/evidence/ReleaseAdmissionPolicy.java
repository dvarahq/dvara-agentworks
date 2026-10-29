package com.dvarahq.agentworks.controlplane.evidence;

import com.dvarahq.agentworks.domain.lifecycle.state.LifecycleRows.ReleaseRow;

/** Promotion-time admission for independently verifiable release evidence. */
@FunctionalInterface
public interface ReleaseAdmissionPolicy {
    void requireAdmissible(ReleaseRow release);

    static ReleaseAdmissionPolicy permissive() { return release -> {}; }

    static ReleaseAdmissionPolicy allOf(ReleaseAdmissionPolicy... policies) {
        return release -> {
            for (ReleaseAdmissionPolicy policy : policies) policy.requireAdmissible(release);
        };
    }
}
