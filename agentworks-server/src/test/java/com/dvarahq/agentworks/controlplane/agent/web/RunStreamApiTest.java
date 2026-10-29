package com.dvarahq.agentworks.controlplane.agent.web;

import com.dvarahq.agentworks.domain.identity.WorkspaceRole;
import com.dvarahq.agentworks.domain.run.ClaimScope;
import com.dvarahq.agentworks.domain.run.RunEventType;
import com.dvarahq.agentworks.domain.run.RunLease;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import com.dvarahq.agentworks.DvaraAgentWorksApplication;
import com.dvarahq.agentworks.identity.TestIdentities;
import com.dvarahq.agentworks.runplane.execution.DurableRunEngine;
import com.dvarahq.agentworks.runplane.execution.DurableRunEngine.EventDraft;
import com.dvarahq.agentworks.runplane.execution.ReleaseManifestReader;
import com.dvarahq.agentworks.runplane.stream.RunEventStreams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reconnectable typed run event stream (issue #35) on the shipped application over real HTTP: typed envelopes,
 * gap-free and duplicate-free resume by {@code Last-Event-ID}, termination at the terminal event, the typed resume
 * refusals, workspace isolation, authorization re-checks while a stream is open, stream limits, heartbeats, and the
 * maximum duration. Runs are driven through the durable engine directly, so every event is deterministic.
 */
@SpringBootTest(classes = DvaraAgentWorksApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"agentworks.runs.streams.poll-interval=PT0.05S",
                "agentworks.runs.streams.reauthorize-interval=PT0.2S",
                "agentworks.runs.streams.heartbeat-interval=PT0.3S",
                "agentworks.runs.streams.max-duration=PT4S",
                "agentworks.runs.streams.batch-size=4",
                "agentworks.runs.streams.max-streams-per-subject=3"})
@Import(TestWorkspaces.class)
class RunStreamApiTest {
    private static final String JSON = "application/json";
    private static final Duration WAIT = Duration.ofSeconds(5);

    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    DurableRunEngine engine;
    @Autowired
    RunEventStreams streams;
    @Autowired
    TestIdentities identities;
    private AgentApiClient api;
    private UUID workspace;
    private String deployment;
    private String token;

    @BeforeEach
    void setUp() {
        api = new AgentApiClient(port);
        workspace = TestWorkspaces.createWorkspace(jdbc);
        token = identities.token(workspace);
        String environment = api.post("/api/v1/environments", JSON, "{\"name\":\"prod\"}", workspace).body()
                .path("id").asString();
        String definition = api.post("/api/v1/agents", JSON, "{\"name\":\"agent\"}", workspace).body().path("id").asString();
        String version = api.post("/api/v1/agents/" + definition + "/versions", "application/yaml",
                "apiVersion: dvarahq.com/v1alpha1\nkind: Agent\nmetadata: {name: agent, version: 1.0.0}\n"
                        + "spec:\n  executor: {type: spring-ai}\n", workspace).body().path("id").asString();
        String release = api.post("/api/v1/agent-versions/" + version + "/releases", JSON, "{\"name\":\"agent-1\"}",
                workspace).body().path("id").asString();
        deployment = api.send("POST", "/api/v1/environments/" + environment + "/promotions",
                "{\"deployment\":\"api\",\"releaseId\":\"" + release + "\"}", workspace, Map.of()).body()
                .path("deploymentId").asString();
    }

