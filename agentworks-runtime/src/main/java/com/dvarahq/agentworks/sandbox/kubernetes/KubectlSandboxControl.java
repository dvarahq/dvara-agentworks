package com.dvarahq.agentworks.sandbox.kubernetes;

import com.dvarahq.agentworks.domain.sandbox.SandboxConfiguration;
import com.dvarahq.agentworks.domain.sandbox.SandboxOwner;
import com.dvarahq.agentworks.domain.sandbox.SandboxRef;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
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

/** Narrow adapter over an operator-installed kubectl authenticated as the configured ServiceAccount. */
class KubectlSandboxControl implements KubernetesSandboxProvider.Control {
    private static final Duration CONTROL_TIMEOUT = Duration.ofMinutes(2);
    private static final int MAX_CAPTURE_BYTES = 1_048_576;
    private static final String MANAGED_SELECTOR = "app.kubernetes.io/managed-by=dvara-agentworks,agentworks.dvara.io/sandbox=true";

    private final String namespace;
    private final String serviceAccount;

    KubectlSandboxControl(String namespace, String serviceAccount) {
        this.namespace = namespace;
        this.serviceAccount = serviceAccount;
    }

    @Override
    public void create(SandboxRef reference, SandboxConfiguration configuration) {
        Result result = run(List.of("kubectl", "--namespace", namespace, "create", "-f", "-"),
                manifest(reference, configuration).getBytes(StandardCharsets.UTF_8), CONTROL_TIMEOUT);
        if (result.exitCode() != 0) {
            KubernetesSandboxException failure = new KubernetesSandboxException(
                    "Kubernetes could not create sandbox resources: " + result.output());
            try {
                remove(reference);
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
        Result ready = run(List.of("kubectl", "--namespace", namespace, "wait", "--for=condition=Ready",
                "pod/" + name(reference.value()), "--timeout=120s"), null, CONTROL_TIMEOUT.plusSeconds(5));
        if (ready.exitCode() != 0) {
            remove(reference);
            throw new KubernetesSandboxException("Kubernetes sandbox did not become ready: " + ready.output());
        }
    }

    @Override
    public String execute(UUID sandboxId, List<String> command, Duration timeout) throws InterruptedException {
        List<String> invocation = new ArrayList<>(List.of("kubectl", "--namespace", namespace, "exec",
                name(sandboxId), "--"));
        invocation.addAll(command);
        Result result = runInterruptibly(invocation, null, timeout);
        if (result.timedOut()) throw new KubernetesSandboxTimeoutException("Kubernetes sandbox command timed out");
        if (result.exitCode() != 0) {
            throw new KubernetesSandboxException("Kubernetes sandbox command failed with exit " + result.exitCode());
        }
        return result.output();
    }

    @Override
    public Set<SandboxRef> discover() {
        Result result = run(List.of("kubectl", "--namespace", namespace, "get", "pods", "--selector",
                MANAGED_SELECTOR, "--output", "name"), null, CONTROL_TIMEOUT);
        if (result.exitCode() != 0) {
            throw new KubernetesSandboxException("Kubernetes could not list sandbox pods: " + result.output());
        }
        Set<SandboxRef> discovered = new LinkedHashSet<>();
        for (String line : result.output().lines().filter(value -> !value.isBlank()).toList()) {
            String pod = line.substring(line.lastIndexOf('/') + 1);
            SandboxRef reference = inspect(pod).orElseThrow(() ->
                    new KubernetesSandboxException("Kubernetes sandbox disappeared during discovery: " + pod));
            if (!discovered.add(reference)) {
                throw new KubernetesSandboxException("Duplicate Kubernetes sandbox identity: " + reference.value());
            }
        }
        return Set.copyOf(discovered);
    }

    @Override
    public Optional<SandboxRef> inspect(UUID sandboxId) {
        return inspect(name(sandboxId));
    }

    private Optional<SandboxRef> inspect(String pod) {
        return inspectResource("pod", pod);
    }

    private Optional<SandboxRef> inspectResource(String resource, String name) {
        Result result = run(List.of("kubectl", "--namespace", namespace, "get", resource, name,
                "--output", "jsonpath=" + ownershipJsonPath()), null, CONTROL_TIMEOUT);
        if (result.exitCode() != 0 && result.output().toLowerCase(Locale.ROOT).contains("not found")) {
            return Optional.empty();
        }
        if (result.exitCode() != 0) {
            throw new KubernetesSandboxException("Kubernetes sandbox inspection failed: " + result.output());
        }
        try {
            String[] fields = result.output().split("\\|", -1);
            if (fields.length != 7) throw new IllegalArgumentException("wrong field count");
            UUID id = UUID.fromString(fields[0]);
            SandboxOwner owner = new SandboxOwner(UUID.fromString(fields[1]), UUID.fromString(fields[2]),
                    UUID.fromString(fields[3]), UUID.fromString(fields[4]), UUID.fromString(fields[5]),
                    UUID.fromString(fields[6]));
            return Optional.of(new SandboxRef(id, owner));
        } catch (IllegalArgumentException malformed) {
            throw new KubernetesSandboxException("Kubernetes sandbox has malformed ownership labels", malformed);
        }
    }

    @Override
    public void remove(SandboxRef expected) {
        Optional<SandboxRef> pod = inspectResource("pod", name(expected.value()));
        Optional<SandboxRef> policy = inspectResource("networkpolicy", name(expected.value()));
        if (pod.isEmpty() && policy.isEmpty()) return;
        if ((pod.isPresent() && !pod.get().equals(expected))
                || (policy.isPresent() && !policy.get().equals(expected))) {
            throw new KubernetesSandboxException("Kubernetes sandbox ownership mismatch; refusing retirement");
        }
        removeVerified(expected.value());
    }

    private void removeVerified(UUID sandboxId) {
        String resourceName = name(sandboxId);
        Result result = run(List.of("kubectl", "--namespace", namespace, "delete", "pod/" + resourceName,
                "networkpolicy/" + resourceName, "--wait=true",
                "--timeout=120s", "--ignore-not-found=true"), null, CONTROL_TIMEOUT.plusSeconds(5));
        if (result.exitCode() != 0) {
            throw new KubernetesSandboxException("Kubernetes could not retire sandbox resources: " + result.output());
        }
    }

    String manifest(SandboxRef reference, SandboxConfiguration configuration) {
        SandboxOwner owner = reference.owner();
        String labels = labels(reference);
        return """
                apiVersion: v1
                kind: List
                items:
                  - apiVersion: networking.k8s.io/v1
                    kind: NetworkPolicy
                    metadata:
                      name: %s
                      labels:
                %s
                    spec:
                      podSelector:
                        matchLabels:
                          agentworks.dvara.io/sandbox-id: %s
                      policyTypes: [Ingress, Egress]
                  - apiVersion: v1
                    kind: Pod
                    metadata:
                      name: %s
                      labels:
                %s
                    spec:
                      restartPolicy: Never
                      serviceAccountName: %s
                      automountServiceAccountToken: false
                      enableServiceLinks: false
                      terminationGracePeriodSeconds: 5
                      securityContext:
                        runAsNonRoot: true
                        runAsUser: 65532
                        runAsGroup: 65532
                        fsGroup: 65532
                        seccompProfile: {type: RuntimeDefault}
                      containers:
                        - name: sandbox
                          image: %s
                          imagePullPolicy: IfNotPresent
                          command: [/bin/sh, -c, "trap 'exit 0' TERM INT; while :; do sleep 3600; done"]
                          workingDir: /workspace
                          securityContext:
                            allowPrivilegeEscalation: false
                            readOnlyRootFilesystem: true
                            capabilities: {drop: [ALL]}
                          resources:
                            requests: {cpu: %s, memory: %s}
                            limits: {cpu: %s, memory: %s}
                          volumeMounts:
                            - {name: workspace, mountPath: /workspace}
                            - {name: tmp, mountPath: /tmp}
                      volumes:
                        - name: workspace
                          emptyDir: {sizeLimit: %s}
                        - name: tmp
                          emptyDir: {sizeLimit: 64Mi}
                """.formatted(name(reference.value()), labels, reference.value(), name(reference.value()), labels,
                serviceAccount, configuration.image().orElseThrow(), configuration.cpu().toPlainString(),
                configuration.memory(), configuration.cpu().toPlainString(), configuration.memory(),
                configuration.memory());
    }

    private static String labels(SandboxRef reference) {
        SandboxOwner owner = reference.owner();
        return """
                            app.kubernetes.io/managed-by: dvara-agentworks
                            agentworks.dvara.io/sandbox: "true"
                            agentworks.dvara.io/sandbox-id: %s
                            agentworks.dvara.io/organization: %s
                            agentworks.dvara.io/workspace: %s
                            agentworks.dvara.io/run: %s
                            agentworks.dvara.io/release: %s
                            agentworks.dvara.io/deployment-revision: %s
                            agentworks.dvara.io/cohort: %s""".formatted(reference.value(), owner.organizationId(),
                owner.workspaceId(), owner.runId(), owner.releaseId(), owner.deploymentRevisionId(), owner.cohortId());
    }

    private static String ownershipJsonPath() {
        return String.join("|",
                "{.metadata.labels.agentworks\\.dvara\\.io/sandbox-id}",
                "{.metadata.labels.agentworks\\.dvara\\.io/organization}",
                "{.metadata.labels.agentworks\\.dvara\\.io/workspace}",
                "{.metadata.labels.agentworks\\.dvara\\.io/run}",
                "{.metadata.labels.agentworks\\.dvara\\.io/release}",
                "{.metadata.labels.agentworks\\.dvara\\.io/deployment-revision}",
                "{.metadata.labels.agentworks\\.dvara\\.io/cohort}");
    }

    private static String name(UUID sandboxId) {
        return "agentworks-sandbox-" + sandboxId;
    }

    private static Result run(List<String> command, byte[] input, Duration timeout) {
        try {
            return runInterruptibly(command, input, timeout);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new KubernetesSandboxException("kubectl command interrupted", interrupted);
        }
    }

    private static Result runInterruptibly(List<String> command, byte[] input, Duration timeout)
            throws InterruptedException {
        if (timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("timeout must be positive");
        Process process;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            try (OutputStream stdin = process.getOutputStream()) {
                if (input != null) stdin.write(input);
            }
        } catch (IOException failure) {
            throw new KubernetesSandboxException("Cannot start kubectl", failure);
        }
        LimitedCapture capture = new LimitedCapture();
        AtomicReference<IOException> readFailure = new AtomicReference<>();
        Thread reader = Thread.ofVirtual().name("dvara-kubectl-output").start(() -> {
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
                throw new KubernetesSandboxException("kubectl output stream did not close");
            }
        }
        if (readFailure.get() != null) {
            throw new KubernetesSandboxException("Cannot read kubectl output", readFailure.get());
        }
        return new Result(exited ? process.exitValue() : -1, capture.output(), !exited);
    }

    record Result(int exitCode, String output, boolean timedOut) {}

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
