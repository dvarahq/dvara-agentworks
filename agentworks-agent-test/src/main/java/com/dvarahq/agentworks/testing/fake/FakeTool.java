package com.dvarahq.agentworks.testing.fake;

import com.dvarahq.agentworks.testing.simulation.*;
import java.util.*;
import java.util.function.Function;

public final class FakeTool {
    private final String name;
    private final Function<Map<String, Object>, Object> handler;
    private final SimulationFaultInjector faults;
    private final long seed;
    private final List<Map<String, Object>> calls = new ArrayList<>();
    public FakeTool(String name, Function<Map<String, Object>, Object> handler) {
        this(name, handler, SimulationFaultInjector.none(), 0);
    }
    private FakeTool(String name, Function<Map<String, Object>, Object> handler, SimulationFaultInjector faults, long seed) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name is required");
        this.name = name; this.handler = Objects.requireNonNull(handler); this.faults = Objects.requireNonNull(faults); this.seed = seed;
    }
    public synchronized Object invoke(Map<String, Object> arguments) {
        faults.before(new DependencyCall("tool", name, "invoke", seed));
        Map<String, Object> copy = Map.copyOf(arguments); calls.add(copy); return handler.apply(copy);
    }
    public String name() { return name; }
    public synchronized List<Map<String, Object>> calls() { return List.copyOf(calls); }
    public FakeTool fresh() { return new FakeTool(name, handler, faults.fresh(), seed); }
    public FakeTool withFaults(SimulationFaultInjector value, long environmentSeed) { return new FakeTool(name, handler, value, environmentSeed); }
}
