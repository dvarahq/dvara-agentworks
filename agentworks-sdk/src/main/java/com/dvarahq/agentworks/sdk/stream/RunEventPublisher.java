package com.dvarahq.agentworks.sdk.stream;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/** Flow publisher with exact-sequence resume, bounded reconnect, cancellation, and demand-aware delivery. */
public final class RunEventPublisher implements Flow.Publisher<TypedRunEvent> {
    private final String runId;
    private final RunStreamOptions options;
    private final RunStreamTransport transport;

    public RunEventPublisher(String runId, RunStreamOptions options, RunStreamTransport transport) {
        this.runId = requireId(runId);
        this.options = Objects.requireNonNull(options, "options");
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    @Override public void subscribe(Flow.Subscriber<? super TypedRunEvent> subscriber) {
        Objects.requireNonNull(subscriber, "subscriber");
        StreamSubscription subscription = new StreamSubscription(subscriber);
        subscriber.onSubscribe(subscription);
        subscription.start();
    }

    private final class StreamSubscription implements Flow.Subscription, Runnable {
        private final Flow.Subscriber<? super TypedRunEvent> subscriber;
        private final AtomicLong demand = new AtomicLong();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private volatile Thread worker;
        private volatile RunStreamTransport.Connection connection;
        private long sequence = options.afterSequence();
        private int reconnects;

        private StreamSubscription(Flow.Subscriber<? super TypedRunEvent> subscriber) { this.subscriber = subscriber; }
        private void start() { worker = Thread.ofVirtual().name("agentworks-run-stream-" + runId).start(this); }

        @Override public void request(long count) {
            if (count <= 0) {
                fail(new IllegalArgumentException("Flow request must be positive"));
                return;
            }
            demand.getAndUpdate(current -> current > Long.MAX_VALUE - count ? Long.MAX_VALUE : current + count);
            LockSupport.unpark(worker);
        }

        @Override public void cancel() {
            if (cancelled.compareAndSet(false, true)) {
                RunStreamTransport.Connection active = connection;
                if (active != null) active.close();
                Thread thread = worker;
                if (thread != null) thread.interrupt();
            }
        }

        @Override public void run() {
            try {
                while (!cancelled.get()) {
                    awaitDemand();
                    if (cancelled.get()) return;
                    try (RunStreamTransport.Connection opened = transport.open(runId, options, sequence)) {
                        connection = opened;
                        if (opened.status() == 204) { complete(); return; }
                        if (opened.status() < 200 || opened.status() >= 300) {
                            throw new RunStreamException("STREAM_HTTP_ERROR", "stream refused with HTTP " + opened.status());
                        }
                        ReadOutcome outcome = read(opened.reader());
                        if (outcome == ReadOutcome.COMPLETE || cancelled.get()) return;
                    } finally {
                        connection = null;
                    }
                    if (options.maxReconnects() >= 0 && reconnects++ >= options.maxReconnects()) {
                        throw new RunStreamException("STREAM_RECONNECT_EXHAUSTED", "stream reconnect limit reached");
                    }
                    if (!options.reconnectDelay().isZero()) Thread.sleep(options.reconnectDelay());
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                if (!cancelled.get()) fail(new RunStreamException("STREAM_INTERRUPTED", "stream interrupted"));
            } catch (RunStreamException exception) {
                fail(exception);
            } catch (IOException exception) {
                fail(new RunStreamException("STREAM_IO_ERROR", "stream transport failed", exception));
            } catch (RuntimeException exception) {
                fail(new RunStreamException("STREAM_PROTOCOL_ERROR", "stream protocol failed", exception));
            }
        }

        private ReadOutcome read(BufferedReader reader) throws IOException, InterruptedException {
            String id = null;
            String event = null;
            StringBuilder data = new StringBuilder();
            String line;
            while (!cancelled.get() && (line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    if (event != null || id != null || !data.isEmpty()) {
                        DispatchOutcome outcome = dispatch(id, event, data.toString());
                        if (outcome == DispatchOutcome.COMPLETE) return ReadOutcome.COMPLETE;
                        if (outcome == DispatchOutcome.RECONNECT) return ReadOutcome.RECONNECT;
                    }
                    id = null; event = null; data.setLength(0); continue;
                }
                if (line.startsWith(":")) continue;
                int colon = line.indexOf(':');
                String field = colon < 0 ? line : line.substring(0, colon);
                String value = colon < 0 ? "" : line.substring(colon + 1);
                if (value.startsWith(" ")) value = value.substring(1);
                switch (field) {
                    case "id" -> id = value;
                    case "event" -> event = value;
                    case "data" -> { if (!data.isEmpty()) data.append('\n'); data.append(value); }
                    default -> { }
                }
            }
            return ReadOutcome.EOF;
        }

        private DispatchOutcome dispatch(String id, String eventName, String data) throws InterruptedException {
            String name = eventName == null || eventName.isBlank() ? "message" : eventName;
            JsonNode payload = data.isBlank() ? JsonNodeFactory.instance.objectNode() : parse(data);
            TypedRunEvent.Kind kind = switch (name) {
                case "stream-end" -> TypedRunEvent.Kind.STREAM_END;
                case "reconnect" -> TypedRunEvent.Kind.RECONNECT;
                case "stream-error" -> TypedRunEvent.Kind.STREAM_ERROR;
                default -> TypedRunEvent.Kind.RUN_EVENT;
            };
            long nextSequence = 0;
            if (kind == TypedRunEvent.Kind.RUN_EVENT) {
                nextSequence = parseSequence(id);
                if (nextSequence <= sequence) return DispatchOutcome.CONTINUE;
                if (nextSequence != sequence + 1) {
                    throw new RunStreamException("STREAM_SEQUENCE_GAP",
                            "expected sequence " + (sequence + 1) + " but received " + nextSequence);
                }
            }
            emit(new TypedRunEvent(kind, name, runId, nextSequence, payload, Instant.now()));
            if (kind == TypedRunEvent.Kind.RUN_EVENT) sequence = nextSequence;
            if (kind == TypedRunEvent.Kind.STREAM_END) { complete(); return DispatchOutcome.COMPLETE; }
            if (kind == TypedRunEvent.Kind.STREAM_ERROR) {
                throw new RunStreamException("STREAM_SERVER_ERROR", "server ended the stream with an error");
            }
            return kind == TypedRunEvent.Kind.RECONNECT ? DispatchOutcome.RECONNECT : DispatchOutcome.CONTINUE;
        }

        private JsonNode parse(String data) {
            try { return JsonMapper.builder().build().readTree(data); }
            catch (RuntimeException exception) { throw new RunStreamException("STREAM_INVALID_JSON", "stream data is not valid JSON", exception); }
        }

        private long parseSequence(String id) {
            if (id == null) throw new RunStreamException("STREAM_ID_MISSING", "run event has no id");
            int separator = id.lastIndexOf(':');
            if (separator <= 0 || !runId.equals(id.substring(0, separator))) {
                throw new RunStreamException("STREAM_ID_MISMATCH", "run event id does not match the requested run");
            }
            try { return Long.parseLong(id.substring(separator + 1)); }
            catch (NumberFormatException exception) { throw new RunStreamException("STREAM_ID_INVALID", "run event sequence is invalid"); }
        }

        private void emit(TypedRunEvent event) throws InterruptedException {
            awaitDemand();
            if (cancelled.get()) return;
            subscriber.onNext(event);
            demand.updateAndGet(current -> current == Long.MAX_VALUE ? current : current - 1);
        }

        private void awaitDemand() throws InterruptedException {
            while (!cancelled.get() && demand.get() == 0) {
                LockSupport.parkNanos(this, 10_000_000L);
                if (Thread.interrupted()) throw new InterruptedException();
            }
        }

        private void complete() {
            if (cancelled.compareAndSet(false, true)) subscriber.onComplete();
        }

        private void fail(Throwable error) {
            if (cancelled.compareAndSet(false, true)) {
                RunStreamTransport.Connection active = connection;
                if (active != null) active.close();
                subscriber.onError(error);
            }
        }
    }

    private enum ReadOutcome { EOF, RECONNECT, COMPLETE }
    private enum DispatchOutcome { CONTINUE, RECONNECT, COMPLETE }

    private static String requireId(String value) {
        if (value == null || value.isBlank() || value.length() > 128 || value.indexOf('/') >= 0) {
            throw new IllegalArgumentException("runId");
        }
        return value;
    }
}
