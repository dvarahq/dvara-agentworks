package com.dvarahq.agentworks.sandbox;

import com.dvarahq.agentworks.artifact.ArtifactStore;
import com.dvarahq.agentworks.domain.sandbox.SandboxProvisionRequest;
import com.dvarahq.agentworks.domain.sandbox.SandboxRef;
import com.dvarahq.agentworks.domain.sandbox.SandboxResource;
import com.dvarahq.agentworks.domain.sandbox.SandboxResource.State;
import com.dvarahq.agentworks.domain.sandbox.SandboxSnapshot;
import com.dvarahq.agentworks.domain.sandbox.SandboxState;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Joins durable sandbox state to one reconcilable provider without holding a database transaction across a provider
 * call. A database reservation always precedes allocation; activation or fenced cleanup always follows it.
 */
public final class SandboxCoordinator implements AutoCloseable {
    public static final Settings DEFAULTS = new Settings("docker", "sandbox-cleaner", Duration.ofMinutes(2),
            Duration.ofMinutes(1), Duration.ofSeconds(30), 256);

    public record Settings(String provider, String worker, Duration provisioningTimeout, Duration cleanupLease,
            Duration cleanupRetry, int maxCleanupClaimsPerPass) {
        public Settings {
            if (provider == null || !provider.matches("[a-z][a-z0-9-]{0,31}")) {
                throw new IllegalArgumentException("sandbox provider identity is invalid");
            }
            if (worker == null || worker.isBlank() || worker.length() > 128) {
                throw new IllegalArgumentException("sandbox cleanup worker is invalid");
            }
            requirePositive(provisioningTimeout, "provisioningTimeout");
            requirePositive(cleanupLease, "cleanupLease");
            requirePositive(cleanupRetry, "cleanupRetry");
            if (maxCleanupClaimsPerPass < 1) {
                throw new IllegalArgumentException("maxCleanupClaimsPerPass must be positive");
            }
        }
    }

    public record ReconciliationResult(int workspaces, int observedResources, int activatedReservations,
            int expiredReservationsQueued, int missingResourcesQueued, int orphanResourcesRetired,
            int cleanupSucceeded, int cleanupFailed, int identityMismatches) {}

    private final SandboxLifecycleAuthority authority;
    private final ReconcilableSandboxProvider provider;
    private final Settings settings;
    private final ArtifactStore artifactStore;
    private final ConcurrentHashMap<UUID, CoordinatedInstance> local = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ReentrantReadWriteLock lifecycle = new ReentrantReadWriteLock();

    public SandboxCoordinator(SandboxLifecycleAuthority authority, ReconcilableSandboxProvider provider) {
        this(authority, provider, DEFAULTS, null);
    }

    public SandboxCoordinator(SandboxLifecycleAuthority authority, ReconcilableSandboxProvider provider,
            Settings settings) {
        this(authority, provider, settings, null);
    }

    public SandboxCoordinator(SandboxLifecycleAuthority authority, ReconcilableSandboxProvider provider,
            Settings settings, ArtifactStore artifactStore) {
        this.authority = Objects.requireNonNull(authority, "authority");
        this.provider = Objects.requireNonNull(provider, "provider");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.artifactStore = artifactStore;
    }

    /** Reserves identity durably, allocates externally, then records activation in a second short transaction. */
    public SandboxInstance provision(SandboxProvisionRequest request) {
        lifecycle.readLock().lock();
        try {
            Objects.requireNonNull(request, "request");
            ensureOpen();
            var configuration = request.configuration().orElseThrow(() ->
                    new IllegalArgumentException("coordinated sandbox provisioning requires pinned configuration"));
            if (!settings.provider().equals(configuration.provider())) {
                throw new IllegalArgumentException("sandbox request targets a different provider");
            }
            UUID id = request.reservedSandboxId().orElseGet(UUID::randomUUID);
            WorkspaceId workspace = new WorkspaceId(request.owner().workspaceId());
            authority.reserve(workspace, id, request.owner(), settings.provider(), configuration.maxRuntimeDuration(),
                    settings.provisioningTimeout());

            try {
                SandboxInstance allocated = provider.provision(request.withReservedSandboxId(id));
                SandboxRef expected = new SandboxRef(id, request.owner());
                if (!expected.equals(allocated.reference())) {
                    throw new IllegalStateException("sandbox provider returned a different reserved identity");
                }
                authority.activate(workspace, id);
                CoordinatedInstance managed = new CoordinatedInstance(workspace, request.owner(), allocated);
                CoordinatedInstance previous = local.putIfAbsent(id, managed);
                if (previous != null) throw new IllegalStateException("sandbox identity is already coordinated");
                return managed;
            } catch (RuntimeException failure) {
                queueAndAttemptCleanup(workspace, id, failure);
                throw failure;
            }
        } finally {
            lifecycle.readLock().unlock();
        }
    }

