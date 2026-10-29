package com.dvarahq.agentworks.runplane.execution;

import com.dvarahq.agentworks.credential.CredentialAuthorization;
import com.dvarahq.agentworks.credential.CredentialProvider;
import com.dvarahq.agentworks.credential.CredentialUseRequest;
import com.dvarahq.agentworks.credential.ResolvedCredential;
import com.dvarahq.agentworks.domain.run.RunBlockReason;
import com.dvarahq.agentworks.domain.run.RunEventType;
import com.dvarahq.agentworks.domain.run.RunLifecycle;
import com.dvarahq.agentworks.domain.security.CredentialRef;
import com.dvarahq.agentworks.domain.security.InvocationIdentity;
import com.dvarahq.agentworks.interaction.HumanInteractionAssignment;
import com.dvarahq.agentworks.interaction.HumanInteractionAuthorityException;
import com.dvarahq.agentworks.interaction.HumanInteractionDraft;
import com.dvarahq.agentworks.interaction.HumanInteractionPrompt;
import com.dvarahq.agentworks.interaction.HumanInteractionResponse;
import com.dvarahq.agentworks.interaction.HumanInteractionStatus;
import com.dvarahq.agentworks.storage.interaction.JdbcHumanInteractionAuthority;
import com.dvarahq.agentworks.storage.lifecycle.LifecycleRetry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Atomic block/create and outcome/resume evidence for issue #42. */
class DurableHumanInteractionCoordinatorPostgresTest {

    @Test
    void workerAtomicallyHandsAnInteractionOutcomeToTheCoordinator() {
        RunScenario scenario = new RunScenario();
        UUID user = UUID.randomUUID();
        var submitted = scenario.submit(java.util.Map.of("task", "approve tool"));
        HumanInteractionDraft draft = draft(scenario, submitted, user, "worker-handoff");
        JdbcHumanInteractionAuthority authority = authority(scenario);
        DurableHumanInteractionCoordinator coordinator = coordinator(scenario, authority);
        try (RunWorker worker = new RunWorker(scenario.engine, context -> RunOutcome.interaction(draft),
                "interaction-worker", scenario.scope(), RunScenario.LEASE, RunWorker.DEFAULT_CLAIM_QUARANTINE,
                RunWorker.DEFAULT_EFFECT_RUNTIME_GRACE, new RunWorker.NodeClaimState(), coordinator)) {
            assertThat(worker.pollOnce()).isEqualTo(RunWorker.Poll.EXECUTED);
        }

        assertThat(scenario.engine.run(scenario.workspace, submitted.id()).lifecycle()).isEqualTo(RunLifecycle.BLOCKED);
        assertThat(authority.findByIdempotency(scenario.workspace, submitted.id(), draft.idempotencyKey()))
                .get().extracting(record -> record.request().id()).isEqualTo(draft.id());

        coordinator.respond(scenario.workspace, draft.id(), identity(scenario, user),
                new HumanInteractionResponse.Approval(true), "user:" + user);
        try (RunWorker worker = new RunWorker(scenario.engine, context -> RunOutcome.completed("approved"),
                "resume-worker", scenario.scope(), RunScenario.LEASE)) {
            assertThat(worker.pollOnce()).isEqualTo(RunWorker.Poll.EXECUTED);
        }
        assertThat(scenario.engine.run(scenario.workspace, submitted.id()).lifecycle()).isEqualTo(RunLifecycle.SUCCEEDED);
    }

