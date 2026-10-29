package com.dvarahq.agentworks.testing.simulation;

import com.dvarahq.agentworks.testing.fake.FakeEcosystem;
import java.util.*;

/** One isolated environment instance. Closing it releases registered resources in reverse order. */
public final class SimulationSession implements AutoCloseable {
    private final String environment;
    private final long seed;
    private final FakeEcosystem fakes;
    private final SimulationFaultInjector faults;
    private final Map<String, AutoCloseable> resources;
    private final Map<String, String> cleanup = new LinkedHashMap<>();
    private boolean closed;
    SimulationSession(String environment, long seed, FakeEcosystem fakes, SimulationFaultInjector faults,
            Map<String, AutoCloseable> resources) {
        this.environment = environment; this.seed = seed; this.fakes = fakes; this.faults = faults;
        this.resources = new LinkedHashMap<>(resources);
    }
    public static SimulationSession direct(FakeEcosystem fakes, long seed) {
        return new SimulationSession("direct-agent-test", seed, Objects.requireNonNull(fakes),
                SimulationFaultInjector.none(), Map.of());
    }
    public FakeEcosystem fakes() { return fakes; }
    /** Intercept a future dependency class without changing the simulation contracts. */
    public void before(DependencyCall call) { faults.before(Objects.requireNonNull(call)); }
    public <T> T resource(String name, Class<T> type) {
        Object resource = resources.get(name);
        if (resource == null) throw new IllegalArgumentException("simulation resource is not registered: " + name);
        return type.cast(resource);
    }
    public synchronized SimulationEvidence evidence() {
        return new SimulationEvidence(environment, 1, seed, faults.configuration(), faults.injections(), cleanup);
    }
    @Override public synchronized void close() {
        if (closed) return;
        List<Map.Entry<String, AutoCloseable>> ordered = new ArrayList<>(resources.entrySet()); Collections.reverse(ordered);
        RuntimeException failure = null;
        for (var entry : ordered) try { entry.getValue().close(); cleanup.put(entry.getKey(), "CLOSED"); }
        catch (Exception closeFailure) { cleanup.put(entry.getKey(), "CLOSE_FAILED"); if (failure == null) failure = new IllegalStateException("SIMULATION_CLEANUP_FAILED: " + entry.getKey(), closeFailure); }
        closed = true; if (failure != null) throw failure;
    }
}
