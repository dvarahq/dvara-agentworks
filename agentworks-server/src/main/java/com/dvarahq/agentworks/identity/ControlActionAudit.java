package com.dvarahq.agentworks.identity;

import com.dvarahq.agentworks.domain.identity.AuthenticatedPrincipal;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import com.dvarahq.agentworks.storage.lifecycle.LifecycleRetry;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Records a control action whose own schema has no actor column, atomically with the action. Two kinds use it:
 * <ul>
 *   <li>Control Plane actions: agent creation and publication, release builds, alias moves, environment creation, and
 *       promotion-policy changes, recorded through the {@link AuthAuditLog} on the Control Plane connection.</li>
 *   <li>Run control actions that no run event can record while a worker holds the run's lease: an operator's pause
 *       request and its withdrawal (#183), recorded through {@link RunControlAuditLog} on the Run Plane connection,
 *       in the run engine's own transaction (#194).</li>
 * </ul>
 * The action runs inside a transaction on the same data source as the service it calls, so the service's transaction
 * joins it, and the authentication audit row carrying the principal, organization, workspace, and authentication
 * method commits or rolls back with the action. The principal is resolved before the action starts, so a request
 * without one fails before anything is written. Deadlock and serialization retry moves to this enclosing transaction:
 * a {@link LifecycleRetry} inside it does not retry, so each attempt is still one whole transaction.
 * Deployment decisions and cohort commands retain their actor on their own decisions and transitions and are also
 * recorded here for a single queryable administrative trail. Other run commands remain on their run events. (The run
 * controller's former best-effort {@code RunControlAudit}, written after the change committed, was removed by #194
 * before any release.)
 */
public final class ControlActionAudit {
    /**
     * The audited target; {@code detail} is a fixed code, never request content. {@code workspace} is the workspace
     * the action was resolved in: run-control recorders require it ({@link RunControlAuditLog}, #198), and the
     * Control Plane recorder ignores it, recording the principal's.
     */
    public record Target(String type, UUID id, String detail, WorkspaceId workspace) {
        public Target(String type, UUID id, String detail) {
            this(type, id, detail, null);
        }
    }

    /** Writes one audit record through the audited service's connection, inside its transaction. */
    @FunctionalInterface
    public interface Recorder {
        void record(String action, AuthenticatedPrincipal principal, Target target);
    }

    /** Used only where no identity storage exists, in which case no request can authenticate anyway. */
    public static final ControlActionAudit NONE = new ControlActionAudit(null, null, null, null);

    private final TransactionTemplate transactions;
    private final Recorder audit;
    private final Supplier<AuthenticatedPrincipal> principals;
    private final LifecycleRetry retry;

    private ControlActionAudit(TransactionTemplate transactions, Recorder audit,
            Supplier<AuthenticatedPrincipal> principals, LifecycleRetry retry) {
        this.transactions = transactions;
        this.audit = audit;
        this.principals = principals;
        this.retry = retry;
    }

    /**
     * @param transactions a template on the data source the audited service writes to
     * @param audit an audit log writing through that same data source
     * @param principals the current principal; it throws when the request has none
     */
    public static ControlActionAudit transactional(TransactionTemplate transactions, AuthAuditLog audit,
            Supplier<AuthenticatedPrincipal> principals, LifecycleRetry retry) {
        Objects.requireNonNull(audit, "audit");
        return transactional(transactions, (Recorder) (action, principal, target) -> audit.record(action, principal,
                target.type(), target.id(), target.detail()), principals, retry);
    }

    /** As {@link #transactional(TransactionTemplate, AuthAuditLog, Supplier, LifecycleRetry)}, with any recorder. */
    public static ControlActionAudit transactional(TransactionTemplate transactions, Recorder audit,
            Supplier<AuthenticatedPrincipal> principals, LifecycleRetry retry) {
        return new ControlActionAudit(Objects.requireNonNull(transactions, "transactions"),
                Objects.requireNonNull(audit, "audit"), Objects.requireNonNull(principals, "principals"),
                Objects.requireNonNull(retry, "retry"));
    }

    /**
     * Runs {@code work} and records {@code action} for the target it yields, atomically. A {@code null} target (for
     * example an idempotent re-publication that created nothing) records nothing.
     */
    public <T> T record(String action, Supplier<T> work, Function<T, Target> target) {
        if (transactions == null) return work.get();
        AuthenticatedPrincipal principal = Objects.requireNonNull(principals.get(), "principal");
        return retry.execute(action, () -> transactions.execute(status -> {
            T result = work.get();
            Target audited = target.apply(result);
            if (audited != null) audit.record(action, principal, audited);
            return result;
        }));
    }
}
