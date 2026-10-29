package com.dvarahq.agentworks.runplane.execution;

import com.dvarahq.agentworks.child.ScheduledWakeupRecord;
import com.dvarahq.agentworks.domain.run.RunLease;
import com.dvarahq.agentworks.domain.run.RunRecord;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import com.dvarahq.agentworks.session.InboxMessageDraft;
import com.dvarahq.agentworks.session.InboxMessageType;
import com.dvarahq.agentworks.storage.child.JdbcChildRunAuthority;
import com.dvarahq.agentworks.storage.lifecycle.LifecycleRetry;
import com.dvarahq.agentworks.storage.session.JdbcSessionInboxAuthority;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/** Database-time, restart-safe, exactly-once scheduled wakeup coordination. */
public final class ScheduledWakeupCoordinator {
    public static final String SYSTEM_ACTOR = "system:scheduled-wakeup";
    private final TransactionTemplate transactions;
    private final LifecycleRetry retry;
    private final DurableRunEngine runs;
    private final JdbcChildRunAuthority authority;
    private final JdbcSessionInboxAuthority inbox;

    public ScheduledWakeupCoordinator(TransactionTemplate transactions, LifecycleRetry retry, DurableRunEngine runs,
            JdbcChildRunAuthority authority, JdbcSessionInboxAuthority inbox) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.retry = Objects.requireNonNull(retry, "retry"); this.runs = Objects.requireNonNull(runs, "runs");
        this.authority = Objects.requireNonNull(authority, "authority"); this.inbox = Objects.requireNonNull(inbox, "inbox");
    }

    public ScheduledWakeupRecord schedule(RunLease lease, UUID sessionId, Instant dueAt, String correlationId,
            String idempotencyKey, Map<String, Object> payload) {
        Objects.requireNonNull(dueAt, "dueAt");
        ScheduledWakeupRecord replay = authority.replayWakeup(lease.workspace(), lease.runId(), idempotencyKey,
                dueAt, correlationId, payload).orElse(null);
        if (replay != null) return replay;
        return required("wakeup.schedule", () -> {
            RunRecord run = runs.run(lease.workspace(), lease.runId()); UUID id = UUID.randomUUID();
            runs.blockWakeup(lease, id, dueAt, correlationId);
            return authority.createWakeup(lease.workspace(), id, sessionId, run.pins(), idempotencyKey,
                    correlationId, dueAt, payload);
        });
    }

    /** Delivers at most one due wakeup. All writes roll back together if resume loses a lifecycle race. */
    public boolean deliverOne(WorkspaceId workspace) {
        return required("wakeup.deliver", () -> {
            ScheduledWakeupRecord pending = authority.claimDue(workspace).orElse(null);
            if (pending == null) return false;
            RunRecord run = runs.run(workspace, pending.runId());
            if (run.lifecycle().terminal()) {
                authority.cancelWakeup(workspace, pending.id());
                return true;
            }
            UUID messageId = UUID.randomUUID();
            Map<String, Object> body = new LinkedHashMap<>(pending.payload());
            body.put("wakeupId", pending.id().toString()); body.put("dueAt", pending.dueAt().toString());
            inbox.enqueue(workspace, new InboxMessageDraft(messageId, pending.workspaceId(), pending.sessionId(),
                    pending.runId(), pending.releaseId(), pending.deploymentId(), pending.revisionId(), pending.cohortId(),
                    InboxMessageType.SCHEDULED_WAKEUP, pending.correlationId(), "wakeup:" + pending.id(), body));
            ScheduledWakeupRecord delivered = authority.delivered(workspace, pending.id());
            runs.resumeWakeup(workspace, delivered, messageId, SYSTEM_ACTOR);
            return true;
        });
    }

    private <T> T required(String name, Supplier<T> work) {
        return retry.execute(name, () -> Objects.requireNonNull(transactions.execute(status -> work.get()), "transaction result"));
    }
}
