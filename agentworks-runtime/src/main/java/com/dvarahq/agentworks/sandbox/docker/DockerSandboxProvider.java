package com.dvarahq.agentworks.sandbox.docker;

import com.dvarahq.agentworks.domain.sandbox.SandboxCapability;
import com.dvarahq.agentworks.domain.sandbox.SandboxConfiguration;
import com.dvarahq.agentworks.domain.sandbox.SandboxOwner;
import com.dvarahq.agentworks.domain.sandbox.SandboxProvisionRequest;
import com.dvarahq.agentworks.domain.sandbox.SandboxRef;
import com.dvarahq.agentworks.domain.sandbox.SandboxSnapshot;
import com.dvarahq.agentworks.domain.sandbox.SandboxState;
import com.dvarahq.agentworks.sandbox.SandboxCapabilityUnavailableException;
import com.dvarahq.agentworks.sandbox.SandboxInstance;
import com.dvarahq.agentworks.sandbox.ReconcilableSandboxProvider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Demand-provisioned Docker hostile-code boundary. Each sandbox is a non-root, read-only, capability-free container
 * with a single provider-owned workspace mount, no network namespace attachment, and explicit CPU, memory, PID, and
 * monotonic runtime limits. This ephemeral adapter deliberately does not advertise snapshot recovery.
 */
public final class DockerSandboxProvider implements ReconcilableSandboxProvider {
    private static final Set<SandboxCapability> CAPABILITIES = Set.of(
            SandboxCapability.PROCESS_EXECUTION,
            SandboxCapability.HOSTILE_CODE_ISOLATION,
            SandboxCapability.DIGEST_PINNED_IMAGE,
            SandboxCapability.NON_ROOT_IDENTITY,
            SandboxCapability.CPU_LIMIT,
            SandboxCapability.MEMORY_LIMIT,
            SandboxCapability.PID_LIMIT,
            SandboxCapability.NETWORK_EGRESS_CONTROL);

    private final Path providerRoot;
    private final SandboxConfiguration defaults;
    private final DockerCli docker;
    private final DockerCli.DockerRuntimeIdentity identity;
    private final Object closeLock = new Object();
    private final ScheduledExecutorService expiry = Executors.newSingleThreadScheduledExecutor(task ->
            Thread.ofPlatform().daemon().name("dvara-docker-sandbox-expiry").unstarted(task));
    private final Map<UUID, DockerInstance> active = new LinkedHashMap<>();
    private final AtomicInteger liveExecutions = new AtomicInteger();
    private boolean admissionClosed;
    private boolean closed;

    public DockerSandboxProvider(Path providerRoot, SandboxConfiguration defaults) {
        this(providerRoot, defaults, new DockerCli());
    }

    DockerSandboxProvider(Path providerRoot, SandboxConfiguration defaults, DockerCli docker) {
        this.providerRoot = Objects.requireNonNull(providerRoot, "providerRoot").toAbsolutePath().normalize();
        if (this.providerRoot.toString().contains(",")) {
            throw new IllegalArgumentException("Docker sandbox root cannot contain a comma");
        }
        this.defaults = dockerConfiguration(defaults);
        this.docker = Objects.requireNonNull(docker, "docker");
        createPrivateDirectory(this.providerRoot);
        this.identity = runtimeIdentity(this.providerRoot);
    }

    @Override
    public Set<SandboxCapability> capabilities() {
        return CAPABILITIES;
    }

    @Override
    public SandboxInstance provision(SandboxOwner owner) {
        return provisionConfigured(owner, defaults);
    }

    @Override
    public SandboxInstance provision(SandboxProvisionRequest request) {
        Objects.requireNonNull(request, "request");
        if (!CAPABILITIES.containsAll(request.requiredCapabilities())) {
            throw new SandboxCapabilityUnavailableException(request.requiredCapabilities(), CAPABILITIES);
        }
        return provisionConfigured(request.owner(), dockerConfiguration(request.configuration().orElse(defaults)),
                request.reservedSandboxId().orElseGet(UUID::randomUUID));
    }

    private synchronized SandboxInstance provisionConfigured(SandboxOwner owner, SandboxConfiguration configuration) {
        return provisionConfigured(owner, configuration, UUID.randomUUID());
    }