    @Test
    void aStreamDeliversEveryTypedEnvelopeInOrderAndEndsAtTheTerminalEvent() {
        UUID run = submit();
        RunLease lease = claim(run);
        lease = emit(lease, 6);
        engine.complete(lease, Map.of("answer", 42));
        long last = engine.run(new WorkspaceId(workspace), run).summary().eventSequence();
        var view = api.get("/api/v1/runs/" + run, workspace).body();

        try (SseClient stream = open(run, Map.of())) {
            assertThat(stream.status()).isEqualTo(200);
            assertThat(stream.header("Content-Type")).startsWith("text/event-stream");
            assertThat(stream.header("X-Accel-Buffering")).isEqualTo("no");
            assertThat(stream.header("Cache-Control")).contains("no-store");
            List<SseClient.Event> events = stream.drain(WAIT);
            assertThat(stream.ended(WAIT)).isTrue();
            assertThat(stream.retry()).isEqualTo(3000L);

            List<SseClient.Event> runEvents = events.subList(0, events.size() - 1);
            assertThat(runEvents).extracting(SseClient.Event::sequence)
                    .containsExactlyElementsOf(LongStream.rangeClosed(1, last).boxed().toList());
            for (SseClient.Event event : runEvents) {
                var envelope = event.json();
                assertThat(event.id()).isEqualTo(run + ":" + envelope.path("sequence").asLong());
                assertThat(event.name()).isEqualTo(envelope.path("type").asString());
                assertThat(envelope.path("envelopeVersion").asInt()).isEqualTo(1);
                assertThat(envelope.path("runId").asString()).isEqualTo(run.toString());
                assertThat(envelope.path("workspaceId").asString()).isEqualTo(workspace.toString());
                assertThat(envelope.path("releaseId").asString()).isEqualTo(view.path("releaseId").asString());
                assertThat(envelope.path("deploymentId").asString()).isEqualTo(deployment);
                assertThat(envelope.path("revisionId").asString()).isEqualTo(view.path("revisionId").asString());
                assertThat(envelope.path("cohortId").asString()).isEqualTo(view.path("cohortId").asString());
                assertThat(envelope.path("traceId").asString()).isEqualTo(view.path("traceId").asString());
                assertThat(envelope.path("schemaVersion").asInt()).isEqualTo(1);
                assertThat(envelope.path("servedSchemaVersion").asInt()).isEqualTo(1);
                assertThat(envelope.path("eventDigest").asString()).startsWith("sha256:");
            }
            assertThat(runEvents.getLast().name()).isEqualTo("RUN_SUCCEEDED");
            SseClient.Event end = events.getLast();
            assertThat(end.name()).isEqualTo("stream-end");
            assertThat(end.id()).isNull();
            assertThat(end.json().path("lastSequence").asLong()).isEqualTo(last);
            assertThat(end.json().path("lifecycle").asString()).isEqualTo("SUCCEEDED");
            // The stream and the event pages serve the same envelopes.
            var page = api.get("/api/v1/runs/" + run + "/events?limit=200", workspace).body();
            assertThat(page.path("items").get(2)).isEqualTo(runEvents.get(2).json());
        }
        // The completed stream released its slot.
        UUID subject = identities.member(workspace, WorkspaceRole.ADMIN).userId();
        assertThat(streams.open(subject)).isZero();
    }

    @Test
    void aLiveStreamFollowsTheRunAsItProgressesAndEndsWhenItEnds() {
        UUID run = submit();
        try (SseClient stream = open(run, Map.of())) {
            assertThat(stream.next(WAIT).name()).isEqualTo("RUN_CREATED");
            assertThat(stream.next(WAIT).name()).isEqualTo("RUN_QUEUED");
            assertThat(stream.next(Duration.ofMillis(300))).isNull();

            RunLease lease = claim(run);
            assertThat(stream.next(WAIT).name()).isEqualTo("RUN_ACTIVATED");
            lease = emit(lease, 3);
            for (int chunk = 0; chunk < 3; chunk++) {
                assertThat(stream.next(WAIT).name()).isEqualTo("MODEL_STREAM_CHUNK");
            }
            engine.complete(lease, Map.of());
            List<String> rest = stream.drain(WAIT).stream().map(SseClient.Event::name).toList();
            assertThat(rest).endsWith("RUN_SUCCEEDED", "stream-end");
            assertThat(stream.ended(WAIT)).isTrue();
        }
    }

