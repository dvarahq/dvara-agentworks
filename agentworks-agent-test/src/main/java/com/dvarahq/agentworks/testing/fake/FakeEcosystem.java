package com.dvarahq.agentworks.testing.fake;

import com.dvarahq.agentworks.testing.simulation.SimulationFaultInjector;
import java.time.Instant;
import java.util.*;

/** Complete fake dependency set passed to the embedded test runtime. */
public final class FakeEcosystem {
    private FakeModel model = FakeModel.echo("agent-test-fake/1");
    private FakeClock clock = new FakeClock(Instant.parse("2000-01-01T00:00:00Z"));
    private FakeNetwork network = new FakeNetwork();
    private FakeDatabase database = new FakeDatabase();
    private SimulationFaultInjector faults = SimulationFaultInjector.none();
    private long seed;
    private final Map<String, FakeTool> tools = new LinkedHashMap<>();
    private final Map<String, FakeMcpServer> mcp = new LinkedHashMap<>();
    private final Map<String, FakeA2aPeer> a2a = new LinkedHashMap<>();
    public FakeEcosystem model(FakeModel value) { model = Objects.requireNonNull(value).withFaults(faults, seed); return this; }
    public FakeEcosystem clock(FakeClock value) { clock = Objects.requireNonNull(value); return this; }
    public FakeEcosystem network(FakeNetwork value) { network = Objects.requireNonNull(value); return this; }
    public FakeEcosystem database(FakeDatabase value) { database = Objects.requireNonNull(value); return this; }
    public FakeEcosystem tool(FakeTool value) { unique(tools, value.name(), value.withFaults(faults, seed)); return this; }
    public FakeEcosystem mcp(String name, FakeMcpServer value) { unique(mcp, name, value); return this; }
    public FakeEcosystem a2a(String name, FakeA2aPeer value) { unique(a2a, name, value); return this; }
    public FakeModel model() { return model; } public FakeClock clock() { return clock; } public FakeNetwork network() { return network; }
    public FakeDatabase database() { return database; }
    public Map<String, FakeTool> tools() { return Collections.unmodifiableMap(tools); }
    public Map<String, FakeMcpServer> mcp() { return Collections.unmodifiableMap(mcp); }
    public Map<String, FakeA2aPeer> a2a() { return Collections.unmodifiableMap(a2a); }
    public Map<String, String> versions() {
        Map<String, String> versions = new TreeMap<>(); versions.put("model", model.version());
        versions.put("clock", "fixed/1"); versions.put("network", "exact-route/1"); versions.put("database", "map/1");
        tools.keySet().forEach(name -> versions.put("tool:" + name, "fake/1"));
        mcp.forEach((name, fake) -> versions.put("mcp:" + name, fake.protocolVersion()));
        a2a.forEach((name, fake) -> versions.put("a2a:" + name, fake.protocolVersion())); return Map.copyOf(versions);
    }
    public FakeEcosystem fresh() {
        FakeEcosystem copy = new FakeEcosystem().faults(faults.fresh(), seed).model(model.fresh()).clock(clock.fresh())
                .network(network.fresh()).database(database.fresh());
        tools.values().forEach(tool -> copy.tool(tool.fresh()));
        mcp.forEach((name, server) -> copy.mcp(name, server.fresh()));
        a2a.forEach((name, peer) -> copy.a2a(name, peer.fresh()));
        return copy;
    }
    public FakeEcosystem faults(SimulationFaultInjector value, long environmentSeed) {
        faults = Objects.requireNonNull(value); seed = environmentSeed; model = model.withFaults(faults, seed);
        tools.replaceAll((name, tool) -> tool.withFaults(faults, seed)); return this;
    }
    private static <T> void unique(Map<String, T> values, String name, T value) {
        if (name == null || name.isBlank() || values.putIfAbsent(name, Objects.requireNonNull(value)) != null) throw new IllegalArgumentException("duplicate or blank fake: " + name);
    }
}
