package com.dvarahq.agentworks.testing.simulation;

import java.util.*;

final class DeterministicFaultInjector implements SimulationFaultInjector {
    private final List<SimulationFault> faults;
    private final Map<String, Long> occurrences = new HashMap<>();
    private final List<FaultInjection> injections = new ArrayList<>();
    DeterministicFaultInjector(List<SimulationFault> faults) { this.faults = List.copyOf(faults); }
    static SimulationFaultInjector none() { return new DeterministicFaultInjector(List.of()); }
    @Override public synchronized void before(DependencyCall call) {
        String key = call.dependencyClass() + "\u0000" + call.dependency() + "\u0000" + call.operation();
        long occurrence = occurrences.merge(key, 1L, Long::sum);
        faults.stream().filter(fault -> fault.matches(call, occurrence)).findFirst().ifPresent(fault -> {
            FaultInjection injection = new FaultInjection(fault.id(), call.dependencyClass(), call.dependency(),
                    call.operation(), occurrence, call.seed(), fault.failureCode());
            injections.add(injection); throw new InjectedFaultException(injection);
        });
    }
    @Override public List<SimulationFault> configuration() { return faults; }
    @Override public synchronized List<FaultInjection> injections() { return List.copyOf(injections); }
    @Override public SimulationFaultInjector fresh() { return new DeterministicFaultInjector(faults); }
}
