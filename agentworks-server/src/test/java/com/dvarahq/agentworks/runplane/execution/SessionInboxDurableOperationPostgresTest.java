package com.dvarahq.agentworks.runplane.execution;

import com.dvarahq.agentworks.domain.run.RunEventType;
import com.dvarahq.agentworks.domain.run.RunLifecycle;
import com.dvarahq.agentworks.operation.DurableOperationAuthorityException;
import com.dvarahq.agentworks.operation.DurableOperationHandle;
import com.dvarahq.agentworks.operation.DurableOperationProvider;
import com.dvarahq.agentworks.operation.DurableOperationRecord;
import com.dvarahq.agentworks.operation.DurableOperationRequest;
import com.dvarahq.agentworks.operation.DurableOperationResult;
import com.dvarahq.agentworks.operation.DurableOperationStatus;
import com.dvarahq.agentworks.session.InboxMessageDraft;
import com.dvarahq.agentworks.session.InboxMessageType;
import com.dvarahq.agentworks.storage.lifecycle.IdempotencyDigests;
import com.dvarahq.agentworks.storage.lifecycle.LifecycleRetry;
import com.dvarahq.agentworks.storage.operation.JdbcDurableOperationAuthority;
import com.dvarahq.agentworks.storage.session.JdbcSessionInboxAuthority;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** PostgreSQL authority, safe-boundary, restart, callback, and one-winner evidence for issue #43. */
class SessionInboxDurableOperationPostgresTest {

    @Test
    void concurrentInboxDeliveryHasStableOrderingAndFencedSafeBoundaryConsumption() throws Exception {
        RunScenario scenario = new RunScenario();
        var active = scenario.started("inbox-worker", RunScenario.LEASE);
        var authority = inbox(scenario);
        UUID sessionId = UUID.randomUUID();
        authority.create(scenario.workspace, sessionId, active.run().pins().deploymentId());
        authority.linkRun(scenario.workspace, sessionId, active.run().id());

        List<Object> outcomes = RunScenario.race(8, index -> authority.enqueue(scenario.workspace,
                message(active.run(), sessionId, "message-" + index, Map.of("text", "value-" + index))));
        assertThat(outcomes).allMatch(com.dvarahq.agentworks.session.SessionInboxMessage.class::isInstance);
        assertThat(authority.list(scenario.workspace, sessionId, null, null, 20))
                .extracting(com.dvarahq.agentworks.session.SessionInboxMessage::sequence)
                .containsExactly(8L, 7L, 6L, 5L, 4L, 3L, 2L, 1L);

        var coordinator = new SessionInboxCoordinator(scenario.fixture.transactions, LifecycleRetry.standard(),
                scenario.engine, authority);
        var claimed = coordinator.claim(active.lease(), 3, Duration.ofMillis(200));
        assertThat(claimed.messages()).extracting(com.dvarahq.agentworks.session.SessionInboxMessage::sequence)
                .containsExactly(1L, 2L, 3L);
        var consumed = coordinator.consume(claimed.lease(), claimed.messages().getFirst());
        assertThat(consumed.message().status().name()).isEqualTo("CONSUMED");
        assertThat(scenario.engine.history(scenario.workspace, active.run().id()))
                .extracting(event -> event.type()).containsSubsequence(RunEventType.INBOX_MESSAGE_RECEIVED,
                        RunEventType.INBOX_MESSAGE_CONSUMED);

        // A reconstructed authority sees the same sequence, reclaims an expired database-time claim, and another
        // workspace sees nothing.
        Thread.sleep(250);
        var restarted = inbox(scenario);
        assertThat(restarted.claim(active.lease(), 1, Duration.ofMinutes(1)))
                .extracting(com.dvarahq.agentworks.session.SessionInboxMessage::sequence).containsExactly(2L);
        assertThat(restarted.find(scenario.workspace, consumed.message().id())).contains(consumed.message());
        assertThat(restarted.find(scenario.fixture.workspace(), consumed.message().id())).isEmpty();
    }

