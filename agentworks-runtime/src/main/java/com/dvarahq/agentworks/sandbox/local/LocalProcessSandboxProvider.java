package com.dvarahq.agentworks.sandbox.local;

import com.dvarahq.agentworks.domain.sandbox.SandboxCapability;
import com.dvarahq.agentworks.domain.sandbox.SandboxOwner;
import com.dvarahq.agentworks.domain.sandbox.SandboxRef;
import com.dvarahq.agentworks.domain.sandbox.SandboxSnapshot;
import com.dvarahq.agentworks.domain.sandbox.SandboxState;
import com.dvarahq.agentworks.sandbox.SandboxInstance;
import com.dvarahq.agentworks.sandbox.SandboxProvider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Deterministic trusted-code local provider. It is not a hostile-code isolation or production security boundary and
 * therefore advertises none of the container isolation, resource-control, or durable-recovery capabilities.
 */
public final class LocalProcessSandboxProvider implements SandboxProvider {

    private final Path providerRoot;
    private final Supplier<? extends AutoCloseable> clientFactory;
    private final Runnable afterClientAcquired;
    private final Map<UUID, LocalInstance> active = new LinkedHashMap<>();
    private final Map<UUID, SnapshotRecord> snapshots = new LinkedHashMap<>();
    private final AtomicInteger liveProcesses = new AtomicInteger();
    private final AtomicInteger openClients = new AtomicInteger();
    private boolean closed;

    public LocalProcessSandboxProvider(Path providerRoot) {
        this(providerRoot, () -> () -> { }, () -> { });
    }

    public LocalProcessSandboxProvider(Path providerRoot, Supplier<? extends AutoCloseable> clientFactory) {
        this(providerRoot, clientFactory, () -> { });
    }

    LocalProcessSandboxProvider(Path providerRoot, Supplier<? extends AutoCloseable> clientFactory,
            Runnable afterClientAcquired) {
        this.providerRoot = providerRoot.toAbsolutePath().normalize();
        this.clientFactory = clientFactory;
        this.afterClientAcquired = afterClientAcquired;
        createDirectories(this.providerRoot);
    }

    @Override
    public Set<SandboxCapability> capabilities() {
        return Set.of(SandboxCapability.PROCESS_EXECUTION, SandboxCapability.FILESYSTEM_SNAPSHOT_RESTORE);
    }

    @Override
    public synchronized SandboxInstance provision(SandboxOwner owner) {
        ensureOpen();
        UUID id = UUID.randomUUID();
        Path workspace = providerRoot.resolve("sandboxes").resolve(id.toString());
        createDirectories(workspace);
        TrackedClient client = null;
        try {
            client = new TrackedClient(clientFactory.get());
            afterClientAcquired.run();
            LocalInstance instance = new LocalInstance(new SandboxRef(id, owner), workspace, client);
            active.put(id, instance);
            return instance;
        } catch (RuntimeException failure) {
            if (client != null) {
                client.closeQuietly();
            }
            deleteTree(workspace);
            throw failure;
        }
    }

