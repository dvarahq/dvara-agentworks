package com.dvarahq.agentworks.gateway;

import com.dvarahq.agentworks.credential.CredentialAuditEvent;
import com.dvarahq.agentworks.credential.CredentialAuditSink;
import com.dvarahq.agentworks.credential.CredentialAuthorization;
import com.dvarahq.agentworks.credential.CredentialProvider;
import com.dvarahq.agentworks.credential.CredentialResolutionException;
import com.dvarahq.agentworks.credential.ResolvedCredential;
import com.dvarahq.agentworks.domain.security.CredentialRef;
import com.dvarahq.agentworks.domain.security.InvocationIdentity;

import java.time.Clock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * Lazily authorizes and resolves a gateway credential on first use within one invocation, audits the outcome, and
 * zeroizes the material when the invocation ends. Resolution runs outside any lock that {@link #close()} takes, so
 * close() never waits for a slow credential provider or audit sink: an invocation that times out while its credential
 * is being resolved returns on time. Material that arrives afterwards is zeroized at once and audited as
 * {@code ABANDONED} (after {@code SUCCEEDED} if close() raced that record). {@code SUCCEEDED} is written before the
 * material is published; if it cannot be written, the material is zeroized unused and the invocation fails.
 * Resolution failures never propagate provider messages.
 */
final class JitGatewayCredential implements GatewayCredential, AutoCloseable {

    static final String AUDIT_ACTION = "MODEL_CREDENTIAL_USE";

    private final String gatewayId;
    private final CredentialRef reference;
    private final CredentialProvider provider;
    private final CredentialAuditSink audit;
    private final InvocationIdentity identity;
    private final Clock clock;
    private final CancellationSignal signal;
    private final Object state = new Object();
    private final ReentrantLock resolving = new ReentrantLock();
    /** A failed resolution is final for the invocation: later and waiting callers rethrow it and never re-resolve. */
    private volatile GatewayException terminal;
    private ResolvedCredential resolved;
    private volatile boolean closed;

    JitGatewayCredential(String gatewayId, CredentialRef reference, CredentialProvider provider,
            CredentialAuditSink audit, InvocationIdentity identity, Clock clock, CancellationSignal signal) {
        this.gatewayId = gatewayId;
        this.reference = reference;
        this.provider = provider;
        this.audit = audit;
        this.identity = identity;
        this.clock = clock;
        this.signal = signal;
    }

    @Override
    public boolean present() {
        return reference != null;
    }

    @Override
    public <T> T withMaterial(Function<char[], T> use) {
        if (reference == null) {
            throw new GatewayException(GatewayErrorCode.CREDENTIAL_UNAVAILABLE,
                    "gateway '" + gatewayId + "' has no credential reference configured");
        }
        ResolvedCredential material = current();
        if (material == null) {
            // One resolution per invocation: concurrent first uses wait for it. close() never takes this lock.
            try {
                resolving.lockInterruptibly();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw ended();
            }
            try {
                material = current();
                if (material == null) {
                    if (terminal != null) {
                        // A fresh instance per caller: the stored one belongs to the thread that failed first (#174).
                        throw terminal.withoutCause();
                    }
                    material = install(resolveOnce());
                }
            } finally {
                resolving.unlock();
            }
        }
        try {
            return material.use(clock.instant(), use);
        } catch (CredentialResolutionException expired) {
            throw new GatewayException(GatewayErrorCode.CREDENTIAL_UNAVAILABLE,
                    "credential for gateway '" + gatewayId + "' expired during the invocation");
        } catch (IllegalStateException zeroized) {
            if (closed) {
                throw ended();
            }
            throw zeroized;
        }
    }

    private ResolvedCredential current() {
        if (closed) {
            throw ended();
        }
        synchronized (state) {
            return resolved;
        }
    }

    /**
     * Publishes a freshly resolved credential. Runs under the resolving lock, so there is exactly one resolution per
     * invocation. The {@code SUCCEEDED} record is written before the material is published, and outside the monitor
     * that {@link #close()} takes, so close() never waits on audit I/O.
     * <ul>
     *   <li>If writing {@code SUCCEEDED} fails, the material is zeroized and never published or used, it is recorded
     *       {@code ABANDONED} (the sink may have persisted {@code SUCCEEDED} first), and the invocation fails with
     *       {@code CREDENTIAL_UNAVAILABLE}, or with its own ending if close() raced the write. The failure is terminal:
     *       no later caller resolves again.</li>
     *   <li>If the invocation ends before publication, the material is zeroized unused and recorded {@code ABANDONED}:
     *       alone, or after {@code SUCCEEDED} when close() raced the success record.</li>
     * </ul>
     */
    private ResolvedCredential install(ResolvedCredential material) {
        if (!closed) {
            try {
                audit.record(event("SUCCEEDED"));
            } catch (RuntimeException unaudited) {
                // The sink may have persisted SUCCEEDED before failing, so the unused material is recorded as abandoned.
                material.close();
                recordQuietly("ABANDONED");
                if (closed) {
                    throw ended();
                }
                terminal = new GatewayException(GatewayErrorCode.CREDENTIAL_UNAVAILABLE,
                        "credential use for gateway '" + gatewayId + "' could not be audited");
                throw terminal;
            }
            synchronized (state) {
                if (!closed) {
                    resolved = material;
                    return material;
                }
            }
        }
        material.close();
        recordQuietly("ABANDONED");
        throw ended();
    }

    private ResolvedCredential resolveOnce() {
        try {
            return resolve();
        } catch (GatewayException unavailable) {
            if (closed || signal.isCancelled()) {
                // The invocation ended (ModelGateway cancels the signal before it closes the credential) while the
                // resolution failed: its own ending (TIMEOUT or CANCELLED) is the outcome, as in install(); the
                // provider failure is neither reported nor stored as terminal.
                throw ended();
            }
            if (unavailable.code() == GatewayErrorCode.CREDENTIAL_UNAVAILABLE) {
                terminal = unavailable;
            }
            throw unavailable;
        }
    }

    private ResolvedCredential resolve() {
        signal.throwIfCancelled();
        if (provider == null || audit == null) {
            throw new GatewayException(GatewayErrorCode.CREDENTIAL_UNAVAILABLE, "gateway '" + gatewayId
                    + "' needs a credential but no credential provider and audit sink are configured");
        }
        ResolvedCredential material = null;
        try {
            CredentialAuthorization authorization = provider.authorize(reference, identity, clock.instant());
            material = provider.resolve(authorization, clock.instant());
            if (material == null) {
                throw new IllegalStateException("credential provider returned no material");
            }
            return material;
        } catch (RuntimeException failure) {
            if (material != null) {
                material.close();
            }
            if (closed || signal.isCancelled()) {
                // The invocation ended during resolution, and no material was obtained, so the outcome is the ending.
                // A typed provider failure (CredentialResolutionException, a denial or refusal) is still a real
                // authorization outcome and is audited (#179); any other failure is taken as the cancellation's own
                // effect on the provider (for example an interrupted call) and is not audited as a denial.
                if (failure instanceof CredentialResolutionException) {
                    recordQuietly("DENIED_OR_FAILED");
                }
                throw ended();
            }
            recordQuietly("DENIED_OR_FAILED");
            throw new GatewayException(GatewayErrorCode.CREDENTIAL_UNAVAILABLE,
                    "credential for gateway '" + gatewayId + "' could not be authorized or resolved");
        }
    }

    private GatewayException ended() {
        return signal.reason().map(CancellationSignal::failure)
                .orElseGet(() -> new GatewayException(GatewayErrorCode.CANCELLED, "the invocation already ended"));
    }

    private CredentialAuditEvent event(String outcome) {
        return new CredentialAuditEvent(AUDIT_ACTION, outcome, reference, identity.organizationId(),
                identity.workspaceId(), identity.userId(), identity.sessionId(), identity.delegatedSubjectId(),
                clock.instant());
    }

    private void recordQuietly(String outcome) {
        try {
            audit.record(event(outcome));
        } catch (RuntimeException ignored) {
            // A broken audit sink must not mask the invocation's typed outcome.
        }
    }

    /** Never blocks on resolution; waits only for an in-progress {@code use} of already-resolved material. */
    @Override
    public void close() {
        closed = true;
        ResolvedCredential material;
        synchronized (state) {
            material = resolved;
            resolved = null;
        }
        if (material != null) {
            material.close();
        }
    }

    @Override
    public String toString() {
        return "GatewayCredential[gateway=" + gatewayId + ", material=<redacted>]";
    }
}