    private synchronized SandboxInstance provisionConfigured(SandboxOwner owner, SandboxConfiguration configuration,
            UUID id) {
        ensureOpen();
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(id, "id");
        if (active.containsKey(id)) throw new IllegalStateException("Sandbox identity is already active: " + id);
        String name = "dvara-sandbox-" + id;
        Path workspace = providerRoot.resolve("sandboxes").resolve(id.toString());
        createWorkspace(workspace, identity);
        DockerInstance instance = new DockerInstance(new SandboxRef(id, owner), name, workspace);
        active.put(id, instance);
        try {
            docker.create(name, workspace, new SandboxRef(id, owner), configuration, identity);
            docker.start(name);
            instance.scheduleExpiry(configuration.maxRuntimeDuration());
            return instance;
        } catch (RuntimeException failure) {
            try {
                instance.close();
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
        return docker.discoverOwnedSandboxes();
    }

    @Override
    public boolean isPresent(SandboxRef expected) {
        Objects.requireNonNull(expected, "expected");
        return docker.isPresent(expected);
    }

    @Override
    public void retire(SandboxRef expected) {
        Objects.requireNonNull(expected, "expected");
        DockerInstance local;
        synchronized (this) {
            local = active.get(expected.value());
        }
        if (local != null) {
            if (!local.reference.equals(expected)) {
                throw new DockerSandboxException("Sandbox ownership does not match the active resource");
            }
            local.close();
            return;
        }
        docker.remove(expected);
        deleteTree(providerRoot.resolve("sandboxes").resolve(expected.value().toString()));
    }

    /** Operational evidence used by integration tests and diagnostics. */
    String inspect(UUID sandboxId, String format) {
        DockerInstance instance;
        synchronized (this) {
            instance = active.get(sandboxId);
        }
        if (instance == null) throw new IllegalArgumentException("Unknown active sandbox: " + sandboxId);
        return docker.inspect(instance.containerName, format);
    }

    String runtimeUser() {
        return identity.dockerUser();
    }

    @Override
    public void close() {
        synchronized (closeLock) {
            List<DockerInstance> instances;
            synchronized (this) {
                if (closed) return;
                admissionClosed = true;
                instances = List.copyOf(active.values());
            }
            expiry.shutdownNow();
            RuntimeException failure = null;
            for (DockerInstance instance : instances) {
                try {
                    instance.close();
                } catch (RuntimeException cleanupFailure) {
                    if (failure == null) failure = cleanupFailure;
                    else failure.addSuppressed(cleanupFailure);
                }
            }
            boolean resourcesRemain;
            synchronized (this) {
                resourcesRemain = !active.isEmpty();
            }
            if (!resourcesRemain) {
                try {
                    deleteTree(providerRoot);
                } catch (RuntimeException cleanupFailure) {
                    if (failure == null) failure = cleanupFailure;
                    else failure.addSuppressed(cleanupFailure);
                }
            }
            if (failure == null && !resourcesRemain) {
                synchronized (this) {
                    closed = true;
                }
            }
            if (failure != null) throw failure;
        }
    }

    private synchronized void retired(DockerInstance instance) {
        active.remove(instance.reference.value());
    }

    private void ensureOpen() {
        if (admissionClosed) throw new IllegalStateException("provider is closed");
    }

    private static SandboxConfiguration dockerConfiguration(SandboxConfiguration configuration) {
        Objects.requireNonNull(configuration, "configuration");
        if (!"docker".equals(configuration.provider())) {
            throw new IllegalArgumentException("DockerSandboxProvider requires provider=docker");
        }
        if (configuration.memoryBytesExact() < 6L * 1024 * 1024) {
            throw new IllegalArgumentException("Docker sandbox memory must be at least 6Mi");
        }
        return configuration;
    }

    private static void createPrivateDirectory(Path path) {
        try {
            Files.createDirectories(path);
            if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"));
            }
        } catch (IOException failure) {
            throw new DockerSandboxException("Cannot create Docker sandbox root", failure);
        }
    }

    private static void createWorkspace(Path workspace, DockerCli.DockerRuntimeIdentity identity) {
        createPrivateDirectory(workspace);
        try {
            if (Files.getFileStore(workspace).supportsFileAttributeView("posix")) {
                if (!identity.ownsWorkspace()) {
                    // Fallback for a non-Unix host: the private parent still prevents other host users from entry.
                    Files.setPosixFilePermissions(workspace, PosixFilePermissions.fromString("rwxrwxrwx"));
                }
            }
        } catch (IOException failure) {
            deleteTree(workspace);
            throw new DockerSandboxException("Cannot prepare Docker sandbox workspace", failure);
        }
    }

    private static DockerCli.DockerRuntimeIdentity runtimeIdentity(Path providerRoot) {
        try {
            long hostUid = ((Number) Files.getAttribute(providerRoot, "unix:uid", LinkOption.NOFOLLOW_LINKS))
                    .longValue();
            long hostGid = ((Number) Files.getAttribute(providerRoot, "unix:gid", LinkOption.NOFOLLOW_LINKS))
                    .longValue();
            if (hostUid != 0) return new DockerCli.DockerRuntimeIdentity(hostUid, hostGid, true);
            return new DockerCli.DockerRuntimeIdentity(65532, 65532, false);
        } catch (IOException | UnsupportedOperationException failure) {
            return new DockerCli.DockerRuntimeIdentity(65532, 65532, false);
        }
    }

    private static void deleteTree(Path root) {
        if (!Files.exists(root)) return;
        try {
            deleteOwnedPath(root);
        } catch (IOException failure) {
            throw new DockerSandboxException("Cannot retire Docker sandbox workspace", failure);
        }
    }

    private static void deleteOwnedPath(Path path) throws IOException {
        if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            Files.deleteIfExists(path);
            return;
        }
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"));
        }
        try (var children = Files.newDirectoryStream(path)) {
            for (Path child : children) deleteOwnedPath(child);
        }
        Files.deleteIfExists(path);
    }

    private final class DockerInstance implements SandboxInstance {
        private final SandboxRef reference;
        private final String containerName;
        private final Path workspace;
        private long deadlineNanos;
        private ScheduledFuture<?> expiryTask;
        private SandboxState state = SandboxState.ACTIVE;

        private DockerInstance(SandboxRef reference, String containerName, Path workspace) {
            this.reference = reference;
            this.containerName = containerName;
            this.workspace = workspace;
        }

        private synchronized void scheduleExpiry(Duration lifetime) {
            if (state == SandboxState.ACTIVE) {
                deadlineNanos = System.nanoTime() + lifetime.toNanos();
                expiryTask = expiry.schedule(this::expire, lifetime.toNanos(), TimeUnit.NANOSECONDS);
            }
        }

        private void expire() {
            try {
                close();
            } catch (RuntimeException ignored) {
                // The instance remains active when Docker removal is uncertain, so provider.close can retry cleanup.
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
            Path resolved = workspace.resolve(candidate).normalize();
            if (!resolved.startsWith(workspace)) throw new SecurityException("Path escapes the sandbox workspace");
            Path current = workspace;
            for (Path component : workspace.relativize(resolved)) {
                current = current.resolve(component);
                if (Files.isSymbolicLink(current)) {
                    throw new SecurityException("Symbolic links cannot escape the sandbox workspace");
                }
            }
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
                DockerSandboxTimeoutException failure = new DockerSandboxTimeoutException(
                        "Sandbox maximum runtime expired");
                closeAfterFailure(failure);
                throw failure;
            }
            synchronized (this) {
                ensureActive();
                liveExecutions.incrementAndGet();
            }
            try {
                return docker.execute(containerName, List.copyOf(command), effective);
            } catch (DockerSandboxTimeoutException failure) {
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
            docker.remove(reference);
            if (expiryTask != null) expiryTask.cancel(false);
            RuntimeException failure = null;
            try {
                deleteTree(workspace);
            } catch (RuntimeException cleanupFailure) {
                if (failure == null) failure = cleanupFailure;
                else failure.addSuppressed(cleanupFailure);
            }
            state = SandboxState.DESTROYED;
            retired(this);
            if (failure != null) throw failure;
        }

        private void ensureActive() {
            if (state != SandboxState.ACTIVE) throw new IllegalStateException("sandbox is not active: " + state);
        }
    }
}
