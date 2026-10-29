package com.dvarahq.agentworks.runplane.execution;

import com.dvarahq.agentworks.domain.run.RunBlockReason;
import com.dvarahq.agentworks.domain.run.RunEventType;
import com.dvarahq.agentworks.domain.run.RunLease;
import com.dvarahq.agentworks.domain.run.RunRecord;
import com.dvarahq.agentworks.domain.security.InvocationIdentity;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import com.dvarahq.agentworks.interaction.HumanInteractionDraft;
import com.dvarahq.agentworks.interaction.HumanInteractionAuthorityException;
import com.dvarahq.agentworks.interaction.HumanInteractionKind;
import com.dvarahq.agentworks.interaction.HumanInteractionRecord;
import com.dvarahq.agentworks.interaction.HumanInteractionPrompt;
import com.dvarahq.agentworks.interaction.HumanInteractionResponse;
import com.dvarahq.agentworks.storage.interaction.JdbcHumanInteractionAuthority;
import com.dvarahq.agentworks.storage.lifecycle.LifecycleRetry;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Couples the human-interaction authority to the durable run lifecycle in one local Spring JDBC transaction.
 * Creating a request and releasing its worker lease commit together; likewise, an interaction outcome and the
 * fenced resume of its original pinned run commit together. No JTA or second transaction manager is involved.
 */
public final class DurableHumanInteractionCoordinator {
    private final TransactionTemplate transactions;
    private final LifecycleRetry retry;
    private final DurableRunEngine runs;
    private final JdbcHumanInteractionAuthority interactions;
    private final DelegatedAuthorizationExchange authorizations;

    public DurableHumanInteractionCoordinator(TransactionTemplate transactions, DurableRunEngine runs,
            JdbcHumanInteractionAuthority interactions, LifecycleRetry retry) {
        this(transactions, runs, interactions, retry, null);
    }