    /**
     * Rebuilds database/provider agreement after restart and drains all cleanup that is due now. Missing active
     * resources become terminal through the cleanup lease; exact orphan resources are retired, never adopted.
     */
    public ReconciliationResult reconcile() {
        lifecycle.readLock().lock();
        try {
            ensureOpen();
            Set<SandboxRef> discovered = Set.copyOf(provider.discoverOwnedSandboxes());
            Set<WorkspaceId> workspaces = new LinkedHashSet<>(
                    authority.workspacesWithOpenResources(settings.provider()));
            discovered.stream().map(ref -> new WorkspaceId(ref.owner().workspaceId())).forEach(workspaces::add);

            MutableResult result = new MutableResult(workspaces.size());
            for (WorkspaceId workspace : workspaces) {
                List<SandboxResource> resources = authority.open(workspace).stream()
                        .filter(resource -> settings.provider().equals(resource.provider())).toList();
                result.observedResources += resources.size();
                for (SandboxResource resource : resources) reconcile(resource, result);
            }

            for (SandboxRef external : discovered) reconcileExternal(external, result);
            for (WorkspaceId workspace : workspaces) drainCleanup(workspace, result);
            return result.freeze();
        } finally {
            lifecycle.readLock().unlock();
        }
    }

    /** Requests cancellation and attempts the exact resource immediately through a fenced cleanup lease. */
    public void cancel(SandboxRef reference) {
        lifecycle.readLock().lock();
        try {
            ensureOpen();
            Objects.requireNonNull(reference, "reference");
            WorkspaceId workspace = new WorkspaceId(reference.owner().workspaceId());
            authority.requestCleanup(workspace, reference.value());
            cleanupSpecific(workspace, reference.value(), true);
        } finally {
            lifecycle.readLock().unlock();
        }
    }

    private void reconcile(SandboxResource resource, MutableResult result) {
        boolean present;
        try {
            present = provider.isPresent(resource.reference());
        } catch (RuntimeException mismatch) {
            result.identityMismatches++;
            if (resource.state() != State.CLEANUP_PENDING && resource.state() != State.CLEANING) {
                authority.requestCleanup(new WorkspaceId(resource.reference().owner().workspaceId()),
                        resource.reference().value());
            }
            return;
        }
        WorkspaceId workspace = new WorkspaceId(resource.reference().owner().workspaceId());
        if (resource.state() == State.PROVISIONING && present) {
            try {
                authority.activate(workspace, resource.reference().value());
                result.activatedReservations++;
            } catch (IllegalStateException noLongerProvisioning) {
                SandboxResource current = authority.find(workspace, resource.reference().value()).orElseThrow();
                if (current.state() == State.PROVISIONING) {
                    authority.requestCleanup(workspace, resource.reference().value());
                    result.expiredReservationsQueued++;
                } else if (current.state() != State.ACTIVE) {
                    throw noLongerProvisioning;
                }
            }
        } else if (resource.state() == State.ACTIVE && !present) {
            authority.requestCleanup(workspace, resource.reference().value());
            result.missingResourcesQueued++;
        }
    }

    private void reconcileExternal(SandboxRef external, MutableResult result) {
        WorkspaceId workspace = new WorkspaceId(external.owner().workspaceId());
        var durable = authority.find(workspace, external.value());
        if (durable.isPresent() && durable.orElseThrow().reference().equals(external)
                && durable.orElseThrow().state() != State.TERMINAL) return;
        if (durable.isPresent() && !durable.orElseThrow().reference().equals(external)) {
            result.identityMismatches++;
            return;
        }
        provider.retire(external);
        result.orphanResourcesRetired++;
    }

    private void drainCleanup(WorkspaceId workspace, MutableResult result) {
        for (int index = 0; index < settings.maxCleanupClaimsPerPass(); index++) {
            var claimed = authority.claimCleanup(workspace, settings.provider(), settings.worker(),
                    settings.cleanupLease());
            if (claimed.isEmpty()) return;
            if (cleanupClaim(workspace, claimed.orElseThrow(), false)) {
                result.cleanupSucceeded++;
            } else {
                result.cleanupFailed++;
                // Never reclaim the same uncertain external effect in one pass, even when its retry delay is tiny.
                return;
            }
        }
    }

    private boolean cleanupSpecific(WorkspaceId workspace, UUID sandboxId, boolean propagateProviderFailure) {
        var claimed = authority.claimCleanup(workspace, sandboxId, settings.provider(), settings.worker(),
                settings.cleanupLease());
        return claimed.isEmpty() || cleanupClaim(workspace, claimed.orElseThrow(), propagateProviderFailure);
    }

    private boolean cleanupClaim(WorkspaceId workspace, SandboxResource claimed, boolean propagateProviderFailure) {
        try {
            provider.retire(claimed.reference());
            authority.cleanupSucceeded(workspace, claimed.reference().value(), settings.worker(),
                    claimed.cleanupFence());
            local.remove(claimed.reference().value());
            return true;
        } catch (RuntimeException failure) {
            try {
                authority.cleanupFailed(workspace, claimed.reference().value(), settings.worker(),
                        claimed.cleanupFence(), "PROVIDER_CLEANUP_FAILED", settings.cleanupRetry());
            } catch (RuntimeException recordingFailure) {
                failure.addSuppressed(recordingFailure);
            }
            if (propagateProviderFailure) throw failure;
            return false;
        }
    }