    @Test
    void allSixKindsBlockWithoutHoldingAWorkerAndAuthorizationHasItsOwnReason() {
        RunScenario scenario = new RunScenario();
        UUID user = UUID.randomUUID();
        DurableHumanInteractionCoordinator coordinator = coordinator(scenario, authority(scenario));
        List<HumanInteractionPrompt> prompts = List.of(
                new HumanInteractionPrompt.Approval("Approve", "Proceed?", "Production"),
                new HumanInteractionPrompt.Question("Question", "Answer?", "{\"type\":\"string\"}"),
                new HumanInteractionPrompt.Selection("Select", "Region?", List.of(
                        new HumanInteractionPrompt.Option("us", "US")), 1, 1),
                new HumanInteractionPrompt.Form("Form", "Complete", "{\"type\":\"object\"}"),
                new HumanInteractionPrompt.Confirmation("Confirm", "Continue?", "Production"),
                new HumanInteractionPrompt.Authorization("Authorize", "Connect",
                        new CredentialRef("env://calendar-token"), Set.of("calendar.read")));

        for (int index = 0; index < prompts.size(); index++) {
            var active = scenario.started("worker-" + index, RunScenario.LEASE);
            HumanInteractionDraft draft = draft(scenario, active.run(), user, "kind-" + index, prompts.get(index));
            var blocked = coordinator.block(active.lease(), draft);
            assertThat(blocked.run().summary().blockReason()).isEqualTo(index == prompts.size() - 1
                    ? RunBlockReason.AUTHORIZATION : RunBlockReason.HUMAN_INTERACTION);
            assertThat(blocked.run().control().leaseOwner()).isNull();
            var history = scenario.engine.history(scenario.workspace, active.run().id());
            assertThat(history.get(history.size() - 2).type()).isEqualTo(index == prompts.size() - 1
                    ? RunEventType.AUTHORIZATION_REQUIRED : RunEventType.HUMAN_INTERACTION_REQUESTED);
            assertThat(history.get(history.size() - 2).payload().toString()).doesNotContain("calendar-token");
        }
    }

    @Test
    void creationReleasesTheLeaseAndAuthorizedOutcomeResumesOnlyThePinnedRun() {
        RunScenario scenario = new RunScenario();
        var active = scenario.started("worker", RunScenario.LEASE);
        UUID user = UUID.randomUUID();
        InvocationIdentity identity = identity(scenario, user);
        JdbcHumanInteractionAuthority authority = authority(scenario);
        DurableHumanInteractionCoordinator coordinator = coordinator(scenario, authority);
        HumanInteractionDraft draft = draft(scenario, active.run(), user, "approval");

        var blocked = coordinator.block(active.lease(), draft);

        assertThat(blocked.run().lifecycle()).isEqualTo(RunLifecycle.BLOCKED);
        assertThat(blocked.run().control().leaseOwner()).isNull();
        assertThat(blocked.interaction().status()).isEqualTo(HumanInteractionStatus.OPEN);
        var blockedHistory = scenario.engine.history(scenario.workspace, active.run().id());
        var requestedEvent = blockedHistory.get(blockedHistory.size() - 2);
        assertThat(requestedEvent.type()).isEqualTo(RunEventType.HUMAN_INTERACTION_REQUESTED);
        assertThat(requestedEvent.payload().path("interactionId").stringValue()).isEqualTo(draft.id().toString());
        assertThat(requestedEvent.payload().path("correlationId").stringValue()).isEqualTo(draft.correlationId());
        assertThat(requestedEvent.payload().toString()).doesNotContain("idem-approval", "Proceed?");
        var blockEvent = blockedHistory.getLast();
        assertThat(blockEvent.type().name()).isEqualTo("RUN_BLOCKED");
        assertThat(blockEvent.payload().path("detail").path("interactionId").stringValue())
                .isEqualTo(draft.id().toString());

        var resolved = coordinator.respond(scenario.workspace, draft.id(), identity,
                new HumanInteractionResponse.Approval(true), "user:" + user);

        assertThat(resolved.interaction().status()).isEqualTo(HumanInteractionStatus.RESPONDED);
        assertThat(resolved.run().lifecycle()).isEqualTo(RunLifecycle.QUEUED);
        var resolvedHistory = scenario.engine.history(scenario.workspace, active.run().id());
        var outcomeEvent = resolvedHistory.get(resolvedHistory.size() - 2);
        assertThat(outcomeEvent.type()).isEqualTo(RunEventType.HUMAN_INTERACTION_RESPONDED);
        assertThat(outcomeEvent.payload().path("status").stringValue()).isEqualTo("RESPONDED");
        assertThat(outcomeEvent.payload().toString()).doesNotContain("approved", "true");
        var resumeEvent = resolvedHistory.getLast();
        assertThat(resumeEvent.type().name()).isEqualTo("RUN_RESUMED");
        assertThat(resumeEvent.payload().path("interactionId").stringValue()).isEqualTo(draft.id().toString());
        assertThat(resumeEvent.payload().path("interactionStatus").stringValue()).isEqualTo("RESPONDED");
    }