    @Test
    void reconnectingWithLastEventIdResumesWithoutGapsOrDuplicatesWhileTheRunProgresses() throws Exception {
        UUID run = submit();
        List<Long> received = new ArrayList<>();
        Thread producer = Thread.ofVirtual().start(() -> {
            RunLease lease = claim(run);
            for (int batch = 0; batch < 10; batch++) {
                lease = emit(lease, 3);
                try {
                    Thread.sleep(20);
                } catch (InterruptedException interrupted) {
                    return;
                }
            }
            engine.complete(lease, Map.of());
        });
        String lastEventId = null;
        boolean ended = false;
        for (int connection = 0; connection < 400 && !ended; connection++) {
            Map<String, String> headers = lastEventId == null ? Map.of() : Map.of("Last-Event-ID", lastEventId);
            try (SseClient stream = open(run, headers)) {
                if (stream.status() == 204) {
                    ended = true;
                    break;
                }
                assertThat(stream.status()).isEqualTo(200);
                // Disconnect after a random number of events, including before the first one.
                int take = ThreadLocalRandom.current().nextInt(0, 6);
                for (int index = 0; index < take; index++) {
                    SseClient.Event event = stream.next(WAIT);
                    if (event == null) break;
                    if (event.name().equals("stream-end")) {
                        ended = true;
                        break;
                    }
                    if (event.name().equals("reconnect")) {
                        // Refused for stream limits (abandoned connections hold their slot until the server's next
                        // write): the server asks the client to retry; the test retries sooner.
                        Thread.sleep(100);
                        break;
                    }
                    received.add(event.sequence());
                    lastEventId = event.id();
                }
            }
        }
        producer.join(Duration.ofSeconds(10));
        long last = engine.run(new WorkspaceId(workspace), run).summary().eventSequence();
        assertThat(ended).isTrue();
        assertThat(received).containsExactlyElementsOf(LongStream.rangeClosed(1, last).boxed().toList());
    }

    @Test
    void aTerminalRunWhoseEventsWereAllDeliveredAnswersNoContentAndCursorsPositionTheFirstConnection() {
        UUID run = submit();
        engine.cancel(new WorkspaceId(workspace), run, "user:test/session", "done");
        long last = engine.run(new WorkspaceId(workspace), run).summary().eventSequence();

        try (SseClient caughtUp = open(run, Map.of("Last-Event-ID", run + ":" + last))) {
            assertThat(caughtUp.status()).isEqualTo(204);
        }
        try (SseClient fromCursor = open(run, "?cursor=1", Map.of())) {
            List<SseClient.Event> events = fromCursor.drain(WAIT);
            assertThat(events.getFirst().sequence()).isEqualTo(2);
            assertThat(events.getLast().name()).isEqualTo("stream-end");
        }
        // Last-Event-ID wins over the cursor, as a browser reconnect keeps the original URL.
        try (SseClient reconnect = open(run, "?cursor=0", Map.of("Last-Event-ID", run + ":2"))) {
            assertThat(reconnect.next(WAIT).sequence()).isEqualTo(3);
        }
    }

    @Test
    void malformedForeignAndAheadPositionsAreTypedRefusalsBeforeAnyEvent() {
        UUID run = submit();
        UUID other = submit();

        try (SseClient malformed = open(run, Map.of("Last-Event-ID", "not-an-id"))) {
            assertThat(malformed.status()).isEqualTo(400);
            assertThat(malformed.problem().path("code").asString()).isEqualTo("INVALID_LAST_EVENT_ID");
        }
        try (SseClient foreign = open(run, Map.of("Last-Event-ID", other + ":1"))) {
            assertThat(foreign.status()).isEqualTo(400);
            assertThat(foreign.problem().path("code").asString()).isEqualTo("LAST_EVENT_ID_MISMATCH");
        }
        try (SseClient ahead = open(run, Map.of("Last-Event-ID", run + ":99"))) {
            assertThat(ahead.status()).isEqualTo(409);
            var problem = ahead.problem();
            assertThat(problem.path("code").asString()).isEqualTo("EVENT_SEQUENCE_AHEAD");
            assertThat(problem.path("currentSequence").asLong()).isEqualTo(2);
        }
        try (SseClient overflow = open(run, Map.of("Last-Event-ID", run + ":99999999999999999999"))) {
            assertThat(overflow.status()).isEqualTo(400);
            assertThat(overflow.problem().path("code").asString()).isEqualTo("INVALID_LAST_EVENT_ID");
        }
        for (String client : List.of("", "a_b", "x".repeat(65), "tab%20one")) {
            try (SseClient badClient = open(run, "?client=" + client, Map.of())) {
                assertThat(badClient.status()).isEqualTo(400);
                assertThat(badClient.problem().path("code").asString()).isEqualTo("INVALID_CLIENT_ID");
            }
        }
        try (SseClient badCursor = open(run, "?cursor=-1", Map.of())) {
            assertThat(badCursor.status()).isEqualTo(400);
            assertThat(badCursor.problem().path("code").asString()).isEqualTo("INVALID_CURSOR");
        }
        var events = api.get("/api/v1/runs/" + run + "/events?cursor=7", workspace);
        assertThat(events.status()).isEqualTo(409);
        assertThat(events.code()).isEqualTo("EVENT_SEQUENCE_AHEAD");
        assertThat(events.body().path("currentSequence").asLong()).isEqualTo(2);
    }