    @Test
    void operationCompletionCreatesOneInboxMessageAndResumesTheOriginalPinnedRun() {
        RunScenario scenario = new RunScenario();
        var active = scenario.started("operation-worker", RunScenario.LEASE);
        var inbox = inbox(scenario);
        var operations = operations(scenario);
        UUID sessionId = UUID.randomUUID();
        inbox.create(scenario.workspace, sessionId, active.run().pins().deploymentId());
        inbox.linkRun(scenario.workspace, sessionId, active.run().id());
        FakeProvider provider = new FakeProvider();
        DurableOperationCoordinator coordinator = coordinator(scenario, inbox, operations, provider);

        var started = coordinator.start(active.lease(), sessionId, provider.id(), "ci.pipeline",
                Map.of("pipeline", "release"), "corr-operation", "operation-1", Duration.ofHours(1));
        assertThat(started.status()).isEqualTo(DurableOperationStatus.RUNNING);
        assertThat(coordinator.start(active.lease(), sessionId, provider.id(), "ci.pipeline",
                Map.of("pipeline", "release"), "corr-operation", "operation-1", Duration.ofHours(1)))
                .isEqualTo(started);
        assertThat(scenario.engine.run(scenario.workspace, active.run().id()).lifecycle())
                .isEqualTo(RunLifecycle.BLOCKED);
        assertThat(scenario.engine.run(scenario.workspace, active.run().id()).control().leaseOwner()).isNull();

        var completed = coordinator.callback(scenario.workspace, started.id(), provider.request.get().callbackToken(),
                new DurableOperationResult(DurableOperationStatus.SUCCEEDED, "COMPLETED", Map.of("build", "ok")));
        assertThat(completed.operation().status()).isEqualTo(DurableOperationStatus.SUCCEEDED);
        assertThat(completed.message().type()).isEqualTo(InboxMessageType.OPERATION_COMPLETED);
        assertThat(completed.run().lifecycle()).isEqualTo(RunLifecycle.QUEUED);
        assertThat(inbox.list(scenario.workspace, sessionId, null, null, 10)).hasSize(1);
        assertThat(scenario.engine.history(scenario.workspace, active.run().id())).extracting(event -> event.type())
                .endsWith(RunEventType.OPERATION_STARTED, RunEventType.RUN_BLOCKED,
                        RunEventType.OPERATION_COMPLETED, RunEventType.INBOX_MESSAGE_RECEIVED,
                        RunEventType.RUN_RESUMED);

        assertThatThrownBy(() -> coordinator.callback(scenario.workspace, started.id(),
                provider.request.get().callbackToken(),
                new DurableOperationResult(DurableOperationStatus.SUCCEEDED, "DUPLICATE", Map.of())))
                .isInstanceOfSatisfying(DurableOperationAuthorityException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(DurableOperationAuthorityException.Code.ALREADY_TERMINAL));
        assertThat(inbox.list(scenario.workspace, sessionId, null, null, 10)).hasSize(1);
    }

    @Test
    void providerStartUncertaintyReconcilesAfterRestartWithTheSameCallbackToken() {
        RunScenario scenario = new RunScenario();
        var active = scenario.started("restart-worker", RunScenario.LEASE);
        var inbox = inbox(scenario);
        var operations = operations(scenario);
        UUID sessionId = UUID.randomUUID();
        inbox.create(scenario.workspace, sessionId, active.run().pins().deploymentId());
        inbox.linkRun(scenario.workspace, sessionId, active.run().id());
        FakeProvider provider = new FakeProvider();
        provider.failStart = true;
        byte[] originalKey = new byte[32];
        Arrays.fill(originalKey, (byte) 7);
        DurableOperationCoordinator first = coordinator(scenario, inbox, operations, provider,
                new IdempotencyDigests(Map.of(1, originalKey), 1));

        assertThatThrownBy(() -> first.start(active.lease(), sessionId, provider.id(), "export",
                Map.of("kind", "audit"), "corr-restart", "operation-restart", Duration.ofHours(1)))
                .isInstanceOfSatisfying(DurableOperationAuthorityException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(DurableOperationAuthorityException.Code.PROVIDER_UNAVAILABLE));
        var pending = operations.recoverable(scenario.workspace, 10).getFirst();
        String firstToken = provider.request.get().callbackToken();

        provider.failStart = false;
        byte[] rotatedKey = new byte[32];
        Arrays.fill(rotatedKey, (byte) 8);
        var restarted = coordinator(scenario, inbox, operations, provider,
                new IdempotencyDigests(Map.of(1, originalKey, 2, rotatedKey), 2));
        var running = restarted.reconcile(scenario.workspace, pending.id());
        assertThat(running.status()).isEqualTo(DurableOperationStatus.RUNNING);
        assertThat(provider.request.get().callbackToken()).isEqualTo(firstToken);
        assertThat(operations(scenario).find(scenario.workspace, pending.id())).contains(running);

        assertThatThrownBy(() -> restarted.callback(scenario.workspace, pending.id(), "wrong-token",
                new DurableOperationResult(DurableOperationStatus.SUCCEEDED, "DONE", Map.of())))
                .isInstanceOfSatisfying(DurableOperationAuthorityException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(DurableOperationAuthorityException.Code.CALLBACK_UNAUTHORIZED));
        assertThat(operations.find(scenario.workspace, pending.id()).orElseThrow().status())
                .isEqualTo(DurableOperationStatus.RUNNING);
    }

