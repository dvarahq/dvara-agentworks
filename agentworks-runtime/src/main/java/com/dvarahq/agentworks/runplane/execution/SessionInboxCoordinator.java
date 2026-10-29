package com.dvarahq.agentworks.runplane.execution;

import com.dvarahq.agentworks.domain.run.RunEventType;
import com.dvarahq.agentworks.domain.run.RunLease;
import com.dvarahq.agentworks.session.SessionInboxMessage;
import com.dvarahq.agentworks.storage.lifecycle.LifecycleRetry;
import com.dvarahq.agentworks.storage.session.JdbcSessionInboxAuthority;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Claims and consumes inbox entries only at explicit worker safe boundaries, with matching ordered run events. */
public final class SessionInboxCoordinator {
    private final TransactionTemplate transactions;
    private final LifecycleRetry retry;
    private final DurableRunEngine runs;
    private final JdbcSessionInboxAuthority inbox;

    public SessionInboxCoordinator(TransactionTemplate transactions, LifecycleRetry retry, DurableRunEngine runs,
            JdbcSessionInboxAuthority inbox) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.retry = Objects.requireNonNull(retry, "retry");
        this.runs = Objects.requireNonNull(runs, "runs");
        this.inbox = Objects.requireNonNull(inbox, "inbox");
    }

    public ClaimedInbox claim(RunLease lease, int limit, Duration claimFor) {
        return required("session-inbox.claim", () -> {
            List<SessionInboxMessage> messages = inbox.claim(lease, limit, claimFor);
            if (messages.isEmpty()) return new ClaimedInbox(lease, messages);
            List<DurableRunEngine.EventDraft> events = new ArrayList<>();
            for (SessionInboxMessage message : messages) {
                events.add(DurableRunEngine.EventDraft.of(RunEventType.INBOX_MESSAGE_RECEIVED, Map.of(
                        "messageId", message.id().toString(), "sessionId", message.sessionId().toString(),
                        "sequence", message.sequence(), "messageType", message.type().name(),
                        "correlationId", message.correlationId())));
            }
            return new ClaimedInbox(runs.append(lease, events).lease(), messages);
        });
    }

    public ConsumedInbox consume(RunLease lease, SessionInboxMessage message) {
        return required("session-inbox.consume", () -> {
            SessionInboxMessage consumed = inbox.consume(lease, message.id(), message.version());
            var written = runs.append(lease, List.of(DurableRunEngine.EventDraft.of(
                    RunEventType.INBOX_MESSAGE_CONSUMED, Map.of("messageId", consumed.id().toString(),
                            "sessionId", consumed.sessionId().toString(), "sequence", consumed.sequence(),
                            "messageType", consumed.type().name(), "correlationId", consumed.correlationId()))));
            return new ConsumedInbox(written.lease(), consumed);
        });
    }

    private <T> T required(String name, java.util.function.Supplier<T> work) {
        return retry.execute(name, () -> Objects.requireNonNull(transactions.execute(status -> work.get()),
                "transaction result"));
    }

    public record ClaimedInbox(RunLease lease, List<SessionInboxMessage> messages) {
        public ClaimedInbox { messages = List.copyOf(messages); }
    }
    public record ConsumedInbox(RunLease lease, SessionInboxMessage message) {}
}
