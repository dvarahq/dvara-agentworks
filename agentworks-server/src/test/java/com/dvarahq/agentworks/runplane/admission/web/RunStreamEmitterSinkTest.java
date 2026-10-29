package com.dvarahq.agentworks.runplane.admission.web;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #192: the stream's sink owns the response only while the request thread and the container do not. It writes and
 * completes nothing until the request thread has left the filter chain, whichever path of the stream writes first;
 * once a write failed, or the container reported a failure, a timeout, or its completion, the container ends the
 * response; and that decision is made once, atomically with the stream's own completion. A stream that completed the
 * response as well raced the container: Tomcat then found no dispatch and rendered an error page into the committed
 * stream, which was logged as an error.
 */
class RunStreamEmitterSinkTest {

    @Test
    void nothingIsWrittenOrCompletedUntilTheRequestThreadHasLeftTheChain() throws Exception {
        Emitter emitter = new Emitter();
        RunStreamController.EmitterSink sink = new RunStreamController.EmitterSink(emitter);
        Thread writer = Thread.ofPlatform().start(() -> {
            try {
                sink.event(null, "stream-error", "{}");
            } catch (IOException gone) {
                throw new IllegalStateException(gone);
            }
            sink.complete();
        });
        awaitParked(writer);
        assertThat(emitter.sends.get()).isZero();
        assertThat(emitter.completions.get()).isZero();

        sink.released(true, () -> {}, () -> {});
        writer.join(10_000);
        assertThat(emitter.sends.get()).isOne();
        assertThat(emitter.completions.get()).isOne();
    }

    @Test
    void aStreamThatEndsOnItsOwnCompletesItsResponseOnce() throws IOException {
        Emitter emitter = new Emitter();
        RunStreamController.EmitterSink sink = writable(emitter);

        sink.event("run:1", "RUN_CREATED", "{}");
        sink.complete();
        sink.complete();

        assertThat(emitter.completions.get()).isOne();
    }

    @Test
    void aStreamWhoseWriteFailedLeavesItsResponseToTheContainer() {
        Emitter emitter = new Emitter();
        emitter.failure = new IOException("Broken pipe");
        RunStreamController.EmitterSink sink = writable(emitter);

        assertThatThrownBy(() -> sink.comment("keepalive")).isInstanceOf(IOException.class);
        sink.complete();

        assertThat(emitter.completions.get()).isZero();
    }

    /** An emitter the container's error or timeout handling already completed refuses writes. */
    @Test
    void aStreamWhoseEmitterTheContainerCompletedLeavesItsResponseToTheContainer() {
        Emitter emitter = new Emitter();
        emitter.failure = new IllegalStateException("ResponseBodyEmitter has already completed");
        RunStreamController.EmitterSink sink = writable(emitter);

        assertThatThrownBy(() -> sink.comment("keepalive")).isInstanceOf(IllegalStateException.class);
        sink.complete();

        assertThat(emitter.completions.get()).isZero();
    }

    /** A write that fails for another reason (an event that cannot be written) leaves the response to the stream. */
    @Test
    void aStreamWhoseWriteFailedForAnotherReasonStillCompletesItsResponse() {
        Emitter emitter = new Emitter();
        emitter.failure = new IllegalStateException("Failed to send", new IllegalArgumentException("not writable"));
        RunStreamController.EmitterSink sink = writable(emitter);

        assertThatThrownBy(() -> sink.event(null, "stream-error", "{}")).isInstanceOf(IllegalStateException.class);
        sink.complete();

        assertThat(emitter.completions.get()).isOne();
    }

    /** The container's error, timeout, and completion callbacks each leave the response to the container. */
    @Test
    void afterAnyContainerCallbackTheStreamLeavesItsResponseToTheContainer() {
        for (String callback : List.of("error", "timeout", "completion")) {
            Emitter emitter = new Emitter();
            RunStreamController.EmitterSink sink = writable(emitter);
            AtomicInteger cancelled = new AtomicInteger();
            sink.watch(emitter, cancelled::incrementAndGet);

            switch (callback) {
                case "error" -> emitter.error.accept(new IOException("reset"));
                case "timeout" -> emitter.timeout.run();
                default -> emitter.completion.run();
            }
            sink.complete();

            assertThat(cancelled.get()).as(callback).isOne();
            assertThat(emitter.completions.get()).as(callback).isZero();
        }
    }