    private void queueAndAttemptCleanup(WorkspaceId workspace, UUID id, RuntimeException original) {
        try {
            authority.requestCleanup(workspace, id);
            cleanupSpecific(workspace, id, true);
        } catch (RuntimeException cleanupFailure) {
            original.addSuppressed(cleanupFailure);
        }
    }

    @Override
    public void close() {
        lifecycle.writeLock().lock();
        try {
            if (!closed.compareAndSet(false, true)) return;
            RuntimeException failure = null;
            for (WorkspaceId workspace : authority.workspacesWithOpenResources(settings.provider())) {
                for (SandboxResource resource : authority.open(workspace)) {
                    if (!settings.provider().equals(resource.provider()) || resource.state() == State.TERMINAL) continue;
                    try {
                        authority.requestCleanup(workspace, resource.reference().value());
                        cleanupSpecific(workspace, resource.reference().value(), true);
                    } catch (RuntimeException cleanupFailure) {
                        if (failure == null) failure = cleanupFailure;
                        else failure.addSuppressed(cleanupFailure);
                    }
                }
            }
            try {
                provider.close();
            } catch (RuntimeException providerFailure) {
                if (failure == null) failure = providerFailure;
                else failure.addSuppressed(providerFailure);
            }
            if (failure != null) throw failure;
        } finally {
            lifecycle.writeLock().unlock();
        }
    }

    private void ensureOpen() {
        if (closed.get()) throw new IllegalStateException("sandbox coordinator is closed");
    }

    private static void requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) throw new IllegalArgumentException(name + " must be positive");
    }

    private static final class MutableResult {
        private final int workspaces;
        private int observedResources;
        private int activatedReservations;
        private int expiredReservationsQueued;
        private int missingResourcesQueued;
        private int orphanResourcesRetired;
        private int cleanupSucceeded;
        private int cleanupFailed;
        private int identityMismatches;

        private MutableResult(int workspaces) { this.workspaces = workspaces; }

        private ReconciliationResult freeze() {
            return new ReconciliationResult(workspaces, observedResources, activatedReservations,
                    expiredReservationsQueued, missingResourcesQueued, orphanResourcesRetired, cleanupSucceeded,
                    cleanupFailed, identityMismatches);
        }
    }

    private final class CoordinatedInstance implements SandboxInstance {
        private final WorkspaceId workspace;
        private final SandboxResultArtifactBinding artifacts;
        private final SandboxInstance delegate;
        private final AtomicBoolean retired = new AtomicBoolean();

        private CoordinatedInstance(WorkspaceId workspace,
                com.dvarahq.agentworks.domain.sandbox.SandboxOwner owner, SandboxInstance delegate) {
            this.workspace = workspace;
            this.delegate = delegate;
            this.artifacts = artifactStore == null ? null : new SandboxResultArtifactBinding(artifactStore, owner,
                    "sandbox:" + delegate.reference().value());
        }

        @Override
        public SandboxRef reference() { return delegate.reference(); }

        @Override
        public SandboxState state() { return retired.get() ? SandboxState.DESTROYED : delegate.state(); }

        @Override
        public Path resolveWorkspacePath(String relativePath) {
            requireActive();
            return delegate.resolveWorkspacePath(relativePath);
        }

        @Override
        public String execute(List<String> command, Duration timeout) throws InterruptedException {
            String result = executeRaw(command, timeout);
            return artifacts == null ? result : artifacts.offloadIfLarge(result);
        }

        @Override
        public String executeRaw(List<String> command, Duration timeout) throws InterruptedException {
            requireActive();
            try {
                return delegate.executeRaw(command, timeout);
            } catch (InterruptedException interrupted) {
                try {
                    close();
                } catch (RuntimeException cleanupFailure) {
                    interrupted.addSuppressed(cleanupFailure);
                }
                throw interrupted;
            }
        }

        @Override
        public SandboxSnapshot suspend() {
            requireActive();
            return delegate.suspend();
        }

        @Override
        public void close() {
            if (!retired.compareAndSet(false, true)) return;
            try {
                SandboxCoordinator.this.cancel(reference());
            } catch (RuntimeException failure) {
                retired.set(false);
                throw failure;
            }
        }

        private void requireActive() {
            if (retired.get()) throw new IllegalStateException("sandbox is not active");
            SandboxResource resource = authority.find(workspace, reference().value()).orElseThrow(() ->
                    new IllegalStateException("sandbox durable state is missing"));
            if (resource.state() != State.ACTIVE || !resource.reference().equals(reference())) {
                throw new IllegalStateException("sandbox is not durably active");
            }
        }
    }
}
