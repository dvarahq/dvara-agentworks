package com.dvarahq.agentworks.runplane.stream;

import com.dvarahq.agentworks.domain.run.ClaimScope;
import com.dvarahq.agentworks.domain.run.DurableRunStore;
import com.dvarahq.agentworks.domain.run.RunRecord;
import com.dvarahq.agentworks.domain.run.RunTransaction;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import com.dvarahq.agentworks.runplane.execution.DurableRunEngine;
import com.dvarahq.agentworks.runplane.execution.ReleaseManifestReader;
import com.dvarahq.agentworks.runplane.execution.RunEventCodec;
import com.dvarahq.agentworks.storage.lifecycle.LifecycleRetry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Stream admission, supersession, and bounded reads without a database (issues #186 and #190). Streams that are never
 * made ready read nothing, a superseded stream writes {@code STREAM_SUPERSEDED} before it would read, and the ticker
 * never polls within a test.
 */
class RunEventStreamsAdmissionTest {
    private static final WorkspaceId WORKSPACE = new WorkspaceId(UUID.randomUUID());
    private final List<RunEventStreams> opened = new ArrayList<>();

    @AfterEach
    void close() {
        opened.forEach(RunEventStreams::close);
    }

    /**
     * The superseded stream can end and release its own subject slot after admission chose it and before it is
     * superseded (its thread runs outside the admission lock). The subject must then still count exactly its open
     * streams and never gain one beyond its limit.
     */
    @Test
    void aSupersededStreamThatEndsJustBeforeItsSupersessionLeavesTheSubjectCountExact() {
        UUID subject = UUID.randomUUID();
        UUID run = UUID.randomUUID();
        List<RunEventStreams> self = new ArrayList<>();
        RunEventStreams streams = streams(settings(8, 1, 1), replaced -> {
            // The superseded stream's client leaves at exactly this moment, and its thread releases its slots.
            replaced.cancel();
            replaced.ready();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (self.getFirst().open() > 1 && System.nanoTime() < deadline) Thread.onSpinWait();
        });
        self.add(streams);
        RunEventStreams.Subscription stale = open(streams, run, "tab", false, subject, new Recording());

        RunEventStreams.Subscription replacement = open(streams, run, "tab", true, subject, new Recording());

        assertThat(stale.reason()).isEqualTo(RunEventStreams.EndReason.CLIENT_GONE);
        assertThat(replacement.ended()).isFalse();
        assertThat(streams.open()).isOne();
        assertThat(streams.open(subject)).isOne();
        // So the subject, at its limit of one, is refused a stream of another run.
        assertThatThrownBy(() -> open(streams, UUID.randomUUID(), "tab", true, subject, new Recording()))
                .isInstanceOf(RunEventStreams.StreamLimitExceededException.class);
        assertThat(streams.open(subject)).isOne();
    }

    /** #190: a client's reconnect supersedes its earlier stream below the limit too, so dead streams never pile up. */
    @Test
    void aReconnectSupersedesTheClientsEarlierStreamBelowTheLimitToo() throws Exception {
        RunEventStreams streams = streams(settings(8, 4, 1), replaced -> {});
        UUID subject = UUID.randomUUID();
        UUID run = UUID.randomUUID();
        Recording dead = new Recording();
        RunEventStreams.Subscription first = open(streams, run, "tab", false, subject, dead);

        RunEventStreams.Subscription second = open(streams, run, "tab", true, subject, new Recording());
        RunEventStreams.Subscription third = open(streams, run, "tab", true, subject, new Recording());

        assertThat(dead.completed.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(dead.codes()).containsExactly("STREAM_SUPERSEDED");
        assertThat(first.reason()).isEqualTo(RunEventStreams.EndReason.SUPERSEDED);
        assertThat(second.ended()).isTrue();
        assertThat(third.ended()).isFalse();
        assertThat(streams.open(subject)).isOne();
    }

    /**
     * #190: a first connection with a known client id (a duplicated tab that copied its storage, or a page mounting
     * its stream again) never ends that client's live stream, below or at the limit.
     */
    @Test
    void aFirstConnectionWithAKnownClientIdNeverEndsALiveStream() {
        RunEventStreams streams = streams(settings(8, 2, 1), replaced -> {});
        UUID subject = UUID.randomUUID();
        UUID run = UUID.randomUUID();
        RunEventStreams.Subscription live = open(streams, run, "tab", false, subject, new Recording());

        RunEventStreams.Subscription duplicate = open(streams, run, "tab", false, subject, new Recording());
        assertThatThrownBy(() -> open(streams, run, "tab", false, subject, new Recording()))
                .isInstanceOf(RunEventStreams.StreamLimitExceededException.class);

        assertThat(live.ended()).isFalse();
        assertThat(duplicate.ended()).isFalse();
        assertThat(streams.open(subject)).isEqualTo(2);
    }

    /** A replaced stream whose response was not ready yet still tells its client {@code STREAM_SUPERSEDED}. */
    @Test
    void aStreamSupersededBeforeItWasReadyIsSupersededNotGone() throws Exception {
        RunEventStreams streams = streams(settings(8, 1, 1), replaced -> {});
        UUID subject = UUID.randomUUID();
        UUID run = UUID.randomUUID();
        Recording first = new Recording();
        RunEventStreams.Subscription notReady = open(streams, run, "tab", false, subject, first);

        open(streams, run, "tab", true, subject, new Recording());

        assertThat(first.completed.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(notReady.reason()).isEqualTo(RunEventStreams.EndReason.SUPERSEDED);
        assertThat(first.codes()).containsExactly("STREAM_SUPERSEDED");
    }

    /** Only a reconnect of the same client of the same run supersedes a stream; nothing else ever does. */
    @Test
    void onlyAReconnectOfTheSameClientOfTheSameRunSupersedesAStream() {
        RunEventStreams streams = streams(settings(8, 1, 1), replaced -> {});
        UUID subject = UUID.randomUUID();
        UUID run = UUID.randomUUID();
        RunEventStreams.Subscription held = open(streams, run, "tab-a", false, subject, new Recording());

        for (String client : new String[]{null, "tab-b"}) {
            for (boolean reconnect : new boolean[]{false, true}) {
                assertThatThrownBy(() -> open(streams, run, client, reconnect, subject, new Recording()))
                        .isInstanceOf(RunEventStreams.StreamLimitExceededException.class);
            }
        }
        assertThatThrownBy(() -> open(streams, UUID.randomUUID(), "tab-a", true, subject, new Recording()))
                .isInstanceOf(RunEventStreams.StreamLimitExceededException.class);
        // Another subject's reconnect with the same client id is not this subject's.
        open(streams, run, "tab-a", true, UUID.randomUUID(), new Recording());
        assertThat(held.ended()).isFalse();
        assertThatThrownBy(() -> open(streams, run, "not_a_client", true, subject, new Recording()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** #190: a reconnect refused by the node limit still ends the client's dead stream, freeing a slot for its retry. */
    @Test
    void aReconnectRefusedByTheNodeLimitStillSupersedesTheClientsDeadStream() throws Exception {
        RunEventStreams streams = streams(settings(1, 4, 1), replaced -> {});
        UUID subject = UUID.randomUUID();
        UUID run = UUID.randomUUID();
        Recording dead = new Recording();
        RunEventStreams.Subscription stale = open(streams, run, "tab", false, subject, dead);

        assertThatThrownBy(() -> open(streams, run, "tab", true, subject, new Recording()))
                .isInstanceOf(RunEventStreams.StreamLimitExceededException.class);

        assertThat(dead.completed.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(stale.reason()).isEqualTo(RunEventStreams.EndReason.SUPERSEDED);
        assertThat(streams.open()).isZero();
        assertThat(open(streams, run, "tab", true, subject, new Recording()).ended()).isFalse();
    }

    /**
     * A stream whose client the container already reported gone still holds its slot until its thread releases it;
     * the client's reconnect supersedes it at once instead of being refused until then.
     */
    @Test
    void aReconnectSupersedesItsOwnStreamThatIsGoneButNotYetReleased() {
        RunEventStreams streams = streams(settings(8, 1, 1), replaced -> {});
        UUID subject = UUID.randomUUID();
        UUID run = UUID.randomUUID();
        // Never ready, so its thread waits and cannot release its slot within the test.
        RunEventStreams.Subscription gone = open(streams, run, "tab", false, subject, new Recording());
        gone.cancel();
        assertThat(streams.open(subject)).isOne();

        RunEventStreams.Subscription reconnected = open(streams, run, "tab", true, subject, new Recording());

        assertThat(reconnected.ended()).isFalse();
        assertThat(streams.open(subject)).isOne();
    }

    /** Racing reconnects of one client each supersede that client's current stream: exactly one stays open. */
    @Test
    void racingReconnectsOfOneClientLeaveExactlyOneStream() throws Exception {
        // Superseded streams keep their node and workspace slots until they end, so those limits leave room for all.
        RunEventStreams streams = streams(settings(64, 1, 1), replaced -> {});
        UUID subject = UUID.randomUUID();
        UUID run = UUID.randomUUID();
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        List<RunEventStreams.Subscription> all = new CopyOnWriteArrayList<>();
        all.add(open(streams, run, "tab", false, subject, new Recording()));
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> racers = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            racers.add(Thread.ofPlatform().start(() -> {
                try {
                    start.await();
                    all.add(open(streams, run, "tab", true, subject, new Recording()));
                } catch (Throwable failed) {
                    failures.add(failed);
                }
            }));
        }
        start.countDown();
        for (Thread racer : racers) racer.join();

        assertThat(failures).isEmpty();
        assertThat(all).hasSize(9);
        assertThat(all.stream().filter(stream -> !stream.ended())).hasSize(1);
        assertThat(streams.open(subject)).isOne();
    }

    /**
     * #190: a stream superseded while it waits for a read permit reads nothing more (its engine here fails every
     * read, which would end it with {@code STREAM_UNAVAILABLE}), and tells its client {@code STREAM_SUPERSEDED}.
     */
    @Test
    void aStreamSupersededWhileWaitingToReadReadsNothing() throws Exception {
        RunEventStreams streams = streams(settings(8, 4, 1), replaced -> {});
        UUID subject = UUID.randomUUID();
        UUID run = UUID.randomUUID();
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = Thread.ofPlatform().start(() -> streams.boundedRead(() -> {
            holding.countDown();
            try {
                release.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return null;
        }));
        assertThat(holding.await(10, TimeUnit.SECONDS)).isTrue();
        Recording waiting = new Recording();
        RunEventStreams.Subscription stream = open(streams, run, "tab", false, subject, waiting);
        stream.ready();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (stream.pageReads() < 1 && System.nanoTime() < deadline) Thread.onSpinWait();

        open(streams, run, "tab", true, subject, new Recording());
        release.countDown();
        holder.join();

        assertThat(waiting.completed.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(stream.reason()).isEqualTo(RunEventStreams.EndReason.SUPERSEDED);
        assertThat(waiting.codes()).containsExactly("STREAM_SUPERSEDED");
    }

    /**
     * #190: the client index survives a key emptied by a released stream: a stream admitted after its client's only
     * earlier stream released is still found, and superseded, by the client's next reconnect.
     */
    @Test
    void aClientsStreamAdmittedAfterItsEarlierStreamReleasedIsStillSuperseded() throws Exception {
        RunEventStreams streams = streams(settings(8, 4, 1), replaced -> {});
        UUID subject = UUID.randomUUID();
        UUID run = UUID.randomUUID();
        RunEventStreams.Subscription first = open(streams, run, "tab", false, subject, new Recording());
        first.cancel();
        first.ready();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (streams.open() > 0 && System.nanoTime() < deadline) Thread.onSpinWait();
        Recording secondSink = new Recording();
        RunEventStreams.Subscription second = open(streams, run, "tab", true, subject, secondSink);

        open(streams, run, "tab", true, subject, new Recording());

        assertThat(secondSink.completed.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(second.reason()).isEqualTo(RunEventStreams.EndReason.SUPERSEDED);
        assertThat(streams.open(subject)).isOne();
    }

    /**
     * A stream gives back its slots before its response completes, so whoever observes the end of a stream (a client
     * that reconnects at once at its limit, or a test) never finds its slot still counted.
     */
    @Test
    void aStreamReleasesItsSlotsBeforeItsResponseCompletes() throws Exception {
        RunEventStreams streams = streams(settings(8, 1, 1), replaced -> {});
        UUID subject = UUID.randomUUID();
        CompletableFuture<int[]> atCompletion = new CompletableFuture<>();
        RunEventStreams.Subscription stream = open(streams, UUID.randomUUID(), null, false, subject, new Recording() {
            @Override
            public void complete() {
                atCompletion.complete(new int[]{streams.open(), streams.open(subject), streams.open(WORKSPACE)});
            }
        });
        stream.cancel();
        stream.ready();

        assertThat(atCompletion.get(10, TimeUnit.SECONDS)).containsExactly(0, 0, 0);
    }

    /**
     * #190: a request's bounded read waits only briefly for a read slot and is refused as busy (not as a stream limit),
     * so a container thread is never held behind the streams' reads.
     */
    @Test
    void aBoundedReadWithNoFreeReadSlotIsRefusedAsBusyPromptly() throws Exception {
        RunEventStreams streams = streams(settings(8, 8, 1), replaced -> {});
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = Thread.ofPlatform().start(() -> streams.boundedRead(() -> {
            holding.countDown();
            try {
                release.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return null;
        }));
        assertThat(holding.await(10, TimeUnit.SECONDS)).isTrue();
        try {
            long started = System.nanoTime();
            assertThatThrownBy(() -> streams.boundedRead(() -> "read"))
                    .isInstanceOf(RunEventStreams.StreamsBusyException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2))
                    .isGreaterThanOrEqualTo(RunEventStreams.PROBE_WAIT.minusMillis(50));
        } finally {
            release.countDown();
            holder.join();
        }
        // Once the slot is free again, reads proceed.
        assertThat(streams.boundedRead(() -> "read")).isEqualTo("read");
    }

    /**
     * #190: an interrupted request thread (only a stopping container interrupts one) is answered as the node stopping,
     * and the interrupt is consumed so that the thread can still write that answer.
     */
    @Test
    void anInterruptedBoundedReadIsAnsweredAsStoppingAndConsumesTheInterrupt() {
        RunEventStreams streams = streams(settings(8, 8, 1), replaced -> {});
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> streams.boundedRead(() -> "read"))
                    .isInstanceOf(RunEventStreams.StreamsStoppedException.class);
            assertThat(Thread.currentThread().isInterrupted()).isFalse();
        } finally {
            Thread.interrupted();
        }
    }

    private static RunEventStreams.Subscription open(RunEventStreams streams, UUID run, String client,
            boolean reconnect, UUID subject, Recording sink) {
        return streams.openDeferred(WORKSPACE, run, 0, client, reconnect, subject, () -> true, sink);
    }

    private static RunEventStreams.Settings settings(int maxStreams, int perSubject, int reads) {
        return new RunEventStreams.Settings(maxStreams, maxStreams, perSubject, reads, 4, Duration.ofHours(1),
                Duration.ofHours(1), Duration.ofHours(1), Duration.ofHours(1), Duration.ofSeconds(3));
    }

    private RunEventStreams streams(RunEventStreams.Settings settings,
            Consumer<RunEventStreams.Subscription> beforeSupersede) {
        RunEventStreams streams = new RunEventStreams(engine(), settings, event -> event, beforeSupersede);
        opened.add(streams);
        return streams;
    }

    private static DurableRunEngine engine() {
        DurableRunStore unused = new DurableRunStore() {
            @Override
            public <T> T inTransaction(WorkspaceId workspace, Function<RunTransaction, T> work) {
                throw new UnsupportedOperationException("these streams read nothing");
            }

            @Override
            public <T> Optional<T> claimNext(ClaimScope scope, BiFunction<RunRecord, RunTransaction, T> work) {
                throw new UnsupportedOperationException("these streams claim nothing");
            }
        };
        return new DurableRunEngine(unused, RunEventCodec.standard(), new ReleaseManifestReader(),
                LifecycleRetry.standard());
    }

    /** A client that accepts everything and records the codes of its stream errors. */
    private static class Recording implements RunEventStreams.Sink {
        final List<Object> data = new CopyOnWriteArrayList<>();
        final CountDownLatch completed = new CountDownLatch(1);

        @Override
        public void retry(Duration retry) {}

        @Override
        public void event(String id, String name, Object payload) {
            data.add(payload);
        }

        @Override
        public void comment(String text) {}

        @Override
        public void complete() {
            completed.countDown();
        }

        List<String> codes() {
            return data.stream().filter(RunEventStreams.StreamError.class::isInstance)
                    .map(error -> ((RunEventStreams.StreamError) error).code()).toList();
        }
    }
}
