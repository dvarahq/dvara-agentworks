package com.dvarahq.agentworks.runplane.execution;

import com.dvarahq.agentworks.domain.run.RunLease;
import com.dvarahq.agentworks.domain.run.RunRecord;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import com.dvarahq.agentworks.operation.DurableOperationAuthorityException;
import com.dvarahq.agentworks.operation.DurableOperationDraft;
import com.dvarahq.agentworks.operation.DurableOperationProvider;
import com.dvarahq.agentworks.operation.DurableOperationRecord;
import com.dvarahq.agentworks.operation.DurableOperationRequest;
import com.dvarahq.agentworks.operation.DurableOperationResult;
import com.dvarahq.agentworks.operation.DurableOperationStatus;
import com.dvarahq.agentworks.session.InboxMessageDraft;
import com.dvarahq.agentworks.session.InboxMessageType;
import com.dvarahq.agentworks.session.SessionInboxMessage;
import com.dvarahq.agentworks.storage.lifecycle.LifecycleRetry;
import com.dvarahq.agentworks.storage.operation.JdbcDurableOperationAuthority;
import com.dvarahq.agentworks.storage.session.JdbcSessionInboxAuthority;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Couples durable operation state, completion inbox delivery, and the run's block/resume lifecycle with one local
 * Spring JDBC transaction. Provider calls happen outside that transaction and are restart-safe by operation id.
 */
public final class DurableOperationCoordinator {
    public static final String SYSTEM_ACTOR = "system:durable-operation";
    private final TransactionTemplate transactions;
    private final LifecycleRetry retry;
    private final DurableRunEngine runs;
    private final JdbcDurableOperationAuthority operations;
    private final JdbcSessionInboxAuthority inbox;
    private final Map<String, DurableOperationProvider> providers;
    private final OperationCallbackTokens callbackTokens;
    private final String callbackBaseUrl;

