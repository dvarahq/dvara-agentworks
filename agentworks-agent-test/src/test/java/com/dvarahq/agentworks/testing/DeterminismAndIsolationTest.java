package com.dvarahq.agentworks.testing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

@ResourceLock("agenttest-report-directory")
class DeterminismAndIsolationTest {
    @Test void nondeterminismIsReportedInsteadOfRetriedToGreen() {
        AtomicInteger attempt = new AtomicInteger();
        AgentTestRuntime changing = invocation -> result("attempt-" + attempt.incrementAndGet());
        assertThatThrownBy(() -> new AgentFixture("agent", 1, "nondeterminism").givenUser("x").runtime(changing).run())
                .isInstanceOfSatisfying(AgentTestAssertionError.class,
                        failure -> assertThat(failure.mismatch().code()).isEqualTo("NONDETERMINISTIC_RESULT"));
        assertThat(attempt).hasValue(2);
    }

    @Test void parallelFixturesDoNotShareModelsOrSeeds() throws Exception {
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<String>> futures = new ArrayList<>();
            for (int index = 0; index < 20; index++) {
                long seed = index;
                futures.add(pool.submit(() -> new AgentFixture("agent", seed, "parallel-" + seed)
                        .givenUser("hello").run().result().answer()));
            }
            for (int index = 0; index < futures.size(); index++) assertThat(futures.get(index).get()).isEqualTo("echo: hello");
        }
    }

    @Test void aFailureOnlyOnTheRepeatIsReportedAsNondeterministic() {
        AtomicInteger attempt = new AtomicInteger();
        AgentTestRuntime secondFails = invocation -> {
            if (attempt.incrementAndGet() == 2) throw new IllegalStateException("second");
            return result("stable");
        };
        assertThatThrownBy(() -> new AgentFixture("agent", 1, "repeat-failure").givenUser("x").runtime(secondFails).run())
                .isInstanceOfSatisfying(AgentTestAssertionError.class,
                        failure -> assertThat(failure.mismatch().code()).isEqualTo("NONDETERMINISTIC_FAILURE"));
    }

    private static AgentTestResult result(String answer) {
        return new AgentTestResult(AgentTestStatus.SUCCEEDED, answer, null, null, List.of(), Map.of());
    }
}
