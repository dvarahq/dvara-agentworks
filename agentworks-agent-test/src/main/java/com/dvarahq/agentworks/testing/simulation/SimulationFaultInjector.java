package com.dvarahq.agentworks.testing.simulation;

import java.util.List;

/** Extensible interception contract; future dependency classes do not require an API change. */
public interface SimulationFaultInjector {
    void before(DependencyCall call);
    List<SimulationFault> configuration();
    List<FaultInjection> injections();
    SimulationFaultInjector fresh();

    static SimulationFaultInjector none() { return DeterministicFaultInjector.none(); }
}
