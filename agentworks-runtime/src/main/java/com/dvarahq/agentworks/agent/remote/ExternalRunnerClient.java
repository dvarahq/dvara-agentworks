package com.dvarahq.agentworks.agent.remote;

import com.dvarahq.agentworks.domain.external.ExternalAccessMode;
import com.dvarahq.agentworks.domain.external.ExternalAssurance;
import com.dvarahq.agentworks.domain.external.ExternalCapability;
import com.dvarahq.agentworks.domain.external.ExternalExecutionRequest;
import com.dvarahq.agentworks.domain.external.ExternalExecutionResult;
import com.dvarahq.agentworks.domain.external.ExternalExecutorEvent;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** NDJSON subprocess adapter; transport representation remains outside domain contracts. */
public final class ExternalRunnerClient {

    public static final String PROTOCOL_VERSION = "agentworks-executor/v1";
    private static final Set<String> TERMINAL = Set.of("RUN_SUCCEEDED", "RUN_FAILED", "RUN_CANCELLED", "RUN_TIMED_OUT");
    private static final Pattern FIELD = Pattern.compile("\\\"([^\\\"]+)\\\":(?:\\\"([^\\\"]*)\\\"|([0-9]+)|(true|false))");

    private final Path runnerScript;
    private volatile Path lastWorkspace;

    public ExternalRunnerClient(Path runnerScript) {
        this.runnerScript = runnerScript.toAbsolutePath().normalize();
    }