    @Test
    void streamsAreConfinedToThePrincipalsWorkspaceAndNeverAcceptCredentialsInTheUrl() {
        UUID run = submit();
        UUID otherWorkspace = TestWorkspaces.createWorkspace(jdbc);

        try (SseClient foreign = SseClient.open(port, "/api/v1/runs/" + run + "/stream", identities.token(otherWorkspace),
                Map.of())) {
            assertThat(foreign.status()).isEqualTo(404);
            var problem = foreign.problem();
            assertThat(problem.path("code").asString()).isEqualTo("NOT_FOUND");
            assertThat(problem.toString()).doesNotContain("RUN_CREATED", deployment);
        }
        try (SseClient anonymous = SseClient.open(port, "/api/v1/runs/" + run + "/stream", null, Map.of())) {
            assertThat(anonymous.status()).isEqualTo(401);
        }
        try (SseClient inUrl = SseClient.open(port, "/api/v1/runs/" + run + "/stream?access_token=" + token, null,
                Map.of())) {
            assertThat(inUrl.status()).isEqualTo(400);
            assertThat(inUrl.problem().path("code").asString()).isEqualTo("CREDENTIAL_IN_URL");
        }
        // A viewer may read the stream.
        var viewer = identities.create(workspace, WorkspaceRole.VIEWER);
        try (SseClient read = SseClient.open(port, "/api/v1/runs/" + run + "/stream", viewer.token(), Map.of())) {
            assertThat(read.status()).isEqualTo(200);
            assertThat(read.next(WAIT).name()).isEqualTo("RUN_CREATED");
        }
    }

    @Test
    void revokingTheCredentialOrTheMembershipEndsAnOpenStream() {
        UUID run = submit();
        var member = identities.create(workspace, WorkspaceRole.VIEWER);
        var removed = identities.create(workspace, WorkspaceRole.VIEWER);
        try (SseClient revoked = SseClient.open(port, "/api/v1/runs/" + run + "/stream", member.token(), Map.of());
             SseClient unenrolled = SseClient.open(port, "/api/v1/runs/" + run + "/stream", removed.token(), Map.of())) {
            assertThat(revoked.next(WAIT).name()).isEqualTo("RUN_CREATED");
            assertThat(unenrolled.next(WAIT).name()).isEqualTo("RUN_CREATED");

            // The member revokes its own token through the API; the other loses its membership.
            String tokenId = api.sendAs(member.token(), "GET", "/api/v1/tokens", null).body().path("items").get(0)
                    .path("id").asString();
            assertThat(api.sendAs(member.token(), "DELETE", "/api/v1/tokens/" + tokenId, null).status()).isEqualTo(204);
            jdbc.update("delete from workspace_membership where workspace_id = ? and subject_id = ?", workspace,
                    removed.userId());

            for (SseClient stream : List.of(revoked, unenrolled)) {
                List<SseClient.Event> rest = stream.drain(WAIT);
                assertThat(rest.getLast().name()).isEqualTo("stream-error");
                assertThat(rest.getLast().json().path("code").asString()).isEqualTo("AUTHORIZATION_REVOKED");
                assertThat(stream.ended(WAIT)).isTrue();
            }
        }
        try (SseClient again = SseClient.open(port, "/api/v1/runs/" + run + "/stream", member.token(), Map.of())) {
            assertThat(again.status()).isEqualTo(401);
        }
    }

