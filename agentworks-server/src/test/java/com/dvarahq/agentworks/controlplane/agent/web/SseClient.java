package com.dvarahq.agentworks.controlplane.agent.web;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * A minimal Server-Sent Events client for stream contract tests (issue #35). It parses the wire format exactly as a
 * browser {@code EventSource} does (fields {@code id}, {@code event}, {@code data}, {@code retry}, and comments) on a
 * reader thread, so a test can read with timeouts and disconnect at any point.
 */
final class SseClient implements AutoCloseable {
    static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Event END = new Event(null, "<eof>", null, null);

    /** One dispatched event or comment; {@code comment} is set only for comment lines. */
    record Event(String id, String name, String data, String comment) {
        JsonNode json() {
            return JSON.readTree(data);
        }

        long sequence() {
            return Long.parseLong(id.substring(id.indexOf(':') + 1));
        }
    }

    private final HttpResponse<InputStream> response;
    private final BlockingQueue<Event> events = new LinkedBlockingQueue<>();
    private final Thread reader;
    private volatile Long retry;
    private volatile String lastEventId;
    /** Guards waiting for the last event id without spinning. */
    private final Object progress = new Object();
    private boolean finished;

    private SseClient(HttpResponse<InputStream> response) {
        this.response = response;
        this.reader = Thread.ofVirtual().start(this::read);
    }

    static SseClient open(int port, String path, String bearer, Map<String, String> headers) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Accept", "text/event-stream").GET();
        if (bearer != null) request.header("Authorization", "Bearer " + bearer);
        headers.forEach(request::header);
        try {
            HttpResponse<InputStream> response = HttpClient.newHttpClient().send(request.build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            return new SseClient(response);
        } catch (IOException failed) {
            throw new java.io.UncheckedIOException(failed);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    int status() {
        return response.statusCode();
    }

    String header(String name) {
        return response.headers().firstValue(name).orElse(null);
    }

    Long retry() {
        return retry;
    }

    /** The id a browser would send as {@code Last-Event-ID} on its reconnect, or {@code null}. */
    String lastEventId() {
        return lastEventId;
    }

    /** Waits for the first frame that sets the last event id (the stream's opening position frame) and returns it. */
    String awaitLastEventId(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        synchronized (progress) {
            while (lastEventId == null && !finished) {
                long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (remaining <= 0) break;
                try {
                    progress.wait(remaining);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            return lastEventId;
        }
    }

    /** Wakes a caller waiting for the last event id or for the end of the stream. */
    private void progressed(Runnable change) {
        synchronized (progress) {
            change.run();
            progress.notifyAll();
        }
    }

    /** The whole body of a non-stream response, such as a problem document. */
    JsonNode problem() {
        List<Event> rest = drain(Duration.ofSeconds(5));
        StringBuilder text = new StringBuilder();
        rest.forEach(event -> {
            if (event.data() != null) text.append(event.data());
        });
        return JSON.readTree(text.toString());
    }

    /** The next event (comments excluded), or {@code null} when none arrives in time or the stream ended. */
    Event next(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            long wait = deadline - System.nanoTime();
            if (wait <= 0) return null;
            Event event;
            try {
                event = events.poll(wait, TimeUnit.NANOSECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return null;
            }
            if (event == null || event == END) {
                if (event == END) events.add(END);
                return null;
            }
            if (event.comment() == null) return event;
        }
    }

    /** The next event or comment, or {@code null} when none arrives in time or the stream ended. */
    Event nextIncludingComments(Duration timeout) {
        try {
            Event event = events.poll(timeout.toNanos(), TimeUnit.NANOSECONDS);
            if (event == END) {
                events.add(END);
                return null;
            }
            return event;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** Every event (comments excluded) until the server ends the stream or {@code timeout} passes. */
    List<Event> drain(Duration timeout) {
        List<Event> drained = new ArrayList<>();
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            Event event;
            try {
                event = events.poll(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
            if (event == null) break;
            if (event == END) {
                events.add(END);
                return drained;
            }
            if (event.comment() == null || event.data() != null) drained.add(event);
        }
        return drained;
    }

    /** True once the server has closed the stream. */
    boolean ended(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (events.contains(END)) return true;
            try {
                Thread.sleep(10);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return events.contains(END);
    }

    @Override
    public void close() {
        try {
            response.body().close();
        } catch (IOException ignored) {
            // The connection is abandoned either way.
        }
        reader.interrupt();
    }

    private void read() {
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        try (BufferedReader lines = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            if (!contentType.startsWith("text/event-stream")) {
                StringBuilder body = new StringBuilder();
                String line;
                while ((line = lines.readLine()) != null) body.append(line);
                events.add(new Event(null, null, body.toString(), "body"));
                return;
            }
            String id = null;
            String name = null;
            StringBuilder data = null;
            String line;
            while ((line = lines.readLine()) != null) {
                if (line.isEmpty()) {
                    // As a browser does, a frame sets the last event id even when it dispatches no event.
                    if (id != null) {
                        String next = id;
                        progressed(() -> lastEventId = next);
                    }
                    if (data != null) events.add(new Event(id, name == null ? "message" : name, data.toString(), null));
                    id = null;
                    name = null;
                    data = null;
                    continue;
                }
                if (line.startsWith(":")) {
                    events.add(new Event(null, null, null, line.substring(1)));
                    continue;
                }
                int colon = line.indexOf(':');
                String field = colon < 0 ? line : line.substring(0, colon);
                String value = colon < 0 ? "" : line.substring(colon + 1);
                if (value.startsWith(" ")) value = value.substring(1);
                switch (field) {
                    case "id" -> id = value;
                    case "event" -> name = value;
                    case "data" -> data = data == null ? new StringBuilder(value) : data.append('\n').append(value);
                    case "retry" -> retry = Long.parseLong(value);
                    default -> { }
                }
            }
        } catch (IOException | RuntimeException closed) {
            // The connection ended.
        } finally {
            events.add(END);
            progressed(() -> finished = true);
        }
    }
}
