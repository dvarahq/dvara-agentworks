package com.dvarahq.agentworks.runplane.fixture;

import com.dvarahq.agentworks.controlplane.mutation.ControlPlaneMutationAuthority;

public final class RunPlaneDependingOnMutationAuthority {

    private final ControlPlaneMutationAuthority mutationAuthority;

    public RunPlaneDependingOnMutationAuthority(ControlPlaneMutationAuthority mutationAuthority) {
        this.mutationAuthority = mutationAuthority;
    }
}
