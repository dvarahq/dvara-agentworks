package com.dvarahq.agentworks.agent.remote;

import com.dvarahq.agentworks.domain.external.ExternalAccessMode;
import com.dvarahq.agentworks.domain.external.ExternalAssurance;
import com.dvarahq.agentworks.domain.external.ExternalCapability;
import com.dvarahq.agentworks.domain.external.ExternalExecutionRequest;
import com.dvarahq.agentworks.domain.external.ExternalExecutionResult;
import com.dvarahq.agentworks.domain.external.ExternalExecutorEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExternalRunnerClientTest {

    private static final Set<ExternalCapability> INSTRUMENTED = Set.of(
            ExternalCapability.EVENT_STREAMING,
            ExternalCapability.CANCELLATION,
            ExternalCapability.TOOL_MEDIATION,
            ExternalCapability.USAGE_REPORTING);

    @TempDir
    Path temporaryDirectory;

    @Test
    void negotiatesAndExecutesOrderedCorrelatedMediatedFlow() {
        ExternalRunnerClient client = client();
        ExternalExecutionRequest request = request("success");
        AtomicReference<String> mediatedTool = new AtomicReference<>();

        ExternalExecutionResult result = client.execute(request, INSTRUMENTED,
                ExternalAssurance.INSTRUMENTED, ExternalAccessMode.DVARAOS_MEDIATED,
                Duration.ofSeconds(3), () -> false, tool -> {
                    mediatedTool.set(tool);
                    return "available";
                });

        assertThat(result.protocolVersion()).isEqualTo(ExternalRunnerClient.PROTOCOL_VERSION);
        assertThat(result.executorId()).isEqualTo("fake-python/1");
        assertThat(result.assurance()).isEqualTo(ExternalAssurance.INSTRUMENTED);
        assertThat(result.capabilities()).containsAll(INSTRUMENTED);
        assertThat(result.events()).extracting(ExternalExecutorEvent::type).containsExactly(
                "RUN_ACTIVATED", "TOOL_REQUESTED", "TOOL_COMPLETED", "USAGE_REPORTED", "RUN_SUCCEEDED");
        assertThat(result.events()).extracting(ExternalExecutorEvent::sequence).containsExactly(1L, 2L, 3L, 4L, 5L);
        assertThat(result.events()).allSatisfy(event -> {
            assertThat(event.runId()).isEqualTo(request.runId());
            assertThat(event.traceId()).isEqualTo(request.traceId());
        });
        assertThat(mediatedTool).hasValue("calendar.lookup");
        assertThat(client.lastWorkspace()).doesNotExist();
    }

    @Test
    void runnerFailureAndToolFailureBecomeNormalizedTerminalEvidence() {
        ExternalExecutionResult runnerFailure = execute("failure", tool -> "unused");
        ExternalExecutionResult toolFailure = execute("success", tool -> {
            throw new IllegalStateException("injected tool failure");
        });

        assertThat(last(runnerFailure).type()).isEqualTo("RUN_FAILED");
        assertThat(last(runnerFailure).payload()).isEqualTo("RUNNER_FAILURE");
        assertThat(last(toolFailure).type()).isEqualTo("RUN_FAILED");
        assertThat(last(toolFailure).payload()).isEqualTo("TOOL_FAILURE");
    }

    @Test
    void cancellationPropagatesAndProducesDeterministicTerminalState() throws Exception {
        AtomicBoolean cancelled = new AtomicBoolean();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var future = executor.submit(() -> client().execute(request("cancel"), INSTRUMENTED,
                    ExternalAssurance.INSTRUMENTED, ExternalAccessMode.DVARAOS_MEDIATED,
                    Duration.ofSeconds(5), cancelled::get, tool -> "unused"));
            Thread.sleep(100);
            cancelled.set(true);

            ExternalExecutionResult result = future.get(3, TimeUnit.SECONDS);
            assertThat(last(result).type()).isEqualTo("RUN_CANCELLED");
            assertThat(last(result).payload()).isEqualTo("caller");
        }
    }

    @Test
    void timeoutPropagatesAndProducesDeterministicTerminalState() {
        ExternalExecutionResult result = client().execute(request("slow"), INSTRUMENTED,
                ExternalAssurance.INSTRUMENTED, ExternalAccessMode.DVARAOS_MEDIATED,
                Duration.ofMillis(100), () -> false, tool -> "unused");

        assertThat(last(result).type()).isEqualTo("RUN_TIMED_OUT");
        assertThat(last(result).payload()).isEqualTo("timeout");
    }

    @Test
    void unsupportedCapabilityAndFalseDurableClaimAreRejected() {
        assertThatThrownBy(() -> client().execute(request("success"),
                Set.of(ExternalCapability.CHECKPOINT_RESTORE), ExternalAssurance.INSTRUMENTED,
                ExternalAccessMode.DVARAOS_MEDIATED, Duration.ofSeconds(2), () -> false, tool -> "unused"))
                .isInstanceOf(ExternalProtocolException.class)
                .hasMessageContaining("missing a required capability");
        assertThatThrownBy(() -> client().execute(request("success"), INSTRUMENTED,
                ExternalAssurance.DURABLE, ExternalAccessMode.DVARAOS_MEDIATED,
                Duration.ofSeconds(2), () -> false, tool -> "unused"))
                .isInstanceOf(ExternalProtocolException.class)
                .hasMessageContaining("DURABLE assurance requires");
    }

    @Test
    void frameworkDirectModeIsExplicitlyReducedToBlackBox() {
        ExternalExecutionResult result = client().execute(request("failure"), Set.of(),
                ExternalAssurance.BLACK_BOX, ExternalAccessMode.FRAMEWORK_DIRECT,
                Duration.ofSeconds(2), () -> false, tool -> "unused");

        assertThat(result.accessMode()).isEqualTo(ExternalAccessMode.FRAMEWORK_DIRECT);
        assertThat(result.assurance()).isEqualTo(ExternalAssurance.BLACK_BOX);
        assertThatThrownBy(() -> client().execute(request("failure"), Set.of(),
                ExternalAssurance.DURABLE, ExternalAccessMode.FRAMEWORK_DIRECT,
                Duration.ofSeconds(2), () -> false, tool -> "unused"))
                .isInstanceOf(ExternalProtocolException.class);
    }

    @Test
    void malformedOrderingCorrelationLateEventsAndCrashFailClosed() {
        for (String mode : List.of("out_of_order", "duplicate", "correlation_mismatch", "late_event", "crash")) {
            assertThatThrownBy(() -> client().execute(request(mode), INSTRUMENTED,
                    ExternalAssurance.INSTRUMENTED, ExternalAccessMode.DVARAOS_MEDIATED,
                    Duration.ofSeconds(2), () -> false, tool -> "ok"))
                    .as(mode)
                    .isInstanceOf(ExternalProtocolException.class);
        }
    }

    @Test
    void protocolVersionMismatchIsRejectedAndDomainContractsRemainFrameworkNeutral() throws Exception {
        Path original = fakeRunner();
        Path incompatible = temporaryDirectory.resolve("incompatible_runner.py");
        Files.writeString(incompatible, Files.readString(original)
                .replace("PROTOCOL = \"agentworks-executor/v1\"", "PROTOCOL = \"unsupported/v9\""));

        assertThatThrownBy(() -> new ExternalRunnerClient(incompatible).execute(request("failure"), Set.of(),
                ExternalAssurance.BLACK_BOX, ExternalAccessMode.DVARAOS_MEDIATED,
                Duration.ofSeconds(2), () -> false, tool -> "unused"))
                .isInstanceOf(ExternalProtocolException.class)
                .hasMessageContaining("Unsupported protocol version");
        assertThat(List.of(ExternalExecutionRequest.class, ExternalExecutionResult.class, ExternalExecutorEvent.class))
                .allSatisfy(type -> assertThat(type.getName().toLowerCase())
                        .doesNotContain("python", "langgraph", "crewai", "openai"));
    }

    private ExternalExecutionResult execute(String mode, java.util.function.Function<String, String> tool) {
        return client().execute(request(mode), INSTRUMENTED, ExternalAssurance.INSTRUMENTED,
                ExternalAccessMode.DVARAOS_MEDIATED, Duration.ofSeconds(2), () -> false, tool);
    }

    private static ExternalExecutionRequest request(String input) {
        return new ExternalExecutionRequest(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "trace-" + UUID.randomUUID(), input);
    }

    private static ExternalExecutorEvent last(ExternalExecutionResult result) {
        return result.events().getLast();
    }

    private static ExternalRunnerClient client() {
        return new ExternalRunnerClient(fakeRunner());
    }

    private static Path fakeRunner() {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null) {
            Path candidate = directory.resolve("scripts/fake_external_runner.py");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException("Cannot locate scripts/fake_external_runner.py from the test working directory");
    }
}
