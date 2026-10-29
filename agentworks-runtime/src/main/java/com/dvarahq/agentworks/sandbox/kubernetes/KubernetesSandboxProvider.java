package com.dvarahq.agentworks.sandbox.kubernetes;

import com.dvarahq.agentworks.domain.sandbox.SandboxCapability;
import com.dvarahq.agentworks.domain.sandbox.SandboxConfiguration;
import com.dvarahq.agentworks.domain.sandbox.SandboxOwner;
import com.dvarahq.agentworks.domain.sandbox.SandboxProvisionRequest;
import com.dvarahq.agentworks.domain.sandbox.SandboxRef;
import com.dvarahq.agentworks.domain.sandbox.SandboxSnapshot;
import com.dvarahq.agentworks.domain.sandbox.SandboxState;
import com.dvarahq.agentworks.sandbox.ReconcilableSandboxProvider;
import com.dvarahq.agentworks.sandbox.SandboxCapabilityUnavailableException;
import com.dvarahq.agentworks.sandbox.SandboxInstance;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Basic Kubernetes hostile-code adapter. Every sandbox is one non-root, read-only Pod plus an exact-pod deny-all
 * NetworkPolicy in a pre-provisioned namespace. The provider uses a fixed ServiceAccount, immutable owner labels,
 * explicit CPU/memory limits, and deterministic owner-checked deletion. It deliberately advertises neither PID
 * limits nor snapshot restore because those are not portable Kubernetes Pod guarantees.
 */
public final class KubernetesSandboxProvider implements ReconcilableSandboxProvider {
    private static final Pattern DNS_LABEL = Pattern.compile("^[a-z0-9](?:[-a-z0-9]{0,61}[a-z0-9])?$");
    private static final Set<SandboxCapability> CAPABILITIES = Set.of(
            SandboxCapability.PROCESS_EXECUTION,
            SandboxCapability.HOSTILE_CODE_ISOLATION,
            SandboxCapability.DIGEST_PINNED_IMAGE,
            SandboxCapability.NON_ROOT_IDENTITY,
            SandboxCapability.CPU_LIMIT,
            SandboxCapability.MEMORY_LIMIT,
            SandboxCapability.NETWORK_EGRESS_CONTROL);

    interface Control {
        void create(SandboxRef reference, SandboxConfiguration configuration);
        String execute(UUID sandboxId, List<String> command, Duration timeout) throws InterruptedException;
        Set<SandboxRef> discover();
        Optional<SandboxRef> inspect(UUID sandboxId);
        void remove(SandboxRef expected);
    }

    private final SandboxConfiguration defaults;
    private final Predicate<UUID> workspacePolicy;
    private final Control control;
    private final ScheduledExecutorService expiry = Executors.newSingleThreadScheduledExecutor(task ->
            Thread.ofPlatform().daemon().name("dvara-kubernetes-sandbox-expiry").unstarted(task));
    private final Map<UUID, KubernetesInstance> active = new LinkedHashMap<>();
    private final AtomicInteger liveExecutions = new AtomicInteger();
    private boolean admissionClosed;
    private boolean closed;

    public KubernetesSandboxProvider(String namespace, String serviceAccount, SandboxConfiguration defaults) {
        this(namespace, serviceAccount, defaults, ignored -> true);
    }

    /** Workspace policy is evaluated before any Kubernetes resource is acquired. */
    public KubernetesSandboxProvider(String namespace, String serviceAccount, SandboxConfiguration defaults,
            Predicate<UUID> workspacePolicy) {
        String checkedNamespace = dnsLabel(namespace, "namespace");
        String checkedServiceAccount = dnsLabel(serviceAccount, "serviceAccount");
        this.defaults = kubernetesConfiguration(defaults);
        this.workspacePolicy = Objects.requireNonNull(workspacePolicy, "workspacePolicy");
        this.control = new KubectlSandboxControl(checkedNamespace, checkedServiceAccount);
    }

