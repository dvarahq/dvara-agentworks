package com.dvarahq.agentworks.runplane.execution;

import com.dvarahq.agentworks.domain.run.ClaimScope;
import com.dvarahq.agentworks.domain.run.DurableRunStore;
import com.dvarahq.agentworks.domain.run.RunLease;
import com.dvarahq.agentworks.domain.run.RunLifecycle;
import com.dvarahq.agentworks.domain.run.RunLimits;
import com.dvarahq.agentworks.domain.run.RunQuery;
import com.dvarahq.agentworks.domain.run.RunRecord;
import com.dvarahq.agentworks.domain.run.RunTransaction;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import com.dvarahq.agentworks.runplane.execution.DurableRunEngine.EventDraft;
import com.dvarahq.agentworks.runplane.execution.DurableRunEngine.EventPage;
import com.dvarahq.agentworks.runplane.execution.RunEventCodec.DecodedEvent;
import com.dvarahq.agentworks.runplane.admission.RunAdmissionPolicy;
import com.dvarahq.agentworks.runplane.admission.RunAdmissionPolicy.OverloadOutcome;
import com.dvarahq.agentworks.runplane.admission.RunAdmissionPolicy.PlacementCapabilities;
import com.dvarahq.agentworks.runplane.admission.RunAdmissionService;
import com.dvarahq.agentworks.runplane.stream.RunEventStreams;
import com.dvarahq.agentworks.storage.lifecycle.LifecycleRetry;
import com.dvarahq.agentworks.storage.run.JdbcDurableRunStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Run event pages, run listing, and stream delivery against PostgreSQL authority (issue #35): pages stay gap-free
 * and anchored while workers append concurrently, a stream ends exactly once at a terminal event that raced
 * another terminal command, the run listing is a stable keyset, the Run Plane role can perform every read, and
 * stream delivery never holds a run, never reads ahead of a slow client, and polls once per workspace.
 */
class RunEventStreamPostgresTest {
    private final RunScenario scenario = new RunScenario();
    private final DurableRunEngine engine = scenario.engine;
    private final List<AutoCloseable> closeables = new ArrayList<>();

    @AfterEach
    void close() throws Exception {
        for (AutoCloseable closeable : closeables) closeable.close();
    }

    @Test
    void pagesReadWhileAWorkerAppendsAreGapFreeAnchoredAndNeverPastTheSummary() throws Exception {
        var claimed = scenario.started("worker", RunScenario.LEASE);
        UUID run = claimed.run().id();
        Thread writer = Thread.ofPlatform().start(() -> {
            RunLease lease = claimed.lease();
            for (int batch = 0; batch < 40; batch++) {
                lease = engine.append(lease, List.of(chunk(batch), chunk(batch))).lease();
            }
            engine.complete(lease, Map.of("done", true));
        });

        List<Long> read = new ArrayList<>();
        long after = 0;
        String anchor = null;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            EventPage page = engine.eventPage(scenario.workspace, run, after, 5, anchor);
            assertThat(page.lastSequence(after)).isLessThanOrEqualTo(page.run().summary().eventSequence());
            for (DecodedEvent event : page.events()) read.add(event.raw().sequence());
            if (!page.events().isEmpty()) anchor = page.events().getLast().raw().eventDigest();
            after = page.lastSequence(after);
            if (!page.more() && page.run().lifecycle().terminal() && after == page.run().summary().eventSequence()) break;
        }
        writer.join(Duration.ofSeconds(30));

        long last = engine.run(scenario.workspace, run).summary().eventSequence();
        assertThat(read).containsExactlyElementsOf(LongStream.rangeClosed(1, last).boxed().toList());
    }

    @Test
    void aStreamEndsExactlyOnceWhenCancellationRacesCompletion() throws Exception {
        for (int attempt = 0; attempt < 5; attempt++) {
            var claimed = scenario.started("worker-" + attempt, RunScenario.LEASE);
            UUID run = claimed.run().id();
            RecordingSink sink = new RecordingSink();
            RunEventStreams streams = streams(engine, RunEventStreams.Settings.DEFAULTS);
            streams.open(scenario.workspace, run, 0, UUID.randomUUID(), () -> true, sink);
            CountDownLatch start = new CountDownLatch(1);
            Thread complete = Thread.ofPlatform().start(() -> {
                await(start);
                try {
                    engine.complete(claimed.lease(), Map.of());
                } catch (RunEngineException lost) {
                    // The cancellation won.
                }
            });
            Thread cancel = Thread.ofPlatform().start(() -> {
                await(start);
                try {
                    engine.cancel(scenario.workspace, run, "system:test", "race");
                } catch (RunEngineException lost) {
                    // The completion won.
                }
            });
            start.countDown();
            complete.join();
            cancel.join();

            assertThat(sink.completed.await(10, TimeUnit.SECONDS)).isTrue();
            List<String> names = sink.names();
            assertThat(names.getLast()).isEqualTo(RunEventStreams.STREAM_END);
            assertThat(names.stream().filter(name -> Set.of("RUN_SUCCEEDED", "RUN_CANCELLED").contains(name)))
                    .hasSize(1);
            assertThat(sink.ids()).containsExactlyElementsOf(LongStream.rangeClosed(1,
                    engine.run(scenario.workspace, run).summary().eventSequence()).mapToObj(sequence -> run + ":" + sequence)
                    .toList());
        }
    }

    @Test
    void racingAdmissionsWithOneKeyCreateOneRunAndReplayItToEveryOtherRequest() throws Exception {
        List<Object> outcomes = RunScenario.race(12, index -> scenario.fixture.admissions.admitRoot(scenario.workspace,
                scenario.promotion.deploymentId(), "one-run", Map.of("q", "same"),
                com.dvarahq.agentworks.runplane.admission.RunAdmissionService.Options.SUBMIT));

        Set<UUID> runs = new java.util.HashSet<>();
        long created = 0;
        for (Object outcome : outcomes) {
            var admitted = (com.dvarahq.agentworks.runplane.admission.RunAdmissionService.AdmittedRun) outcome;
            runs.add(admitted.run().id());
            if (!admitted.replayed()) created++;
        }
        assertThat(created).isOne();
        assertThat(runs).hasSize(1);
        assertThat(scenario.count("select count(*) from agent_run where workspace_id = ?", scenario.workspace.value()))
                .isOne();
        assertThat(engine.eventPage(scenario.workspace, runs.iterator().next(), 0, 10, null).events())
                .extracting(event -> event.type().name()).containsExactly("RUN_CREATED", "RUN_QUEUED");
    }

    @Test
    void theRunListingIsAStableNewestFirstKeysetWithFiltersAndWorkspaceConfinement() {
        List<UUID> submitted = new ArrayList<>();
        for (int index = 0; index < 7; index++) submitted.add(scenario.submit(Map.of("index", index)).id());
        engine.cancel(scenario.workspace, submitted.get(2), "system:test", null);
        engine.cancel(scenario.workspace, submitted.get(5), "system:test", null);
        RunScenario other = new RunScenario(scenario.fixture);
        other.submit(Map.of());

        List<UUID> paged = new ArrayList<>();
        RunRecord last = null;
        do {
            List<RunRecord> page = engine.runs(scenario.workspace, new RunQuery(null, null, Set.of(),
                    last == null ? null : last.pins().createdAt(), last == null ? null : last.id(), 3));
            page.forEach(run -> paged.add(run.id()));
            last = page.size() < 3 ? null : page.getLast();
        } while (last != null);
        List<UUID> newestFirst = new ArrayList<>(submitted);
        Collections.reverse(newestFirst);
        assertThat(paged).containsExactlyElementsOf(newestFirst);

        assertThat(engine.runs(scenario.workspace, new RunQuery(null, null, Set.of(RunLifecycle.CANCELLED), null, null, 10)))
                .extracting(RunRecord::id).containsExactly(submitted.get(5), submitted.get(2));
        assertThat(engine.runs(scenario.workspace, new RunQuery(scenario.promotion.deploymentId(), null,
                Set.of(RunLifecycle.QUEUED), null, null, 10))).hasSize(5);
        assertThat(engine.runs(scenario.workspace, new RunQuery(null, UUID.randomUUID(), Set.of(), null, null, 10))).isEmpty();
        assertThat(engine.runs(scenario.workspace, RunQuery.first(50))).extracting(RunRecord::id)
                .doesNotContainAnyElementsOf(engine.runs(other.workspace, RunQuery.first(50)).stream().map(RunRecord::id)
                        .toList());
    }

    @Test
    void theRunPlaneRolePerformsEveryStreamAndListingRead() throws Exception {
        var claimed = scenario.started("worker", RunScenario.LEASE);
        engine.append(claimed.lease(), List.of(chunk(1)));
        UUID run = claimed.run().id();
        try (Connection connection = scenario.fixture.dataSource.getConnection()) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("set role agentworks_run_plane");
            }
            var dataSource = new SingleConnectionDataSource(connection, true);
            var store = new JdbcDurableRunStore(new JdbcTemplate(dataSource),
                    new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
            var runPlane = new DurableRunEngine(store, RunEventCodec.standard(), new ReleaseManifestReader(),
                    LifecycleRetry.standard());

            assertThat(runPlane.eventPage(scenario.workspace, run, 0, 10, null).events()).hasSize(4);
            assertThat(runPlane.eventPage(scenario.workspace, run, 2, 10, null).events()).hasSize(2);
            assertThat(runPlane.eventSequences(scenario.workspace, List.of(run))).containsEntry(run, 4L);
            assertThat(runPlane.runs(scenario.workspace, RunQuery.first(10))).extracting(RunRecord::id).contains(run);
            try (Statement statement = connection.createStatement()) {
                statement.execute("reset role");
            }
        }
    }

    @Test
    void aSlowClientNeverDelaysTheRunAndIsNeverReadAheadOfWhatItAccepted() throws Exception {
        var claimed = scenario.started("worker", RunScenario.LEASE);
        UUID run = claimed.run().id();
        Counting counting = new Counting(scenario.fixture.runStore);
        DurableRunEngine counted = new DurableRunEngine(counting, RunEventCodec.standard(), new ReleaseManifestReader(),
                LifecycleRetry.standard());
        RunEventStreams streams = streams(counted, new RunEventStreams.Settings(8, 8, 8, 2, 4, Duration.ofMillis(20),
                Duration.ofSeconds(15), Duration.ofSeconds(30), Duration.ofMinutes(1), Duration.ofSeconds(3)));
        BlockingSink slow = new BlockingSink();
        streams.open(scenario.workspace, run, 0, UUID.randomUUID(), () -> true, slow);
        assertThat(slow.blocked.await(10, TimeUnit.SECONDS)).isTrue();

        // While the client accepts nothing, the worker keeps writing at full speed.
        long started = System.nanoTime();
        RunLease lease = claimed.lease();
        for (int batch = 0; batch < 50; batch++) lease = engine.append(lease, List.of(chunk(batch), chunk(batch))).lease();
        engine.complete(lease, Map.of());
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(20));
        // The blocked stream holds no transaction (so no lock or pooled connection) while it waits on its client,
        // and control commands are not delayed either.
        assertThat(scenario.count("select count(*) from pg_stat_activity where datname = current_database() "
                + "and state like 'idle in transaction%' and state_change < now() - interval '500 milliseconds'")).isZero();
        Thread.sleep(300);
        // Only the page being written was read: the page size plus its look-ahead event.
        assertThat(counting.eventsRead.get()).isLessThanOrEqualTo(4 + 1);

        slow.release.countDown();
        assertThat(slow.completed.await(20, TimeUnit.SECONDS)).isTrue();
        long last = engine.run(scenario.workspace, run).summary().eventSequence();
        assertThat(slow.ids()).containsExactlyElementsOf(LongStream.rangeClosed(1, last)
                .mapToObj(sequence -> run + ":" + sequence).toList());
        // Each page after the first also reads its anchor: bounded by pages x (size + anchor + look-ahead).
        assertThat(counting.eventsRead.get()).isLessThanOrEqualTo((last / 4 + 1) * 6);
    }

    @Test
    void manyIdleStreamsPollOncePerWorkspaceAndReadNothing() throws Exception {
        Counting counting = new Counting(scenario.fixture.runStore);
        DurableRunEngine counted = new DurableRunEngine(counting, RunEventCodec.standard(), new ReleaseManifestReader(),
                LifecycleRetry.standard());
        // Heartbeats and re-checks fall due several times while the streams are observed; neither reads history.
        RunEventStreams streams = streams(counted, new RunEventStreams.Settings(300, 300, 300, 2, 16, Duration.ofMillis(50),
                Duration.ofMillis(200), Duration.ofMillis(300), Duration.ofMinutes(1), Duration.ofSeconds(3)));
        List<RecordingSink> sinks = new ArrayList<>();
        List<RunEventStreams.Subscription> opened = new ArrayList<>();
        RunAdmissionService highCapacityAdmissions = new RunAdmissionService(scenario.fixture.tenants,
                scenario.fixture.lifecycle, scenario.fixture.runRecords, scenario.fixture.clock,
                LifecycleRetry.standard(), null, scenario.fixture.runStore, scenario.fixture.engine,
                RunLimitsPolicy.fixed(RunLimits.UNBOUNDED), new RunAdmissionPolicy("stream-load-test", 1_000, 1_000,
                        1_000, 1_000, 1_000, Duration.ofMinutes(1), 1_000,
                        Map.of("SHARED", 1_000L, "ISOLATED", 1_000L, "DEDICATED", 1_000L), null, null,
                        new PlacementCapabilities(Set.of(), Set.of(), Set.of(), Set.of()), OverloadOutcome.REJECTED));
        int platformBefore = Thread.getAllStackTraces().size();
        for (int index = 0; index < 200; index++) {
            var admitted = highCapacityAdmissions.admitRoot(scenario.workspace, scenario.promotion.deploymentId(), null,
                    Map.of("index", index), RunAdmissionService.Options.SUBMIT);
            RunRecord run = engine.run(scenario.workspace, admitted.run().id());
            RecordingSink sink = new RecordingSink();
            sinks.add(sink);
            opened.add(streams.open(scenario.workspace, run.id(), run.summary().eventSequence(), UUID.randomUUID(),
                    () -> true, sink));
        }
        // The window starts only once every stream has read its first page and is waiting: until then a stream's first
        // read may still be pending, however long the machine takes to schedule 200 streams.
        long settled = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!opened.stream().allMatch(stream -> stream.pageReads() >= 1 && stream.waiting())
                && System.nanoTime() < settled) {
            Thread.sleep(20);
        }
        assertThat(opened).allSatisfy(stream -> assertThat(stream.pageReads()).isOne());
        long pollsBefore = counting.sequencePolls.get();
        long readsBefore = counting.runReads.get();
        Thread.sleep(1000);

        assertThat(streams.open()).isEqualTo(200);
        // Each stream thread is virtual: platform threads (the ticker, virtual-thread carriers, and JVM helpers) do not
        // grow with the number of streams; 200 streams add far fewer than one platform thread per four streams.
        assertThat(Thread.getAllStackTraces().size() - platformBefore).isLessThan(50);
        // About 20 ticks in a second, each one query for the whole workspace, and no page reads while idle.
        assertThat(counting.sequencePolls.get() - pollsBefore).isBetween(5L, 40L);
        assertThat(counting.runReads.get() - readsBefore).isZero();
        assertThat(opened).allSatisfy(stream -> assertThat(stream.pageReads()).isOne());
        assertThat(sinks.getFirst().comments.get()).isPositive();
        streams.close();
        for (RecordingSink sink : sinks) assertThat(sink.completed.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(sinks.getFirst().names()).containsExactly(RunEventStreams.RECONNECT);
        assertThat(streams.open()).isZero();
    }

    @Test
    void aReCheckThatFailsEndsTheStreamAsUnavailableNotAsRevoked() throws Exception {
        RunRecord run = scenario.submit(Map.of());
        RunEventStreams streams = streams(engine, new RunEventStreams.Settings(8, 8, 8, 2, 4, Duration.ofMillis(20),
                Duration.ofSeconds(15), Duration.ofMillis(100), Duration.ofMinutes(1), Duration.ofSeconds(3)));
        RecordingSink failing = new RecordingSink();
        RecordingSink revoked = new RecordingSink();
        streams.open(scenario.workspace, run.id(), 0, UUID.randomUUID(), () -> {
            throw new org.springframework.dao.DataAccessResourceFailureException("database unavailable");
        }, failing);
        streams.open(scenario.workspace, run.id(), 0, UUID.randomUUID(), () -> false, revoked);

        assertThat(failing.completed.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(revoked.completed.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(failing.names().getLast()).isEqualTo(RunEventStreams.STREAM_ERROR);
        assertThat(((RunEventStreams.StreamError) failing.data.getLast()).code()).isEqualTo("STREAM_UNAVAILABLE");
        assertThat(((RunEventStreams.StreamError) revoked.data.getLast()).code()).isEqualTo("AUTHORIZATION_REVOKED");
        assertThat(streams.open()).isZero();
    }

    @Test
    void aDeferredStreamWritesNothingUntilItsResponseIsReady() throws Exception {
        RunRecord run = scenario.submit(Map.of());
        Counting counting = new Counting(scenario.fixture.runStore);
        DurableRunEngine counted = new DurableRunEngine(counting, RunEventCodec.standard(), new ReleaseManifestReader(),
                LifecycleRetry.standard());
        RunEventStreams streams = streams(counted, RunEventStreams.Settings.DEFAULTS);
        RecordingSink sink = new RecordingSink();
        var subscription = streams.openDeferred(scenario.workspace, run.id(), 0, null, false, UUID.randomUUID(),
                () -> true,
                sink);
        Thread.sleep(300);
        assertThat(sink.written).isEmpty();
        assertThat(counting.runReads.get()).isZero();

        subscription.ready();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (sink.written.size() < 2 && System.nanoTime() < deadline) Thread.sleep(10);
        assertThat(sink.ids()).containsExactly(run.id() + ":1", run.id() + ":2");

        var abandoned = streams.openDeferred(scenario.workspace, run.id(), 0, null, false, UUID.randomUUID(),
                () -> true,
                new RecordingSink());
        abandoned.cancel();
        abandoned.ready();
        deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (streams.open() > 1 && System.nanoTime() < deadline) Thread.sleep(10);
        assertThat(streams.open()).isOne();
    }

    @Test
    void aReconnectWithItsClientIdSupersedesOnlyThatClientsStreamAndWorkspacesAreCapped() throws Exception {
        RunRecord first = scenario.submit(Map.of());
        RunRecord second = scenario.submit(Map.of());
        RunEventStreams streams = streams(engine, new RunEventStreams.Settings(8, 3, 2, 2, 4, Duration.ofMillis(20),
                Duration.ofSeconds(15), Duration.ofSeconds(30), Duration.ofMinutes(1), Duration.ofSeconds(3)));
        UUID subject = UUID.randomUUID();
        RecordingSink tabA = new RecordingSink();
        RecordingSink tabB = new RecordingSink();
        var a = streams.open(scenario.workspace, first.id(), 2, "tab-a", false, subject, () -> true, tabA);
        var b = streams.open(scenario.workspace, first.id(), 2, "tab-b", false, subject, () -> true, tabB);

        // At its limit, tab B reconnecting to the run replaces tab B's own stream, and nothing else.
        var b2 = streams.open(scenario.workspace, first.id(), 2, "tab-b", true, subject, () -> true,
                new RecordingSink());
        assertThat(tabB.completed.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(b.reason()).isEqualTo(RunEventStreams.EndReason.SUPERSEDED);
        assertThat(((RunEventStreams.StreamError) tabB.data.getLast()).code()).isEqualTo("STREAM_SUPERSEDED");
        assertThat(a.ended()).isFalse();
        assertThat(b2.ended()).isFalse();
        assertThat(streams.open(subject)).isEqualTo(2);
        // A stream of another run is not a reconnect, even with a known client id: the subject is refused.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> streams.open(scenario.workspace, second.id(), 2,
                "tab-b", true, subject, () -> true, new RecordingSink()))
                .isInstanceOf(RunEventStreams.StreamLimitExceededException.class);
        // The workspace holds at most three streams, whoever opens them.
        streams.open(scenario.workspace, second.id(), 2, UUID.randomUUID(), () -> true, new RecordingSink());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> streams.open(scenario.workspace, second.id(), 2,
                UUID.randomUUID(), () -> true, new RecordingSink()))
                .isInstanceOf(RunEventStreams.StreamLimitExceededException.class);
        assertThat(streams.open(scenario.workspace)).isEqualTo(3);
        RunScenario other = new RunScenario(scenario.fixture);
        RunRecord elsewhere = other.submit(Map.of());
        streams.open(other.workspace, elsewhere.id(), 2, UUID.randomUUID(), () -> true, new RecordingSink());
        assertThat(streams.open(other.workspace)).isOne();
        assertThat(tabA.completed.getCount()).isOne();
    }

    /**
     * A client whose connection dropped (half-open, so the server keeps writing into it) reconnects with its client
     * stream id from behind what the server wrote, and replaces exactly its own dead stream (issue #186).
     */
    @Test
    void aClientWhoseConnectionDroppedReclaimsItsOwnSlotFromAnyPosition() throws Exception {
        var claimed = scenario.started("worker", RunScenario.LEASE);
        UUID run = claimed.run().id();
        engine.append(claimed.lease(), List.of(chunk(1), chunk(2)));
        long head = engine.run(scenario.workspace, run).summary().eventSequence();
        RunEventStreams streams = streams(engine, new RunEventStreams.Settings(8, 8, 2, 2, 4, Duration.ofMillis(20),
                Duration.ofSeconds(15), Duration.ofSeconds(30), Duration.ofMinutes(1), Duration.ofSeconds(3)));
        UUID subject = UUID.randomUUID();
        RecordingSink liveTab = new RecordingSink();
        var live = streams.open(scenario.workspace, run, 0, "live-tab", false, subject, () -> true, liveTab);
        RecordingSink dropped = new RecordingSink();
        var dead = streams.open(scenario.workspace, run, 0, "dropped-tab", false, subject, () -> true, dropped);
        awaitDelivered(live, head);
        awaitDelivered(dead, head);

        // The dropped tab received only event 1; it resumes from there with its client id.
        RecordingSink resumed = new RecordingSink();
        var replacement = streams.open(scenario.workspace, run, 1, "dropped-tab", true, subject, () -> true, resumed);
        assertThat(dropped.completed.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(dead.reason()).isEqualTo(RunEventStreams.EndReason.SUPERSEDED);
        awaitDelivered(replacement, head);
        assertThat(resumed.ids()).containsExactlyElementsOf(LongStream.rangeClosed(2, head)
                .mapToObj(sequence -> run + ":" + sequence).toList());
        assertThat(live.ended()).isFalse();
        assertThat(liveTab.completed.getCount()).isOne();
        assertThat(streams.open(subject)).isEqualTo(2);
    }

    /**
     * Without its own client id a stream never replaces anything (issue #186): not a new tab, not a stream without
     * an id, not another tab's id, however far behind or ahead the other streams are, so no replacement ever
     * cascades through the live tabs.
     */
    @Test
    void aStreamWithoutTheSameClientIdNeverReplacesAnything() throws Exception {
        var claimed = scenario.started("worker", RunScenario.LEASE);
        UUID run = claimed.run().id();
        long head = engine.run(scenario.workspace, run).summary().eventSequence();
        RunEventStreams streams = streams(engine, new RunEventStreams.Settings(8, 8, 2, 2, 4, Duration.ofMillis(20),
                Duration.ofSeconds(15), Duration.ofSeconds(30), Duration.ofMinutes(1), Duration.ofSeconds(3)));
        UUID subject = UUID.randomUUID();
        // One tab is caught up; the other lags, stalled on its first write.
        RecordingSink caughtUp = new RecordingSink();
        var ahead = streams.open(scenario.workspace, run, 0, "tab-a", false, subject, () -> true, caughtUp);
        BlockingSink lagging = new BlockingSink();
        var behind = streams.open(scenario.workspace, run, 0, "tab-b", false, subject, () -> true, lagging);
        awaitDelivered(ahead, head);
        assertThat(lagging.blocked.await(10, TimeUnit.SECONDS)).isTrue();

        for (long position : new long[]{0, 1, head}) {
            for (String client : new String[]{null, "tab-c", "tab-a"}) {
                // A reconnect of an unknown client, or a first connection with any id, including a known one.
                boolean reconnect = !"tab-a".equals(client);
                org.assertj.core.api.Assertions.assertThatThrownBy(() -> streams.open(scenario.workspace, run,
                        position, client, reconnect, subject, () -> true, new RecordingSink()))
                        .isInstanceOf(RunEventStreams.StreamLimitExceededException.class);
            }
        }
        Thread.sleep(100);
        assertThat(ahead.ended()).isFalse();
        assertThat(behind.ended()).isFalse();
        assertThat(caughtUp.completed.getCount()).isOne();
        assertThat(streams.open(subject)).isEqualTo(2);
        lagging.release.countDown();
    }

    @Test
    void aRefusedOpenEndsOnlyItsOwnClientsDeadStreamsAndRacingReconnectsOfOneClientKeepOneStream() throws Exception {
        RunRecord run = scenario.submit(Map.of());
        RunRecord other = scenario.submit(Map.of());
        // A workspace of three streams, a subject of two.
        RunEventStreams streams = streams(engine, new RunEventStreams.Settings(50, 3, 2, 2, 4, Duration.ofMillis(20),
                Duration.ofSeconds(15), Duration.ofSeconds(30), Duration.ofMinutes(1), Duration.ofSeconds(3)));
        UUID subject = UUID.randomUUID();
        RecordingSink first = new RecordingSink();
        RecordingSink second = new RecordingSink();
        var a = streams.open(scenario.workspace, run.id(), 2, "tab-a", false, subject, () -> true, first);
        var b = streams.open(scenario.workspace, run.id(), 2, "tab-b", false, subject, () -> true, second);
        var neighbour = streams.open(scenario.workspace, other.id(), 2, UUID.randomUUID(), () -> true,
                new RecordingSink());

        // The subject and the workspace are both at their limits. A first connection with tab B's id, and a
        // reconnect of an unknown client, are refused and end nothing.
        for (boolean reconnect : new boolean[]{false, true}) {
            String client = reconnect ? "tab-c" : "tab-b";
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> streams.open(scenario.workspace, run.id(), 2,
                    client, reconnect, subject, () -> true, new RecordingSink()))
                    .isInstanceOf(RunEventStreams.StreamLimitExceededException.class);
        }
        Thread.sleep(100);
        assertThat(a.ended()).isFalse();
        assertThat(b.ended()).isFalse();
        assertThat(first.completed.getCount()).isOne();
        assertThat(second.completed.getCount()).isOne();
        // Tab B's reconnect is refused by the full workspace too, but its earlier stream is dead (the client
        // reconnected), so it is superseded and frees its slot for the retry; tab A is untouched (#190).
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> streams.open(scenario.workspace, run.id(), 2,
                "tab-b", true, subject, () -> true, new RecordingSink()))
                .isInstanceOf(RunEventStreams.StreamLimitExceededException.class);
        assertThat(second.completed.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(b.reason()).isEqualTo(RunEventStreams.EndReason.SUPERSEDED);
        assertThat(a.ended()).isFalse();

        // Racing reconnects of tab B each supersede tab B's current stream: exactly one of them stays open, tab A is
        // untouched, and the subject stays within its limit.
        neighbour.cancel();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (streams.open(scenario.workspace) > 1 && System.nanoTime() < deadline) Thread.sleep(10);
        List<Object> outcomes = RunScenario.race(6, index -> {
            try {
                return streams.open(scenario.workspace, run.id(), 2, "tab-b", true, subject, () -> true,
                        new RecordingSink());
            } catch (RunEventStreams.StreamLimitExceededException refused) {
                return refused;
            }
        });
        List<RunEventStreams.Subscription> admitted = outcomes.stream()
                .filter(RunEventStreams.Subscription.class::isInstance).map(RunEventStreams.Subscription.class::cast)
                .toList();
        assertThat(admitted).isNotEmpty();
        // A client's newest request supersedes all its older streams, even when that request is itself refused
        // (superseded streams hold their workspace slots until they end), so at most one survives the race; the
        // client's next reconnect, once the superseded streams ended, leaves exactly one.
        assertThat(admitted.stream().filter(subscription -> !subscription.ended()).count()).isLessThanOrEqualTo(1);
        deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (streams.open(scenario.workspace) > 2 && System.nanoTime() < deadline) Thread.sleep(10);
        var settled = streams.open(scenario.workspace, run.id(), 2, "tab-b", true, subject, () -> true,
                new RecordingSink());
        assertThat(admitted).allSatisfy(subscription -> assertThat(subscription.ended()).isTrue());
        assertThat(settled.ended()).isFalse();
        assertThat(a.ended()).isFalse();
        assertThat(streams.open(subject)).isEqualTo(2);
    }

    private static void awaitDelivered(RunEventStreams.Subscription stream, long sequence) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (stream.delivered() < sequence && System.nanoTime() < deadline) Thread.sleep(10);
        assertThat(stream.delivered()).isEqualTo(sequence);
    }

    @Test
    void stoppingEndsEveryStreamWithAReconnectBeforeTheServerDrains() throws Exception {
        RunRecord run = scenario.submit(Map.of());
        RunEventStreams streams = streams(engine, RunEventStreams.Settings.DEFAULTS);
        RecordingSink sink = new RecordingSink();
        streams.open(scenario.workspace, run.id(), 2, UUID.randomUUID(), () -> true, sink);

        assertThat(streams.isRunning()).isTrue();
        assertThat(streams.getPhase()).isGreaterThan(org.springframework.context.SmartLifecycle.DEFAULT_PHASE - 2048);
        long started = System.nanoTime();
        streams.stop();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
        assertThat(streams.open()).isZero();
        assertThat(sink.names()).containsExactly(RunEventStreams.RECONNECT);
        assertThat(((RunEventStreams.Reconnect) sink.data.getLast()).reason()).isEqualTo("SHUTDOWN");
        assertThat(streams.isRunning()).isFalse();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> streams.open(scenario.workspace, run.id(), 2,
                UUID.randomUUID(), () -> true, new RecordingSink()))
                .isInstanceOf(RunEventStreams.StreamsStoppedException.class);

        // A restarted lifecycle (for example a paused test context that resumes) serves streams again.
        streams.start();
        assertThat(streams.isRunning()).isTrue();
        RecordingSink resumed = new RecordingSink();
        streams.open(scenario.workspace, run.id(), 0, UUID.randomUUID(), () -> true, resumed);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (resumed.written.size() < 2 && System.nanoTime() < deadline) Thread.sleep(10);
        assertThat(resumed.ids()).containsExactly(run.id() + ":1", run.id() + ":2");
    }

    private RunEventStreams streams(DurableRunEngine owner, RunEventStreams.Settings settings) {
        RunEventStreams streams = new RunEventStreams(owner, settings, event -> Map.of("type", event.type().name()));
        closeables.add(streams);
        return streams;
    }

    private static EventDraft chunk(int index) {
        return EventDraft.of(com.dvarahq.agentworks.domain.run.RunEventType.MODEL_STREAM_CHUNK, Map.of("index", index));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** Records what a stream writes. */
    static class RecordingSink implements RunEventStreams.Sink {
        final List<String[]> written = new CopyOnWriteArrayList<>();
        final List<Object> data = new CopyOnWriteArrayList<>();
        final java.util.concurrent.atomic.AtomicInteger comments = new java.util.concurrent.atomic.AtomicInteger();
        final CountDownLatch completed = new CountDownLatch(1);

        @Override
        public void retry(Duration retry) {}

        @Override
        public void event(String id, String name, Object data) throws IOException {
            written.add(new String[]{id, name});
            this.data.add(data);
        }

        @Override
        public void comment(String text) {
            comments.incrementAndGet();
        }

        @Override
        public void complete() {
            completed.countDown();
        }

        List<String> names() {
            return written.stream().map(entry -> entry[1]).toList();
        }

        List<String> ids() {
            return written.stream().map(entry -> entry[0]).filter(java.util.Objects::nonNull).toList();
        }
    }

    /** A client that accepts nothing until released, as a stalled socket applies back pressure. */
    static final class BlockingSink extends RecordingSink {
        final CountDownLatch blocked = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        @Override
        public void event(String id, String name, Object data) throws IOException {
            blocked.countDown();
            try {
                release.await();
            } catch (InterruptedException interrupted) {
                throw new IOException("interrupted", interrupted);
            }
            super.event(id, name, data);
        }
    }

    /** Counts page reads, events read, and sequence polls through the persistence port. */
    private static final class Counting implements DurableRunStore {
        private final DurableRunStore delegate;
        final AtomicLong eventsRead = new AtomicLong();
        final AtomicLong pageReads = new AtomicLong();
        final AtomicLong sequencePolls = new AtomicLong();
        /** Every read of a run row, the start of every page read. */
        final AtomicLong runReads = new AtomicLong();

        Counting(DurableRunStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public <T> T inTransaction(WorkspaceId workspace, Function<RunTransaction, T> work) {
            return delegate.inTransaction(workspace, tx -> work.apply(counted(tx)));
        }

        @Override
        public <T> Optional<T> claimNext(ClaimScope scope, BiFunction<RunRecord, RunTransaction, T> work) {
            return delegate.claimNext(scope, (run, tx) -> work.apply(run, counted(tx)));
        }

        private RunTransaction counted(RunTransaction tx) {
            return (RunTransaction) Proxy.newProxyInstance(RunTransaction.class.getClassLoader(),
                    new Class<?>[]{RunTransaction.class}, (proxy, method, arguments) -> {
                        Object result;
                        try {
                            result = method.invoke(tx, arguments);
                        } catch (InvocationTargetException failure) {
                            throw failure.getCause();
                        }
                        if (method.getName().equals("eventWindow")) {
                            pageReads.incrementAndGet();
                            eventsRead.addAndGet(((List<?>) result).size());
                        }
                        if (method.getName().equals("eventSequences")) sequencePolls.incrementAndGet();
                        if (method.getName().equals("findRun")) runReads.incrementAndGet();
                        return result;
                    });
        }
    }
}
