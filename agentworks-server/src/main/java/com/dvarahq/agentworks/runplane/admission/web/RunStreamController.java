package com.dvarahq.agentworks.runplane.admission.web;

import com.dvarahq.agentworks.api.web.ApiRequestException;
import com.dvarahq.agentworks.api.web.PageCursor;
import com.dvarahq.agentworks.controlplane.agent.web.WorkspaceScopeResolver;
import com.dvarahq.agentworks.domain.identity.AuthenticatedPrincipal;
import com.dvarahq.agentworks.domain.identity.WorkspacePermission;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import com.dvarahq.agentworks.identity.PrincipalRevalidation;
import com.dvarahq.agentworks.identity.web.CurrentPrincipal;
import com.dvarahq.agentworks.runplane.execution.DurableRunEngine;
import com.dvarahq.agentworks.runplane.execution.DurableRunEngine.EventPage;
import com.dvarahq.agentworks.runplane.stream.RunEventStreams;
import com.dvarahq.agentworks.runplane.stream.RunEventStreams.StreamLimitExceededException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The reconnectable, typed run event stream (issue #35): {@code GET /api/v1/runs/{id}/stream} as Server-Sent Events.
 *
 * <ul>
 *   <li><b>Events.</b> Each run event is one SSE event whose {@code event} is the run event type, whose {@code id}
 *       is {@code <runId>:<sequence>}, and whose {@code data} is the {@link RunEventView} envelope that
 *       {@code GET /runs/{id}/events} also returns. Events arrive in gapless sequence order, each exactly once per
 *       connection.</li>
 *   <li><b>Resume.</b> A reconnect sends {@code Last-Event-ID} (browsers do this automatically), and delivery
 *       continues with the next sequence, with no gap or duplicate; a first connection may start after a known
 *       sequence with {@code cursor}. The header wins over the parameter. A malformed id is 400
 *       {@code INVALID_LAST_EVENT_ID}, an id of another run is 400 {@code LAST_EVENT_ID_MISMATCH}, a position beyond
 *       the run is 409 {@code EVENT_SEQUENCE_AHEAD}, and a position before retained history is 410
 *       {@code EVENT_HISTORY_EXPIRED}; the last two carry {@code currentSequence} for resynchronization.</li>
 *   <li><b>End.</b> After the run's terminal event the stream sends {@code stream-end} and closes. Reconnecting to a
 *       terminal run whose events were all delivered answers 204, which stops a browser {@code EventSource}. A
 *       {@code reconnect} event (maximum duration or shutdown) or a dropped connection means: reconnect with
 *       {@code Last-Event-ID}. A {@code stream-error} event carries a stable code and ends the stream.</li>
 *   <li><b>Security.</b> The request authenticates like any other, with a session cookie or an
 *       {@code Authorization} header; a credential in the URL is refused. The run must belong to the principal's
 *       workspace (otherwise 404), and the principal is re-checked while the stream is open.</li>
 *   <li><b>Limits.</b> Too many open streams on the node, in the workspace, or for the principal is 429
 *       {@code STREAM_LIMIT_EXCEEDED} with {@code Retry-After} (an SSE client gets a {@code reconnect} event
 *       instead); see {@link RunEventStreams} for every bound. The run and the position are checked first, so a
 *       missing run is still 404 and a caught-up terminal run still 204 at the limit.</li>
 *   <li><b>Client stream id.</b> An optional {@code client} parameter (1 to 64 letters, digits, or hyphens, such as
 *       a UUID an {@code EventSource} is created with) identifies the client across its reconnects; a browser
 *       {@code EventSource} reconnects to the same URL, so it keeps it. A reconnect (a client id and
 *       {@code Last-Event-ID}) supersedes that client's earlier streams of the run, whose connections must be dead;
 *       nothing else is ever superseded. A malformed id is 400 {@code INVALID_CLIENT_ID}.</li>
 *   <li><b>Busy.</b> The admission probe waits briefly for a stream read slot; when none frees up, an SSE client gets
 *       a {@code reconnect} with reason {@code STREAM_BUSY}, any other caller 503 {@code STREAM_BUSY}, both with
 *       {@code Retry-After}.</li>
 * </ul>
 */
@RestController
@RequestMapping(path = "/api/v1")
public final class RunStreamController {
    public static final MediaType EVENT_STREAM = MediaType.TEXT_EVENT_STREAM;
    private static final Pattern EVENT_ID = Pattern.compile(
            "([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}):(0|[1-9][0-9]{0,18})");
    private static final int MAX_EVENT_ID_LENGTH = 64;
    /** Allowance past the stream's own maximum duration before the container times the response out. */
    private static final Duration CONTAINER_TIMEOUT_MARGIN = Duration.ofMinutes(1);

    private final DurableRunEngine engine;
    private final RunEventStreams streams;
    private final WorkspaceScopeResolver workspaces;
    private final PrincipalRevalidation revalidation;

    public RunStreamController(DurableRunEngine engine, RunEventStreams streams, WorkspaceScopeResolver workspaces,
            PrincipalRevalidation revalidation) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.streams = Objects.requireNonNull(streams, "streams");
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
        this.revalidation = Objects.requireNonNull(revalidation, "revalidation");
    }

    @GetMapping("/runs/{id}/stream")
    public ResponseEntity<SseEmitter> stream(HttpServletRequest request, @PathVariable UUID id,
            @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "client", required = false) String client) {
        WorkspaceId workspace = workspaces.resolve(request);
        AuthenticatedPrincipal principal = CurrentPrincipal.require();
        long after = position(id, lastEventId, cursor);
        if (client != null && !RunEventStreams.isClientId(client)) {
            throw new ApiRequestException(HttpStatus.BAD_REQUEST, "INVALID_CLIENT_ID",
                    "client must be 1 to 64 letters, digits, or hyphens, such as a UUID.");
        }
        if (!streams.isRunning()) {
            // A stopping node reads nothing: the client reconnects, and the next node answers it.
            return reconnectLater(request, id, after, RunEventStreams.EndReason.SHUTDOWN.name(),
                    streams.settings().clientRetry());
        }
        // Fails fast when the release filter does not wrap this request: without it the stream could use the response
        // while filters still do (#192).
        RunStreamReleaseFilter.Pending pending = RunStreamReleaseFilter.require(request);
        SseEmitter emitter;
        EmitterSink sink;
        RunEventStreams.Subscription subscription;
        try {
            // Validates the run (404 across workspaces) and the position before any byte of the stream is sent,
            // before any limit is applied, and before anything is superseded. So a client at its stream limit still
            // gets the answers that stop an EventSource (204, 404, 409, 410) rather than a reconnect forever, and a
            // refused reconnect never costs the client its running stream. The probe is one bounded read (the run and
            // at most three events), takes no stream slot, and shares the streams' bound on concurrent reads.
            EventPage probe = streams.boundedRead(() -> RunAdmissionController.eventPage(engine, workspace, id, after, 1));
            if (probe.run().lifecycle().terminal() && after >= probe.run().summary().eventSequence()) {
                // Nothing is left to deliver; 204 tells an EventSource to stop reconnecting.
                return ResponseEntity.noContent().build();
            }
            // Building the emitter only assigns its timeout: nothing is registered or written until it is returned,
            // so one discarded on a refused admission costs nothing (#186, #203).
            emitter = new SseEmitter(streams.settings().maxDuration().plus(CONTAINER_TIMEOUT_MARGIN).toMillis());
            // The stream writes nothing until the request thread has left the filter chain (see EmitterSink). A
            // reconnect (a client stream id and Last-Event-ID) supersedes that client's earlier streams of the run,
            // whose connections must be dead since the client reconnected; nothing else is superseded.
            subscription = streams.openDeferred(workspace, id, after, client, lastEventId != null,
                    principal.subjectId(),
                    () -> authorized(principal, workspace), sink = new EmitterSink(emitter));
        } catch (RunEventStreams.StreamsStoppedException stopping) {
            return reconnectLater(request, id, after, RunEventStreams.EndReason.SHUTDOWN.name(),
                    streams.settings().clientRetry());
        } catch (StreamLimitExceededException full) {
            return reconnectLater(request, id, after, "STREAM_LIMIT_EXCEEDED", LIMIT_RETRY);
        } catch (RunEventStreams.StreamsBusyException busy) {
            return reconnectLater(request, id, after, "STREAM_BUSY", streams.settings().clientRetry());
        }
        sink.watch(emitter, subscription::cancel);
        pending.hold(async -> sink.released(async, subscription::cancel, subscription::ready));
        return ResponseEntity.ok().contentType(EVENT_STREAM)
                // Reverse proxies must not buffer the stream, and a reconnect opens a new connection, so a node that
                // is draining is not reused for it.
                .header("X-Accel-Buffering", "no").header(HttpHeaders.CONNECTION, "close").body(emitter);
    }

    /** How long a client refused for stream limits waits before it tries again. */
    static final Duration LIMIT_RETRY = Duration.ofSeconds(10);

    /**
     * Answers a stream that cannot be served now. An SSE client (one that accepts {@code text/event-stream}) gets 200
     * with a {@code retry} hint and a single {@code reconnect} event naming the reason, because any other status stops
     * a browser {@code EventSource} for good. Every other caller gets 429 {@code STREAM_LIMIT_EXCEEDED} for stream
     * limits, 503 {@code STREAM_BUSY} when no stream read is free, or, while the node stops, the same reconnect; all
     * carry {@code Retry-After} (at least one second).
     */
    private ResponseEntity<SseEmitter> reconnectLater(HttpServletRequest request, UUID runId, long after, String reason,
            Duration retry) {
        String retryAfter = Long.toString(Math.max(1, retry.toSeconds()));
        if ("STREAM_LIMIT_EXCEEDED".equals(reason) && !acceptsEventStream(request)) {
            throw new ApiRequestException(HttpStatus.TOO_MANY_REQUESTS, "STREAM_LIMIT_EXCEEDED",
                    "Too many event streams are open; retry later.", Map.of(), Map.of(HttpHeaders.RETRY_AFTER, retryAfter));
        }
        if ("STREAM_BUSY".equals(reason) && !acceptsEventStream(request)) {
            throw new ApiRequestException(HttpStatus.SERVICE_UNAVAILABLE, "STREAM_BUSY",
                    "The event streams are busy reading; retry shortly.", Map.of(),
                    Map.of(HttpHeaders.RETRY_AFTER, retryAfter));
        }
        SseEmitter reconnect = new SseEmitter(CONTAINER_TIMEOUT_MARGIN.toMillis());
        try {
            reconnect.send(SseEmitter.event().reconnectTime(retry.toMillis()));
            reconnect.send(SseEmitter.event().name(RunEventStreams.RECONNECT).data(
                    new RunEventStreams.Reconnect(runId, after, reason), MediaType.APPLICATION_JSON));
            reconnect.complete();
        } catch (IOException gone) {
            reconnect.completeWithError(gone);
        }
        return ResponseEntity.ok().contentType(EVENT_STREAM).header(HttpHeaders.CONNECTION, "close")
                .header(HttpHeaders.RETRY_AFTER, retryAfter)
                .header("X-Accel-Buffering", "no").body(reconnect);
    }

    private static boolean acceptsEventStream(HttpServletRequest request) {
        String accept = request.getHeader(HttpHeaders.ACCEPT);
        if (accept == null) return false;
        try {
            return MediaType.parseMediaTypes(accept).stream()
                    .anyMatch(type -> type.getQualityValue() > 0 && EVENT_STREAM.isCompatibleWith(type)
                            && !type.isWildcardType());
        } catch (org.springframework.util.InvalidMimeTypeException malformed) {
            return false;
        }
    }

    /** True while the stream's principal may still read the run's workspace. */
    private boolean authorized(AuthenticatedPrincipal principal, WorkspaceId workspace) {
        return revalidation.revalidate(principal)
                .filter(current -> current.subjectId().equals(principal.subjectId()))
                .filter(current -> current.workspace().map(workspace::equals).orElse(false))
                .map(current -> current.can(WorkspacePermission.WORKSPACE_READ)).orElse(false);
    }

    /** The sequence to continue after: {@code Last-Event-ID} when present, else {@code cursor}, else the start. */
    static long position(UUID runId, String lastEventId, String cursor) {
        if (lastEventId != null) {
            String value = lastEventId.strip();
            Matcher matcher = value.length() > MAX_EVENT_ID_LENGTH ? null : EVENT_ID.matcher(value);
            if (matcher == null || !matcher.matches()) {
                throw new ApiRequestException(HttpStatus.BAD_REQUEST, "INVALID_LAST_EVENT_ID",
                        "Last-Event-ID must be an event id this stream sent: <runId>:<sequence>.");
            }
            if (!matcher.group(1).equals(runId.toString())) {
                throw new ApiRequestException(HttpStatus.BAD_REQUEST, "LAST_EVENT_ID_MISMATCH",
                        "Last-Event-ID belongs to another stream.");
            }
            try {
                return Long.parseLong(matcher.group(2));
            } catch (NumberFormatException overflow) {
                throw new ApiRequestException(HttpStatus.BAD_REQUEST, "INVALID_LAST_EVENT_ID",
                        "Last-Event-ID must be an event id this stream sent: <runId>:<sequence>.");
            }
        }
        return cursor == null ? 0 : PageCursor.sequence(cursor);
    }

    /**
     * Writes stream output as Server-Sent Events, and owns the response only while the request thread does not (#192).
     *
     * <ul>
     *   <li><b>Start.</b> Nothing is written or completed until {@link #writable()}, which the
     *       {@link RunStreamReleaseFilter} calls once the request thread has left the whole filter chain (Spring
     *       Security's {@code HeaderWriterFilter} writes headers there after the controller returns). The gate is here,
     *       in the one place every write passes, so no path of the stream (a supersession, a shutdown, or its start
     *       timeout) can write or complete before it.</li>
     *   <li><b>End.</b> Once a write failed or the container reported the connection failed, the container ends the
     *       response through its own error notification, so the stream must not complete it as well: a completion
     *       racing that notification left Tomcat without a dispatch and made it render an error page into the already
     *       committed stream, logged as an error.</li>
     * </ul>
     */
    static final class EmitterSink implements RunEventStreams.Sink {
        /**
         * The longest the stream waits, in all, for the request thread to leave the filter chain, over its start, its
         * writes, and its completion.
         */
        static final Duration WRITABLE_TIMEOUT = Duration.ofSeconds(30);

        private final SseEmitter emitter;
        private final java.util.concurrent.CountDownLatch writable = new java.util.concurrent.CountDownLatch(1);
        private final long writableDeadline;
        /**
         * The backstop on how long a container error or timeout waits for the stream's completion already under way.
         * The wait itself is limited by Spring (7.0.9), not by this bound (#203): on an error, Spring takes the request
         * state lock and sets the state to ERROR before it calls back, so a completion whose flush waits for that lock
         * gives up within its 500 ms lock attempt, sets its result, and dispatches; on a timeout Spring holds no lock,
         * and the flush of a stream whose every event was already flushed writes nothing. A generous bound keeps the
         * callback waiting for the whole completion, so Spring never skips its own dispatch while the stream's is
         * still to come (#200); a short one would let that race through.
         */
        static final Duration COMPLETION_WAIT = Duration.ofSeconds(5);
        private static final int OPEN = 0;
        private static final int CONTAINER_ENDS = 1;
        private static final int STREAM_ENDS = 2;

        /**
         * Who ends the response, decided once by compare-and-set (#192, #200): the container (its completion, timeout,
         * or error callback, or a failed write) or the stream (its own completion). No lock is held while the emitter
         * is completed, so the container's callbacks, which run under Spring's and Tomcat's own locks, can never wait
         * on a lock the completion holds.
         */
        private final java.util.concurrent.atomic.AtomicInteger owner = new java.util.concurrent.atomic.AtomicInteger();
        /** Counted down once the stream's completion of the emitter (and the dispatch it requests) has returned. */
        private final java.util.concurrent.CountDownLatch completedByStream = new java.util.concurrent.CountDownLatch(1);
        private final Duration completionWait;

        EmitterSink(SseEmitter emitter) {
            this(emitter, WRITABLE_TIMEOUT, COMPLETION_WAIT);
        }

        EmitterSink(SseEmitter emitter, Duration writableTimeout, Duration completionWait) {
            this.emitter = emitter;
            this.writableDeadline = System.nanoTime() + writableTimeout.toNanos();
            this.completionWait = completionWait;
        }

        /**
         * Registers the container's completion, timeout, and error callbacks: after any of them the container ends the
         * response, and the stream stops ({@code cancel}).
         */
        void watch(SseEmitter watched, Runnable cancel) {
            // The request already completed: the flag is enough, and nothing ever waits here.
            watched.onCompletion(() -> {
                containerEnds();
                cancel.run();
            });
            // A timeout or an error that finds the stream completing waits until that completion has requested its
            // dispatch: once the stream set its result, Spring skips its own timeout or error dispatch, so Tomcat would
            // otherwise find the request without one. No lock of ours is held here, and the completion never waits
            // long for a lock the container holds: on an error Spring has set the state to ERROR first, so the
            // completion's flush gives up within its 500 ms lock attempt (see COMPLETION_WAIT, only a backstop).
            watched.onTimeout(() -> {
                containerEndsAfterCompletion();
                cancel.run();
            });
            watched.onError(failure -> {
                containerEndsAfterCompletion();
                cancel.run();
            });
        }

        private void containerEndsAfterCompletion() {
            if (containerEnds()) return;
            try {
                completedByStream.await(completionWait.toNanos(), java.util.concurrent.TimeUnit.NANOSECONDS);
            } catch (InterruptedException stopping) {
                // Consumed, not restored (#203): this container thread goes on to dispatch and write the response, and
                // an interrupted thread's NIO write closes the connection. The wait simply ends early.
            }
        }

        /**
         * The request thread left the filter chain. A request that did not go asynchronous, or whose chain failed, has
         * no response for the stream: the container ends it, and the stream is cancelled before it starts.
         */
        void released(boolean async, Runnable cancel, Runnable ready) {
            if (!async) {
                containerEnds();
                cancel.run();
            }
            writable();
            ready.run();
        }

        /** The request thread no longer uses the response: the stream may write to it and complete it. */
        void writable() {
            writable.countDown();
        }

        /**
         * Whether the response is writable, waiting at most until the stream's overall deadline. Once it is writable,
         * an interrupt no longer matters. Nothing interrupts a stream's thread (#200); an interrupt that arrives all the
         * same is consumed rather than restored (#203), as {@link RunEventStreams#boundedRead} does, because the write
         * that may follow would close the connection of an interrupted thread (NIO).
         */
        private boolean awaitWritable() {
            while (writable.getCount() != 0) {
                long remaining = writableDeadline - System.nanoTime();
                if (remaining <= 0) return false;
                try {
                    return writable.await(remaining, java.util.concurrent.TimeUnit.NANOSECONDS);
                } catch (InterruptedException consumed) {
                    // Keeps waiting until the deadline; the interrupt is not restored.
                }
            }
            return true;
        }

        /**
         * The container ends the response itself (it reported a failure, a timeout, or its completion). Returns whether
         * the container now owns the end, or {@code false} when the stream had already taken it.
         */
        boolean containerEnds() {
            return owner.compareAndSet(OPEN, CONTAINER_ENDS) || owner.get() == CONTAINER_ENDS;
        }

        @Override
        public void retry(Duration retry) throws IOException {
            send(SseEmitter.event().reconnectTime(retry.toMillis()));
        }

        @Override
        public void position(String id) throws IOException {
            // An id field and no data: the browser records the id and dispatches no event.
            send(SseEmitter.event().id(id));
        }

        @Override
        public void event(String id, String name, Object data) throws IOException {
            SseEmitter.SseEventBuilder event = SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON);
            if (id != null) event.id(id);
            send(event);
        }

        @Override
        public void comment(String text) throws IOException {
            send(SseEmitter.event().comment(text));
        }

        private void send(SseEmitter.SseEventBuilder event) throws IOException {
            if (!awaitWritable()) throw new IOException("the response never became writable");
            try {
                emitter.send(event);
            } catch (IOException failed) {
                // A failed write already told the container, which ends the response.
                containerEnds();
                throw failed;
            } catch (IllegalStateException unusable) {
                // An emitter that refuses a write because the container's error or timeout handling completed it
                // (no cause, or an I/O cause) is ended by the container. Any other failure, such as an event that
                // cannot be serialized, leaves the response to the stream, which completes it.
                Throwable cause = unusable.getCause();
                if (cause == null || cause instanceof IOException) containerEnds();
                throw unusable;
            }
        }

        @Override
        public void complete() {
            // Waits for the request thread too. A response that never became writable is not completed here, since the
            // request thread may still be using it; the container's timeout of the asynchronous request ends it.
            if (!awaitWritable()) return;
            if (!owner.compareAndSet(OPEN, STREAM_ENDS)) return;
            try {
                emitter.complete();
            } finally {
                completedByStream.countDown();
            }
        }
    }
}
