package com.dvarahq.agentworks.runplane.execution;

import com.dvarahq.agentworks.domain.run.RunInvoker;
import com.dvarahq.agentworks.domain.run.RunRecord;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import com.dvarahq.agentworks.runplane.admission.RunAdmissionService;
import com.dvarahq.agentworks.session.AgentSession;
import com.dvarahq.agentworks.session.InboxMessageDraft;
import com.dvarahq.agentworks.session.InboxMessageType;
import com.dvarahq.agentworks.session.SessionAuthorityException;
import com.dvarahq.agentworks.session.SessionInboxMessage;
import com.dvarahq.agentworks.storage.lifecycle.LifecycleRetry;
import com.dvarahq.agentworks.storage.session.JdbcSessionInboxAuthority;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Classifies a session delivery as a current-route root or an existing-run message after deduplication. */
public final class SessionDeliveryCoordinator {
    private final TransactionTemplate transactions;
    private final LifecycleRetry retry;
    private final RunAdmissionService admissions;
    private final DurableRunEngine runs;
    private final JdbcSessionInboxAuthority inbox;

    public SessionDeliveryCoordinator(TransactionTemplate transactions, LifecycleRetry retry,
            RunAdmissionService admissions, DurableRunEngine runs, JdbcSessionInboxAuthority inbox) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.retry = Objects.requireNonNull(retry, "retry");
        this.admissions = Objects.requireNonNull(admissions, "admissions");
        this.runs = Objects.requireNonNull(runs, "runs");
        this.inbox = Objects.requireNonNull(inbox, "inbox");
    }

    public Delivery deliver(WorkspaceId workspace, UUID sessionId, UUID correlatedRunId, InboxMessageType type,
            String correlationId, String idempotencyKey, Map<String, Object> payload, String traceId, String actor,
            RunInvoker invoker) {
        Objects.requireNonNull(workspace, "workspace");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(payload, "payload");
        return retry.execute("session-inbox.deliver", () -> Objects.requireNonNull(transactions.execute(status -> {
            Optional<SessionInboxMessage> replay = inbox.findByIdempotency(workspace, sessionId, idempotencyKey);
            if (replay.isPresent()) return new Delivery(replay.orElseThrow(), true, correlatedRunId == null);
            AgentSession session = inbox.findSession(workspace, sessionId).orElseThrow(() ->
                    new SessionAuthorityException(SessionAuthorityException.Code.NOT_FOUND, "session does not exist"));
            UUID runId = correlatedRunId;
            boolean root = runId == null;
            if (root) {
                var admitted = admissions.admitRoot(workspace, session.deploymentId(),
                        "session/" + sessionId + "/" + idempotencyKey, payload,
                        new RunAdmissionService.Options(true, traceId, actor, invoker));
                runId = admitted.run().id();
                inbox.linkRun(workspace, sessionId, runId);
            }
            RunRecord run = runs.run(workspace, runId);
            if (run.lifecycle().terminal()) throw new SessionAuthorityException(
                    SessionAuthorityException.Code.INVALID_TRANSITION,
                    "a terminal run cannot receive a correlated inbox message");
            SessionInboxMessage message = inbox.enqueue(workspace, new InboxMessageDraft(UUID.randomUUID(),
                    workspace.value(), sessionId, run.id(), run.pins().releaseId(), run.pins().deploymentId(),
                    run.pins().revisionId(), run.pins().cohortId(), type, correlationId, idempotencyKey, payload));
            return new Delivery(message, false, root);
        }), "transaction result"));
    }

    public record Delivery(SessionInboxMessage message, boolean replayed, boolean rootRun) {}
}
