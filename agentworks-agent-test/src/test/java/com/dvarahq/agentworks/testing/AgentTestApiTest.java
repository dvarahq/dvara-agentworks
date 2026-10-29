package com.dvarahq.agentworks.testing;

import com.dvarahq.agentworks.testing.fake.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.*;
import java.time.*;
import java.util.*;

import static com.dvarahq.agentworks.testing.AgentTests.agent;
import static org.assertj.core.api.Assertions.*;

@AgentTest(value = "refund-agent", seed = 73)
@ResourceLock("agenttest-report-directory")
class AgentTestApiTest {
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

    @Test void annotationRunsARepeatableModelAndWritesMachineReadableEvidence() throws Exception {
        AgentTestRun run = agent().workspace("workspace-a").evaluator("assertions/1").givenUser("refund order 123").run()
                .expectStatus(AgentTestStatus.SUCCEEDED).expectAnswerContains("refund order")
                .expectNoTool("refund").expectNoToolExecution("refund").expectNoHumanInteraction()
                .expectMaxToolCalls(0).expectMaxTokens(20).expectMaxCost(BigDecimal.ZERO);

        assertThat(run.reportPath()).exists();
        var report = JsonMapper.builder().build().readTree(run.reportPath().toFile());
        assertThat(report.path("seed").longValue()).isEqualTo(73);
        assertThat(report.path("evaluatorIdentity").stringValue()).isEqualTo("assertions/1");
        assertThat(report.path("dependencies").path("model").stringValue()).isEqualTo("agent-test-fake/1");
        assertThat(report.path("traceDigest").stringValue()).hasSize(64);
    }

    @Test void completeExpectationSetHasActionableMismatchDiagnostics() throws Exception {
        var structured = JsonMapper.builder().build().readTree("{\"approved\":true}");
        AgentTestRuntime runtime = invocation -> new AgentTestResult(AgentTestStatus.BLOCKED, "approval required", structured,
                new AgentTestUsage(3, 2, 1, new BigDecimal("0.01")), List.of(
                event(1, AgentTestEvent.Type.TOOL_REQUESTED, "lookupOrder"),
                event(2, AgentTestEvent.Type.TOOL_EXECUTED, "lookupOrder"),
                event(3, AgentTestEvent.Type.POLICY_DECISION, "STEP_UP"),
                event(4, AgentTestEvent.Type.HUMAN_INTERACTION, "APPROVAL"),
                event(5, AgentTestEvent.Type.ARTIFACT, "receipt")), Map.of("runtime", "fake/1"));

        AgentTestRun run = agent().givenUser("large refund").runtime(runtime).run()
                .expectStatus(AgentTestStatus.BLOCKED).expectAnswerContains("approval")
                .expectTool("lookupOrder").expectHumanInteraction("APPROVAL")
                .expectPolicyDecision("STEP_UP").expectArtifact("receipt")
                .expectTrace("lookupOrder", "lookupOrder", "STEP_UP", "APPROVAL", "receipt")
                .expectStructuredOutput("{\"type\":\"object\",\"required\":[\"approved\"],\"properties\":{\"approved\":{\"type\":\"boolean\"}}}")
                .expectMaxToolCalls(1).expectMaxTokens(5).expectMaxCost(new BigDecimal("0.01"));
        assertThatThrownBy(() -> run.expectNoToolExecution("lookupOrder"))
                .isInstanceOfSatisfying(AgentTestAssertionError.class, error -> {
                    assertThat(error.mismatch().code()).isEqualTo("UNEXPECTED_TOOL_EXECUTION");
                    assertThat(error.getMessage()).contains("lookupOrder");
                });
        assertThat(Files.readString(run.reportPath())).contains("UNEXPECTED_TOOL_EXECUTION");
        assertThatThrownBy(() -> run.expectStructuredOutput("{\"$ref\":\"https://attacker.invalid/schema\"}"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("self-contained");
        assertThatThrownBy(() -> run.expectMaxTokens(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void forbiddenEffectsFailClosedUnlessIsolationAndEachEffectAreExplicit() {
        assertThatThrownBy(() -> agent().givenUser("x").allowLiveNetwork()).hasMessageContaining("isolated");
        AgentTestRuntime probes = invocation -> {
            invocation.effects().requireNetwork();
            invocation.effects().requireCredentials();
            return result("allowed");
        };
        assertThatThrownBy(() -> agent().givenUser("x").runtime(probes).run())
                .isInstanceOfSatisfying(ForbiddenEffectException.class, failure -> assertThat(failure.code()).isEqualTo("LIVE_NETWORK_FORBIDDEN"));
        agent().givenUser("x").isolated().allowLiveNetwork().allowLiveCredentials().runtime(probes).run().expectAnswerContains("allowed");
    }

    @Test void runtimeFailuresRemainNonzeroAndLeaveContentFreeEvidence() throws Exception {
        assertThatThrownBy(() -> agent().givenUser("secret-canary").runtime(invocation -> {
            throw new IllegalStateException("secret-canary");
        }).run()).isInstanceOf(IllegalStateException.class);
        Path report = Files.list(reports).findFirst().orElseThrow();
        assertThat(Files.readString(report)).contains("RUNTIME_FAILED").contains("java.lang.IllegalStateException")
                .doesNotContain("secret-canary");
    }

    private static AgentTestEvent event(long sequence, AgentTestEvent.Type type, String name) {
        return new AgentTestEvent(sequence, Instant.parse("2026-01-01T00:00:00Z"), type, name, Map.of());
    }
    private static AgentTestResult result(String answer) {
        return new AgentTestResult(AgentTestStatus.SUCCEEDED, answer, null, new AgentTestUsage(0, 0, 0, null),
                List.of(event(1, AgentTestEvent.Type.OUTPUT, "answer")), Map.of());
    }
}