    /** A request whose chain failed, or that never went asynchronous, is ended by the container. */
    @Test
    void aStreamReleasedWithoutAnAsynchronousRequestIsCancelledAndNeverCompletesTheResponse() {
        Emitter emitter = new Emitter();
        RunStreamController.EmitterSink sink = new RunStreamController.EmitterSink(emitter);
        List<String> order = new CopyOnWriteArrayList<>();

        sink.released(false, () -> order.add("cancel"), () -> order.add("ready"));
        sink.complete();

        assertThat(order).containsExactly("cancel", "ready");
        assertThat(emitter.completions.get()).isZero();
    }

    /**
     * A container callback that arrives while the stream completes the emitter waits until that completion (and the
     * dispatch it requests) returned, so the container never finds the request without a dispatch.
     */
    @Test
    void aContainerCallbackDuringTheStreamsCompletionWaitsForIt() throws Exception {
        Emitter emitter = new Emitter();
        CountDownLatch completing = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        List<String> order = new CopyOnWriteArrayList<>();
        emitter.onComplete = () -> {
            completing.countDown();
            await(finish);
            order.add("stream completed");
        };
        // A bound far beyond the test's own steps, so that only the stream's completion ends the wait.
        RunStreamController.EmitterSink sink = new RunStreamController.EmitterSink(emitter,
                RunStreamController.EmitterSink.WRITABLE_TIMEOUT, Duration.ofSeconds(30));
        sink.writable();
        sink.watch(emitter, () -> {});

        Thread stream = Thread.ofPlatform().start(sink::complete);
        assertThat(completing.await(10, TimeUnit.SECONDS)).isTrue();
        Thread container = Thread.ofPlatform().start(() -> {
            emitter.error.accept(new IOException("reset"));
            order.add("container callback returned");
        });
        awaitParked(container);
        assertThat(order).isEmpty();

        finish.countDown();
        stream.join(10_000);
        container.join(10_000);
        assertThat(order).containsExactly("stream completed", "container callback returned");
        assertThat(emitter.completions.get()).isOne();
    }

    /**
     * #200: the container fires its completion callback while it holds its own locks (Spring's request state lock,
     * Tomcat's asynchronous state machine), the locks the stream's completion of the emitter needs. The callback must
     * therefore never wait for the stream, and the stream must hold no lock of its own while it completes the emitter;
     * otherwise the two threads wait on each other for good.
     */
    @Test
    void aCompletionCallbackUnderTheContainersLocksNeverWaitsOnTheStreamsCompletion() throws Exception {
        Emitter emitter = new Emitter();
        java.util.concurrent.locks.ReentrantLock containerLock = new java.util.concurrent.locks.ReentrantLock();
        CountDownLatch completing = new CountDownLatch(1);
        emitter.onComplete = () -> {
            completing.countDown();
            // Spring's completion flushes and dispatches under the container's locks.
            containerLock.lock();
            containerLock.unlock();
        };
        RunStreamController.EmitterSink sink = writable(emitter);
        sink.watch(emitter, () -> {});

        containerLock.lock();
        Thread stream;
        try {
            stream = Thread.ofPlatform().start(sink::complete);
            assertThat(completing.await(10, TimeUnit.SECONDS)).isTrue();
            awaitParked(stream);
            // The container, still holding its locks, reports the request complete.
            CompletableFuture<Void> callback = CompletableFuture.runAsync(() -> emitter.completion.run());
            callback.get(10, TimeUnit.SECONDS);
        } finally {
            containerLock.unlock();
        }
        stream.join(10_000);
        assertThat(stream.isAlive()).isFalse();
        assertThat(emitter.completions.get()).isOne();
    }

    /**
     * #200: an error or a timeout that finds the stream completing waits for that completion, but only up to its bound,
     * even if the completion never returns; the completion callback never waits.
     */
    @Test
    void aContainerCallbacksWaitForTheStreamsCompletionIsBounded() throws Exception {
        Emitter emitter = new Emitter();
        CountDownLatch completing = new CountDownLatch(1);
        CountDownLatch never = new CountDownLatch(1);
        emitter.onComplete = () -> {
            completing.countDown();
            await(never);
        };
        RunStreamController.EmitterSink sink = new RunStreamController.EmitterSink(emitter,
                RunStreamController.EmitterSink.WRITABLE_TIMEOUT, Duration.ofSeconds(2));
        sink.writable();
        sink.watch(emitter, () -> {});
        Thread stream = Thread.ofPlatform().start(sink::complete);
        try {
            assertThat(completing.await(10, TimeUnit.SECONDS)).isTrue();
            for (Runnable callback : List.<Runnable>of(() -> emitter.error.accept(new IOException("reset")),
                    () -> emitter.timeout.run())) {
                long started = System.nanoTime();
                callback.run();
                assertThat(Duration.ofNanos(System.nanoTime() - started)).isBetween(Duration.ofMillis(1900),
                        Duration.ofSeconds(8));
            }
            // The request already completed: that callback never waits.
            long started = System.nanoTime();
            emitter.completion.run();
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
        } finally {
            never.countDown();
            stream.join(10_000);
        }
    }