    public ExternalExecutionResult execute(ExternalExecutionRequest request,
            Set<ExternalCapability> requiredCapabilities, ExternalAssurance requestedAssurance,
            ExternalAccessMode accessMode, Duration timeout, BooleanSupplier cancelled,
            Function<String, String> mediatedTool) {
        Path workspace = createWorkspace();
        lastWorkspace = workspace;
        Process process = null;
        try {
            process = new ProcessBuilder("python3", "-I", runnerScript.toString())
                    .directory(workspace.toFile()).redirectErrorStream(true).start();
            BlockingQueue<String> output = new LinkedBlockingQueue<>();
            Process runningProcess = process;
            Thread.ofVirtual().start(() -> readOutput(runningProcess, output));
            try (BufferedWriter input = new BufferedWriter(new OutputStreamWriter(
                    process.getOutputStream(), StandardCharsets.UTF_8))) {
                send(input, handshake(requiredCapabilities, requestedAssurance, accessMode));
                Negotiation negotiation = negotiate(await(output, process, Duration.ofSeconds(2)),
                        requiredCapabilities, requestedAssurance, accessMode);
                send(input, start(request));
                List<ExternalExecutorEvent> events = consume(request, output, process, input, timeout,
                        cancelled, mediatedTool);
                return new ExternalExecutionResult(PROTOCOL_VERSION, negotiation.executorId(),
                        negotiation.assurance(), accessMode, negotiation.capabilities(), events);
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new ExternalProtocolException("External runner transport failed", failure);
        } catch (IOException failure) {
            throw new ExternalProtocolException("External runner transport failed", failure);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            deleteTree(workspace);
        }
    }

    Path lastWorkspace() {
        return lastWorkspace;
    }

    private static List<ExternalExecutorEvent> consume(ExternalExecutionRequest request,
            BlockingQueue<String> output, Process process, BufferedWriter input, Duration timeout,
            BooleanSupplier cancelled, Function<String, String> mediatedTool)
            throws InterruptedException, IOException {
        long deadline = System.nanoTime() + timeout.toNanos();
        long expectedSequence = 1;
        boolean terminal = false;
        boolean cancelSent = false;
        List<ExternalExecutorEvent> events = new ArrayList<>();
        while (true) {
            if (!cancelSent && cancelled.getAsBoolean()) {
                send(input, "{\"type\":\"cancel\",\"reason\":\"caller\"}");
                cancelSent = true;
            } else if (!cancelSent && System.nanoTime() >= deadline) {
                send(input, "{\"type\":\"cancel\",\"reason\":\"timeout\"}");
                cancelSent = true;
            }
            String line = output.poll(20, TimeUnit.MILLISECONDS);
            if (line == null) {
                if (!process.isAlive()) {
                    throw new ExternalProtocolException("Runner exited without a terminal event");
                }
                continue;
            }
            if (terminal) {
                throw new ExternalProtocolException("Late event received after terminal state");
            }
            ExternalExecutorEvent event = parseEvent(line);
            if (!event.runId().equals(request.runId()) || !event.traceId().equals(request.traceId())) {
                throw new ExternalProtocolException("Event correlation mismatch");
            }
            if (event.sequence() != expectedSequence++) {
                throw new ExternalProtocolException("Event sequence gap or duplicate");
            }
            events.add(event);
            if (event.type().equals("TOOL_REQUESTED")) {
                try {
                    String result = mediatedTool.apply(event.payload());
                    send(input, "{\"type\":\"tool_result\",\"result\":\"" + escape(result) + "\"}");
                } catch (RuntimeException toolFailure) {
                    send(input, "{\"type\":\"tool_error\"}");
                }
            }
            terminal = event.terminal();
            if (terminal) {
                String late = output.poll(100, TimeUnit.MILLISECONDS);
                if (late != null) {
                    throw new ExternalProtocolException("Late event received after terminal state");
                }
                return events;
            }
        }
    }

    private static Negotiation negotiate(String line, Set<ExternalCapability> required,
            ExternalAssurance requested, ExternalAccessMode accessMode) {
        if (!"handshake_ack".equals(field(line, "type"))) {
            throw new ExternalProtocolException("Malformed handshake response");
        }
        if (!PROTOCOL_VERSION.equals(field(line, "protocolVersion"))) {
            throw new ExternalProtocolException("Unsupported protocol version");
        }
        Set<ExternalCapability> advertised = EnumSet.noneOf(ExternalCapability.class);
        for (ExternalCapability capability : ExternalCapability.values()) {
            if (line.contains("\"" + capability.name() + "\"")) {
                advertised.add(capability);
            }
        }
        if (!advertised.containsAll(required)) {
            throw new ExternalProtocolException("Runner is missing a required capability");
        }
        ExternalAssurance achieved = ExternalAssurance.valueOf(field(line, "assurance"));
        if (accessMode == ExternalAccessMode.FRAMEWORK_DIRECT) {
            achieved = ExternalAssurance.BLACK_BOX;
        }
        if (requested == ExternalAssurance.DURABLE
                && (!advertised.contains(ExternalCapability.CHECKPOINT_EXPORT)
                || !advertised.contains(ExternalCapability.CHECKPOINT_RESTORE))) {
            throw new ExternalProtocolException("DURABLE assurance requires checkpoint export and restore evidence");
        }
        if (accessMode == ExternalAccessMode.FRAMEWORK_DIRECT && requested == ExternalAssurance.DURABLE) {
            throw new ExternalProtocolException("Framework-direct access cannot claim DURABLE assurance");
        }
        if (achieved.ordinal() < requested.ordinal()) {
            throw new ExternalProtocolException("Runner cannot provide requested assurance");
        }
        return new Negotiation(field(line, "executorId"), achieved, advertised);
    }

    private static ExternalExecutorEvent parseEvent(String line) {
        if (!"event".equals(field(line, "type"))) {
            throw new ExternalProtocolException("Malformed event envelope");
        }
        try {
            UUID runId = UUID.fromString(field(line, "runId"));
            String eventType = field(line, "eventType");
            return new ExternalExecutorEvent(runId, field(line, "traceId"),
                    Long.parseLong(field(line, "sequence")), eventType,
                    field(line, "payload"), TERMINAL.contains(eventType));
        } catch (RuntimeException failure) {
            throw new ExternalProtocolException("Malformed event envelope", failure);
        }
    }

    private static String handshake(Set<ExternalCapability> required, ExternalAssurance assurance,
            ExternalAccessMode accessMode) {
        String capabilities = required.stream().map(value -> "\"" + value.name() + "\"")
                .sorted().reduce((left, right) -> left + "," + right).orElse("");
        return "{\"type\":\"handshake\",\"protocolVersion\":\"" + PROTOCOL_VERSION
                + "\",\"requiredCapabilities\":[" + capabilities + "],\"requestedAssurance\":\""
                + assurance + "\",\"accessMode\":\"" + accessMode + "\"}";
    }

    private static String start(ExternalExecutionRequest request) {
        return "{\"type\":\"start\",\"runId\":\"" + request.runId() + "\",\"releaseId\":\""
                + request.releaseId() + "\",\"deploymentId\":\"" + request.deploymentId()
                + "\",\"traceId\":\"" + escape(request.traceId()) + "\",\"input\":\""
                + escape(request.input()) + "\"}";
    }

    private static String field(String json, String name) {
        Matcher matcher = FIELD.matcher(json);
        while (matcher.find()) {
            if (matcher.group(1).equals(name)) {
                if (matcher.group(2) != null) return matcher.group(2);
                if (matcher.group(3) != null) return matcher.group(3);
                return matcher.group(4);
            }
        }
        throw new ExternalProtocolException("Missing protocol field: " + name);
    }

    private static void send(BufferedWriter writer, String line) throws IOException {
        writer.write(line);
        writer.newLine();
        writer.flush();
    }

    private static String await(BlockingQueue<String> output, Process process, Duration timeout)
            throws InterruptedException {
        String line = output.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (line == null) {
            throw new ExternalProtocolException(process.isAlive()
                    ? "Runner handshake timed out" : "Runner exited during handshake");
        }
        return line;
    }

    private static void readOutput(Process process, BlockingQueue<String> output) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.add(line);
            }
        } catch (IOException ignored) {
            // The execution loop observes process termination and reports a normalized transport failure.
        }
    }

    private static Path createWorkspace() {
        try {
            return Files.createTempDirectory("dvara-external-runner-");
        } catch (IOException failure) {
            throw new ExternalProtocolException("Cannot create runner workspace", failure);
        }
    }

    private static void deleteTree(Path root) {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException failure) {
            throw new ExternalProtocolException("Cannot clean runner workspace", failure);
        }
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private record Negotiation(String executorId, ExternalAssurance assurance,
            Set<ExternalCapability> capabilities) {
    }
}