    @Test
    void authorizationAndNonResponseOutcomesHaveExactTypedEvents() throws InterruptedException {
        RunScenario scenario = new RunScenario();
        UUID user = UUID.randomUUID();
        InvocationIdentity identity = identity(scenario, user);
        DurableHumanInteractionCoordinator coordinator = coordinatorWithCredential(scenario, authority(scenario), identity);

        var grantedRun = scenario.started("worker-granted", RunScenario.LEASE);
        var grantedDraft = draft(scenario, grantedRun.run(), user, "granted",
                new HumanInteractionPrompt.Authorization("Authorize", "Connect",
                        new CredentialRef("env://calendar-token"), Set.of("calendar.read")));
        coordinator.block(grantedRun.lease(), grantedDraft);
        coordinator.respond(scenario.workspace, grantedDraft.id(), identity,
                new HumanInteractionResponse.Authorization(true), "user:" + user);
        assertOutcome(scenario, grantedRun.run().id(), RunEventType.AUTHORIZATION_GRANTED);

        var deniedRun = scenario.started("worker-denied", RunScenario.LEASE);
        var deniedDraft = draft(scenario, deniedRun.run(), user, "denied",
                new HumanInteractionPrompt.Authorization("Authorize", "Connect",
                        new CredentialRef("env://calendar-token"), Set.of("calendar.read")));
        coordinator.block(deniedRun.lease(), deniedDraft);
        coordinator.respond(scenario.workspace, deniedDraft.id(), identity,
                new HumanInteractionResponse.Authorization(false), "user:" + user);
        assertOutcome(scenario, deniedRun.run().id(), RunEventType.AUTHORIZATION_FAILED);

        var cancelledRun = scenario.started("worker-cancelled", RunScenario.LEASE);
        var cancelledDraft = draft(scenario, cancelledRun.run(), user, "cancelled");
        coordinator.block(cancelledRun.lease(), cancelledDraft);
        coordinator.cancel(scenario.workspace, cancelledDraft.id(), "system:run-cancel");
        assertOutcome(scenario, cancelledRun.run().id(), RunEventType.HUMAN_INTERACTION_CANCELLED);

        var expiredRun = scenario.started("worker-expired", RunScenario.LEASE);
        var expiring = draft(scenario, expiredRun.run(), user, "expired");
        var expiringDraft = new HumanInteractionDraft(expiring.id(), expiring.workspaceId(), expiring.runId(),
                expiring.releaseId(), expiring.deploymentId(), expiring.revisionId(), expiring.cohortId(),
                expiring.correlationId(), expiring.idempotencyKey(), expiring.prompt(), expiring.assignment(),
                Duration.ofMillis(1));
        coordinator.block(expiredRun.lease(), expiringDraft);
        Thread.sleep(20);
        coordinator.expire(scenario.workspace, expiringDraft.id(), "system:interaction-expiry");
        assertOutcome(scenario, expiredRun.run().id(), RunEventType.HUMAN_INTERACTION_EXPIRED);

        var revokedRun = scenario.started("worker-revoked", RunScenario.LEASE);
        var revokedDraft = draft(scenario, revokedRun.run(), user, "revoked",
                new HumanInteractionPrompt.Authorization("Authorize", "Connect",
                        new CredentialRef("env://calendar-token"), Set.of("calendar.read")));
        coordinator.block(revokedRun.lease(), revokedDraft);
        coordinator.revoke(scenario.workspace, revokedDraft.id(), "system:credential-revocation");
        assertOutcome(scenario, revokedRun.run().id(), RunEventType.AUTHORIZATION_REVOKED);
    }

