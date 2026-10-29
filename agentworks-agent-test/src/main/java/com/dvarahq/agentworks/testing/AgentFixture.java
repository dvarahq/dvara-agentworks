package com.dvarahq.agentworks.testing;

import com.dvarahq.agentworks.testing.fake.*;
import com.dvarahq.agentworks.testing.simulation.*;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.function.Function;

/** Fluent, one-run fixture. It carries no server, database, Spring, credential, or network dependency. */
public final class AgentFixture {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final String agent;
    private final long seed;
    private final String testId;
    private String workspace = "agent-test";
    private String userMessage;
    private final Map<String, Object> fixtures = new LinkedHashMap<>();
    private final FakeEcosystem fakes = new FakeEcosystem();
    private AgentTestRuntime runtime = new DefaultFakeRuntime();
    private EffectPolicy effects = EffectPolicy.denyAll();
    private String evaluatorIdentity = "none";
    private boolean determinismCheck = true;
    private SimulationEnvironment simulation;

    AgentFixture(String agent, long seed, String testId) {
        if (agent == null || agent.isBlank()) throw new IllegalArgumentException("agent is required");
        this.agent = agent; this.seed = seed; this.testId = testId;
    }
    AgentFixture copy() { return new AgentFixture(agent, seed, testId); }
    public AgentFixture workspace(String value) { workspace = requireText(value, "workspace"); return this; }
    public AgentFixture givenUser(String value) { userMessage = requireText(value, "userMessage"); return this; }
    public AgentFixture fixture(String name, Object value) { unique(fixtures, name, value); return this; }
    public AgentFixture runtime(AgentTestRuntime value) { runtime = Objects.requireNonNull(value); return this; }
    public AgentFixture model(FakeModel value) { fakes.model(value); return this; }
    public AgentFixture tool(String name, Function<Map<String, Object>, Object> handler) { fakes.tool(new FakeTool(name, handler)); return this; }
    public AgentFixture mcp(String name, FakeMcpServer server) { fakes.mcp(name, server); return this; }
    public AgentFixture a2a(String name, FakeA2aPeer peer) { fakes.a2a(name, peer); return this; }
    public AgentFixture clock(FakeClock clock) { fakes.clock(clock); return this; }
    public AgentFixture network(FakeNetwork network) { fakes.network(network); return this; }
    public AgentFixture database(FakeDatabase database) { fakes.database(database); return this; }
    public AgentFixture simulation(SimulationEnvironment value) { simulation = Objects.requireNonNull(value); return this; }
    public AgentFixture evaluator(String identity) { evaluatorIdentity = requireText(identity, "evaluatorIdentity"); return this; }
    public AgentFixture isolated() { effects = new EffectPolicy(true, false, false); return this; }
    public AgentFixture allowLiveNetwork() { requireIsolated(); effects = new EffectPolicy(true, true, effects.liveCredentials()); return this; }
    public AgentFixture allowLiveCredentials() { requireIsolated(); effects = new EffectPolicy(true, effects.liveNetwork(), true); return this; }
    /** Disable only when deliberately testing a nondeterministic dependency; the report still records the seed. */
    public AgentFixture allowNondeterminism() { determinismCheck = false; return this; }

