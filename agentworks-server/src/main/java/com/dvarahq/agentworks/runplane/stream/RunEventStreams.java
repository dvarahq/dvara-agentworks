package com.dvarahq.agentworks.runplane.stream;

import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import com.dvarahq.agentworks.runplane.execution.DurableRunEngine;
import com.dvarahq.agentworks.runplane.execution.DurableRunEngine.EventPage;
import com.dvarahq.agentworks.runplane.execution.RunEngineException;
import com.dvarahq.agentworks.runplane.execution.RunEventCodec.DecodedEvent;

import org.springframework.context.SmartLifecycle;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Delivers durable run events to reconnectable streams (issue #35). A stream only reads the canonical history; it
 * never writes, locks, or holds a run, so a slow, stalled, or vanished client cannot delay durable run progress.
 *
 * <h2>Resource bounds</h2>
 * <ul>
 *   <li><b>Streams.</b> At most {@code maxStreams} per node, {@code maxStreamsPerWorkspace} per workspace, and
 *       {@code maxStreamsPerSubject} per authenticated subject are open; further opens are refused with
 *       {@link StreamLimitExceededException}. A client that reconnects to a run with its client stream id supersedes
 *       its own dead streams of that run (a vanished client is only noticed when a write to it fails, which can take
 *       the operating system's retransmission timeout for a half-open connection); see {@link #openDeferred}. The replaced stream still counts against the node and workspace limits until it has
 *       written its last event. A stream gives its slots back before its response completes.</li>
 *   <li><b>Threads.</b> Each stream runs on its own virtual thread, which is parked while it waits for events or
 *       while its client applies back pressure (a blocked socket write). No platform thread is held per stream.</li>
 *   <li><b>Memory.</b> A stream holds at most one page of {@code batchSize} events; it reads the next page only
 *       after the previous one was written, so a slow consumer never accumulates a queue.</li>
 *   <li><b>Database.</b> One ticker polls the current event sequence of every open stream's run with one query per
 *       workspace per {@code pollInterval}; after its first page, a stream reads again only when the ticker saw its
 *       run advance. At most {@code maxConcurrentReads} reads and authorization re-checks run at once, so streams
 *       never exhaust the connection pool.</li>
 *   <li><b>Lifetime.</b> A stream ends after {@code maxDuration} with a {@code reconnect} event; the client resumes
 *       with {@code Last-Event-ID}. A heartbeat comment every {@code heartbeatInterval} keeps idle connections open
 *       through proxies and detects vanished clients.</li>
 * </ul>
 *
 * <h2>Authorization</h2>
 * Every {@code reauthorizeInterval} (with jitter) the stream re-checks its principal; when the credential no longer
 * authorizes reading the run's workspace (sign-out, revocation, expiry, a disabled account, or a membership change)
 * the stream ends with a {@code stream-error} event whose code is {@code AUTHORIZATION_REVOKED}, and a reconnect is
 * refused. A re-check that fails, for example because the database is unavailable, is never assumed to succeed: the
 * stream ends with {@code STREAM_UNAVAILABLE}, and the reconnect authenticates again.
 *
 * <h2>Shutdown</h2>
 * As a {@link SmartLifecycle} in the last-started phase, the streams stop before the web server's graceful shutdown
 * waits for in-flight requests: every stream ends with a {@code reconnect} event, and stopping waits a bounded time
 * for them (a stream blocked writing to a stalled client ends only when that write times out).
 */
public final class RunEventStreams implements AutoCloseable, SmartLifecycle {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(RunEventStreams.class);

    /** SSE event names besides the run event types. */
    public static final String STREAM_END = "stream-end";
    public static final String STREAM_ERROR = "stream-error";
    public static final String RECONNECT = "reconnect";
    /**
     * A client stream id: 1 to 64 letters, digits, or hyphens, such as a UUID. It has no underscore, so it can never
     * take the shape of an AgentWorks credential, which a URL must not carry.
     */
    public static final java.util.regex.Pattern CLIENT_ID = java.util.regex.Pattern.compile("[A-Za-z0-9-]{1,64}");
    static final long START_TIMEOUT_SECONDS = 10;

    /**
     * Stream limits. Worst-case stream memory is about {@code maxStreams x batchSize x} the largest event payload
     * (128 KiB), so the defaults bound it to 256 x 16 x 128 KiB = 512 MiB even if every stream reads maximal
     * events at once; typical events are a few hundred bytes.
     */
    public record Settings(int maxStreams, int maxStreamsPerWorkspace, int maxStreamsPerSubject,
            int maxConcurrentReads, int batchSize, Duration pollInterval, Duration heartbeatInterval,
            Duration reauthorizeInterval, Duration maxDuration, Duration clientRetry) {
        public static final Settings DEFAULTS = new Settings(256, 128, 16, 4, 16, Duration.ofMillis(250),
                Duration.ofSeconds(15), Duration.ofSeconds(30), Duration.ofMinutes(10), Duration.ofSeconds(3));

        public Settings {
            if (maxStreams < 1 || maxStreamsPerWorkspace < 1 || maxStreamsPerSubject < 1 || maxConcurrentReads < 1) {
                throw new IllegalArgumentException("stream limits must be positive");
            }
            if (batchSize < 1 || batchSize > DurableRunEngine.MAX_EVENT_PAGE) {
                throw new IllegalArgumentException("batch size must be between 1 and " + DurableRunEngine.MAX_EVENT_PAGE);
            }
            for (Duration duration : List.of(pollInterval, heartbeatInterval, reauthorizeInterval, maxDuration,
                    clientRetry)) {
                if (duration == null || duration.isNegative() || duration.isZero()) {
                    throw new IllegalArgumentException("stream intervals must be positive");
                }
            }
        }
    }

    /**
     * Where one stream writes, for example a Server-Sent Events response. Only the stream's own thread calls it, one
     * call at a time. A write that fails because the client is gone throws {@link IOException}.
     */
    public interface Sink {
        void retry(Duration retry) throws IOException;

        /**
         * An id-only frame that sets the client's last event id to {@code id} without dispatching any event, so that a
         * browser {@code EventSource} sends it as {@code Last-Event-ID} on its reconnect even when no run event has
         * arrived yet (#190 review). A sink that is not a browser stream may ignore it.
         */
        default void position(String id) throws IOException {}

        /** One event; {@code id} is {@code null} for control events, which must not move the client's position. */
        void event(String id, String name, Object data) throws IOException;

        void comment(String text) throws IOException;

        /** Ends the response normally. */
        void complete();
    }

    /** Why a stream ended. */
    public enum EndReason {
        /** The run is terminal and every event through its terminal event was delivered. */
        TERMINAL,
        /** The stream reached its maximum duration; the client reconnects. */
        MAX_DURATION,
        /** The node is shutting down; the client reconnects. */
        SHUTDOWN,
        /** The principal no longer authorizes the stream. */
        AUTHORIZATION_REVOKED,
        /** The history could not be delivered as a verified, gap-free continuation. */
        HISTORY_UNAVAILABLE,
        /** The history could not be read, for example because the database is unavailable; the client reconnects. */
        UNAVAILABLE,
        /** The subject reconnected to the run, and the newer stream replaced this one. */
        SUPERSEDED,
        /** The client disconnected or its connection failed. */
        CLIENT_GONE
    }

    /** The data of a {@value #STREAM_END} event. */
    public record StreamEnd(UUID runId, long lastSequence, String lifecycle, String terminalCode) {}

    /** The data of a {@value #RECONNECT} event: resume after {@code lastSequence}. */
    public record Reconnect(UUID runId, long lastSequence, String reason) {}

    /** The data of a {@value #STREAM_ERROR} event; {@code code} is a stable problem code. */
    public record StreamError(UUID runId, long lastSequence, String code) {}

    /** Engine refusals that mean the history cannot be continued gap-free from the stream's position. */
    private static final Set<String> HISTORY_FAILURES = Set.of("CORRUPT_HISTORY", "UNKNOWN_EVENT_TYPE",
            "UNSUPPORTED_EVENT_VERSION", "EVENT_HISTORY_EXPIRED", "EVENT_SEQUENCE_AHEAD", "NOT_FOUND");

    private final DurableRunEngine engine;
    private final Settings settings;
    private final Function<DecodedEvent, Object> views;
    private final Semaphore reads;
    private final AtomicInteger open = new AtomicInteger();
    private final Map<UUID, Integer> perSubject = new ConcurrentHashMap<>();
    private final Map<WorkspaceId, Integer> perWorkspace = new ConcurrentHashMap<>();
    private final java.util.concurrent.locks.ReentrantLock admission = new java.util.concurrent.locks.ReentrantLock();
    /** How long a deferred stream waits for its response to be ready before it gives up its slot. */
    static final Duration START_TIMEOUT = Duration.ofSeconds(START_TIMEOUT_SECONDS);
    /** How long stopping waits for streams to deliver their reconnect event and end. */
    static final Duration STOP_TIMEOUT = Duration.ofSeconds(5);
    private final Set<Subscription> subscriptions = ConcurrentHashMap.newKeySet();
    /** The open streams that have a client stream id, by subject, run, and client id. */
    private final Map<ClientKey, Set<Subscription>> byClient = new ConcurrentHashMap<>();
    /** How long a request's bounded read (the stream admission probe) waits for a read slot (#190). */
    static final Duration PROBE_WAIT = Duration.ofMillis(250);
    private final AtomicBoolean closed = new AtomicBoolean();
    /** False while the lifecycle is stopped: no stream is opened, and stopping ended the open ones. */
    private volatile boolean accepting = true;
    private final AtomicLong names = new AtomicLong();
    private final Thread ticker;
    private volatile long lastTickFailureLog;
    /** Runs under the admission lock just before a replaced stream is superseded; only race tests set it. */
    private final java.util.function.Consumer<Subscription> beforeSupersede;

    /**
     * @param views maps a decoded event to the data written for it, such as the public event envelope
     */
    public RunEventStreams(DurableRunEngine engine, Settings settings, Function<DecodedEvent, Object> views) {
        this(engine, settings, views, replaced -> {});
    }

    /** As the public constructor, with a hook that race tests run just before a replaced stream is superseded. */
    RunEventStreams(DurableRunEngine engine, Settings settings, Function<DecodedEvent, Object> views,
            java.util.function.Consumer<Subscription> beforeSupersede) {
        this.beforeSupersede = Objects.requireNonNull(beforeSupersede, "beforeSupersede");
        this.engine = Objects.requireNonNull(engine, "engine");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.views = Objects.requireNonNull(views, "views");
        this.reads = new Semaphore(settings.maxConcurrentReads(), true);
        this.ticker = Thread.ofPlatform().daemon().name("agentworks-run-stream-ticker").unstarted(this::tick);
        this.ticker.start();
    }

    public Settings settings() {
        return settings;
    }

    /** Streams open now. */
    public int open() {
        return open.get();
    }

    /** Whether {@code value} is a well-formed client stream id ({@link #CLIENT_ID}); the one check for both layers. */
    public static boolean isClientId(String value) {
        return value != null && CLIENT_ID.matcher(value).matches();
    }

    /**
     * Runs a read made on a stream's behalf outside the stream, such as the admission probe of a stream request, within
     * the same bound as the streams' own reads ({@code maxConcurrentReads}), so requests cannot exhaust the connection
     * pool either. The caller is a request thread, so it waits at most {@link #PROBE_WAIT} for a read slot and never
     * holds a container thread behind the streams' reads.
     *
     * @throws StreamsBusyException when no read slot frees up within {@link #PROBE_WAIT}; the caller answers it as
     *     {@code STREAM_BUSY} (a {@code reconnect} for SSE clients, otherwise 503), never as a stream limit
     * @throws StreamsStoppedException when the waiting request thread is interrupted, which only a stopping container
     *     does; the interrupt is consumed rather than re-asserted, so that the thread can still write the
     *     {@code reconnect} answer (an interrupted thread's socket write can close the channel)
     */
    public <T> T boundedRead(Supplier<T> read) {
        Objects.requireNonNull(read, "read");
        try {
            if (!reads.tryAcquire(PROBE_WAIT.toNanos(), TimeUnit.NANOSECONDS)) throw new StreamsBusyException();
        } catch (InterruptedException interrupted) {
            throw new StreamsStoppedException();
        }
        try {
            return read.get();
        } finally {
            reads.release();
        }
    }

    /** Opens a stream without a client stream id (see {@link #openDeferred}). */
    public Subscription open(WorkspaceId workspace, UUID runId, long afterSequence, UUID subject,
            BooleanSupplier authorized, Sink sink) {
        return open(workspace, runId, afterSequence, null, false, subject, authorized, sink);
    }

    /**
     * Opens a stream of the events of {@code runId} after {@code afterSequence} and starts delivering them on a
     * virtual thread (see {@link #openDeferred}).
     */
    public Subscription open(WorkspaceId workspace, UUID runId, long afterSequence, String client, boolean reconnect,
            UUID subject, BooleanSupplier authorized, Sink sink) {
        Subscription subscription = openDeferred(workspace, runId, afterSequence, client, reconnect, subject,
                authorized, sink);
        subscription.ready();
        return subscription;
    }

    /**
     * Opens a stream of the events of {@code runId} after {@code afterSequence} and starts delivering them on a
     * virtual thread. The caller has already authorized the request and validated the position. The stream writes
     * nothing until {@link Subscription#ready()} says its response can be written, for example once an SSE response
     * is initialized; a stream that is not ready within {@value #START_TIMEOUT_SECONDS} seconds ends and frees its
     * slot.
     *
     * <p><b>Supersession (#186, #190).</b> A <em>reconnect</em> of a client (a client stream id and a resume position
     * from {@code Last-Event-ID}) supersedes every earlier stream of the same subject, run, and client id, at or below
     * the subject's limit: the client reconnects only when its previous connection ended or failed, so those streams'
     * connections are dead, even when the server has not noticed yet (a write to a half-open connection succeeds into
     * socket buffers until they fill). They end with {@code STREAM_SUPERSEDED} and give their subject slots back at
     * once, so a client's dead streams never pile up. They are superseded even when this reconnect is refused by a
     * limit, so that their slots are free for its retry. Only the browser's own {@code EventSource} reconnect sends
     * {@code Last-Event-ID} (a page cannot set it), so a client that rebuilds its connection supersedes nothing. Every
     * stream opens with an id-only frame at its position ({@link Sink#position}), so even a client that received no
     * run event reconnects with {@code Last-Event-ID} and supersedes its dead stream. A first connection, even with a
     * known client id (a duplicated browser tab, or a page that mounts its stream again), supersedes nothing, and neither
     * does a stream without a client id; there is no position-based fallback, because positions cannot tell a live
     * tab from a dead connection.
     *
     * @param client the client's opaque stream id ({@link #CLIENT_ID}), stable across its reconnects, or {@code null}
     * @param reconnect whether the request resumes a stream this client was reading ({@code Last-Event-ID})
     * @param subject the authenticated subject the per-subject limit counts
     * @param authorized re-checks the principal; false ends the stream
     * @throws StreamLimitExceededException when the node, the workspace, or the subject has no free stream
     * @throws StreamsStoppedException while the node is stopping
     */
    public Subscription openDeferred(WorkspaceId workspace, UUID runId, long afterSequence, String client,
            boolean reconnect, UUID subject, BooleanSupplier authorized, Sink sink) {
        Objects.requireNonNull(workspace, "workspace");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(authorized, "authorized");
        Objects.requireNonNull(sink, "sink");
        if (afterSequence < 0) throw new IllegalArgumentException("afterSequence must not be negative");
        if (client != null && !isClientId(client)) {
            throw new IllegalArgumentException("a client stream id is 1 to 64 letters, digits, or hyphens");
        }
        ClientKey key = client == null ? null : new ClientKey(subject, runId, client);
        Subscription subscription;
        // Admission is serialized: every increment of a limit happens under this lock, so a check and its increment
        // are atomic, and a supersession is decided exactly once per slot. Releases only lower counts.
        admission.lock();
        try {
            if (closed.get() || !accepting) throw new StreamsStoppedException();
            List<Subscription> superseded = reconnect && key != null ? ownStreams(key) : List.of();
            try {
                admit(workspace, subject, superseded.size());
            } catch (StreamLimitExceededException refused) {
                // Refused by a limit: the client's earlier streams are dead all the same, and ending them frees their
                // slots for its retry.
                superseded.forEach(this::supersede);
                throw refused;
            }
            subscription = new Subscription(workspace, runId, afterSequence, key, subject, authorized,
                    new GuardedSink(sink));
            // The new stream always takes a subject slot of its own, and a superseded stream gives its slot back
            // exactly once (see Subscription#supersede), whether or not it already ended on its own. So a superseded
            // stream that releases itself between its selection and its supersession can neither leave the subject
            // counted one low nor let the subject open a stream beyond its limit.
            open.incrementAndGet();
            perWorkspace.merge(workspace, 1, Integer::sum);
            perSubject.merge(subject, 1, Integer::sum);
            subscriptions.add(subscription);
            if (key != null) {
                // Added inside the map's compute, so it is atomic with a released stream removing an emptied key.
                byClient.compute(key, (ignored, own) -> {
                    Set<Subscription> streams = own == null ? ConcurrentHashMap.newKeySet() : own;
                    streams.add(subscription);
                    return streams;
                });
            }
            try {
                Thread.ofVirtual().name("agentworks-run-stream-" + names.incrementAndGet()).start(subscription::run);
            } catch (RuntimeException | Error failed) {
                // Nothing is superseded yet, so a stream that cannot start costs the client no running stream.
                subscription.released();
                throw failed;
            }
            // The superseded streams' node and workspace slots stay counted until they have written their last event,
            // so a stalled client can never multiply uncounted streams.
            superseded.forEach(this::supersede);
        } finally {
            admission.unlock();
        }
        // A stop that ran while this stream was admitted must still end it.
        if (closed.get() || !accepting) subscription.shutdown();
        return subscription;
    }

    private void supersede(Subscription stream) {
        beforeSupersede.accept(stream);
        stream.supersede();
    }

    /**
     * The one admission decision: refuses a stream that exceeds a limit. {@code giving} streams of the subject are
     * superseded by this one and give their subject slots back, so they do not count against the subject; they keep
     * their node and workspace slots until they end. The limits are checked before anything is superseded.
     */
    private void admit(WorkspaceId workspace, UUID subject, int giving) {
        if (open(subject) - giving >= settings.maxStreamsPerSubject()) {
            throw new StreamLimitExceededException("the subject has no free stream");
        }
        if (open.get() >= settings.maxStreams()) throw new StreamLimitExceededException("the node has no free stream");
        if (open(workspace) >= settings.maxStreamsPerWorkspace()) {
            throw new StreamLimitExceededException("the workspace has no free stream");
        }
    }

    /**
     * The client's streams that still hold their subject slot. A stream whose client the container already reported
     * gone still holds its slot until its thread releases it, so it counts too: the reconnect supersedes it rather
     * than waiting for that release. An index keyed by subject, run, and client keeps this off the node's full set.
     */
    private List<Subscription> ownStreams(ClientKey key) {
        Set<Subscription> own = byClient.get(key);
        if (own == null) return List.of();
        return own.stream().filter(stream -> !stream.superseded && stream.reason == null).toList();
    }

    /** The index key of a client's streams. */
    private record ClientKey(UUID subject, UUID runId, String client) {}

    /** Ends every stream with a {@value #RECONNECT} event and stops polling. */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        ticker.interrupt();
        for (Subscription subscription : List.copyOf(subscriptions)) subscription.shutdown();
    }

    private void release(UUID subject) {
        perSubject.computeIfPresent(subject, (ignored, count) -> count <= 1 ? null : count - 1);
    }

    private void release(WorkspaceId workspace) {
        perWorkspace.computeIfPresent(workspace, (ignored, count) -> count <= 1 ? null : count - 1);
    }

    /** Streams the workspace has open now. */
    public int open(WorkspaceId workspace) {
        return perWorkspace.getOrDefault(workspace, 0);
    }

    // SmartLifecycle: stop before the web server's graceful shutdown waits for in-flight (stream) requests.

    /** Accepts streams again after a stop, for example when a paused application context resumes. */
    @Override
    public void start() {
        if (!closed.get()) accepting = true;
    }

    /** Ends every open stream with a {@value #RECONNECT} event and waits briefly for them to finish. */
    @Override
    public void stop() {
        accepting = false;
        for (Subscription subscription : List.copyOf(subscriptions)) subscription.shutdown();
        long deadline = System.nanoTime() + STOP_TIMEOUT.toNanos();
        while (open.get() > 0 && System.nanoTime() - deadline < 0) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    @Override
    public boolean isRunning() {
        return accepting && !closed.get();
    }

    @Override
    public int getPhase() {
        return SmartLifecycle.DEFAULT_PHASE;
    }

    /** Streams the subject has open now. */
    public int open(UUID subject) {
        return perSubject.getOrDefault(subject, 0);
    }

    /** Wakes every stream whose run has events it has not delivered, with one query per workspace. */
    private void tick() {
        long interval = settings.pollInterval().toNanos();
        while (!closed.get()) {
            try {
                TimeUnit.NANOSECONDS.sleep(interval);
            } catch (InterruptedException interrupted) {
                if (closed.get()) return;
                continue;
            }
            try {
                tickOnce();
            } catch (InterruptedException interrupted) {
                if (closed.get()) return;
            } catch (Throwable failed) {
                // The ticker is what wakes idle streams; it must outlive any single failure.
                logTickFailure(failed);
            }
        }
    }

    private void logTickFailure(Throwable failed) {
        long now = System.nanoTime();
        if (now - lastTickFailureLog > TimeUnit.MINUTES.toNanos(1)) {
            lastTickFailureLog = now;
            LOG.warn("run stream polling failed: {}", failed.getClass().getSimpleName());
        }
    }

    private void tickOnce() throws InterruptedException {
        Map<WorkspaceId, List<Subscription>> byWorkspace = new HashMap<>();
        for (Subscription subscription : subscriptions) {
            if (!subscription.ended()) {
                byWorkspace.computeIfAbsent(subscription.workspace, ignored -> new ArrayList<>()).add(subscription);
            }
        }
        for (var entry : byWorkspace.entrySet()) {
            Map<UUID, Long> sequences;
            reads.acquire();
            try {
                sequences = engine.eventSequences(entry.getKey(),
                        entry.getValue().stream().map(subscription -> subscription.runId).distinct().toList());
            } catch (RuntimeException failed) {
                // The streams keep their heartbeats; a later tick retries.
                logTickFailure(failed);
                continue;
            } finally {
                reads.release();
            }
            for (Subscription subscription : entry.getValue()) {
                Long current = sequences.get(subscription.runId);
                if (current != null && current > subscription.delivered) subscription.wake();
            }
        }
    }

    /** One open stream. */
    public final class Subscription {
        private final WorkspaceId workspace;
        private final UUID runId;
        private final UUID subject;
        private final BooleanSupplier authorized;
        private final Sink sink;
        private final Semaphore signal = new Semaphore(0);
        private final java.util.concurrent.CountDownLatch readyLatch = new java.util.concurrent.CountDownLatch(1);
        private final AtomicBoolean released = new AtomicBoolean();
        private final AtomicBoolean subjectReleased = new AtomicBoolean();
        private final AtomicLong pageReads = new AtomicLong();
        private volatile boolean waiting;
        private volatile boolean superseded;
        private final long opened = System.nanoTime();
        /** The stream's index key when its client sent a client stream id, or {@code null}. */
        private final ClientKey client;
        private volatile long delivered;
        private volatile boolean cancelled;
        private volatile boolean shuttingDown;
        private volatile EndReason reason;
        private String anchor;

        private Subscription(WorkspaceId workspace, UUID runId, long afterSequence, ClientKey client, UUID subject,
                BooleanSupplier authorized, Sink sink) {
            this.workspace = workspace;
            this.runId = runId;
            this.delivered = afterSequence;
            this.client = client;
            this.subject = subject;
            this.authorized = authorized;
            this.sink = sink;
        }

        /** The sequence of the last event written to the client. */
        public long delivered() {
            return delivered;
        }

        /** How many history pages the stream has read. */
        public long pageReads() {
            return pageReads.get();
        }

        /** True while the stream waits for its run to advance, a heartbeat, or a re-check, reading nothing. */
        public boolean waiting() {
            return waiting;
        }

        /** Why the stream ended, or {@code null} while it is open. */
        public EndReason reason() {
            return reason;
        }

        public boolean ended() {
            return reason != null || cancelled || superseded;
        }

        /** The client went away, for example the container reported a disconnect or a timeout; stop writing. */
        public void cancel() {
            cancelled = true;
            signal.release();
        }

        private void shutdown() {
            shuttingDown = true;
            signal.release();
            readyLatch.countDown();
        }

        /** The stream's response can be written; until then the stream writes nothing. */
        public void ready() {
            readyLatch.countDown();
        }

        /**
         * The same client reconnected to the run, and its new stream replaces this one (see {@link #openDeferred}).
         * The replacement took a subject slot of its own, so this stream gives its slot back now, unless it already
         * gave it back by ending on its own, in which case nothing is recorded; either way exactly once. The node and
         * workspace slots stay counted until this stream has written its last event, because a client that stalled
         * its socket can keep a write blocked until it times out. A client that still reads receives
         * {@code STREAM_SUPERSEDED}, even when this stream was not ready yet, and stops.
         */
        private void supersede() {
            // Only a shortcut, deliberately not atomic with released(): if the stream releases concurrently, the
            // subjectReleased CAS below still gives its subject slot back exactly once, and the flag on a released
            // stream changes nothing (it is out of every set, and its thread writes nothing more).
            if (released.get()) return;
            superseded = true;
            if (subjectReleased.compareAndSet(false, true)) release(subject);
            signal.release();
            readyLatch.countDown();
        }

        private void wake() {
            if (signal.availablePermits() == 0) signal.release();
        }

        private void run() {
            try {
                // A superseded stream goes on to write STREAM_SUPERSEDED (buffered until its response is initialized).
                if (!readyLatch.await(START_TIMEOUT.toNanos(), TimeUnit.NANOSECONDS) || cancelled) {
                    end(EndReason.CLIENT_GONE);
                    return;
                }
            } catch (InterruptedException interrupted) {
                end(EndReason.SHUTDOWN);
                return;
            }
            long start = System.nanoTime();
            long deadline = start + settings.maxDuration().toNanos();
            long interval = settings.reauthorizeInterval().toNanos();
            // Jitter spreads the re-checks of streams opened together, for example after a restart.
            long nextAuthorization = start + interval + java.util.concurrent.ThreadLocalRandom.current()
                    .nextLong(Math.max(1, interval / 4));
            long lastWrite = start;
            boolean readDue = true;
            try {
                sink.retry(settings.clientRetry());
                // Every connection starts with the position it resumes from, so that its client's native reconnect is
                // always recognized as a reconnect, also on a run that stays idle.
                sink.position(runId + ":" + delivered);
                while (true) {
                    if (cancelled) {
                        end(EndReason.CLIENT_GONE);
                        return;
                    }
                    if (superseded) {
                        sink.event(null, STREAM_ERROR, new StreamError(runId, delivered, "STREAM_SUPERSEDED"));
                        end(EndReason.SUPERSEDED);
                        return;
                    }
                    if (shuttingDown || closed.get()) {
                        sink.event(null, RECONNECT, new Reconnect(runId, delivered, EndReason.SHUTDOWN.name()));
                        end(EndReason.SHUTDOWN);
                        return;
                    }
                    long now = System.nanoTime();
                    if (now - deadline >= 0) {
                        sink.event(null, RECONNECT, new Reconnect(runId, delivered, EndReason.MAX_DURATION.name()));
                        end(EndReason.MAX_DURATION);
                        return;
                    }
                    if (now - nextAuthorization >= 0) {
                        Boolean authorizedNow = stillAuthorized();
                        if (authorizedNow == null) {
                            sink.event(null, STREAM_ERROR, new StreamError(runId, delivered, "STREAM_UNAVAILABLE"));
                            end(EndReason.UNAVAILABLE);
                            return;
                        }
                        if (!authorizedNow) {
                            sink.event(null, STREAM_ERROR, new StreamError(runId, delivered, "AUTHORIZATION_REVOKED"));
                            end(EndReason.AUTHORIZATION_REVOKED);
                            return;
                        }
                        nextAuthorization = System.nanoTime() + interval;
                    }
                    if (!readDue) {
                        lastWrite = idle(lastWrite, nextAuthorization, deadline);
                        readDue = signalled;
                        continue;
                    }
                    // A stream superseded or gone since the checks above reads nothing more.
                    if (cancelled || superseded) continue;
                    EventPage page = read();
                    if (page == null) continue;
                    for (DecodedEvent event : page.events()) {
                        // A replaced stream stops writing to its dead connection at once.
                        if (cancelled || superseded) break;
                        long sequence = event.raw().sequence();
                        sink.event(runId + ":" + sequence, event.type().name(), views.apply(event));
                        delivered = sequence;
                        anchor = event.raw().eventDigest();
                        lastWrite = System.nanoTime();
                    }
                    if (cancelled || superseded) continue;
                    if (page.more()) continue;
                    var run = page.run();
                    if (run.lifecycle().terminal() && delivered >= run.summary().eventSequence()) {
                        sink.event(null, STREAM_END, new StreamEnd(runId, delivered, run.lifecycle().name(),
                                run.summary().terminalCode()));
                        end(EndReason.TERMINAL);
                        return;
                    }
                    lastWrite = idle(lastWrite, nextAuthorization, deadline);
                    readDue = signalled;
                }
            } catch (IOException clientGone) {
                end(EndReason.CLIENT_GONE);
            } catch (InterruptedException interrupted) {
                end(EndReason.SHUTDOWN);
            } catch (RunEngineException refused) {
                if (HISTORY_FAILURES.contains(refused.code())) {
                    fail(refused.code(), EndReason.HISTORY_UNAVAILABLE);
                } else {
                    LOG.warn("run stream stopped: {}", refused.code());
                    fail("STREAM_UNAVAILABLE", EndReason.UNAVAILABLE);
                }
            } catch (RuntimeException failed) {
                LOG.warn("run stream stopped: {}", failed.getClass().getSimpleName());
                fail("STREAM_UNAVAILABLE", EndReason.UNAVAILABLE);
            } finally {
                released();
            }
        }

        /** Whether the last wait ended because the ticker saw the run advance (or the stream must stop). */
        private boolean signalled;

        /**
         * Waits until the ticker signals, a heartbeat, the next re-check, or the deadline is due; sends the heartbeat
         * if it is due. Returns the time of the last write.
         */
        private long idle(long lastWrite, long nextAuthorization, long deadline) throws IOException, InterruptedException {
            long heartbeat = lastWrite + settings.heartbeatInterval().toNanos();
            long wake = Math.min(Math.min(heartbeat, nextAuthorization), deadline);
            waiting = true;
            try {
                signalled = signal.tryAcquire(Math.max(0, wake - System.nanoTime()), TimeUnit.NANOSECONDS);
            } finally {
                waiting = false;
            }
            signal.drainPermits();
            if (!signalled && System.nanoTime() - heartbeat >= 0) {
                sink.comment("keepalive");
                return System.nanoTime();
            }
            return lastWrite;
        }

        /** The next page, or {@code null} when the stream was superseded or its client left while it waited. */
        private EventPage read() throws InterruptedException {
            pageReads.incrementAndGet();
            reads.acquire();
            try {
                // A stream superseded while it waited for a read permit reads nothing (#190).
                if (cancelled || superseded) return null;
                return engine.eventPage(workspace, runId, delivered, settings.batchSize(), delivered == 0 ? null : anchor);
            } finally {
                reads.release();
            }
        }

        /** True or false when the principal was re-checked; {@code null} when the re-check itself failed. */
        private Boolean stillAuthorized() throws InterruptedException {
            reads.acquire();
            try {
                return authorized.getAsBoolean();
            } catch (RuntimeException failed) {
                // An authorization that cannot be re-checked is never assumed; the client reconnects and authenticates.
                LOG.warn("run stream authorization re-check failed: {}", failed.getClass().getSimpleName());
                return null;
            } finally {
                reads.release();
            }
        }

        private void fail(String code, EndReason why) {
            try {
                sink.event(null, STREAM_ERROR, new StreamError(runId, delivered, code));
            } catch (IOException | RuntimeException ignored) {
                // The client is gone as well; there is nothing more to deliver.
            }
            end(why);
        }

        /**
         * Ends the stream. Its slots are given back before its response completes, so whoever observes the end (a
         * client reconnecting at its limit at once, or a caller waiting on the response) never finds the slot still
         * counted; everything that could block on the client (the writes) is done by then.
         */
        private void end(EndReason why) {
            if (reason == null) reason = why;
            released();
            try {
                sink.complete();
            } catch (RuntimeException ignored) {
                // Completing a response whose client already left has no effect worth reporting.
            }
        }

        private void released() {
            if (!released.compareAndSet(false, true)) return;
            if (reason == null) reason = cancelled ? EndReason.CLIENT_GONE : EndReason.UNAVAILABLE;
            subscriptions.remove(this);
            if (client != null) {
                byClient.computeIfPresent(client, (ignored, own) -> {
                    own.remove(this);
                    return own.isEmpty() ? null : own;
                });
            }
            if (subjectReleased.compareAndSet(false, true)) release(subject);
            release(workspace);
            open.decrementAndGet();
        }
    }

    /** No read slot was free for a request's bounded read in time: the client retries shortly ({@code STREAM_BUSY}). */
    public static final class StreamsBusyException extends RuntimeException {
        public StreamsBusyException() {
            super("no stream read is available now", null, false, false);
        }
    }

    /** The node is stopping: the client reconnects, and a load balancer routes it to another node. */
    public static final class StreamsStoppedException extends RuntimeException {
        public StreamsStoppedException() {
            super("the node is stopping", null, false, false);
        }
    }

    /** A sink write failed for a reason other than the client leaving. */
    private static final class SinkFailedException extends RuntimeException {
        private SinkFailedException(Throwable cause) {
            super(cause.getClass().getSimpleName(), cause, false, false);
        }
    }

    /**
     * Translates a sink's failures: a response that is no longer usable (completed, or failed by an I/O error) is the
     * client leaving; any other failure is reported and logged. Nothing else in the loop is treated as a disconnect.
     */
    private record GuardedSink(Sink delegate) implements Sink {
        @Override
        public void retry(Duration retry) throws IOException {
            try {
                delegate.retry(retry);
            } catch (IllegalStateException unusable) {
                throw translate(unusable);
            }
        }

        @Override
        public void position(String id) throws IOException {
            try {
                delegate.position(id);
            } catch (IllegalStateException unusable) {
                throw translate(unusable);
            }
        }

        @Override
        public void event(String id, String name, Object data) throws IOException {
            try {
                delegate.event(id, name, data);
            } catch (IllegalStateException unusable) {
                throw translate(unusable);
            }
        }

        @Override
        public void comment(String text) throws IOException {
            try {
                delegate.comment(text);
            } catch (IllegalStateException unusable) {
                throw translate(unusable);
            }
        }

        @Override
        public void complete() {
            delegate.complete();
        }

        private static IOException translate(IllegalStateException unusable) {
            Throwable cause = unusable.getCause();
            if (cause == null || cause instanceof IOException) return new IOException("the response is no longer usable", unusable);
            throw new SinkFailedException(cause);
        }
    }

    /** No stream is available for this request; the client retries later. */
    public static final class StreamLimitExceededException extends RuntimeException {
        public StreamLimitExceededException(String message) {
            super(message, null, false, false);
        }
    }
}
