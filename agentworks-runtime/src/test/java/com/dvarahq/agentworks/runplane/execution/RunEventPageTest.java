package com.dvarahq.agentworks.runplane.execution;

import com.dvarahq.agentworks.domain.run.ClaimScope;
import com.dvarahq.agentworks.domain.run.DurableRunStore;
import com.dvarahq.agentworks.domain.run.RunEvent;
import com.dvarahq.agentworks.domain.run.RunLease;
import com.dvarahq.agentworks.domain.run.RunRecord;
import com.dvarahq.agentworks.domain.run.RunTransaction;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import com.dvarahq.agentworks.runplane.execution.DurableRunEngine.EventDraft;
import com.dvarahq.agentworks.runplane.execution.DurableRunEngine.EventPage;
import com.dvarahq.agentworks.runplane.execution.RunEventCodec.DecodedEvent;
import com.dvarahq.agentworks.storage.lifecycle.LifecycleRetry;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.LongStream;

import static com.dvarahq.agentworks.runplane.execution.DurableRunEngineTest.assertCode;
import static com.dvarahq.agentworks.runplane.execution.DurableRunEngineTest.model;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verified history pages (issue #35): the read the events API and the reconnectable stream both use. Each page is a
 * slice of the digest chain anchored to the event before it, never extends past the run's summary, and refuses a
 * position ahead of the run, before retained history, or whose stored anchor differs from the reader's.
 */
class RunEventPageTest {
    private final EngineHarness harness = new EngineHarness();
    private final DurableRunEngine engine = harness.engine;

    @Test
    void consecutivePagesFormOneVerifiedGapFreeChain() {
        UUID run = runWithEvents(7);
        long last = engine.run(harness.workspace, run).summary().eventSequence();
        List<DecodedEvent> history = engine.history(harness.workspace, run);

        List<DecodedEvent> read = new ArrayList<>();
        long after = 0;
        String anchor = null;
        boolean more = true;
        int pages = 0;
        while (more) {
            EventPage page = engine.eventPage(harness.workspace, run, after, 4, anchor);
            assertThat(page.events()).hasSizeLessThanOrEqualTo(4);
            read.addAll(page.events());
            after = page.lastSequence(after);
            anchor = page.events().isEmpty() ? anchor : page.events().getLast().raw().eventDigest();
            more = page.more();
            pages++;
        }

        assertThat(pages).isEqualTo((int) Math.ceil(last / 4.0));
        assertThat(read).extracting(event -> event.raw().sequence())
                .containsExactlyElementsOf(LongStream.rangeClosed(1, last).boxed().toList());
        assertThat(read).isEqualTo(history);
        EventPage atEnd = engine.eventPage(harness.workspace, run, last, 4, anchor);
        assertThat(atEnd.events()).isEmpty();
        assertThat(atEnd.more()).isFalse();
        assertThat(atEnd.run().summary().eventSequence()).isEqualTo(last);
    }

    @Test
    void positionsAheadOfTheRunNegativeOrOutOfRangeAreRefusedWithoutReading() {
        UUID run = runWithEvents(2);
        long last = engine.run(harness.workspace, run).summary().eventSequence();

        assertCode(() -> engine.eventPage(harness.workspace, run, last + 1, 10, null), "EVENT_SEQUENCE_AHEAD");
        assertCode(() -> engine.eventPage(harness.workspace, run, -1, 10, null), "INVALID_EVENT_POSITION");
        assertThatThrownBy(() -> engine.eventPage(harness.workspace, run, 0, 0, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> engine.eventPage(harness.workspace, run, 0, DurableRunEngine.MAX_EVENT_PAGE + 1, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> engine.eventPage(harness.workspace, run, 0, 10, "sha256:anything"))
                .isInstanceOf(IllegalArgumentException.class);
        assertCode(() -> engine.eventPage(harness.workspace, UUID.randomUUID(), 0, 10, null), "NOT_FOUND");
    }

    @Test
    void aRunOfAnotherWorkspaceIsIndistinguishableFromAnAbsentOne() {
        UUID run = runWithEvents(1);
        EngineHarness other = new EngineHarness(harness);

        assertCode(() -> engine.eventPage(other.workspace, run, 0, 10, null), "NOT_FOUND");
        assertThat(engine.eventSequences(other.workspace, List.of(run))).isEmpty();
        assertThat(engine.eventSequences(harness.workspace, List.of(run, UUID.randomUUID())))
                .containsOnlyKeys(run);
    }

    @Test
    void anAnchorThatDiffersFromTheReadersEventIsCorruptHistory() {
        UUID run = runWithEvents(3);
        EventPage first = engine.eventPage(harness.workspace, run, 0, 2, null);

        assertCode(() -> engine.eventPage(harness.workspace, run, 2, 2, "sha256:" + "0".repeat(64)), "CORRUPT_HISTORY");
        assertThat(engine.eventPage(harness.workspace, run, 2, 2, first.events().getLast().raw().eventDigest()).events())
                .hasSize(2);
    }

    @Test
    void aTamperedOrMissingEventInThePageIsCorruptHistoryNeverASilentGap() {
        UUID tampered = runWithEvents(4);
        List<RunEvent> events = harness.store.raw().events.get(tampered);
        RunEvent original = events.get(3);
        events.set(3, new RunEvent(original.id(), original.workspaceId(), original.runId(), original.sequence(),
                original.type(), original.schemaVersion(), "{\"usage\":{\"modelCalls\":99}}", original.payloadDigest(),
                original.eventDigest(), original.traceId(), original.spanId(), original.releaseId(),
                original.deploymentId(), original.revisionId(), original.cohortId(), original.actor(),
                original.leaseOwner(), original.leaseFence(), original.createdAt()));
        assertCode(() -> engine.eventPage(harness.workspace, tampered, 2, 5, null), "CORRUPT_HISTORY");

        UUID gap = runWithEvents(4);
        harness.store.raw().events.get(gap).remove(4);
        assertCode(() -> engine.eventPage(harness.workspace, gap, 3, 5, null), "CORRUPT_HISTORY");
        UUID missingAnchor = runWithEvents(4);
        harness.store.raw().events.get(missingAnchor).remove(2);
        assertCode(() -> engine.eventPage(harness.workspace, missingAnchor, 3, 5, null), "CORRUPT_HISTORY");
    }

    @Test
    void aPositionBeforeRetainedHistoryIsExpiredAndNeverResumedAcrossTheGap() {
        UUID run = runWithEvents(6);
        long last = engine.run(harness.workspace, run).summary().eventSequence();
        // A store whose retention compacted events 1 to 3.
        DurableRunEngine compacted = new DurableRunEngine(new Compacting(harness.store, 3), engine.codec(),
                new ReleaseManifestReader(), new LifecycleRetry(2));

        for (long position : new long[]{0, 1, 3}) {
            assertCode(() -> compacted.eventPage(harness.workspace, run, position, 10, null), "EVENT_HISTORY_EXPIRED");
        }
        EventPage resumed = compacted.eventPage(harness.workspace, run, 4, 10, null);
        assertThat(resumed.events()).extracting(event -> event.raw().sequence())
                .containsExactlyElementsOf(LongStream.rangeClosed(5, last).boxed().toList());
    }

    @Test
    void codecSlicesMustStartWhereTheirAnchorSays() {
        UUID run = runWithEvents(2);
        List<RunEvent> stored = harness.store.raw().events.get(run);
        RunEventCodec codec = engine.codec();

        assertThat(codec.decodeRange(stored.get(0).eventDigest(), 2, stored.subList(1, stored.size()))).hasSize(stored.size() - 1);
        assertCode(() -> codec.decodeRange(stored.get(0).eventDigest(), 3, stored.subList(1, stored.size())),
                "CORRUPT_HISTORY");
        assertCode(() -> codec.decodeRange(stored.get(1).eventDigest(), 2, stored.subList(1, stored.size())),
                "CORRUPT_HISTORY");
        assertThatThrownBy(() -> codec.decodeRange(null, 2, stored)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> codec.decodeRange("sha256:x", 1, stored)).isInstanceOf(IllegalArgumentException.class);
    }

    /** A run with its creation, queueing, activation, and {@code chunks} worker events. */
    private UUID runWithEvents(int chunks) {
        UUID id = harness.run();
        engine.enqueue(harness.workspace, id, "system:test");
        var scope = ClaimScope.any(ReleaseManifestReader.SUPPORTED_VERSIONS).in(harness.workspace)
                .excluding(harness.store.raw().runs.keySet().stream().filter(other -> !other.equals(id))
                        .collect(java.util.stream.Collectors.toSet()));
        RunLease lease = engine.claimNext("worker", scope, EngineHarness.LEASE).orElseThrow().lease();
        List<EventDraft> drafts = new ArrayList<>();
        for (int index = 0; index < chunks; index++) drafts.add(model(1));
        if (!drafts.isEmpty()) engine.append(lease, drafts);
        return id;
    }

    /** A store whose retention has compacted each run's events up to {@code through}. */
    private record Compacting(DurableRunStore delegate, long through) implements DurableRunStore {
        @Override
        public <T> T inTransaction(WorkspaceId workspace, Function<RunTransaction, T> work) {
            return delegate.inTransaction(workspace, tx -> work.apply(compacted(tx)));
        }

        @Override
        public <T> Optional<T> claimNext(ClaimScope scope, BiFunction<RunRecord, RunTransaction, T> work) {
            return delegate.claimNext(scope, (run, tx) -> work.apply(run, compacted(tx)));
        }

        private RunTransaction compacted(RunTransaction tx) {
            return (RunTransaction) Proxy.newProxyInstance(RunTransaction.class.getClassLoader(),
                    new Class<?>[]{RunTransaction.class}, (proxy, method, arguments) -> switch (method.getName()) {
                        case "compactedThrough" -> through;
                        case "eventWindow" -> tx.eventWindow((UUID) arguments[0], Math.max((long) arguments[1], through + 1),
                                (long) arguments[2], (int) arguments[3]);
                        default -> {
                            try {
                                yield method.invoke(tx, arguments);
                            } catch (InvocationTargetException failure) {
                                throw failure.getCause();
                            }
                        }
                    });
        }
    }
}