    public AgentTestRun run() {
        requireText(userMessage, "userMessage");
        AgentTestReporter reporter = new AgentTestReporter();
        SimulationSession firstSession = session(false);
        AgentTestResult first;
        try {
            first = execute(firstSession);
        } catch (RuntimeException | AssertionError failure) {
            AgentTestResult failed = new AgentTestResult(AgentTestStatus.FAILED, "", null, null, List.of(), firstSession.fakes().versions());
            AgentTestMismatch mismatch = new AgentTestMismatch("RUNTIME_FAILED", "successful result", failure.getClass().getName());
            reporter.write(new AgentTestReport(2, testId, agent, workspace, seed, firstSession.fakes().clock().instant(),
                    digest(stableFixtures()), evaluatorIdentity, firstSession.fakes().versions(), failed, fingerprint(failed),
                    firstSession.evidence(), List.of(mismatch)));
            throw failure;
        }
        String firstFingerprint = fingerprint(first);
        Map<String, String> dependencies = new TreeMap<>(firstSession.fakes().versions());
        dependencies.putAll(first.dependencyVersions()); dependencies.put("agent-test", "1");
        AgentTestReport report = new AgentTestReport(2, testId, agent, workspace, seed, firstSession.fakes().clock().instant(),
                digest(stableFixtures()), evaluatorIdentity, Map.copyOf(dependencies), first, firstFingerprint,
                firstSession.evidence(), List.of());
        if (determinismCheck) {
            SimulationSession repeatSession = session(true);
            AgentTestResult repeat;
            try {
                repeat = execute(repeatSession);
            } catch (RuntimeException | AssertionError failure) {
                AgentTestMismatch mismatch = new AgentTestMismatch("NONDETERMINISTIC_FAILURE", "repeat result",
                        failure.getClass().getName());
                reporter.write(new AgentTestReport(2, testId, agent, workspace, seed, firstSession.fakes().clock().instant(),
                        report.fixtureDigest(), evaluatorIdentity, report.dependencies(), first, firstFingerprint,
                        report.simulation(), List.of(mismatch)));
                throw new AgentTestAssertionError(mismatch);
            }
            String repeatFingerprint = fingerprint(repeat);
            if (!firstFingerprint.equals(repeatFingerprint)) {
                AgentTestMismatch mismatch = new AgentTestMismatch("NONDETERMINISTIC_RESULT", firstFingerprint, repeatFingerprint);
                reporter.write(new AgentTestReport(2, testId, agent, workspace, seed, firstSession.fakes().clock().instant(),
                        report.fixtureDigest(), evaluatorIdentity, report.dependencies(), first, firstFingerprint,
                        report.simulation(), List.of(mismatch)));
                throw new AgentTestAssertionError(mismatch);
            }
        }
        return new AgentTestRun(first, report, reporter);
    }

    private SimulationSession session(boolean repeat) {
        if (simulation != null) return simulation.instantiate(seed);
        return SimulationSession.direct(repeat ? fakes.fresh() : fakes, seed);
    }
    private AgentTestResult execute(SimulationSession session) {
        Throwable executionFailure = null;
        try {
            AgentTestInvocation invocation = new AgentTestInvocation(agent, workspace, userMessage, seed,
                    fixtures, session.fakes(), effects, session);
            return Objects.requireNonNull(runtime.execute(invocation), "runtime result");
        } catch (RuntimeException | AssertionError failure) {
            executionFailure = failure; throw failure;
        } finally {
            try { session.close(); }
            catch (RuntimeException cleanupFailure) {
                if (executionFailure != null) executionFailure.addSuppressed(cleanupFailure); else throw cleanupFailure;
            }
        }
    }

    private String fingerprint(AgentTestResult result) {
        try {
            List<?> events = result.events().stream().map(event -> List.of(event.sequence(), event.at().toString(),
                    event.type().name(), event.name(), new TreeMap<>(event.attributes()))).toList();
            return digest(JSON.writeValueAsString(List.of(result.status().name(), result.answer(),
                    result.structuredOutput() == null ? "null" : result.structuredOutput(), result.usage(), events,
                    new TreeMap<>(result.dependencyVersions()))));
        } catch (Exception failure) { throw new IllegalStateException("AGENT_TEST_FINGERPRINT_FAILED", failure); }
    }
    private String stableFixtures() {
        TreeMap<String, String> stable = new TreeMap<>(); fixtures.forEach((key, value) -> stable.put(key, String.valueOf(value)));
        return stable.toString();
    }
    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private void requireIsolated() { if (!effects.isolated()) throw new IllegalStateException("call isolated() before opting into a live effect"); }
    private static String requireText(String value, String name) { if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required"); return value; }
    private static void unique(Map<String, Object> values, String name, Object value) {
        if (name == null || name.isBlank() || values.putIfAbsent(name, Objects.requireNonNull(value)) != null) throw new IllegalArgumentException("duplicate or blank fixture: " + name);
    }
}
