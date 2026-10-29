package com.dvarahq.agentworks.sandbox.docker;

import com.dvarahq.agentworks.domain.sandbox.SandboxConfiguration;
import com.dvarahq.agentworks.domain.sandbox.SandboxOwner;
import com.dvarahq.agentworks.domain.sandbox.SandboxRef;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Narrow Docker Engine adapter implemented through the operator-installed Docker CLI. */
class DockerCli {
    private static final Duration CONTROL_TIMEOUT = Duration.ofMinutes(2);
    private static final int MAX_CAPTURE_BYTES = 1_048_576;

    void create(String name, Path workspace, SandboxRef reference, SandboxConfiguration configuration,
            DockerRuntimeIdentity identity) {
        List<String> command = createCommand(name, workspace, reference, configuration, identity);
        Result result = run(command, CONTROL_TIMEOUT);
        if (result.exitCode() != 0) {
            throw new DockerSandboxException("Docker could not create sandbox container: " + result.output());
        }
    }

    void start(String name) {
        requireSuccess(List.of("docker", "start", name), CONTROL_TIMEOUT, "start sandbox container");
    }

    String execute(String name, List<String> command, Duration timeout) throws InterruptedException {
        List<String> invocation = new ArrayList<>(List.of("docker", "exec", "--workdir", "/workspace", name));
        invocation.addAll(command);
        Result result = runInterruptibly(invocation, timeout);
        if (result.timedOut()) throw new DockerSandboxTimeoutException("Sandbox command timed out");
        if (result.exitCode() != 0) {
            throw new DockerSandboxException("Sandbox command failed with exit " + result.exitCode());
        }
        return result.output();
    }

    void remove(String name) {
        Result result = run(List.of("docker", "rm", "--force", "--volumes", name), CONTROL_TIMEOUT);
        if (result.exitCode() != 0 && !result.output().contains("No such container")) {
            throw new DockerSandboxException("Docker could not retire sandbox container: " + result.output());
        }
    }

    void remove(SandboxRef expected) {
        Optional<SandboxRef> actual = inspectReference(containerName(expected.value()));
        if (actual.isEmpty()) return;
        if (!actual.get().equals(expected)) {
            throw new DockerSandboxException("Docker sandbox ownership mismatch; refusing retirement");
        }
        remove(containerName(expected.value()));
    }

    boolean isPresent(SandboxRef expected) {
        Optional<SandboxRef> actual = inspectReference(containerName(expected.value()));
        if (actual.isEmpty()) return false;
        if (!actual.get().equals(expected)) {
            throw new DockerSandboxException("Docker sandbox ownership mismatch; refusing adoption");
        }
        return true;
    }

    Set<SandboxRef> discoverOwnedSandboxes() {
        Result listed = runControl(List.of("docker", "ps", "--all", "--quiet", "--filter",
                "label=io.dvara.sandbox=true"));
        if (listed.exitCode() != 0) {
            throw new DockerSandboxException("Docker could not discover sandbox containers: " + listed.output());
        }
        Set<SandboxRef> resources = new LinkedHashSet<>();
        for (String container : listed.output().lines().filter(line -> !line.isBlank()).toList()) {
            Optional<SandboxRef> resource = inspectReference(container.trim());
            if (resource.isEmpty()) {
                throw new DockerSandboxException("Docker sandbox disappeared during discovery: " + container);
            }
            if (!resources.add(resource.get())) {
                throw new DockerSandboxException("Duplicate Docker sandbox identity: " + resource.get().value());
            }
        }
        return Set.copyOf(resources);
    }

    private Optional<SandboxRef> inspectReference(String container) {
        Result result = runControl(List.of("docker", "inspect", "--format", ownershipFormat(), container));
        if (result.exitCode() != 0 && result.output().toLowerCase(Locale.ROOT).contains("no such")) {
            return Optional.empty();
        }
        if (result.exitCode() != 0) throw new DockerSandboxException("Docker inspect failed: " + result.output());
        try {
            String[] fields = result.output().trim().split("\\|", -1);
            if (fields.length != 7) throw new IllegalArgumentException("wrong field count");
            UUID id = UUID.fromString(fields[0]);
            SandboxOwner owner = new SandboxOwner(UUID.fromString(fields[1]), UUID.fromString(fields[2]),
                    UUID.fromString(fields[3]), UUID.fromString(fields[4]), UUID.fromString(fields[5]),
                    UUID.fromString(fields[6]));
            return Optional.of(new SandboxRef(id, owner));
        } catch (IllegalArgumentException malformed) {
            throw new DockerSandboxException("Docker sandbox has malformed ownership labels", malformed);
        }
    }

    String inspect(String name, String format) {
        Result result = run(List.of("docker", "inspect", "--format", format, name), CONTROL_TIMEOUT);
        if (result.exitCode() != 0) throw new DockerSandboxException("Docker inspect failed: " + result.output());
        return result.output().trim();
    }