    public DurableHumanInteractionCoordinator(TransactionTemplate transactions, DurableRunEngine runs,
            JdbcHumanInteractionAuthority interactions, LifecycleRetry retry,
            DelegatedAuthorizationExchange authorizations) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.runs = Objects.requireNonNull(runs, "runs");
        this.interactions = Objects.requireNonNull(interactions, "interactions");
        this.retry = Objects.requireNonNull(retry, "retry");
        this.authorizations = authorizations;
    }

    /** ACTIVE -> BLOCKED and durable interaction creation, with lease release, in one commit. */
    public BlockedInteraction block(RunLease lease, HumanInteractionDraft draft) {
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(draft, "draft");
        if (!lease.workspace().value().equals(draft.workspaceId()) || !lease.runId().equals(draft.runId())
                || !lease.cohortId().equals(draft.cohortId())) {
            throw new IllegalArgumentException("interaction workspace, run, and cohort must match the worker lease");
        }
        return required(() -> {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("interactionId", draft.id().toString());
            event.put("kind", draft.prompt().kind().name());
            event.put("correlationId", draft.correlationId());
            event.put("assignmentLevel", draft.assignment().level().name());
            event.put("timeoutSeconds", draft.timeout().toSeconds());
            if (draft.prompt() instanceof HumanInteractionPrompt.Authorization authorization) {
                event.put("policyId", authorization.policyId());
            }
            RunEventType requested = draft.prompt().kind() == HumanInteractionKind.AUTHORIZATION
                    ? RunEventType.AUTHORIZATION_REQUIRED : RunEventType.HUMAN_INTERACTION_REQUESTED;
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("interactionId", draft.id().toString());
            detail.put("kind", draft.prompt().kind().name());
            detail.put("correlationId", draft.correlationId());
            RunBlockReason reason = draft.prompt().kind() == HumanInteractionKind.AUTHORIZATION
                    ? RunBlockReason.AUTHORIZATION : RunBlockReason.HUMAN_INTERACTION;
            RunRecord blocked = runs.blockInteraction(lease, reason, detail,
                    DurableRunEngine.EventDraft.of(requested, event));
            HumanInteractionRecord interaction = interactions.create(lease.workspace(), draft);
            return new BlockedInteraction(blocked, interaction);
        });
    }

    /** Records an authorized response and resumes only the run that blocked for this interaction. */
    public ResolvedInteraction respond(WorkspaceId workspace, UUID interactionId, InvocationIdentity responder,
            HumanInteractionResponse response, String actor) {
        if (response instanceof HumanInteractionResponse.Authorization authorization && authorization.granted()) {
            requireCredential(workspace, interactionId, responder);
        }
        return resolve(workspace, interactionId,
                () -> interactions.respond(workspace, interactionId, responder, response, actor), actor);
    }

    private void requireCredential(WorkspaceId workspace, UUID interactionId, InvocationIdentity responder) {
        Objects.requireNonNull(workspace, "workspace");
        Objects.requireNonNull(interactionId, "interactionId");
        Objects.requireNonNull(responder, "responder");
        HumanInteractionRecord pending = interactions.find(workspace, interactionId).orElseThrow(() ->
                new HumanInteractionAuthorityException(HumanInteractionAuthorityException.Code.NOT_FOUND,
                        "interaction does not exist"));
        if (!(pending.request().prompt() instanceof HumanInteractionPrompt.Authorization prompt)) {
            throw new HumanInteractionAuthorityException(HumanInteractionAuthorityException.Code.INVALID_RESPONSE,
                    "authorization response does not match the interaction request");
        }
        if (pending.status().terminal()) {
            throw new HumanInteractionAuthorityException(HumanInteractionAuthorityException.Code.ALREADY_TERMINAL,
                    "interaction already has outcome " + pending.status());
        }
        if (!pending.request().assignment().permits(responder)) {
            throw new HumanInteractionAuthorityException(
                    HumanInteractionAuthorityException.Code.UNAUTHORIZED_RESPONDER,
                    "responder is not assigned to this interaction");
        }
        if (authorizations == null) {
            throw new HumanInteractionAuthorityException(
                    HumanInteractionAuthorityException.Code.CREDENTIAL_UNAVAILABLE,
                    "delegated authorization requires a credential provider and audit sink");
        }
        RunRecord run = runs.run(workspace, pending.request().runId());
        UUID definitionId = run.pins().definitionId();
        if (definitionId == null) {
            throw new HumanInteractionAuthorityException(
                    HumanInteractionAuthorityException.Code.CREDENTIAL_UNAVAILABLE,
                    "delegated authorization requires a pinned agent identity");
        }
        authorizations.requireCurrent(prompt, responder, definitionId);
    }

    /** Records database-time expiry and resumes the original run so it can handle the timeout. */
    public ResolvedInteraction expire(WorkspaceId workspace, UUID interactionId, String actor) {
        return resolve(workspace, interactionId, () -> interactions.expire(workspace, interactionId, actor), actor);
    }

    /** Records cancellation and resumes the original run so it can handle cancellation. */
    public ResolvedInteraction cancel(WorkspaceId workspace, UUID interactionId, String actor) {
        return resolve(workspace, interactionId, () -> interactions.cancel(workspace, interactionId, actor), actor);
    }

    /** Records authorization revocation and resumes the original run so it can handle revocation. */
    public ResolvedInteraction revoke(WorkspaceId workspace, UUID interactionId, String actor) {
        return resolve(workspace, interactionId, () -> interactions.revoke(workspace, interactionId, actor), actor);
    }

    private ResolvedInteraction resolve(WorkspaceId workspace, UUID interactionId,
            Supplier<HumanInteractionRecord> outcome, String actor) {
        Objects.requireNonNull(workspace, "workspace");
        Objects.requireNonNull(interactionId, "interactionId");
        Objects.requireNonNull(outcome, "outcome");
        return required(() -> {
            HumanInteractionRecord pending = interactions.find(workspace, interactionId).orElseThrow(() ->
                    new HumanInteractionAuthorityException(HumanInteractionAuthorityException.Code.NOT_FOUND,
                            "interaction does not exist"));
            if (pending.status().terminal()) {
                throw new HumanInteractionAuthorityException(
                        HumanInteractionAuthorityException.Code.ALREADY_TERMINAL,
                        "interaction already has outcome " + pending.status());
            }
            // Take the run lock first; the authority then takes the cohort and interaction locks.
            try {
                runs.lockInteractionOutcome(workspace, pending);
            } catch (RunEngineException failure) {
                // A concurrent responder can resume the run before this transaction acquires its lock. Translate
                // that normal losing race to the interaction authority's public one-winner outcome.
                if ("INTERACTION_NOT_BLOCKING".equals(failure.code())) {
                    HumanInteractionRecord latest = interactions.find(workspace, interactionId).orElseThrow(() ->
                            new HumanInteractionAuthorityException(
                                    HumanInteractionAuthorityException.Code.NOT_FOUND,
                                    "interaction does not exist"));
                    if (latest.status().terminal()) {
                        throw new HumanInteractionAuthorityException(
                                HumanInteractionAuthorityException.Code.ALREADY_TERMINAL,
                                "interaction already has outcome " + latest.status());
                    }
                }
                throw failure;
            }
            HumanInteractionRecord interaction = outcome.get();
            RunRecord resumed = runs.resumeInteraction(workspace, interaction, actor);
            return new ResolvedInteraction(interaction, resumed);
        });
    }

    private <T> T required(Supplier<T> work) {
        return retry.execute("human-interaction.run-transition", () ->
                Objects.requireNonNull(transactions.execute(status -> work.get()), "transaction result"));
    }

    public record BlockedInteraction(RunRecord run, HumanInteractionRecord interaction) {
        public BlockedInteraction {
            Objects.requireNonNull(run, "run");
            Objects.requireNonNull(interaction, "interaction");
        }
    }

    public record ResolvedInteraction(HumanInteractionRecord interaction, RunRecord run) {
        public ResolvedInteraction {
            Objects.requireNonNull(interaction, "interaction");
            Objects.requireNonNull(run, "run");
        }
    }
}
