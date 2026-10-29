package com.dvarahq.agentworks.testing.simulation;

import com.dvarahq.agentworks.testing.fake.*;
import java.util.*;
import java.util.function.Supplier;

/** Immutable, reusable simulation recipe; each instantiate call creates isolated dependency state and resources. */
public final class SimulationEnvironment {
    private final String name;
    private final FakeEcosystem template;
    private final List<SimulationFault> faults;
    private final Map<String, Supplier<? extends AutoCloseable>> resources;
    private SimulationEnvironment(Builder builder) {
        name = builder.name; template = builder.fakes.fresh(); faults = List.copyOf(builder.faults);
        resources = Collections.unmodifiableMap(new LinkedHashMap<>(builder.resources));
    }
    public static Builder named(String name) { return new Builder(name); }
    public SimulationSession instantiate(long seed) {
        SimulationFaultInjector injector = new DeterministicFaultInjector(faults);
        FakeEcosystem fakes = template.fresh().faults(injector, seed);
        Map<String, AutoCloseable> opened = new LinkedHashMap<>();
        try { resources.forEach((resourceName, factory) -> opened.put(resourceName, Objects.requireNonNull(factory.get()))); }
        catch (RuntimeException failure) {
            List<AutoCloseable> values = new ArrayList<>(opened.values()); Collections.reverse(values);
            values.forEach(value -> { try { value.close(); } catch (Exception suppressed) { failure.addSuppressed(suppressed); } });
            throw failure;
        }
        return new SimulationSession(name, seed, fakes, injector, opened);
    }
    public static final class Builder {
        private final String name;
        private final FakeEcosystem fakes = new FakeEcosystem();
        private final List<SimulationFault> faults = new ArrayList<>();
        private final Set<String> faultIds = new HashSet<>();
        private final Map<String, Supplier<? extends AutoCloseable>> resources = new LinkedHashMap<>();
        private Builder(String name) { if (name == null || name.isBlank()) throw new IllegalArgumentException("name is required"); this.name = name; }
        public Builder model(FakeModel value) { fakes.model(value); return this; }
        public Builder tool(FakeTool value) { fakes.tool(value); return this; }
        public Builder mcp(String name, FakeMcpServer value) { fakes.mcp(name, value); return this; }
        public Builder a2a(String name, FakeA2aPeer value) { fakes.a2a(name, value); return this; }
        public Builder clock(FakeClock value) { fakes.clock(value); return this; }
        public Builder network(FakeNetwork value) { fakes.network(value); return this; }
        public Builder database(FakeDatabase value) { fakes.database(value); return this; }
        public Builder fault(SimulationFault value) {
            Objects.requireNonNull(value);
            if (!faultIds.add(value.id())) throw new IllegalArgumentException("duplicate fault id: " + value.id());
            faults.add(value); return this;
        }
        public Builder resource(String name, Supplier<? extends AutoCloseable> factory) {
            if (name == null || name.isBlank() || resources.putIfAbsent(name, Objects.requireNonNull(factory)) != null)
                throw new IllegalArgumentException("duplicate or blank resource: " + name);
            return this;
        }
        public SimulationEnvironment build() { return new SimulationEnvironment(this); }
    }
}