    @Test
    void grantedAuthorizationWithoutCurrentCredentialStaysOpenAndBlockedUntilAValidRetry() {
        RunScenario scenario = new RunScenario();
        UUID user = UUID.randomUUID();
        InvocationIdentity identity = identity(scenario, user);
        JdbcHumanInteractionAuthority authority = authority(scenario);
        DurableHumanInteractionCoordinator coordinator = coordinator(scenario, authority);
        var active = scenario.started("worker-unavailable", RunScenario.LEASE);
        var draft = draft(scenario, active.run(), user, "unavailable",
                new HumanInteractionPrompt.Authorization("Authorize", "Connect",
                        new CredentialRef("env://calendar-token"), Set.of("calendar.read")));
        coordinator.block(active.lease(), draft);

        assertThatThrownBy(() -> coordinator.respond(scenario.workspace, draft.id(), identity,
                new HumanInteractionResponse.Authorization(true), "user:" + user))
                .isInstanceOfSatisfying(HumanInteractionAuthorityException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(HumanInteractionAuthorityException.Code.CREDENTIAL_UNAVAILABLE));
        assertThat(authority.find(scenario.workspace, draft.id()).orElseThrow().status())
                .isEqualTo(HumanInteractionStatus.OPEN);
        assertThat(scenario.engine.run(scenario.workspace, active.run().id()).lifecycle())
                .isEqualTo(RunLifecycle.BLOCKED);

        var resolved = coordinatorWithCredential(scenario, authority, identity).respond(scenario.workspace,
                draft.id(), identity, new HumanInteractionResponse.Authorization(true), "user:" + user);
        assertThat(resolved.interaction().status()).isEqualTo(HumanInteractionStatus.RESPONDED);
        assertThat(resolved.run().lifecycle()).isEqualTo(RunLifecycle.QUEUED);
    }

    @Test
    void failedCreationRollsBackTheBlockAndFailedResumeRollsBackTheOutcome() {
        RunScenario scenario = new RunScenario();
        var active = scenario.started("worker", RunScenario.LEASE);
        UUID user = UUID.randomUUID();
        JdbcHumanInteractionAuthority authority = authority(scenario);
        DurableHumanInteractionCoordinator coordinator = coordinator(scenario, authority);
        HumanInteractionDraft valid = draft(scenario, active.run(), user, "rollback");
        HumanInteractionDraft wrongRelease = new HumanInteractionDraft(valid.id(), valid.workspaceId(), valid.runId(),
                UUID.randomUUID(), valid.deploymentId(), valid.revisionId(), valid.cohortId(), valid.correlationId(),
                valid.idempotencyKey(), valid.prompt(), valid.assignment(), valid.timeout());

        assertThatThrownBy(() -> coordinator.block(active.lease(), wrongRelease))
                .isInstanceOf(HumanInteractionAuthorityException.class);
        assertThat(scenario.engine.run(scenario.workspace, active.run().id()).lifecycle()).isEqualTo(RunLifecycle.ACTIVE);
        assertThat(authority.find(scenario.workspace, valid.id())).isEmpty();

        var standalone = authority.create(scenario.workspace, valid);
        InvocationIdentity identity = identity(scenario, user);
        assertThatThrownBy(() -> coordinator.respond(scenario.workspace, standalone.request().id(), identity,
                new HumanInteractionResponse.Approval(true), "user:" + user))
                .isInstanceOfSatisfying(RunEngineException.class,
                        failure -> assertThat(failure.code()).isEqualTo("INTERACTION_NOT_BLOCKING"));
        assertThat(authority.find(scenario.workspace, standalone.request().id()).orElseThrow().status())
                .isEqualTo(HumanInteractionStatus.OPEN);
        assertThat(scenario.engine.run(scenario.workspace, active.run().id()).lifecycle()).isEqualTo(RunLifecycle.ACTIVE);
    }