    @Test
    void aPrincipalHasABoundedNumberOfStreamsAndClosedStreamsReleaseTheirSlots() throws Exception {
        UUID run = submit();
        UUID otherRun = submit();
        UUID finished = submit();
        var member = identities.create(workspace, WorkspaceRole.VIEWER);
        List<SseClient> open = new ArrayList<>();
        try {
            for (int index = 0; index < 3; index++) {
                SseClient stream = SseClient.open(port, "/api/v1/runs/" + run + "/stream?client=tab-" + index,
                        member.token(), Map.of());
                assertThat(stream.status()).isEqualTo(200);
                assertThat(stream.next(WAIT)).isNotNull();
                open.add(stream);
            }
            // At the limit, a stream of another run is refused: an SSE client with a reconnect event (so a browser
            // EventSource keeps retrying), any other caller with 429.
            try (SseClient refused = SseClient.open(port, "/api/v1/runs/" + otherRun + "/stream", member.token(),
                    Map.of())) {
                assertThat(refused.status()).isEqualTo(200);
                assertThat(refused.header("Retry-After")).isEqualTo("10");
                SseClient.Event reconnect = refused.next(WAIT);
                assertThat(reconnect.name()).isEqualTo("reconnect");
                assertThat(reconnect.json().path("reason").asString()).isEqualTo("STREAM_LIMIT_EXCEEDED");
                assertThat(refused.retry()).isEqualTo(10_000L);
                assertThat(refused.ended(WAIT)).isTrue();
            }
            var json = api.sendAs(member.token(), "GET", "/api/v1/runs/" + otherRun + "/stream", null);
            assertThat(json.status()).isEqualTo(429);
            assertThat(json.header("Retry-After")).isEqualTo("10");
            assertThat(json.code()).isEqualTo("STREAM_LIMIT_EXCEEDED");
            // At its limit, a principal still learns that a run does not exist (404) or that a terminal run has
            // nothing more to deliver (204), the answers that stop a browser EventSource (issue #186); a limit
            // refusal would have it reconnect forever.
            var unknown = api.sendAs(member.token(), "GET", "/api/v1/runs/" + UUID.randomUUID() + "/stream", null);
            assertThat(unknown.status()).isEqualTo(404);
            assertThat(unknown.code()).isEqualTo("NOT_FOUND");
            try (SseClient missing = SseClient.open(port, "/api/v1/runs/" + UUID.randomUUID() + "/stream",
                    member.token(), Map.of())) {
                assertThat(missing.status()).isEqualTo(404);
            }
            engine.cancel(new WorkspaceId(workspace), finished, "user:test/session", "done");
            long last = engine.run(new WorkspaceId(workspace), finished).summary().eventSequence();
            try (SseClient caughtUp = SseClient.open(port, "/api/v1/runs/" + finished + "/stream", member.token(),
                    Map.of("Last-Event-ID", finished + ":" + last))) {
                assertThat(caughtUp.status()).isEqualTo(204);
            }
            // A terminal run with events left to deliver still needs a stream: that is a limit refusal.
            try (SseClient behind = SseClient.open(port, "/api/v1/runs/" + finished + "/stream", member.token(),
                    Map.of("Last-Event-ID", finished + ":1"))) {
                assertThat(behind.status()).isEqualTo(200);
                assertThat(behind.next(WAIT).json().path("reason").asString()).isEqualTo("STREAM_LIMIT_EXCEEDED");
            }
            assertThat(streams.open(member.userId())).isEqualTo(3);
            // Another principal is unaffected.
            try (SseClient other = open(run, Map.of())) {
                assertThat(other.status()).isEqualTo(200);
            }
            // A reconnect to the same run without its client id, or with another one, replaces nothing (#186), and a
            // first connection (no Last-Event-ID) with the first tab's id, as a duplicated tab would send, neither (#190).
            for (String query : List.of("", "?client=tab-9", "?client=tab-0")) {
                Map<String, String> headers = query.endsWith("tab-0") ? Map.of() : Map.of("Last-Event-ID", run + ":2");
                try (SseClient refused = SseClient.open(port, "/api/v1/runs/" + run + "/stream" + query,
                        member.token(), headers)) {
                    assertThat(refused.next(WAIT).json().path("reason").asString()).isEqualTo("STREAM_LIMIT_EXCEEDED");
                }
            }
            assertThat(open.getFirst().ended(Duration.ofMillis(200))).isFalse();
            // ...while the first tab reconnecting with its client id replaces its own stream, and only that one.
            SseClient superseding = SseClient.open(port, "/api/v1/runs/" + run + "/stream?client=tab-0",
                    member.token(), Map.of("Last-Event-ID", run + ":2"));
            assertThat(superseding.status()).isEqualTo(200);
            // Served, not refused: a refusal carries Retry-After (and a 10 s retry hint with a reconnect event). The
            // served stream's own frames arrive in order, its 3 s retry hint and then its position, so once the
            // position is read the hint is too. Reading the hint before that raced the stream's first write.
            assertThat(superseding.header("Retry-After")).isNull();
            assertThat(superseding.awaitLastEventId(WAIT)).isEqualTo(run + ":2");
            assertThat(superseding.retry()).isEqualTo(3000L);
            List<SseClient.Event> rest = open.getFirst().drain(WAIT);
            assertThat(rest.getLast().name()).isEqualTo("stream-error");
            assertThat(rest.getLast().json().path("code").asString()).isEqualTo("STREAM_SUPERSEDED");
            assertThat(open.getFirst().ended(WAIT)).isTrue();
            open.removeFirst().close();
            open.add(superseding);
            assertThat(streams.open(member.userId())).isEqualTo(3);
            open.removeFirst().close();
            // The server notices the disconnect at its next write (a heartbeat at the latest) and frees the slot.
            long deadline = System.nanoTime() + WAIT.toNanos();
            while (streams.open(member.userId()) > 2 && System.nanoTime() < deadline) Thread.sleep(20);
            assertThat(streams.open(member.userId())).isEqualTo(2);
            try (SseClient admitted = SseClient.open(port, "/api/v1/runs/" + run + "/stream", member.token(), Map.of())) {
                assertThat(admitted.status()).isEqualTo(200);
            }
        } finally {
            open.forEach(SseClient::close);
        }
    }

