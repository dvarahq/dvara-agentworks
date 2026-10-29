package com.dvarahq.agentworks.testing.simulation;

/** Typed, content-free failure raised at the selected simulated occurrence. */
public final class InjectedFaultException extends RuntimeException {
    private final FaultInjection injection;
    public InjectedFaultException(FaultInjection injection) {
        super("SIMULATION_FAULT: " + injection.failureCode()); this.injection = injection;
    }
    public FaultInjection injection() { return injection; }
}