    KubernetesSandboxProvider(SandboxConfiguration defaults, Predicate<UUID> workspacePolicy, Control control) {
        this.defaults = kubernetesConfiguration(defaults);
        this.workspacePolicy = Objects.requireNonNull(workspacePolicy, "workspacePolicy");
        this.control = Objects.requireNonNull(control, "control");
    }

    @Override
    public Set<SandboxCapability> capabilities() {
        return CAPABILITIES;
    }

    @Override
    public SandboxInstance provision(SandboxOwner owner) {
        return provisionConfigured(owner, defaults, UUID.randomUUID());
    }

    @Override
    public SandboxInstance provision(SandboxProvisionRequest request) {
        Objects.requireNonNull(request, "request");
        if (!CAPABILITIES.containsAll(request.requiredCapabilities())) {
            throw new SandboxCapabilityUnavailableException(request.requiredCapabilities(), CAPABILITIES);
        }
        return provisionConfigured(request.owner(),
                kubernetesConfiguration(request.configuration().orElse(defaults)),
                request.reservedSandboxId().orElseGet(UUID::randomUUID));
    }

    private synchronized SandboxInstance provisionConfigured(SandboxOwner owner, SandboxConfiguration configuration,
            UUID id) {
        ensureOpen();
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(id, "id");
        if (!workspacePolicy.test(owner.workspaceId())) {
            throw new SecurityException("Workspace policy denies Kubernetes sandbox provisioning");
        }
        if (active.containsKey(id)) throw new IllegalStateException("Sandbox identity is already active: " + id);
        SandboxRef reference = new SandboxRef(id, owner);
        KubernetesInstance instance = new KubernetesInstance(reference);
        active.put(id, instance);
        try {
            control.create(reference, configuration);
            instance.scheduleExpiry(configuration.maxRuntimeDuration());
            return instance;
        } catch (RuntimeException failure) {
            active.remove(id);
            try {
                control.remove(reference);
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    @Override
    public SandboxInstance restore(SandboxSnapshot snapshot) {
        throw new SandboxCapabilityUnavailableException(Set.of(SandboxCapability.FILESYSTEM_SNAPSHOT_RESTORE),
                CAPABILITIES);
    }

    public synchronized int activeSandboxCount() {
        return active.size();
    }

    public int liveExecutionCount() {
        return liveExecutions.get();
    }

    @Override
    public Set<SandboxRef> discoverOwnedSandboxes() {
        return control.discover();
    }

    @Override
    public boolean isPresent(SandboxRef expected) {
        Objects.requireNonNull(expected, "expected");
        Optional<SandboxRef> actual = control.inspect(expected.value());
        if (actual.isEmpty()) return false;
        if (!actual.get().equals(expected)) {
            throw new KubernetesSandboxException("Kubernetes sandbox ownership mismatch; refusing adoption");
        }
        return true;
    }

    @Override
    public void retire(SandboxRef expected) {
        Objects.requireNonNull(expected, "expected");
        KubernetesInstance local;
        synchronized (this) {
            local = active.get(expected.value());
        }
        if (local != null) {
            if (!local.reference.equals(expected)) {
                throw new KubernetesSandboxException("Sandbox ownership does not match the active resource");
            }
            local.close();
            return;
        }
        control.remove(expected);
    }

    @Override
    public void close() {
        List<KubernetesInstance> instances;
        synchronized (this) {
            if (closed) return;
            admissionClosed = true;
            instances = List.copyOf(active.values());
        }
        expiry.shutdownNow();
        RuntimeException failure = null;
        for (KubernetesInstance instance : instances) {
            try {
                instance.close();
            } catch (RuntimeException cleanupFailure) {
                if (failure == null) failure = cleanupFailure;
                else failure.addSuppressed(cleanupFailure);
            }
        }
        synchronized (this) {
            if (active.isEmpty() && failure == null) closed = true;
        }
        if (failure != null) throw failure;
    }

    private synchronized void retired(KubernetesInstance instance) {
        active.remove(instance.reference.value());
    }

    private void ensureOpen() {
        if (admissionClosed) throw new IllegalStateException("provider is closed");
    }

    private static SandboxConfiguration kubernetesConfiguration(SandboxConfiguration configuration) {
        Objects.requireNonNull(configuration, "configuration");
        if (!"kubernetes".equals(configuration.provider())) {
            throw new IllegalArgumentException("KubernetesSandboxProvider requires provider=kubernetes");
        }
        if (configuration.memoryBytesExact() < 6L * 1024 * 1024) {
            throw new IllegalArgumentException("Kubernetes sandbox memory must be at least 6Mi");
        }
        return configuration;
    }

    private static String dnsLabel(String value, String name) {
        Objects.requireNonNull(value, name);
        if (!DNS_LABEL.matcher(value).matches()) throw new IllegalArgumentException(name + " is not a DNS label");
        return value;
    }

    private final class KubernetesInstance implements SandboxInstance {
        private final SandboxRef reference;
        private long deadlineNanos;
        private ScheduledFuture<?> expiryTask;
        private SandboxState state = SandboxState.ACTIVE;

        private KubernetesInstance(SandboxRef reference) {
            this.reference = reference;
        }

        private synchronized void scheduleExpiry(Duration lifetime) {
            deadlineNanos = System.nanoTime() + lifetime.toNanos();
            expiryTask = expiry.schedule(this::expire, lifetime.toNanos(), TimeUnit.NANOSECONDS);
        }

        private void expire() {
            try {
                close();
            } catch (RuntimeException ignored) {
                // Retain the instance so shutdown or durable reconciliation can retry owner-checked cleanup.
            }
        }

        @Override
        public SandboxRef reference() {
            return reference;
        }

        @Override
        public synchronized SandboxState state() {
            return state;
        }

        @Override
        public synchronized Path resolveWorkspacePath(String relativePath) {
            ensureActive();
            Path candidate = Path.of(relativePath);
            if (candidate.isAbsolute()) throw new SecurityException("Absolute paths are outside the sandbox workspace");
            Path root = Path.of("/workspace");
            Path resolved = root.resolve(candidate).normalize();
            if (!resolved.startsWith(root)) throw new SecurityException("Path escapes the sandbox workspace");
            return resolved;
        }

        @Override
        public String execute(List<String> command, Duration timeout) throws InterruptedException {
            Objects.requireNonNull(command, "command");
            if (command.isEmpty() || command.stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("command must contain non-null arguments");
            }
            Objects.requireNonNull(timeout, "timeout");
            Duration remaining = Duration.ofNanos(Math.max(0, deadlineNanos - System.nanoTime()));
            Duration effective = timeout.compareTo(remaining) < 0 ? timeout : remaining;
            if (effective.isZero() || effective.isNegative()) {
                KubernetesSandboxException failure = new KubernetesSandboxException("Sandbox maximum runtime expired");
                closeAfterFailure(failure);
                throw failure;
            }
            synchronized (this) {
                ensureActive();
                liveExecutions.incrementAndGet();
            }
            try {
                return control.execute(reference.value(), List.copyOf(command), effective);
            } catch (KubernetesSandboxTimeoutException failure) {
                closeAfterFailure(failure);
                throw failure;
            } catch (InterruptedException interrupted) {
                closeAfterFailure(interrupted);
                throw interrupted;
            } finally {
                liveExecutions.decrementAndGet();
            }
        }

        private void closeAfterFailure(Throwable failure) {
            try {
                close();
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
        }

        @Override
        public SandboxSnapshot suspend() {
            throw new SandboxCapabilityUnavailableException(Set.of(SandboxCapability.FILESYSTEM_SNAPSHOT_RESTORE),
                    CAPABILITIES);
        }

        @Override
        public synchronized void close() {
            if (state == SandboxState.DESTROYED) return;
            control.remove(reference);
            if (expiryTask != null) expiryTask.cancel(false);
            state = SandboxState.DESTROYED;
            retired(this);
        }

        private void ensureActive() {
            if (state != SandboxState.ACTIVE) throw new IllegalStateException("sandbox is not active: " + state);
        }
    }
}
