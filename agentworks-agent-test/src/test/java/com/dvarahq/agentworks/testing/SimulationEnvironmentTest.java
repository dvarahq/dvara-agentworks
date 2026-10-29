package com.dvarahq.agentworks.testing;

import com.dvarahq.agentworks.testing.fake.*;
import com.dvarahq.agentworks.testing.simulation.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

@ResourceLock("agenttest-report-directory")
class SimulationEnvironmentTest {
    @TempDir Path reports;
    private String oldDirectory;

    @BeforeEach void reportsToTestDirectory() {
        oldDirectory = System.getProperty("agentworks.agenttest.reportDirectory");
        System.setProperty("agentworks.agenttest.reportDirectory", reports.toString());
    }
    @AfterEach void restoreReportDirectory() {
        if (oldDirectory == null) System.clearProperty("agentworks.agenttest.reportDirectory");
        else System.setProperty("agentworks.agenttest.reportDirectory", oldDirectory);
    }

    @Test void reusableEnvironmentComposesFreshFixedDependencies() {
        SimulationEnvironment environment = completeEnvironment().build();
        try (SimulationSession first = environment.instantiate(7); SimulationSession second = environment.instantiate(7)) {
            assertThat(first.fakes().clock().instant()).isEqualTo(Instant.EPOCH);
            assertThat(first.fakes().network().get(URI.create("https://simulation.invalid/status")).body()).isEqualTo("ok");
            assertThat(first.fakes().mcp()).containsKey("orders");
            assertThat(first.fakes().a2a()).containsKey("fulfillment");
            assertThat(first.fakes().database().get("order-7")).contains("pending");
            first.fakes().database().put("order-7", "changed");
            assertThat(second.fakes().database().get("order-7")).contains("pending");
        }
    }

    @Test void selectedModelOccurrenceAndSeedAreReportedAndCrashCleanupRuns() throws Exception {
        AtomicInteger closed = new AtomicInteger();
        SimulationEnvironment environment = completeEnvironment()
                .fault(SimulationFault.model("model-second", "model/1", 2, "MODEL_TIMEOUT").onlyForSeed(41))
                .resource("worker", () -> closed::incrementAndGet).build();
        AgentTestRuntime twice = invocation -> {
            invocation.fakes().model().invoke(new FakeModel.Request("first", invocation.seed()));
            invocation.fakes().model().invoke(new FakeModel.Request("second", invocation.seed()));
            return result("unreachable");
        };

        assertThatThrownBy(() -> new AgentFixture("agent", 41, "model-fault").givenUser("go")
                .simulation(environment).runtime(twice).run())
                .isInstanceOfSatisfying(InjectedFaultException.class, failure -> {
                    assertThat(failure.injection().failureCode()).isEqualTo("MODEL_TIMEOUT");
                    assertThat(failure.injection().occurrence()).isEqualTo(2);
                });
        assertThat(closed).hasValue(1);
        var report = JsonMapper.builder().build().readTree(reports.resolve("model-fault.json").toFile());
        assertThat(report.path("simulation").path("environment").stringValue()).isEqualTo("checkout-world");
        assertThat(report.path("simulation").path("seed").longValue()).isEqualTo(41);
        assertThat(report.path("simulation").path("faults").get(0).path("id").stringValue()).isEqualTo("model-second");
        assertThat(report.path("simulation").path("injections").get(0).path("occurrence").longValue()).isEqualTo(2);
        assertThat(report.path("simulation").path("cleanup").path("worker").stringValue()).isEqualTo("CLOSED");
    }

    @Test void toolFaultsAreOccurrenceBoundAndDoNotEscapeFreshSessions() {
        SimulationEnvironment environment = completeEnvironment()
                .fault(SimulationFault.tool("tool-second", "lookup", 2, "TOOL_UNAVAILABLE")).build();
        for (int run = 0; run < 2; run++) {
            try (SimulationSession session = environment.instantiate(9)) {
                FakeTool tool = session.fakes().tools().get("lookup");
                assertThat(tool.invoke(Map.of("id", 1))).isEqualTo("order-1");
                assertThatThrownBy(() -> tool.invoke(Map.of("id", 2)))
                        .isInstanceOfSatisfying(InjectedFaultException.class,
                                failure -> assertThat(failure.injection().occurrence()).isEqualTo(2));
            }
        }
    }