    @Test
    void idleStreamsSendHeartbeatsAndEndAfterTheirMaximumDurationWithAReconnectEvent() {
        UUID run = submit();
        try (SseClient stream = open(run, Map.of())) {
            assertThat(stream.next(WAIT).name()).isEqualTo("RUN_CREATED");
            assertThat(stream.next(WAIT).name()).isEqualTo("RUN_QUEUED");
            boolean heartbeat = false;
            SseClient.Event reconnect = null;
            long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
            while (System.nanoTime() < deadline && reconnect == null) {
                SseClient.Event event = stream.nextIncludingComments(Duration.ofSeconds(1));
                if (event == null) continue;
                if ("keepalive".equals(event.comment())) heartbeat = true;
                if ("reconnect".equals(event.name())) reconnect = event;
            }
            assertThat(heartbeat).isTrue();
            assertThat(reconnect).isNotNull();
            assertThat(reconnect.id()).isNull();
            assertThat(reconnect.json().path("reason").asString()).isEqualTo("MAX_DURATION");
            assertThat(reconnect.json().path("lastSequence").asLong()).isEqualTo(2);
            assertThat(stream.ended(WAIT)).isTrue();
        }
        try (SseClient resumed = open(run, Map.of("Last-Event-ID", run + ":2"))) {
            assertThat(resumed.status()).isEqualTo(200);
            assertThat(resumed.next(Duration.ofMillis(300))).isNull();
        }
    }

    /**
     * #36 review: a stream of an idle run sends no event, yet its first frame gives the browser a last event id, so
     * the browser's native reconnect carries Last-Event-ID and supersedes the dropped stream.
     */
    @Test
    void aStreamOfAnIdleRunGivesTheBrowserALastEventIdSoItsReconnectSupersedesTheDroppedStream() {
        UUID run = submit();
        long head = engine.run(new WorkspaceId(workspace), run).summary().eventSequence();
        String path = "/api/v1/runs/" + run + "/stream?cursor=" + head + "&client=idle-tab";
        try (SseClient first = open(run, "?cursor=" + head + "&client=idle-tab", Map.of())) {
            assertThat(first.status()).isEqualTo(200);
            // No run event arrives, and none is dispatched, but the position is set.
            assertThat(first.awaitLastEventId(WAIT)).isEqualTo(run + ":" + head);
            assertThat(first.next(Duration.ofMillis(300))).isNull();

            // The connection drops; the browser reconnects to the same URL with the id it holds.
            try (SseClient reconnected = SseClient.open(port, path, token,
                    Map.of("Last-Event-ID", first.lastEventId()))) {
                assertThat(reconnected.status()).isEqualTo(200);
                SseClient.Event superseded = first.next(WAIT);
                assertThat(superseded.name()).isEqualTo("stream-error");
                assertThat(superseded.json().path("code").asString()).isEqualTo("STREAM_SUPERSEDED");
                assertThat(first.ended(WAIT)).isTrue();
            }
        }
    }