    @Test
    void sessionRootsUseCurrentRoutingWhileDedupReplayKeepsTheOriginalPins() {
        RunScenario scenario = new RunScenario();
        var inbox = inbox(scenario);
        UUID sessionId = UUID.randomUUID();
        inbox.create(scenario.workspace, sessionId, scenario.promotion.deploymentId());
        var deliveries = new SessionDeliveryCoordinator(scenario.fixture.transactions, LifecycleRetry.standard(),
                scenario.fixture.admissions, scenario.engine, inbox);

        var first = deliveries.deliver(scenario.workspace, sessionId, null, InboxMessageType.USER_MESSAGE,
                "corr-first", "root-first", Map.of("text", "first"), null, "system:test", null);
        UUID firstRevision = first.message().revisionId();
        UUID firstRun = first.message().runId();

        scenario.fixture.promote(scenario.workspace, scenario.environment, "api",
                scenario.fixture.release(scenario.workspace, "agent", "2.0.0"));
        var replay = deliveries.deliver(scenario.workspace, sessionId, null, InboxMessageType.USER_MESSAGE,
                "corr-first", "root-first", Map.of("text", "first"), null, "system:test", null);
        var second = deliveries.deliver(scenario.workspace, sessionId, null, InboxMessageType.USER_MESSAGE,
                "corr-second", "root-second", Map.of("text", "second"), null, "system:test", null);

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.message().runId()).isEqualTo(firstRun);
        assertThat(replay.message().revisionId()).isEqualTo(firstRevision);
        assertThat(second.message().runId()).isNotEqualTo(firstRun);
        assertThat(second.message().revisionId()).isNotEqualTo(firstRevision);
        assertThat(inbox.findSession(scenario.workspace, sessionId).orElseThrow().deploymentId())
                .isEqualTo(scenario.promotion.deploymentId());
    }

    @RepeatedTest(5)
    void callbackAndPollRaceProduceOneTerminalResultAndOneCompletionMessage() throws Exception {
        RunScenario scenario = new RunScenario();
        var active = scenario.started("race-worker", RunScenario.LEASE);
        var inbox = inbox(scenario);
        var operations = operations(scenario);
        UUID sessionId = UUID.randomUUID();
        inbox.create(scenario.workspace, sessionId, active.run().pins().deploymentId());
        inbox.linkRun(scenario.workspace, sessionId, active.run().id());
        FakeProvider provider = new FakeProvider();
        DurableOperationCoordinator coordinator = coordinator(scenario, inbox, operations, provider);
        var started = coordinator.start(active.lease(), sessionId, provider.id(), "race",
                Map.of("work", "same"), "corr-race", "race-1", Duration.ofHours(1));
        provider.terminalResult = new DurableOperationResult(DurableOperationStatus.SUCCEEDED, "POLLED", Map.of());

        List<Object> outcomes = RunScenario.race(2, index -> index == 0
                ? coordinator.reconcile(scenario.workspace, started.id())
                : coordinator.callback(scenario.workspace, started.id(), provider.request.get().callbackToken(),
                        new DurableOperationResult(DurableOperationStatus.SUCCEEDED, "CALLBACK", Map.of())));

        List<DurableOperationRecord> returned = new ArrayList<>();
        List<DurableOperationAuthorityException> rejected = new ArrayList<>();
        for (Object outcome : outcomes) {
            if (outcome instanceof DurableOperationRecord operation) returned.add(operation);
            else if (outcome instanceof DurableOperationCoordinator.CompletedOperation completed) {
                returned.add(completed.operation());
            } else {
                assertThat(outcome).isInstanceOf(DurableOperationAuthorityException.class);
                rejected.add((DurableOperationAuthorityException) outcome);
            }
        }
        // If the callback commits before reconcile's first read, reconcile idempotently returns that terminal row;
        // otherwise the overlapping loser observes ALREADY_TERMINAL after the one-winner transaction commits.
        assertThat(returned).isNotEmpty().allSatisfy(operation -> {
            assertThat(operation.id()).isEqualTo(started.id());
            assertThat(operation.status()).isEqualTo(DurableOperationStatus.SUCCEEDED);
        });
        assertThat(rejected).hasSizeLessThanOrEqualTo(1).allSatisfy(failure ->
                assertThat(failure.code()).isEqualTo(DurableOperationAuthorityException.Code.ALREADY_TERMINAL));
        DurableOperationRecord stored = operations.find(scenario.workspace, started.id()).orElseThrow();
        assertThat(stored.status()).isEqualTo(DurableOperationStatus.SUCCEEDED);
        assertThat(returned).allSatisfy(operation -> assertThat(operation.result()).isEqualTo(stored.result()));
        assertThat(coordinator.reconcile(scenario.workspace, started.id())).isEqualTo(stored);
        assertThat(inbox.list(scenario.workspace, sessionId, null, null, 10)).singleElement().satisfies(message ->
                assertThat(message.payload().get("code")).isEqualTo(stored.result().orElseThrow().code()));
        assertThat(scenario.engine.run(scenario.workspace, active.run().id()).lifecycle()).isEqualTo(RunLifecycle.QUEUED);
    }

    @Test
    void correlatedDeliveryCannotInjectAnotherSessionsRun() {
        RunScenario scenario = new RunScenario();
        var active = scenario.started("session-owner", RunScenario.LEASE);
        var inbox = inbox(scenario);
        UUID owner = UUID.randomUUID();
        UUID attacker = UUID.randomUUID();
        inbox.create(scenario.workspace, owner, active.run().pins().deploymentId());
        inbox.create(scenario.workspace, attacker, active.run().pins().deploymentId());
        inbox.linkRun(scenario.workspace, owner, active.run().id());
        var deliveries = new SessionDeliveryCoordinator(scenario.fixture.transactions, LifecycleRetry.standard(),
                scenario.fixture.admissions, scenario.engine, inbox);

        assertThatThrownBy(() -> deliveries.deliver(scenario.workspace, attacker, active.run().id(),
                InboxMessageType.USER_MESSAGE, "corr-injection", "inject-1", Map.of("text", "no"), null,
                "system:test", null)).isInstanceOfSatisfying(
                        com.dvarahq.agentworks.session.SessionAuthorityException.class,
                        failure -> assertThat(failure.code()).isEqualTo(
                                com.dvarahq.agentworks.session.SessionAuthorityException.Code.RUN_NOT_IN_SESSION));
    }

    private static JdbcSessionInboxAuthority inbox(RunScenario scenario) {
        return new JdbcSessionInboxAuthority(scenario.fixture.jdbc, scenario.fixture.transactions);
    }

    private static JdbcDurableOperationAuthority operations(RunScenario scenario) {
        return new JdbcDurableOperationAuthority(scenario.fixture.jdbc, scenario.fixture.transactions);
    }

    private static DurableOperationCoordinator coordinator(RunScenario scenario, JdbcSessionInboxAuthority inbox,
            JdbcDurableOperationAuthority operations, FakeProvider provider) {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) 7);
        return coordinator(scenario, inbox, operations, provider, new IdempotencyDigests(Map.of(1, key), 1));
    }

    private static DurableOperationCoordinator coordinator(RunScenario scenario, JdbcSessionInboxAuthority inbox,
            JdbcDurableOperationAuthority operations, FakeProvider provider, IdempotencyDigests digests) {
        return new DurableOperationCoordinator(scenario.fixture.transactions, LifecycleRetry.standard(),
                scenario.engine, operations, inbox, List.of(provider),
                new OperationCallbackTokens(digests),
                "https://callbacks.example.test");
    }

    private static InboxMessageDraft message(com.dvarahq.agentworks.domain.run.RunRecord run, UUID sessionId,
            String key, Map<String, Object> payload) {
        return new InboxMessageDraft(UUID.randomUUID(), run.pins().workspaceId(), sessionId, run.id(),
                run.pins().releaseId(), run.pins().deploymentId(), run.pins().revisionId(), run.pins().cohortId(),
                InboxMessageType.USER_MESSAGE, "corr-" + key, key, payload);
    }

    private static final class FakeProvider implements DurableOperationProvider {
        private final AtomicReference<DurableOperationRequest> request = new AtomicReference<>();
        private volatile boolean failStart;
        private volatile DurableOperationResult terminalResult;

        @Override public String id() { return "fake-operation"; }
        @Override public DurableOperationHandle start(DurableOperationRequest request) {
            this.request.set(request);
            if (failStart) throw new IllegalStateException("provider unavailable");
            return new DurableOperationHandle(id(), "external-" + request.operationId());
        }
        @Override public ProviderStatus status(DurableOperationHandle handle) {
            return terminalResult == null
                    ? new ProviderStatus(ProviderStatus.State.RUNNING, 50, "RUNNING")
                    : new ProviderStatus(ProviderStatus.State.SUCCEEDED, 100, "SUCCEEDED");
        }
        @Override public Optional<DurableOperationResult> result(DurableOperationHandle handle) {
            return Optional.ofNullable(terminalResult);
        }
        @Override public void cancel(DurableOperationHandle handle) {}
    }
}