    /** An interrupt no longer matters once the response is writable. */
    @Test
    void anInterruptedStreamStillWritesAndCompletesAWritableResponse() throws IOException {
        Emitter emitter = new Emitter();
        RunStreamController.EmitterSink sink = writable(emitter);
        Thread.currentThread().interrupt();
        try {
            sink.comment("keepalive");
            sink.complete();
        } finally {
            Thread.interrupted();
        }
        assertThat(emitter.sends.get()).isOne();
        assertThat(emitter.completions.get()).isOne();
    }

    /**
     * #203: on an error, Spring (7.0.9) takes the request state lock and sets the state to ERROR before it calls back,
     * so a completion whose flush was waiting for that lock gives up within its 500 ms attempt, sets its result, and
     * requests its dispatch. The callback waits for exactly that, well within its backstop, so Spring never skips its
     * own dispatch while the stream's is still to come. A short bound (200 ms) returned first, reopening that race.
     */
    @Test
    void anErrorCallbackWaitsForACompletionWhoseFlushGivesUpOnTheContainersLock() throws Exception {
        Emitter emitter = new Emitter();
        java.util.concurrent.locks.ReentrantLock stateLock = new java.util.concurrent.locks.ReentrantLock();
        java.util.concurrent.atomic.AtomicReference<String> state = new java.util.concurrent.atomic.AtomicReference<>(
                "ASYNC");
        List<String> order = new CopyOnWriteArrayList<>();
        emitter.onComplete = () -> {
            // StandardServletAsyncWebRequest.tryObtainLock, as the completion's flush runs it.
            try {
                while ("ASYNC".equals(state.get())) {
                    if (stateLock.tryLock(500, TimeUnit.MILLISECONDS)) {
                        stateLock.unlock();
                        break;
                    }
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            order.add("stream set its result and requested its dispatch");
        };
        RunStreamController.EmitterSink sink = writable(emitter);
        sink.watch(emitter, () -> {});
        Thread stream;
        long elapsed;
        // StandardServletAsyncWebRequest.onError: lock, state ERROR, then the handlers.
        stateLock.lock();
        try {
            stream = Thread.ofPlatform().start(sink::complete);
            awaitParked(stream);
            state.set("ERROR");
            long started = System.nanoTime();
            emitter.error.accept(new IOException("reset"));
            elapsed = System.nanoTime() - started;
            order.add("container callback returned");
        } finally {
            stateLock.unlock();
        }
        stream.join(10_000);
        assertThat(order).containsExactly("stream set its result and requested its dispatch",
                "container callback returned");
        assertThat(Duration.ofNanos(elapsed)).isLessThan(Duration.ofSeconds(2));
        assertThat(emitter.completions.get()).isOne();
    }

    /**
     * #203: an interrupt of the container thread while it waits for the stream's completion ends the wait and is
     * consumed, not restored: that thread goes on to dispatch and write the response, and an interrupted thread's NIO
     * write closes the connection.
     */
    @Test
    void anInterruptOfTheContainersWaitIsConsumed() throws Exception {
        Emitter emitter = new Emitter();
        CountDownLatch completing = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        emitter.onComplete = () -> {
            completing.countDown();
            await(finish);
        };
        RunStreamController.EmitterSink sink = new RunStreamController.EmitterSink(emitter,
                RunStreamController.EmitterSink.WRITABLE_TIMEOUT, Duration.ofSeconds(30));
        sink.writable();
        sink.watch(emitter, () -> {});
        Thread stream = Thread.ofPlatform().start(sink::complete);
        try {
            assertThat(completing.await(10, TimeUnit.SECONDS)).isTrue();
            java.util.concurrent.atomic.AtomicBoolean interruptedAfter = new java.util.concurrent.atomic.AtomicBoolean(true);
            Thread container = Thread.ofPlatform().start(() -> {
                Thread.currentThread().interrupt();
                emitter.timeout.run();
                interruptedAfter.set(Thread.currentThread().isInterrupted());
            });
            container.join(10_000);
            assertThat(container.isAlive()).isFalse();
            assertThat(interruptedAfter.get()).isFalse();
        } finally {
            finish.countDown();
            stream.join(10_000);
        }
    }

    /**
     * #203: an interrupt of the stream's thread while it waits for the request thread is consumed, not restored, so a
     * write that follows is not made by an interrupted thread. The wait goes on: a response that becomes writable is
     * written, and one that never does is not.
     */
    @Test
    void anInterruptOfTheStreamsWaitForTheRequestThreadIsConsumed() throws Exception {
        Emitter never = new Emitter();
        RunStreamController.EmitterSink unwritable = new RunStreamController.EmitterSink(never, Duration.ofMillis(200),
                RunStreamController.EmitterSink.COMPLETION_WAIT);
        Thread.currentThread().interrupt();
        boolean interruptedAfter;
        try {
            assertThatThrownBy(() -> unwritable.comment("keepalive")).isInstanceOf(IOException.class);
            interruptedAfter = Thread.currentThread().isInterrupted();
        } finally {
            Thread.interrupted();
        }
        assertThat(interruptedAfter).isFalse();
        assertThat(never.sends.get()).isZero();

        Emitter later = new Emitter();
        RunStreamController.EmitterSink sink = new RunStreamController.EmitterSink(later);
        java.util.concurrent.atomic.AtomicBoolean interruptedAfterWrite = new java.util.concurrent.atomic.AtomicBoolean(true);
        Thread stream = Thread.ofPlatform().start(() -> {
            Thread.currentThread().interrupt();
            try {
                sink.comment("keepalive");
            } catch (IOException gone) {
                throw new IllegalStateException(gone);
            }
            interruptedAfterWrite.set(Thread.currentThread().isInterrupted());
        });
        awaitParked(stream);
        sink.writable();
        stream.join(10_000);
        assertThat(later.sends.get()).isOne();
        assertThat(interruptedAfterWrite.get()).isFalse();
    }

    /** The wait for the request thread is bounded over the stream's start, writes, and completion together. */
    @Test
    void theWaitForTheRequestThreadIsBoundedInAll() throws Exception {
        Emitter emitter = new Emitter();
        RunStreamController.EmitterSink sink = new RunStreamController.EmitterSink(emitter, Duration.ofMillis(200),
                RunStreamController.EmitterSink.COMPLETION_WAIT);

        assertThatThrownBy(() -> sink.comment("keepalive")).isInstanceOf(IOException.class);
        long started = System.nanoTime();
        sink.complete();
        assertThatThrownBy(() -> sink.comment("keepalive")).isInstanceOf(IOException.class);

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(150));
        assertThat(emitter.completions.get()).isZero();
    }

    private static RunStreamController.EmitterSink writable(Emitter emitter) {
        RunStreamController.EmitterSink sink = new RunStreamController.EmitterSink(emitter);
        sink.writable();
        return sink;
    }

    private static void awaitParked(Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (thread.getState() != Thread.State.WAITING && thread.getState() != Thread.State.TIMED_WAITING) {
            if (System.nanoTime() > deadline) throw new AssertionError("the thread never blocked: " + thread.getState());
            Thread.sleep(1);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** An emitter that records writes and completions, can fail its writes, and exposes its container callbacks. */
    private static final class Emitter extends SseEmitter {
        private volatile Exception failure;
        private volatile Runnable onComplete = () -> {};
        private final AtomicInteger sends = new AtomicInteger();
        private final AtomicInteger completions = new AtomicInteger();
        private volatile Runnable timeout;
        private volatile Runnable completion;
        private volatile Consumer<Throwable> error;

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            if (failure instanceof IOException io) throw io;
            if (failure instanceof RuntimeException runtime) throw runtime;
            sends.incrementAndGet();
        }

        @Override
        public void complete() {
            onComplete.run();
            completions.incrementAndGet();
        }

        @Override
        public void onTimeout(Runnable callback) {
            timeout = callback;
        }

        @Override
        public void onCompletion(Runnable callback) {
            completion = callback;
        }

        @Override
        public void onError(Consumer<Throwable> callback) {
            error = callback;
        }
    }
}