    private static JdbcHumanInteractionAuthority authority(RunScenario scenario) {
        return new JdbcHumanInteractionAuthority(scenario.fixture.jdbc, scenario.fixture.transactions);
    }

    private static DurableHumanInteractionCoordinator coordinator(RunScenario scenario,
            JdbcHumanInteractionAuthority authority) {
        return new DurableHumanInteractionCoordinator(scenario.fixture.transactions, scenario.engine, authority,
                LifecycleRetry.standard());
    }

    private static DurableHumanInteractionCoordinator coordinatorWithCredential(RunScenario scenario,
            JdbcHumanInteractionAuthority authority, InvocationIdentity expectedIdentity) {
        CredentialProvider provider = new CredentialProvider() {
            @Override
            public CredentialAuthorization authorize(CredentialUseRequest request, Instant now) {
                assertThat(request.identity()).isEqualTo(expectedIdentity);
                assertThat(request.agentId()).isPresent();
                return new CredentialAuthorization(UUID.randomUUID(), request.credentialRef(), request.identity());
            }

            @Override
            public CredentialAuthorization authorize(CredentialRef reference, InvocationIdentity identity,
                    Instant now) {
                throw new AssertionError("agent-scoped authorization must be used");
            }

            @Override
            public ResolvedCredential resolve(CredentialAuthorization authorization, Instant now) {
                return new ResolvedCredential("secret-canary".toCharArray(), now.plusSeconds(60));
            }
        };
        var exchange = new DelegatedAuthorizationExchange(provider, event -> {
            assertThat(event.action()).isEqualTo(DelegatedAuthorizationExchange.AUDIT_ACTION);
            assertThat(event.toString()).doesNotContain("secret-canary");
        }, Clock.systemUTC());
        return new DurableHumanInteractionCoordinator(scenario.fixture.transactions, scenario.engine, authority,
                LifecycleRetry.standard(), exchange);
    }

    private static InvocationIdentity identity(RunScenario scenario, UUID user) {
        return new InvocationIdentity(UUID.randomUUID(), scenario.workspace.value(), user, UUID.randomUUID(),
                UUID.randomUUID());
    }

    private static void assertOutcome(RunScenario scenario, UUID runId, RunEventType expected) {
        var history = scenario.engine.history(scenario.workspace, runId);
        assertThat(history.get(history.size() - 2).type()).isEqualTo(expected);
        assertThat(history.getLast().type()).isEqualTo(RunEventType.RUN_RESUMED);
    }

    private static HumanInteractionDraft draft(RunScenario scenario,
            com.dvarahq.agentworks.domain.run.RunRecord run, UUID user, String key) {
        return draft(scenario, run, user, key,
                new HumanInteractionPrompt.Approval("Approve", "Proceed?", "Production"));
    }

    private static HumanInteractionDraft draft(RunScenario scenario,
            com.dvarahq.agentworks.domain.run.RunRecord run, UUID user, String key, HumanInteractionPrompt prompt) {
        return new HumanInteractionDraft(UUID.randomUUID(), scenario.workspace.value(), run.id(), run.pins().releaseId(),
                run.pins().deploymentId(), run.pins().revisionId(), run.pins().cohortId(), "corr-" + key,
                "idem-" + key, prompt,
                HumanInteractionAssignment.user(scenario.workspace.value(), user), Duration.ofMinutes(5));
    }
}