    static List<String> createCommand(String name, Path workspace, SandboxRef reference,
            SandboxConfiguration configuration, DockerRuntimeIdentity identity) {
        SandboxOwner owner = reference.owner();
        String image = configuration.image().orElseThrow(() ->
                new IllegalArgumentException("Docker sandbox configuration requires an image"));
        List<String> command = new ArrayList<>(List.of(
                "docker", "create",
                "--name", name,
                "--label", "io.dvara.sandbox=true",
                "--label", "io.dvara.sandbox-id=" + reference.value(),
                "--label", "io.dvara.organization=" + owner.organizationId(),
                "--label", "io.dvara.workspace=" + owner.workspaceId(),
                "--label", "io.dvara.run=" + owner.runId(),
                "--label", "io.dvara.release=" + owner.releaseId(),
                "--label", "io.dvara.deployment-revision=" + owner.deploymentRevisionId(),
                "--label", "io.dvara.cohort=" + owner.cohortId(),
                "--network", "none",
                "--ipc", "none",
                "--read-only",
                "--cap-drop", "ALL",
                "--security-opt", "no-new-privileges:true",
                "--user", identity.dockerUser(),
                "--pids-limit", Integer.toString(configuration.pidLimit()),
                "--cpus", configuration.cpu().toPlainString(),
                "--memory", Long.toString(configuration.memoryBytesExact()),
                "--workdir", "/workspace",
                "--mount", "type=bind,src=" + workspace + ",dst=/workspace,bind-propagation=rprivate",
                "--tmpfs", "/tmp:rw,noexec,nosuid,nodev,size=67108864",
                image,
                "/bin/sh", "-c", "trap 'exit 0' TERM INT; while :; do sleep 3600; done"));
        return List.copyOf(command);
    }

    private static String containerName(UUID sandboxId) {
        return "dvara-sandbox-" + sandboxId;
    }

    private static String ownershipFormat() {
        return String.join("|",
                "{{ index .Config.Labels \"io.dvara.sandbox-id\" }}",
                "{{ index .Config.Labels \"io.dvara.organization\" }}",
                "{{ index .Config.Labels \"io.dvara.workspace\" }}",
                "{{ index .Config.Labels \"io.dvara.run\" }}",
                "{{ index .Config.Labels \"io.dvara.release\" }}",
                "{{ index .Config.Labels \"io.dvara.deployment-revision\" }}",
                "{{ index .Config.Labels \"io.dvara.cohort\" }}");
    }

    Result runControl(List<String> command) {
        return run(command, CONTROL_TIMEOUT);
    }

    private void requireSuccess(List<String> command, Duration timeout, String operation) {
        Result result = run(command, timeout);
        if (result.exitCode() != 0) {
            throw new DockerSandboxException("Docker could not " + operation + ": " + result.output());
        }
    }

    private static Result run(List<String> command, Duration timeout) {
        try {
            return runInterruptibly(command, timeout);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new DockerSandboxException("Docker command interrupted", interrupted);
        }
    }

    private static Result runInterruptibly(List<String> command, Duration timeout) throws InterruptedException {
        if (timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("timeout must be positive");
        Process process;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
        } catch (IOException failure) {
            throw new DockerSandboxException("Cannot start Docker CLI", failure);
        }
        LimitedCapture capture = new LimitedCapture();
        AtomicReference<IOException> readFailure = new AtomicReference<>();
        Thread reader = Thread.ofVirtual().name("dvara-docker-cli-output").start(() -> {
            try (InputStream output = process.getInputStream()) {
                capture.read(output);
            } catch (IOException failure) {
                readFailure.set(failure);
            }
        });
        boolean exited = false;
        try {
            exited = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!exited) {
                process.destroyForcibly();
                process.waitFor();
            }
        } finally {
            if (process.isAlive()) process.destroyForcibly();
            reader.join(Duration.ofSeconds(2));
            if (reader.isAlive()) {
                reader.interrupt();
                throw new DockerSandboxException("Docker CLI output stream did not close");
            }
        }
        if (readFailure.get() != null) throw new DockerSandboxException("Cannot read Docker CLI output", readFailure.get());
        return new Result(exited ? process.exitValue() : -1, capture.output(), !exited);
    }

    record Result(int exitCode, String output, boolean timedOut) {}

    record DockerRuntimeIdentity(long uid, long gid, boolean ownsWorkspace) {
        DockerRuntimeIdentity {
            if (uid <= 0 || gid < 0) throw new IllegalArgumentException("Docker sandbox identity must be non-root");
        }

        String dockerUser() {
            return uid + ":" + gid;
        }
    }

    private static final class LimitedCapture {
        private final ByteArrayOutputStream captured = new ByteArrayOutputStream();
        private boolean truncated;

        private void read(InputStream input) throws IOException {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                int remaining = MAX_CAPTURE_BYTES - captured.size();
                if (remaining > 0) captured.write(buffer, 0, Math.min(count, remaining));
                if (count > remaining) truncated = true;
            }
        }

        private String output() {
            String value = captured.toString(StandardCharsets.UTF_8).trim();
            return truncated ? value + "\n[output truncated]" : value;
        }
    }
}