    @Test void agentTestRepeatsAgainstFreshSameSeedSimulationState() {
        SimulationEnvironment environment = completeEnvironment().build();
        AgentTestRuntime mutating = invocation -> {
            Object before = invocation.fakes().database().get("run-value").orElse("empty");
            invocation.fakes().database().put("run-value", "changed");
            return result(before + ":" + invocation.seed());
        };
        new AgentFixture("agent-release-1", 29, "fresh-repeat").givenUser("go")
                .simulation(environment).runtime(mutating).run().expectAnswerContains("empty:29");
    }

    @Test void faultSpiAcceptsFutureDependencyClassesWithoutContractChanges() {
        SimulationFault future = new SimulationFault("memory-first", "memory", "conversation", "read", 1, null, "MEMORY_DOWN");
        try (SimulationSession session = completeEnvironment().fault(future).build().instantiate(3)) {
            assertThatThrownBy(() -> session.before(new DependencyCall("memory", "conversation", "read", 3)))
                    .isInstanceOf(InjectedFaultException.class).hasMessageContaining("MEMORY_DOWN");
        }
    }

    @Test void cancellationStillClosesEveryRunLocalResource() {
        AtomicInteger closed = new AtomicInteger();
        SimulationEnvironment environment = completeEnvironment().resource("lease", () -> closed::incrementAndGet).build();
        assertThatThrownBy(() -> new AgentFixture("agent", 1, "cancel-cleanup").givenUser("go")
                .simulation(environment).runtime(invocation -> { throw new CancellationException("cancelled"); }).run())
                .isInstanceOf(CancellationException.class);
        assertThat(closed).hasValue(1);
    }

    @Test void parallelSessionsHaveIndependentFaultOccurrencesAndState() throws Exception {
        SimulationEnvironment environment = completeEnvironment()
                .fault(SimulationFault.tool("first", "lookup", 1, "TOOL_DOWN")).build();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Long>> futures = new ArrayList<>();
            for (int index = 0; index < 12; index++) futures.add(pool.submit(() -> {
                try (SimulationSession session = environment.instantiate(17)) {
                    assertThatThrownBy(() -> session.fakes().tools().get("lookup").invoke(Map.of("id", 1)))
                            .isInstanceOf(InjectedFaultException.class);
                    return session.evidence().injections().getFirst().occurrence();
                }
            }));
            for (Future<Long> future : futures) assertThat(future.get()).isEqualTo(1);
        }
    }

    @Test void unregisteredNetworkAlwaysFailsClosedInsideTheSimulation() {
        try (SimulationSession session = completeEnvironment().build().instantiate(5)) {
            assertThatThrownBy(() -> session.fakes().network().get(URI.create("https://live.invalid")))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("ROUTE_MISSING");
        }
    }

    private static SimulationEnvironment.Builder completeEnvironment() {
        FakeTool tool = new FakeTool("lookup", arguments -> "order-" + arguments.get("id"));
        return SimulationEnvironment.named("checkout-world")
                .model(new FakeModel("model/1", request -> new FakeModel.Reply("ok", 1, 1)))
                .tool(tool).mcp("orders", new FakeMcpServer("2025-06-18").tool(tool))
                .a2a("fulfillment", new FakeA2aPeer("a2a/1", request -> "done"))
                .clock(new FakeClock(Instant.EPOCH))
                .network(new FakeNetwork().route(URI.create("https://simulation.invalid/status"), new FakeNetwork.Response(200, "ok")))
                .database(new FakeDatabase().put("order-7", "pending"));
    }
    private static AgentTestResult result(String answer) {
        return new AgentTestResult(AgentTestStatus.SUCCEEDED, answer, null, null, List.of(), Map.of());
    }
}