    @Override
    public synchronized SandboxInstance restore(SandboxSnapshot snapshot) {
        ensureOpen();
        SnapshotRecord record = snapshots.get(snapshot.value());
        if (record == null || !record.source().equals(snapshot.source())) {
            throw new IllegalArgumentException("Unknown snapshot: " + snapshot.value());
        }
        LocalInstance restored = (LocalInstance) provision(snapshot.source().owner());
        try {
            copyTree(record.path(), restored.workspace);
            return restored;
        } catch (RuntimeException failure) {
            try {
                restored.close();
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    public synchronized int activeSandboxCount() {
        return active.size();
    }

    public int liveProcessCount() {
        return liveProcesses.get();
    }

    public int openClientCount() {
        return openClients.get();
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        RuntimeException failure = null;
        for (LocalInstance instance : List.copyOf(active.values())) {
            try {
                instance.close();
            } catch (RuntimeException cleanupFailure) {
                if (failure == null) {
                    failure = cleanupFailure;
                } else {
                    failure.addSuppressed(cleanupFailure);
                }
            }
        }
        deleteTree(providerRoot);
        snapshots.clear();
        closed = true;
        if (failure != null) {
            throw failure;
        }
    }

    private synchronized SandboxSnapshot snapshot(LocalInstance instance) {
        UUID snapshotId = UUID.randomUUID();
        Path path = providerRoot.resolve("snapshots").resolve(snapshotId.toString());
        createDirectories(path);
        copyTree(instance.workspace, path);
        SandboxSnapshot snapshot = new SandboxSnapshot(snapshotId, instance.reference);
        snapshots.put(snapshotId, new SnapshotRecord(instance.reference, path));
        return snapshot;
    }

    private synchronized void retired(LocalInstance instance) {
        active.remove(instance.reference.value());
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("provider is closed");
        }
    }

    private static void createDirectories(Path path) {
        try {
            Files.createDirectories(path);
        } catch (IOException failure) {
            throw new SandboxExecutionException("Cannot create sandbox workspace", failure);
        }
    }

    private static void copyTree(Path source, Path target) {
        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path destination = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (IOException failure) {
            deleteTree(target);
            throw new SandboxExecutionException("Cannot copy sandbox snapshot", failure);
        }
    }

    private static void deleteTree(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException failure) {
            throw new SandboxExecutionException("Cannot retire sandbox resources", failure);
        }
    }

    private final class LocalInstance implements SandboxInstance {
        private final SandboxRef reference;
        private final Path workspace;
        private final TrackedClient client;
        private final List<Process> processes = new ArrayList<>();
        private SandboxState state = SandboxState.ACTIVE;

        private LocalInstance(SandboxRef reference, Path workspace, TrackedClient client) {
            this.reference = reference;
            this.workspace = workspace;
            this.client = client;
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
            if (candidate.isAbsolute()) {
                throw new SecurityException("Absolute paths are outside the sandbox workspace");
            }
            Path resolved = workspace.resolve(candidate).normalize();
            if (!resolved.startsWith(workspace)) {
                throw new SecurityException("Path escapes the sandbox workspace");
            }
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
            ensureActive();
            Process process;
            try {
                process = new ProcessBuilder(command).directory(workspace.toFile()).redirectErrorStream(true).start();
            } catch (IOException failure) {
                throw new SandboxExecutionException("Cannot start sandbox command", failure);
            }
            synchronized (this) {
                processes.add(process);
            }
            liveProcesses.incrementAndGet();
            try {
                if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                    process.waitFor();
                    throw new SandboxExecutionException("Sandbox command timed out");
                }
                String output = new String(process.getInputStream().readAllBytes());
                if (process.exitValue() != 0) {
                    throw new SandboxExecutionException("Sandbox command failed with exit " + process.exitValue());
                }
                return output;
            } catch (IOException failure) {
                throw new SandboxExecutionException("Cannot read sandbox command output", failure);
            } finally {
                if (process.isAlive()) {
                    process.destroyForcibly();
                }
                synchronized (this) {
                    if (processes.remove(process)) {
                        liveProcesses.decrementAndGet();
                    }
                }
            }
        }

        @Override
        public synchronized SandboxSnapshot suspend() {
            ensureActive();
            if (!processes.isEmpty()) {
                throw new IllegalStateException("Cannot snapshot while a command is running");
            }
            SandboxSnapshot snapshot = snapshot(this);
            state = SandboxState.SUSPENDED;
            close();
            return snapshot;
        }

        @Override
        public synchronized void close() {
            if (state == SandboxState.DESTROYED) {
                return;
            }
            for (Process process : List.copyOf(processes)) {
                process.destroyForcibly();
                if (processes.remove(process)) {
                    liveProcesses.decrementAndGet();
                }
            }
            RuntimeException failure = null;
            try {
                client.close();
            } catch (RuntimeException cleanupFailure) {
                failure = cleanupFailure;
            }
            try {
                deleteTree(workspace);
            } catch (RuntimeException cleanupFailure) {
                if (failure == null) {
                    failure = cleanupFailure;
                } else {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            state = SandboxState.DESTROYED;
            retired(this);
            if (failure != null) {
                throw failure;
            }
        }

        private synchronized void ensureActive() {
            if (state != SandboxState.ACTIVE) {
                throw new IllegalStateException("sandbox is not active: " + state);
            }
        }
    }

    private final class TrackedClient implements AutoCloseable {
        private final AutoCloseable delegate;
        private boolean closed;

        private TrackedClient(AutoCloseable delegate) {
            this.delegate = delegate;
            openClients.incrementAndGet();
        }

        @Override
        public synchronized void close() {
            if (closed) {
                return;
            }
            closed = true;
            try {
                delegate.close();
            } catch (Exception failure) {
                throw new SandboxExecutionException("Client cleanup failed", failure);
            } finally {
                openClients.decrementAndGet();
            }
        }

        private void closeQuietly() {
            try {
                close();
            } catch (RuntimeException ignored) {
                // Provisioning failure retains the original cause after best-effort cleanup.
            }
        }
    }

    private record SnapshotRecord(SandboxRef source, Path path) {
    }
}