    public DurableOperationCoordinator(TransactionTemplate transactions, LifecycleRetry retry, DurableRunEngine runs,
            JdbcDurableOperationAuthority operations, JdbcSessionInboxAuthority inbox,
            List<DurableOperationProvider> providers, OperationCallbackTokens callbackTokens,
            String callbackBaseUrl) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.retry = Objects.requireNonNull(retry, "retry");
        this.runs = Objects.requireNonNull(runs, "runs");
        this.operations = Objects.requireNonNull(operations, "operations");
        this.inbox = Objects.requireNonNull(inbox, "inbox");
        this.callbackTokens = Objects.requireNonNull(callbackTokens, "callbackTokens");
        if (callbackBaseUrl == null || callbackBaseUrl.isBlank() || callbackBaseUrl.length() > 1800) {
            throw new IllegalArgumentException("operation callback base URL is required");
        }
        this.callbackBaseUrl = callbackBaseUrl.replaceAll("/+$", "");
        Map<String, DurableOperationProvider> indexed = new LinkedHashMap<>();
        for (DurableOperationProvider provider : providers) {
            if (provider.id() == null || provider.id().isBlank() || indexed.putIfAbsent(provider.id(), provider) != null) {
                throw new IllegalArgumentException("durable operation provider ids must be unique and nonblank");
            }
        }
        this.providers = Map.copyOf(indexed);
    }

    /** Records the intent and blocks atomically, then starts the provider without holding database locks. */
    public DurableOperationRecord start(RunLease lease, UUID sessionId, String providerId, String operationType,
            Map<String, Object> input, String correlationId, String idempotencyKey, Duration timeout) {
        DurableOperationProvider provider = provider(providerId);
        RunRecord run = runs.run(lease.workspace(), lease.runId());
        UUID id = UUID.randomUUID();
        String callbackToken = callbackTokens.issue(lease.workspace().value(), id);
        DurableOperationDraft draft = new DurableOperationDraft(id, lease.workspace().value(), sessionId,
                run.id(), run.pins().releaseId(), run.pins().deploymentId(), run.pins().revisionId(),
                run.pins().cohortId(), correlationId, idempotencyKey, providerId, operationType, input,
                JdbcDurableOperationAuthority.tokenDigest(callbackToken), callbackTokens.activeVersion(), timeout);
        DurableOperationRecord replay = operations.replay(lease.workspace(), draft).orElse(null);
        if (replay != null) {
            if (replay.status() != DurableOperationStatus.STARTING) return replay;
            return dispatch(replay, provider, callbackTokens.issue(replay.callbackTokenVersion(),
                    lease.workspace().value(), replay.id()));
        }
        DurableOperationRecord recorded = required("durable-operation.block", () -> {
            runs.blockOperation(lease, id, providerId, operationType, correlationId);
            return operations.create(lease.workspace(), draft);
        });
        return dispatch(recorded, provider, callbackToken);
    }

    /** Replays a STARTING provider call after restart, or polls a RUNNING handle and applies its result once. */
    public DurableOperationRecord reconcile(WorkspaceId workspace, UUID operationId) {
        DurableOperationRecord operation = operations.find(workspace, operationId)
                .orElseThrow(() -> failure(DurableOperationAuthorityException.Code.NOT_FOUND,
                        "operation does not exist"));
        if (operation.status().terminal()) return operation;
        DurableOperationProvider provider = provider(operation.provider());
        if (operation.status() == DurableOperationStatus.STARTING) {
            return dispatch(operation, provider, callbackTokens.issue(operation.callbackTokenVersion(),
                    workspace.value(), operation.id()));
        }
        DurableOperationProvider.ProviderStatus status = provider.status(operation.handle().orElseThrow());
        if (status.progressPercent() != null && (operation.progressPercent() == null
                || status.progressPercent() > operation.progressPercent())) {
            operation = progress(workspace, operation.id(), status.progressPercent());
        }
        if (status.state() == DurableOperationProvider.ProviderStatus.State.RUNNING
                || status.state() == DurableOperationProvider.ProviderStatus.State.UNKNOWN) return operation;
        DurableOperationResult result = provider.result(operation.handle().orElseThrow())
                .orElseThrow(() -> failure(DurableOperationAuthorityException.Code.RESULT_NOT_READY,
                        "provider reported a terminal state without a result"));
        return finish(workspace, operation.id(), result, null, SYSTEM_ACTOR).operation();
    }

    public CompletedOperation callback(WorkspaceId workspace, UUID operationId, String callbackToken,
            DurableOperationResult result) {
        return finish(workspace, operationId, result, callbackToken, SYSTEM_ACTOR);
    }

    /** Provider cancellation is requested first; the database then decides cancellation versus completion once. */
    public CompletedOperation cancel(WorkspaceId workspace, UUID operationId, String actor) {
        DurableOperationRecord operation = operations.find(workspace, operationId)
                .orElseThrow(() -> failure(DurableOperationAuthorityException.Code.NOT_FOUND,
                        "operation does not exist"));
        if (operation.status().terminal()) throw failure(DurableOperationAuthorityException.Code.ALREADY_TERMINAL,
                "operation already has an outcome");
        if (operation.handle().isPresent()) provider(operation.provider()).cancel(operation.handle().orElseThrow());
        return finish(workspace, operationId,
                new DurableOperationResult(DurableOperationStatus.CANCELLED, "CANCELLED", Map.of()), null, actor);
    }

    private DurableOperationRecord dispatch(DurableOperationRecord operation, DurableOperationProvider provider,
            String callbackToken) {
        try {
            var handle = provider.start(new DurableOperationRequest(operation.id(), operation.workspaceId(),
                    operation.sessionId(), operation.runId(), operation.operationType(), operation.input(),
                    callbackBaseUrl + "/api/v1/operation-callbacks/" + operation.workspaceId() + "/"
                            + operation.id(), callbackToken,
                    operation.deadline()));
            return operations.running(new WorkspaceId(operation.workspaceId()), operation.id(), handle);
        } catch (DurableOperationAuthorityException failure) {
            throw failure;
        } catch (RuntimeException unavailable) {
            // STARTING remains authoritative. Reconciliation can safely repeat start with the same operation id.
            throw failure(DurableOperationAuthorityException.Code.PROVIDER_UNAVAILABLE,
                    "durable operation provider is unavailable", unavailable);
        }
    }

    private CompletedOperation finish(WorkspaceId workspace, UUID operationId, DurableOperationResult result,
            String callbackToken, String actor) {
        try {
            return required("durable-operation.complete", () -> {
                DurableOperationRecord pending = operations.find(workspace, operationId)
                        .orElseThrow(() -> failure(DurableOperationAuthorityException.Code.NOT_FOUND,
                                "operation does not exist"));
                if (pending.status().terminal()) {
                    throw failure(DurableOperationAuthorityException.Code.ALREADY_TERMINAL,
                            "operation already has an outcome");
                }
                runs.lockOperationOutcome(workspace, pending);
                DurableOperationRecord completed = callbackToken == null
                        ? operations.complete(workspace, operationId, result, actor)
                        : operations.completeFromCallback(workspace, operationId, callbackToken, result, actor);
                UUID messageId = UUID.randomUUID();
                SessionInboxMessage message = inbox.enqueue(workspace, new InboxMessageDraft(messageId,
                        completed.workspaceId(), completed.sessionId(), completed.runId(), completed.releaseId(),
                        completed.deploymentId(), completed.revisionId(), completed.cohortId(),
                        InboxMessageType.OPERATION_COMPLETED, completed.correlationId(),
                        "operation:" + completed.id(), Map.of("operationId", completed.id().toString(),
                                "status", completed.status().name(), "code", completed.result().orElseThrow().code())));
                RunRecord resumed = runs.resumeOperation(workspace, completed, message.id(), actor);
                return new CompletedOperation(completed, message, resumed);
            });
        } catch (RunEngineException race) {
            // A callback/poll contender may have read OPEN immediately before the winner resumed the run.
            if (operations.find(workspace, operationId).filter(operation -> operation.status().terminal()).isPresent()) {
                throw failure(DurableOperationAuthorityException.Code.ALREADY_TERMINAL,
                        "operation already has an outcome");
            }
            throw race;
        }
    }

    /** Progress follows the same run-then-operation lock order as terminal callback and poll outcomes. */
    private DurableOperationRecord progress(WorkspaceId workspace, UUID operationId, int percent) {
        try {
            return required("durable-operation.progress", () -> {
                DurableOperationRecord pending = operations.find(workspace, operationId)
                        .orElseThrow(() -> failure(DurableOperationAuthorityException.Code.NOT_FOUND,
                                "operation does not exist"));
                if (pending.status().terminal()) {
                    throw failure(DurableOperationAuthorityException.Code.ALREADY_TERMINAL,
                            "operation already has an outcome");
                }
                runs.lockOperationOutcome(workspace, pending);
                return operations.progress(workspace, operationId, percent);
            });
        } catch (RunEngineException race) {
            // Completion may have resumed the run immediately before this contender acquired the run lock.
            if (operations.find(workspace, operationId).filter(operation -> operation.status().terminal()).isPresent()) {
                throw failure(DurableOperationAuthorityException.Code.ALREADY_TERMINAL,
                        "operation already has an outcome");
            }
            throw race;
        }
    }

    private DurableOperationProvider provider(String id) {
        DurableOperationProvider provider = providers.get(id);
        if (provider == null) throw failure(DurableOperationAuthorityException.Code.PROVIDER_NOT_FOUND,
                "durable operation provider is not configured");
        return provider;
    }

    private <T> T required(String name, Supplier<T> work) {
        return retry.execute(name, () -> Objects.requireNonNull(transactions.execute(status -> work.get()),
                "transaction result"));
    }

    private static DurableOperationAuthorityException failure(DurableOperationAuthorityException.Code code,
            String message) {
        return new DurableOperationAuthorityException(code, message);
    }
    private static DurableOperationAuthorityException failure(DurableOperationAuthorityException.Code code,
            String message, Throwable cause) {
        return new DurableOperationAuthorityException(code, message, cause);
    }

    public record CompletedOperation(DurableOperationRecord operation, SessionInboxMessage message, RunRecord run) {
        public CompletedOperation {
            Objects.requireNonNull(operation, "operation");
            Objects.requireNonNull(message, "message");
            Objects.requireNonNull(run, "run");
        }
    }
}