    /**
     * #190: when every stream read slot is taken, the admission probe waits only briefly and answers STREAM_BUSY, as a
     * reconnect for an SSE client and as 503 for any other caller, never as a stream limit.
     */
    @Test
    void aStreamRequestWhileEveryReadSlotIsTakenIsAnsweredBusyPromptly() throws Exception {
        UUID run = submit();
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        int slots = streams.settings().maxConcurrentReads();
        java.util.concurrent.CountDownLatch holding = new java.util.concurrent.CountDownLatch(slots);
        List<Thread> holders = new ArrayList<>();
        for (int slot = 0; slot < slots; slot++) {
            holders.add(Thread.ofPlatform().start(() -> streams.boundedRead(() -> {
                holding.countDown();
                try {
                    release.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                return null;
            })));
        }
        try {
            assertThat(holding.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            long started = System.nanoTime();
            try (SseClient busy = open(run, Map.of())) {
                assertThat(busy.status()).isEqualTo(200);
                SseClient.Event reconnect = busy.next(WAIT);
                assertThat(reconnect.name()).isEqualTo("reconnect");
                assertThat(reconnect.json().path("reason").asString()).isEqualTo("STREAM_BUSY");
                assertThat(busy.header("Retry-After")).isNotNull();
            }
            var json = api.get("/api/v1/runs/" + run + "/stream", workspace);
            assertThat(json.status()).isEqualTo(503);
            assertThat(json.code()).isEqualTo("STREAM_BUSY");
            assertThat(json.header("Retry-After")).isNotNull();
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
        } finally {
            release.countDown();
            for (Thread holder : holders) holder.join();
        }
        try (SseClient served = open(run, Map.of())) {
            assertThat(served.next(WAIT).name()).isEqualTo("RUN_CREATED");
        }
    }

    @Test
    void aStoppingNodeAnswersAStreamWithAReconnectRatherThanARefusal() {
        UUID run = submit();
        streams.stop();
        try (SseClient stopping = open(run, Map.of())) {
            // A non-200 answer would stop a browser EventSource for good.
            assertThat(stopping.status()).isEqualTo(200);
            assertThat(stopping.header("Connection")).isEqualToIgnoringCase("close");
            SseClient.Event reconnect = stopping.next(WAIT);
            assertThat(reconnect.name()).isEqualTo("reconnect");
            assertThat(reconnect.json().path("reason").asString()).isEqualTo("SHUTDOWN");
            assertThat(stopping.ended(WAIT)).isTrue();
        } finally {
            streams.start();
        }
        try (SseClient served = open(run, Map.of())) {
            assertThat(served.next(WAIT).name()).isEqualTo("RUN_CREATED");
        }
    }

    private SseClient open(UUID run, Map<String, String> headers) {
        return open(run, "", headers);
    }

    private SseClient open(UUID run, String query, Map<String, String> headers) {
        return SseClient.open(port, "/api/v1/runs/" + run + "/stream" + query, token, headers);
    }

    private UUID submit() {
        var response = api.send("POST", "/api/v1/deployments/" + deployment + "/runs", "{\"input\":{\"q\":1}}",
                workspace, Map.of());
        assertThat(response.status()).isEqualTo(201);
        return UUID.fromString(response.body().path("id").asString());
    }

    private RunLease claim(UUID run) {
        var scope = ClaimScope.any(ReleaseManifestReader.SUPPORTED_VERSIONS).in(new WorkspaceId(workspace));
        var others = Set.copyOf(jdbc.queryForList("select id from agent_run where workspace_id = ? and id <> ?",
                UUID.class, workspace, run));
        var claimed = engine.claimNext("stream-test-worker", scope.excluding(others), Duration.ofMinutes(1))
                .orElseThrow();
        assertThat(claimed.run().id()).isEqualTo(run);
        return claimed.lease();
    }

    private RunLease emit(RunLease lease, int count) {
        List<EventDraft> drafts = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            drafts.add(EventDraft.of(RunEventType.MODEL_STREAM_CHUNK, Map.of("index", index, "text", "chunk")));
        }
        return engine.append(lease, drafts).lease();
    }
}
