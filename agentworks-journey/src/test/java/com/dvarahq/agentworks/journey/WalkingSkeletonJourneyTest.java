package com.dvarahq.agentworks.journey;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.extension.TestWatcher;
import tools.jackson.databind.JsonNode;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Issue #37: the end-to-end walking skeleton, run against the packaged server boot jar and PostgreSQL through public
 * surfaces only (REST, SSE, and the Console). The journeys run in order on a shared installation, because each builds
 * on the state an operator would have at that point; every journey records its facts and PASS/FAIL in the evidence
 * directory. The only things outside the product are the test-only harness extension (a reference tool and a scripted
 * deterministic fake model, contributed through public extension points) and two operator steps that have no product
 * surface in M1, both done by the database administrator and named where they happen: pre-creating the database roles
 * (ISSUE-155's documented install path) and provisioning a second workspace through the documented administrator
 * database boundary. Workspace self-service provisioning is not part of the ADR-030 OSS 1.0 minimum.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WalkingSkeletonJourneyTest {
    private static final Duration RUN_TIMEOUT = Duration.ofMinutes(2);
    private static final Set<String> TERMINAL = Set.of("SUCCEEDED", "FAILED", "CANCELLED");
    private static final String PASSWORD = "journey-" + UUID.randomUUID();
    private static final String LEDGER_CAPABILITY = "journey:journey-ledger:1.0.0";

    static final Evidence EVIDENCE = new Evidence(Path.of(System.getProperty("journey.evidence.dir",
            "target/journey-evidence")));

    @RegisterExtension
    static final TestWatcher WATCHER = EVIDENCE.watcher();

    private final Path serverJar = Path.of(System.getProperty("journey.server.jar"));
    private final Path extensionJar = Path.of(System.getProperty("journey.extension.jar"));
    private final Path tokenFile = EVIDENCE.work("bootstrap-token");
    private final Path effectsFile = EVIDENCE.work("ledger-effects.log");
    private final Path holdFile = EVIDENCE.work("hold");

    private JourneyDatabase database;
    private ServerProcess server;
    private ServerProcess production;
    private OpenAiCompatibleStub productionModel;
    private boolean toolRepeatObserved;
    private boolean localJourneysFinished;
    private JourneyDatabase productionDatabase;
    private int serverCount;
    private int refusedServerStarts;
    private final Instant started = Instant.now();

    // State an operator accumulates across the journeys.
    private Api admin;
    private Api adminSession;
    private UUID organizationId;
    private UUID workspaceId;
    private UUID adminUserId;
    private String environmentId;
    private String referenceDefinitionId;
    private String webDeploymentId;
    private JsonNode goldenRun;
    private List<String> goldenDigests;

    @BeforeAll
    void install() throws Exception {
        assertThat(serverJar).as("the packaged server boot jar").isRegularFile();
        assertThat(extensionJar).as("the harness extension jar").isRegularFile();
        EVIDENCE.json("artifacts.json", artifacts());
        database = new JourneyDatabase().start();
        Map<String, Object> environment = new LinkedHashMap<>();
        environment.put("java", System.getProperty("java.vendor") + " " + System.getProperty("java.runtime.version"));
        environment.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version") + " "
                + System.getProperty("os.arch"));
        environment.put("postgresImage", JourneyDatabase.IMAGE);
        environment.put("postgres", database.serverVersion());
        environment.put("ci", System.getenv().getOrDefault("CI", "false"));
        environment.put("headSha", System.getenv().getOrDefault("JOURNEY_HEAD_SHA", ""));
        environment.put("githubSha", System.getenv().getOrDefault("GITHUB_SHA", ""));
        environment.put("mavenCommand", System.getenv().getOrDefault("JOURNEY_MAVEN_COMMAND",
                "./mvnw -B -ntp -Pjourney -DskipTests verify -pl agentworks-journey -am"));
        environment.put("githubRunId", System.getenv().getOrDefault("GITHUB_RUN_ID", ""));
        EVIDENCE.json("environment.json", environment);
        server = start(Map.of());
        admin = new Api(this::base);
    }

    /** Releases any run a failed journey left held, so one failure cannot cascade into later journeys. */
    @AfterEach
    void releaseHeldRuns() throws IOException {
        Files.deleteIfExists(holdFile);
        // A journey that fails after killing the shared local server must not turn every later journey into a
        // dead-server cascade. The failed journey remains failed; the next one gets a fresh process (#211).
        if (!localJourneysFinished && server != null && !server.process.isAlive()) server = start(Map.of());
    }

    @AfterAll
    void tearDown() {
        RuntimeException stopFailure = null;
        try {
            if (server != null && server.process.isAlive()) server.stop();
        } catch (RuntimeException failure) {
            stopFailure = failure;
        }
        try {
            if (production != null && production.process.isAlive()) production.stop();
        } catch (RuntimeException failure) {
            if (stopFailure == null) stopFailure = failure;
            else stopFailure.addSuppressed(failure);
        } finally {
            Map<String, Object> header = new LinkedHashMap<>();
            header.put("issue", 37);
            header.put("started", started.toString());
            header.put("finished", Instant.now().toString());
            header.put("successfulLocalServerProcesses", serverCount);
            header.put("productionServerProcesses", production == null ? 0 : 1);
            header.put("refusedServerStarts", refusedServerStarts);
            header.put("expectedJourneys", (int) java.util.Arrays.stream(getClass().getDeclaredMethods())
                    .filter(method -> method.isAnnotationPresent(Test.class)).count());
            header.put("profile", "journeys j1-j10 run under spring.profiles.active=local, which the deterministic fake "
                    + "model gateway requires: loopback bind, agentworks.development.local=true, the published local "
                    + "idempotency HMAC key, and a non-Secure session cookie; j11 runs the packaged jar on the "
                    + "production profile without the harness extension (non-loopback bind, a random HMAC key, "
                    + "Secure cookie) and invokes the direct OpenAI-compatible adapter against a harness-owned "
                    + "loopback HTTP endpoint");
            List<String> documentedGaps = new ArrayList<>();
            if (toolRepeatObserved) documentedGaps.add(
                    "a kill after a tool ran and before the answer is checkpointed repeated the tool (Beta gate "
                            + "BLOCKER: exact-once recovery regressed after #39 and #44)");
            header.put("verdictIfAllJourneysPass", documentedGaps.isEmpty() ? "PASS"
                    : "PASS WITH DOCUMENTED GAPS");
            header.put("documentedGaps", documentedGaps);
            header.put("supportedOperationalBoundaries", List.of(
                    "additional workspaces are provisioned by an administrator at the database boundary; "
                            + "self-service workspace provisioning is outside the ADR-030 OSS 1.0 minimum"));
            EVIDENCE.writeReport(header);
            if (productionModel != null) productionModel.close();
            if (database != null) database.close();
            if (productionDatabase != null) productionDatabase.close();
        }
        if (stopFailure != null) throw stopFailure;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // AC1: clean install, default tenancy, first administrator from the bootstrap token file, then an API token.
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    @Order(1)
    @DisplayName("J1 clean install: bootstrap the first administrator from the token file, then issue an API token")
    void j1CleanInstall() throws Exception {
        String journey = "j1CleanInstall";
        // The server issues the token after it reports started; it logs the file's path once the file is written.
        long deadline = System.nanoTime() + Duration.ofMinutes(1).toNanos();
        while (!server.output().contains("A one-time bootstrap token was written to") && System.nanoTime() < deadline) {
            ServerProcess.sleep(100);
        }
        assertThat(server.output()).contains("A one-time bootstrap token was written to " + tokenFile);
        assertThat(tokenFile).as("the one-time bootstrap token file").isRegularFile();
        assertThat(Files.getPosixFilePermissions(tokenFile)).containsExactlyInAnyOrder(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
        String token = Files.readString(tokenFile).strip();
        assertThat(server.output().contains(token)).as("the bootstrap token is never logged").isFalse();

        Api anonymous = new Api(this::base);
        assertThat(anonymous.get("/api/v1/agents").status()).as("every request authenticates").isEqualTo(401);

        Map<String, Object> bootstrap = Map.of("token", token, "username", "journey-admin",
                "displayName", "Journey Administrator", "password", PASSWORD);
        Api.Response created = anonymous.post("/api/v1/auth/bootstrap", bootstrap);
        assertThat(created.status()).as(created.raw()).isEqualTo(201);
        organizationId = UUID.fromString(created.json().path("organizationId").asString());
        workspaceId = UUID.fromString(created.json().path("workspaceId").asString());
        adminUserId = UUID.fromString(created.json().path("userId").asString());
        Api.Response replayed = anonymous.post("/api/v1/auth/bootstrap", bootstrap);
        assertThat(replayed.status()).isEqualTo(403);
        assertThat(replayed.code()).isEqualTo("BOOTSTRAP_UNAVAILABLE");

        adminSession = new Api(this::base);
        Api.Response session = adminSession.post("/api/v1/auth/login",
                Map.of("username", "journey-admin", "password", PASSWORD));
        assertThat(session.status()).as(session.raw()).isEqualTo(200);
        assertThat(session.json().path("workspaceId").asString()).isEqualTo(workspaceId.toString());
        assertThat(session.json().path("role").asString()).isEqualTo("ADMIN");
        Api.Response issued = adminSession.post("/api/v1/tokens",
                Map.of("name", "journey-operator", "role", "ADMIN", "expiresInDays", 1));
        assertThat(issued.status()).as(issued.raw()).isEqualTo(201);
        admin.bearer(issued.json().path("token").asString());
        assertThat(admin.get("/api/v1/agents").status()).isEqualTo(200);
        Map<String, Object> ledger = Map.ofEntries(Map.entry("namespace", "journey"),
                Map.entry("name", "journey-ledger"), Map.entry("version", "1.0.0"),
                Map.entry("type", "JAVA"), Map.entry("summary", "Append to the journey ledger"),
                Map.entry("description", "Appends one entry to the journey ledger and returns its receipt."),
                Map.entry("inputSchema", Evidence.JSON.readTree(
                        com.dvarahq.agentworks.journey.extension.JourneyLedgerTool.INPUT_SCHEMA)),
                Map.entry("effect", Map.of("resource", "journey-ledger", "action", "write", "risk", "HIGH")),
                Map.entry("tags", List.of("journey")), Map.entry("compatibility", List.of("runtime-v1")),
                Map.entry("summaryTokens", 8), Map.entry("detailTokens", 24));
        assertThat(admin.post("/api/v1/tools", ledger).status()).isEqualTo(201);
        assertThat(admin.post("/api/v1/tools/journey/journey-ledger/versions/1.0.0/lifecycle",
                Map.of("state", "ACTIVE")).status()).isEqualTo(200);
        assertThat(admin.post("/api/v1/capability-packs", Map.of("id", "journey-ledger", "version", "1.0.0",
                "tools", List.of(Map.of("namespace", "journey", "name", "journey-ledger",
                        "version", "1.0.0")))).status()).isEqualTo(201);
        assertThat(admin.post("/api/v1/capability-packs/journey-ledger/versions/1.0.0/lifecycle",
                Map.of("state", "ACTIVE")).status()).isEqualTo(200);

        EVIDENCE.fact(journey, "organizationId", organizationId.toString());
        EVIDENCE.fact(journey, "defaultWorkspaceId", workspaceId.toString());
        EVIDENCE.fact(journey, "administratorId", adminUserId.toString());
        EVIDENCE.fact(journey, "tokenFilePermissions", "0600");
        EVIDENCE.fact(journey, "bootstrapReplay", replayed.status() + " " + replayed.code());
        EVIDENCE.fact(journey, "apiTokenId", issued.json().path("metadata").path("id").asString());
    }

    // ---------------------------------------------------------------------------------------------------------------
    // AC1 + AC2: publish, release, environment, deployment, promote, run; pins, one allowed capability, ordered
    // events over SSE, immutable history.
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    @Order(2)
    @DisplayName("J2 golden path: publish, release, promote, run with one allowed tool, stream, and verify history")
    void j2GoldenPath() throws Exception {
        String journey = "j2GoldenPath";
        referenceDefinitionId = createAgent(admin, "reference-agent");
        JsonNode version = publish(admin, referenceDefinitionId, spec("reference-agent", "1.0.0", "reference",
                "Use the journey ledger when asked.", true));
        JsonNode release = release(admin, version.path("id").asString(), "reference-agent-1");
        JsonNode manifest = admin.get("/api/v1/releases/" + release.path("id").asString() + "/manifest").json();
        assertThat(manifest.toString()).contains("journey-model-a")
                .contains("\"id\":\"journey-ledger\"")
                .contains("\"schemaHash\":\"sha256:");

        Api.Response environment = admin.post("/api/v1/environments", Map.of("name", "production"));
        assertThat(environment.status()).as(environment.raw()).isEqualTo(201);
        environmentId = environment.json().path("id").asString();
        String promoteKey = "journey-promote-web-" + UUID.randomUUID();
        JsonNode promoted = promote(admin, environmentId, "web", release.path("id").asString(), promoteKey, "\"0\"");
        webDeploymentId = promoted.path("deploymentId").asString();
        UUID permissionId = UUID.randomUUID();
        rememberedPermission(permissionId, 1, UUID.fromString(environmentId), false);
        Api.Response replay = admin.post("/api/v1/environments/" + environmentId + "/promotions",
                Map.of("deployment", "web", "releaseId", release.path("id").asString(), "reason", "journey"),
                Map.of("Idempotency-Key", promoteKey, "If-Match", "\"0\""));
        assertThat(replay.status()).as(replay.raw()).isIn(200, 201);
        assertThat(replay.header("Idempotent-Replayed")).isEqualTo("true");
        assertThat(replay.json().path("decisionId").asString()).isEqualTo(promoted.path("decisionId").asString());

        String traceId = HexFormat.of().formatHex(randomBytes(16));
        Api.Response submitted = admin.post("/api/v1/deployments/" + webDeploymentId + "/runs",
                Map.of("input", Map.of("prompt", "tool journey_ledger golden-1",
                        "capabilityDetails", List.of(LEDGER_CAPABILITY))),
                Map.of("traceparent", "00-" + traceId + "-" + HexFormat.of().formatHex(randomBytes(8)) + "-01",
                        "Idempotency-Key", "journey-golden-" + UUID.randomUUID()));
        assertThat(submitted.status()).as(submitted.raw()).isEqualTo(201);
        JsonNode run = submitted.json();
        String runId = run.path("id").asString();
        // Exact pins at admission.
        assertThat(run.path("releaseId").asString()).isEqualTo(release.path("id").asString());
        assertThat(run.path("versionId").asString()).isEqualTo(version.path("id").asString());
        assertThat(run.path("deploymentId").asString()).isEqualTo(webDeploymentId);
        assertThat(run.path("revisionId").asString()).isEqualTo(promoted.path("revisionId").asString());
        assertThat(run.path("cohortId").asString()).isEqualTo(promoted.path("cohortId").asString());
        assertThat(run.path("routingGenerationId").asString()).isEqualTo(promoted.path("routingGenerationId").asString());
        assertThat(run.path("traceId").asString()).isEqualTo(traceId);
        assertThat(run.path("admission").asString()).isEqualTo("ROOT");

        // Watch it live over SSE from the first event to the end.
        Api.Stream stream = admin.stream("/api/v1/runs/" + runId + "/stream?cursor=0&client=" + UUID.randomUUID(),
                null, event -> false, line -> EVIDENCE.transcript("j2-golden.sse", line));
        assertThat(stream.status()).isEqualTo(200);
        assertThat(stream.ended()).as("the stream ends after the terminal event").isTrue();
        List<Api.SseEvent> runEvents = stream.events().stream().filter(event -> event.id() != null).toList();
        assertGapless(runEvents.stream().map(Api.SseEvent::sequence).toList(), 1);

        JsonNode finished = awaitTerminal(admin, runId);
        assertThat(finished.path("lifecycle").asString()).isEqualTo("SUCCEEDED");
        List<JsonNode> events = events(admin, runId);
        EVIDENCE.events("j2-golden-events.json", events);
        // The SSE transcript is the verified history, event for event.
        assertThat(runEvents.stream().map(Api.SseEvent::name).toList()).isEqualTo(types(events));
        assertThat(runEvents.stream().map(event -> Evidence.JSON.readTree(event.data()).path("eventDigest").asString())
                .toList()).isEqualTo(digests(events));

        assertThat(types(events).getFirst()).isEqualTo("RUN_CREATED");
        assertThat(count(events, "RUN_SUCCEEDED")).isOne();
        assertThat(events.stream().filter(event -> TERMINAL_EVENTS.contains(event.path("type").asString())).count())
                .isOne();
        assertThat(count(events, "CAPABILITY_RESOLVED")).isEqualTo(2);
        assertThat(events.stream().filter(event -> "CAPABILITY_RESOLVED".equals(event.path("type").asString()))
                .map(event -> event.path("payload").path("decisions").toString()).toList().getLast())
                .contains("\"disclosure\":\"DETAIL\"");
        assertThat(count(events, "TOOL_REQUESTED")).isOne();
        assertThat(count(events, "TOOL_INPUT_SCHEMA_VALIDATED")).isOne();
        assertThat(count(events, "TOOL_POLICY_EVALUATED")).isOne();
        assertThat(count(events, "TOOL_INPUT_GUARDRAIL_EVALUATED")).isOne();
        assertThat(count(events, "TOOL_STARTED")).isOne();
        assertThat(count(events, "TOOL_OUTPUT_GUARDRAIL_EVALUATED")).isOne();
        assertThat(count(events, "TOOL_COMPLETED")).isOne();
        assertThat(count(events, "MODEL_REQUESTED")).as("tool round and answer round").isEqualTo(2);
        assertThat(first(events, "TOOL_REQUESTED").path("payload").path("name").asString())
                .isEqualTo("journey_ledger");
        JsonNode inputValidation = first(events, "TOOL_INPUT_SCHEMA_VALIDATED").path("payload");
        assertThat(inputValidation.path("decision").asString()).isEqualTo("ALLOW");
        assertThat(inputValidation.path("reason").asString()).isEqualTo("SCHEMA_MATCHED");
        assertThat(inputValidation.path("violations").asInt()).isZero();
        assertThat(inputValidation.path("schemaHash").asString()).startsWith("sha256:");
        JsonNode policy = first(events, "TOOL_POLICY_EVALUATED").path("payload");
        assertThat(policy.path("decision").asString()).isEqualTo("ALLOW");
        assertThat(policy.path("reason").asString()).isEqualTo("REMEMBERED_PERMISSION_MATCHED_ALLOW");
        assertThat(policy.path("intent").asString()).isEqualTo("EXECUTE");
        assertThat(policy.path("autonomyProfile").asString()).isEqualTo("SUPERVISED");
        assertThat(policy.at("/rememberedPermission/decision").asString()).isEqualTo("ALLOW");
        assertThat(policy.at("/rememberedPermission/reason").asString()).isEqualTo("MATCHED_ALLOW");
        assertThat(policy.at("/rememberedPermission/ruleId").asString()).isEqualTo(permissionId.toString());
        assertThat(policy.at("/rememberedPermission/ruleVersion").asLong()).isOne();
        assertThat(policy.at("/rememberedPermission/capability").asString()).isEqualTo(LEDGER_CAPABILITY);
        assertThat(policy.at("/rememberedPermission/resource").asString()).isEqualTo("journey-ledger");
        assertThat(policy.at("/rememberedPermission/action").asString()).isEqualTo("write");
        assertThat(policy.at("/rememberedPermission/risk").asString()).isEqualTo("HIGH");
        JsonNode inputGuardrail = first(events, "TOOL_INPUT_GUARDRAIL_EVALUATED").path("payload");
        assertThat(inputGuardrail.path("decision").asString()).isEqualTo("ALLOW");
        assertThat(inputGuardrail.path("reason").asString()).isEqualTo("VALIDATED_INPUT_ALLOWED");
        assertThat(inputGuardrail.path("guardrail").asString()).isEqualTo("core:validated-input:1");
        assertThat(inputGuardrail.path("risk").asString()).isEqualTo("HIGH");
        JsonNode outputGuardrail = first(events, "TOOL_OUTPUT_GUARDRAIL_EVALUATED").path("payload");
        assertThat(outputGuardrail.path("decision").asString()).isEqualTo("ALLOW");
        assertThat(outputGuardrail.path("reason").asString()).isEqualTo("VALIDATED_OUTPUT_ALLOWED");
        assertThat(outputGuardrail.path("guardrail").asString()).isEqualTo("core:validated-output:1");
        assertThat(outputGuardrail.path("risk").asString()).isEqualTo("HIGH");
        assertThat(types(events)).containsSubsequence("OPERATION_STARTED", "OPERATION_COMPLETED", "TOOL_COMPLETED");
        assertThat(count(events, "OPERATION_STARTED")).isOne();
        assertThat(count(events, "OPERATION_COMPLETED")).isOne();
        for (JsonNode event : events) {
            assertThat(event.path("releaseId").asString()).isEqualTo(run.path("releaseId").asString());
            assertThat(event.path("deploymentId").asString()).isEqualTo(webDeploymentId);
            assertThat(event.path("revisionId").asString()).isEqualTo(run.path("revisionId").asString());
            assertThat(event.path("cohortId").asString()).isEqualTo(run.path("cohortId").asString());
            assertThat(event.path("traceId").asString()).isEqualTo(traceId);
        }
        assertThat(first(events, "MODEL_REQUESTED").path("payload").path("providerModel").asString())
                .isEqualTo("journey-model-a");
        assertThat(first(events, "RUN_SUCCEEDED").toString()).contains("ledger-receipt:golden-1");
        assertThat(effects()).containsExactly("golden-1");

        JsonNode steps = admin.get("/api/v1/runs/" + runId + "/steps").json();
        assertThat(steps.path("items").toString()).contains("journey_ledger");

        // Immutable history: the Run Plane and Control Plane logins have no privilege to rewrite or delete an event,
        // and even the schema owner is refused by the append-only trigger.
        Map<String, String> refusals = new LinkedHashMap<>();
        for (String login : List.of("journey_run", "journey_control", "installer")) {
            String expected = "installer".equals(login) ? "23001" : "42501";
            for (String sql : List.of("update agent_run_event set payload_json = payload_json where run_id = ?",
                    "delete from agent_run_event where run_id = ?")) {
                assertThatThrownBy(() -> executeAs(login, sql, UUID.fromString(runId))).as(login + ": " + sql)
                        .isInstanceOfSatisfying(SQLException.class, refused -> {
                            assertThat(refused.getSQLState()).as(login + ": " + sql).isEqualTo(expected);
                            if ("23001".equals(expected)) {
                                assertThat(refused.getMessage()).contains("agentworks:IMMUTABLE_HISTORY");
                            }
                            refusals.put(login + ": " + sql.substring(0, 6), refused.getSQLState());
                        });
            }
        }
        goldenRun = finished;
        goldenDigests = digests(events);

        // The callback boundary performs another database read on every use: a later immutable revocation version
        // denies the next call before TOOL_STARTED, without rebuilding the release or restarting the worker.
        rememberedPermission(permissionId, 2, UUID.fromString(environmentId), true);
        String revokedRunId = submit(admin, webDeploymentId, "tool journey_ledger revoked-1");
        JsonNode revokedRun = awaitTerminal(admin, revokedRunId);
        List<JsonNode> revokedEvents = events(admin, revokedRunId);
        assertThat(revokedRun.path("lifecycle").asString()).isEqualTo("FAILED");
        assertThat(revokedRun.path("terminalCode").asString()).isEqualTo("TOOL_POLICY_DENIED");
        assertThat(first(revokedEvents, "TOOL_POLICY_EVALUATED").path("payload").path("reason").asString())
                .isEqualTo("REMEMBERED_PERMISSION_REVOKED");
        assertThat(types(revokedEvents)).doesNotContain("TOOL_STARTED", "TOOL_COMPLETED");
        assertThat(effects()).containsExactly("golden-1");
        rememberedPermission(permissionId, 3, UUID.fromString(environmentId), false);

        EVIDENCE.fact(journey, "agentVersion", version.path("id").asString() + " " + version.path("digest").asString());
        EVIDENCE.fact(journey, "release", release.path("id").asString() + " " + release.path("manifestDigest").asString());
        EVIDENCE.fact(journey, "promotion", promoted);
        EVIDENCE.fact(journey, "promotionReplay", "Idempotent-Replayed: " + replay.header("Idempotent-Replayed"));
        EVIDENCE.fact(journey, "run", finished);
        EVIDENCE.fact(journey, "eventTypes", types(events));
        EVIDENCE.fact(journey, "ledgerEffects", effects());
        EVIDENCE.fact(journey, "historyRewriteRefused", refusals);
        EVIDENCE.fact(journey, "rememberedPermissionRevocation", types(revokedEvents));
    }

    private static final Set<String> TERMINAL_EVENTS = Set.of("RUN_SUCCEEDED", "RUN_FAILED", "RUN_CANCELLED");

    // ---------------------------------------------------------------------------------------------------------------
    // AC4, denied capability, variant 1: the model names a tool the server has not registered.
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    @Order(3)
    @DisplayName("J3 denied capability: a tool the server has not registered is refused before it runs")
    void j3UnregisteredToolIsRefused() {
        String journey = "j3UnregisteredToolIsRefused";
        List<String> before = effects();
        String runId = submit(admin, webDeploymentId, "tool journey_unregistered denied-1");
        JsonNode run = awaitTerminal(admin, runId);
        List<JsonNode> events = events(admin, runId);
        EVIDENCE.events("j3-unregistered-tool-events.json", events);
        assertThat(run.path("lifecycle").asString()).isEqualTo("FAILED");
        assertThat(run.path("terminalCode").asString()).isEqualTo("EXECUTOR_FAILED");
        assertThat(types(events)).doesNotContain("TOOL_REQUESTED", "TOOL_STARTED", "TOOL_COMPLETED", "TOOL_FAILED");
        assertThat(count(events, "RUN_FAILED")).isOne();
        assertThat(effects()).isEqualTo(before);
        EVIDENCE.fact(journey, "run", run);
        EVIDENCE.fact(journey, "eventTypes", types(events));
        EVIDENCE.fact(journey, "gap", "untyped EXECUTOR_FAILED; no TOOL_* evidence of the refusal (#39)");
    }

    // ---------------------------------------------------------------------------------------------------------------
    // ADR-027: two agent definitions and releases through one worker pool, with distinct pins.
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    @Order(4)
    @DisplayName("J4 two agents, one worker pool: distinct release, revision, cohort, and model pins")
    void j4TwoAgentsShareOneWorkerPool() {
        String journey = "j4TwoAgentsShareOneWorkerPool";
        String second = createAgent(admin, "second-agent");
        JsonNode version = publish(admin, second, spec("second-agent", "1.0.0", "second", "Answer briefly.", true));
        JsonNode release = release(admin, version.path("id").asString(), "second-agent-1");
        JsonNode promoted = promote(admin, environmentId, "second", release.path("id").asString(),
                "journey-promote-second-" + UUID.randomUUID(), "\"0\"");
        String secondDeployment = promoted.path("deploymentId").asString();

        String a = submit(admin, webDeploymentId, "tool journey_ledger pool-a");
        String b = submit(admin, secondDeployment, "tool journey_ledger pool-b");
        JsonNode runA = awaitTerminal(admin, a);
        JsonNode runB = awaitTerminal(admin, b);
        assertThat(runA.path("lifecycle").asString()).isEqualTo("SUCCEEDED");
        assertThat(runB.path("lifecycle").asString()).isEqualTo("SUCCEEDED");
        for (String field : List.of("definitionId", "releaseId", "deploymentId", "revisionId", "cohortId")) {
            assertThat(runA.path(field).asString()).as(field).isNotEqualTo(runB.path(field).asString());
        }
        List<JsonNode> eventsA = events(admin, a);
        List<JsonNode> eventsB = events(admin, b);
        EVIDENCE.events("j4-agent-a-events.json", eventsA);
        EVIDENCE.events("j4-agent-b-events.json", eventsB);
        assertThat(first(eventsA, "MODEL_REQUESTED").path("payload").path("providerModel").asString())
                .isEqualTo("journey-model-a");
        assertThat(first(eventsB, "MODEL_REQUESTED").path("payload").path("providerModel").asString())
                .isEqualTo("journey-model-b");
        eventsB.forEach(event -> assertThat(event.path("releaseId").asString()).isEqualTo(release.path("id").asString()));
        // Both were executed by this one server's worker pool.
        String workerA = first(eventsA, "RUN_ACTIVATED").path("actor").asString();
        String workerB = first(eventsB, "RUN_ACTIVATED").path("actor").asString();
        assertThat(workerA).startsWith("worker:journey-node");
        assertThat(workerB).startsWith("worker:journey-node");
        assertThat(effects()).contains("pool-a", "pool-b");
        EVIDENCE.fact(journey, "runA", runA);
        EVIDENCE.fact(journey, "runB", runB);
        EVIDENCE.fact(journey, "workers", List.of(workerA, workerB));
        EVIDENCE.fact(journey, "capabilityBinding",
                "both releases pin the exact ledger capability; only its selected DETAIL callback is offered");
    }

    // ---------------------------------------------------------------------------------------------------------------
    // ADR-027: a durable run admitted before a promotion is recovered after a crash and resumes on its original
    // revision, while a new run admitted after the promotion uses the new one.
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    @Order(5)
    @DisplayName("J5 promotion cutoff: a pre-promotion run resumes on its revision after a crash; a new run gets the promoted one")
    void j5PromotionCutoff() throws Exception {
        String journey = "j5PromotionCutoff";
        List<String> effectsBefore = effects();
        Files.writeString(holdFile, "held");
        String oldRun = submit(admin, webDeploymentId, "hold cutoff-old");
        JsonNode before = admin.get("/api/v1/runs/" + oldRun).json();
        awaitEvents(admin, oldRun, "MODEL_REQUESTED", 1);
        assertThat(admin.get("/api/v1/runs/" + oldRun).json().path("lifecycle").asString()).isEqualTo("ACTIVE");

        // Revision two also moves the reasoning model, so the model each run invokes tells the revisions apart.
        JsonNode version = publish(admin, referenceDefinitionId, spec("reference-agent", "1.1.0", "second",
                "Use the journey ledger when asked. Revision two.", true));
        JsonNode release = release(admin, version.path("id").asString(), "reference-agent-2");
        JsonNode promoted = promote(admin, environmentId, "web", release.path("id").asString(),
                "journey-promote-web-2-" + UUID.randomUUID(), null);
        assertThat(promoted.path("deploymentId").asString()).isEqualTo(webDeploymentId);
        assertThat(promoted.path("revisionId").asString()).isNotEqualTo(before.path("revisionId").asString());
        assertThat(promoted.path("drainedCohortIds").toString()).contains(before.path("cohortId").asString());

        // The node running the pre-promotion run crashes; a new node recovers the run from its own pins.
        String crashed = server.name;
        int exit = server.kill();
        assertThat(exit).as("SIGKILL").isEqualTo(137);
        server = start(Map.of());
        awaitEvents(admin, oldRun, "RUN_RECOVERED", 1);
        awaitEvents(admin, oldRun, "MODEL_REQUESTED", 2);

        String newRun = submit(admin, webDeploymentId, "hello cutoff-new");
        JsonNode fresh = awaitTerminal(admin, newRun);
        assertThat(fresh.path("lifecycle").asString()).isEqualTo("SUCCEEDED");
        assertThat(fresh.path("releaseId").asString()).isEqualTo(release.path("id").asString());
        assertThat(fresh.path("revisionId").asString()).isEqualTo(promoted.path("revisionId").asString());
        assertThat(fresh.path("cohortId").asString()).isEqualTo(promoted.path("cohortId").asString());
        // The recovered run is still in flight on its original revision while the new one has finished.
        JsonNode inFlight = admin.get("/api/v1/runs/" + oldRun).json();
        assertThat(inFlight.path("lifecycle").asString()).isEqualTo("ACTIVE");
        JsonNode draining = admin.get("/api/v1/cohorts/" + before.path("cohortId").asString()).json();
        assertThat(draining.path("state").asString()).isEqualTo("DRAINING");

        Files.delete(holdFile);
        JsonNode old = awaitTerminal(admin, oldRun);
        assertThat(old.path("lifecycle").asString()).isEqualTo("SUCCEEDED");
        for (String field : List.of("releaseId", "versionId", "revisionId", "cohortId", "routingGenerationId")) {
            assertThat(old.path(field).asString()).as(field).isEqualTo(before.path(field).asString());
        }
        List<JsonNode> oldEvents = events(admin, oldRun);
        EVIDENCE.events("j5-old-run-events.json", oldEvents);
        EVIDENCE.events("j5-new-run-events.json", events(admin, newRun));
        assertThat(count(oldEvents, "RUN_RECOVERED")).isOne();
        assertThat(count(oldEvents, "RUN_SUCCEEDED")).isOne();
        oldEvents.forEach(event -> {
            assertThat(event.path("revisionId").asString()).isEqualTo(before.path("revisionId").asString());
            assertThat(event.path("cohortId").asString()).isEqualTo(before.path("cohortId").asString());
        });
        oldEvents.stream().filter(event -> "MODEL_REQUESTED".equals(event.path("type").asString())).forEach(event ->
                assertThat(event.path("payload").path("providerModel").asString()).isEqualTo("journey-model-a"));
        assertThat(first(events(admin, newRun), "MODEL_REQUESTED").path("payload").path("providerModel").asString())
                .isEqualTo("journey-model-b");
        assertThat(effects()).as("no external effect").isEqualTo(effectsBefore);
        Instant newFinished = Instant.parse(fresh.path("completedAt").asString());
        Instant oldFinished = Instant.parse(old.path("completedAt").asString());
        assertThat(oldFinished).as("the old run finished after the new one").isAfter(newFinished);

        EVIDENCE.fact(journey, "crash", crashed + " killed with SIGKILL (exit " + exit + ") while the old run was in "
                + "flight; recovered by " + server.name);
        EVIDENCE.fact(journey, "oldRun", old);
        EVIDENCE.fact(journey, "oldRunEventTypes", types(oldEvents));
        EVIDENCE.fact(journey, "newRun", fresh);
        EVIDENCE.fact(journey, "promotion", promoted);
        EVIDENCE.fact(journey, "oldCohortDuringFlight", draining);
        EVIDENCE.fact(journey, "oldCohortAfter", admin.get("/api/v1/cohorts/" + before.path("cohortId").asString())
                .json());
    }

    // ---------------------------------------------------------------------------------------------------------------
    // AC4: cross-tenant access fails closed, and a second tenant runs through the same worker pool.
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    @Order(6)
    @DisplayName("J6 cross-tenant: another workspace's resources are indistinguishable from absent ones")
    void j6CrossTenantAccessFailsClosed() throws Exception {
        String journey = "j6CrossTenantAccessFailsClosed";
        // Documented administrator provisioning boundary: a second workspace in the organization, administered by
        // the same user. Everything after this uses public surfaces; self-service provisioning is outside ADR-030.
        UUID tenantB = UUID.randomUUID();
        try (Connection connection = database.administrator()) {
            update(connection, "insert into workspace(id, organization_id, name) values (?, ?, ?)", tenantB,
                    organizationId, "tenant-b");
            update(connection, "insert into workspace_membership(organization_id, workspace_id, subject_id, "
                    + "membership_role, updated_at) values (?, ?, ?, 'ADMIN', current_timestamp)", organizationId,
                    tenantB, adminUserId);
        }
        Api sessionB = new Api(this::base);
        Api.Response login = sessionB.post("/api/v1/auth/login", Map.of("username", "journey-admin",
                "password", PASSWORD, "workspaceId", tenantB.toString()));
        assertThat(login.status()).as(login.raw()).isEqualTo(200);
        assertThat(login.json().path("workspaceId").asString()).isEqualTo(tenantB.toString());
        Api.Response token = sessionB.post("/api/v1/tokens", Map.of("name", "tenant-b", "role", "ADMIN",
                "expiresInDays", 1));
        assertThat(token.status()).as(token.raw()).isEqualTo(201);
        Api b = new Api(this::base).bearer(token.json().path("token").asString());

        // Tenant B deploys and runs its own agent (the same names as tenant A's: names are per workspace).
        String definition = createAgent(b, "reference-agent");
        JsonNode version = publish(b, definition, spec("reference-agent", "1.0.0", "reference", "Tenant B.", false));
        JsonNode release = release(b, version.path("id").asString(), "reference-agent-1");
        String environment = b.post("/api/v1/environments", Map.of("name", "production")).json().path("id").asString();
        JsonNode promoted = promote(b, environment, "web", release.path("id").asString(),
                "journey-tenant-b-" + UUID.randomUUID(), "\"0\"");
        String deploymentB = promoted.path("deploymentId").asString();
        String runB = submit(b, deploymentB, "hello tenant-b");
        JsonNode finishedB = awaitTerminal(b, runB);
        assertThat(finishedB.path("lifecycle").asString()).isEqualTo("SUCCEEDED");
        assertThat(finishedB.path("workspaceId").asString()).isEqualTo(tenantB.toString());
        long sequenceB = finishedB.path("eventSequence").asLong();

        // Tenant A cannot see, stream, admit into, or control anything of tenant B's, and the reverse.
        Map<String, Integer> probes = new LinkedHashMap<>();
        probes.put("GET /runs/{B}", admin.get("/api/v1/runs/" + runB).status());
        probes.put("GET /runs/{B}/events", admin.get("/api/v1/runs/" + runB + "/events").status());
        probes.put("GET /runs/{B}/steps", admin.get("/api/v1/runs/" + runB + "/steps").status());
        probes.put("GET /runs/{B}/stream", admin.stream("/api/v1/runs/" + runB + "/stream?cursor=0", null,
                event -> true, line -> EVIDENCE.transcript("j6-cross-tenant.sse", line)).status());
        probes.put("POST /runs/{B}/cancel", admin.post("/api/v1/runs/" + runB + "/cancel", Map.of()).status());
        probes.put("POST /deployments/{B}/runs", admin.post("/api/v1/deployments/" + deploymentB + "/runs",
                Map.of("input", Map.of("prompt", "intrusion"))).status());
        probes.put("GET /deployments/{B}", admin.get("/api/v1/deployments/" + deploymentB).status());
        probes.put("GET /releases/{B}", admin.get("/api/v1/releases/" + release.path("id").asString()).status());
        probes.put("GET /agents/{B}", admin.get("/api/v1/agents/" + definition).status());
        probes.put("GET /environments/{B}", admin.get("/api/v1/environments/" + environment).status());
        probes.put("B: GET /runs/{A}", b.get("/api/v1/runs/" + goldenRun.path("id").asString()).status());
        probes.put("B: GET /deployments/{A}", b.get("/api/v1/deployments/" + webDeploymentId).status());
        probes.put("B: GET /runs/{A}/stream", b.stream("/api/v1/runs/" + goldenRun.path("id").asString()
                + "/stream?cursor=0", null, event -> true, line -> EVIDENCE.transcript("j6-cross-tenant.sse", line))
                .status());
        probes.put("B: POST /runs/{A}/cancel", b.post("/api/v1/runs/" + goldenRun.path("id").asString() + "/cancel",
                Map.of()).status());
        probes.put("B: POST /deployments/{A}/runs", b.post("/api/v1/deployments/" + webDeploymentId + "/runs",
                Map.of("input", Map.of("prompt", "intrusion"))).status());
        // A distinct principal who is a member of tenant B only: created and enrolled through the public API.
        Api.Response user = sessionB.post("/api/v1/users", Map.of("username", "tenant-b-operator",
                "displayName", "Tenant B Operator", "organizationRole", "MEMBER", "workspaceRole", "OPERATOR"));
        assertThat(user.status()).as(user.raw()).isEqualTo(201);
        Api userSession = new Api(this::base);
        String temporary = user.json().path("temporaryPassword").asString();
        assertThat(userSession.post("/api/v1/auth/login", Map.of("username", "tenant-b-operator",
                "password", temporary)).status()).isEqualTo(200);
        String userPassword = "tenant-b-" + UUID.randomUUID();
        Api.Response changed = userSession.post("/api/v1/auth/password", Map.of("currentPassword", temporary,
                "newPassword", userPassword));
        assertThat(changed.status()).as(changed.raw()).isEqualTo(200);
        assertThat(changed.json().path("workspaceId").asString()).isEqualTo(tenantB.toString());
        Api.Response userToken = userSession.post("/api/v1/tokens", Map.of("name", "tenant-b-operator",
                "expiresInDays", 1));
        assertThat(userToken.status()).as(userToken.raw()).isEqualTo(201);
        Api operatorB = new Api(this::base).bearer(userToken.json().path("token").asString());
        assertThat(operatorB.get("/api/v1/runs/" + runB).status()).isEqualTo(200);
        probes.put("operator B: GET /runs/{A}", operatorB.get("/api/v1/runs/" + goldenRun.path("id").asString())
                .status());
        probes.put("operator B: GET /deployments/{A}", operatorB.get("/api/v1/deployments/" + webDeploymentId)
                .status());
        probes.put("operator B: GET /runs/{A}/stream", operatorB.stream("/api/v1/runs/"
                + goldenRun.path("id").asString() + "/stream?cursor=0", null, event -> true,
                line -> EVIDENCE.transcript("j6-cross-tenant.sse", line)).status());
        probes.put("operator B: POST /deployments/{A}/runs", operatorB.post("/api/v1/deployments/" + webDeploymentId
                + "/runs", Map.of("input", Map.of("prompt", "intrusion"))).status());
        probes.put("operator B: POST /runs/{A}/cancel", operatorB.post("/api/v1/runs/"
                + goldenRun.path("id").asString() + "/cancel", Map.of()).status());
        probes.forEach((probe, status) -> assertThat(status).as(probe).isEqualTo(404));
        // Operator B cannot sign in to tenant A's workspace at all.
        Api.Response intoA = new Api(this::base).post("/api/v1/auth/login", Map.of("username", "tenant-b-operator",
                "password", userPassword, "workspaceId", workspaceId.toString()));
        assertThat(intoA.status()).as(intoA.raw()).isEqualTo(403);
        assertThat(intoA.code()).isEqualTo("WORKSPACE_SCOPE_UNAVAILABLE");
        assertThat(admin.get("/api/v1/runs?limit=200").raw()).doesNotContain(runB);
        assertThat(b.get("/api/v1/runs?limit=200").json().path("items")).hasSize(1);
        // The Console confines a session the same way.
        Api consoleA = new Api(this::base);
        assertThat(consoleA.consoleSignIn("journey-admin", PASSWORD).status()).isEqualTo(303);
        assertThat(consoleA.page("/console/runs/" + runB).status()).isEqualTo(404);
        // Nothing tenant A tried changed tenant B's run.
        JsonNode after = b.get("/api/v1/runs/" + runB).json();
        assertThat(after.path("lifecycle").asString()).isEqualTo("SUCCEEDED");
        assertThat(after.path("eventSequence").asLong()).isEqualTo(sequenceB);

        EVIDENCE.fact(journey, "tenantB", tenantB.toString());
        EVIDENCE.fact(journey, "tenantBRun", finishedB);
        EVIDENCE.fact(journey, "probes", probes);
        EVIDENCE.fact(journey, "operatorBSignInToA", intoA.status() + " " + intoA.code());
        EVIDENCE.fact(journey, "provisioning", "workspace and membership inserted through the documented database "
                + "administrator boundary; self-service workspace provisioning is outside the ADR-030 OSS 1.0 "
                + "minimum");
    }

    // ---------------------------------------------------------------------------------------------------------------
    // AC5: the Console and the REST API show the same final state and correlation identifiers.
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    @Order(7)
    @DisplayName("J7 Console and REST agree on the final state, pins, and correlation identifiers")
    void j7ConsoleMatchesRest() {
        String journey = "j7ConsoleMatchesRest";
        Api console = new Api(this::base);
        assertThat(console.consoleSignIn("journey-admin", PASSWORD).status()).isEqualTo(303);
        String runId = goldenRun.path("id").asString();
        JsonNode view = admin.get("/api/v1/runs/" + runId).json();
        Api.Response page = console.page("/console/runs/" + runId);
        assertThat(page.status()).isEqualTo(200);
        Map<String, String> compared = new LinkedHashMap<>();
        // Each value is read from its own labelled row (<dt>label</dt><dd>value</dd>) of the page and compared with
        // the API's field, so a value shown under another label, or a missing one, fails.
        Map<String, String> rows = definitions(page.raw());
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("Agent definition", "definitionId");
        labels.put("Agent version", "versionId");
        labels.put("Release", "releaseId");
        labels.put("Deployment", "deploymentId");
        labels.put("Environment", "environmentId");
        labels.put("Deployment revision", "revisionId");
        labels.put("Cohort", "cohortId");
        labels.put("Routing generation", "routingGenerationId");
        labels.put("Trace id", "traceId");
        labels.put("Manifest version", "manifestVersion");
        labels.put("Admission", "admission");
        labels.put("Events", "eventSequence");
        labels.forEach((label, field) -> {
            String value = view.path(field).asString();
            assertThat(value).as(field).isNotBlank();
            assertThat(rows.get(label)).as(label).isNotNull();
            assertThat(rows.get(label).split("\\s+")[0]).as(label).isEqualTo(value);
            compared.put(label, value);
        });
        assertThat(rows.get("Model calls")).isEqualTo(view.path("usage").path("modelCalls").asString());
        assertThat(rows.get("Tool calls")).isEqualTo(view.path("usage").path("toolCalls").asString());
        compared.put("Model calls", rows.get("Model calls"));
        compared.put("Tool calls", rows.get("Tool calls"));
        assertThat(page.raw()).contains("Run <code>" + runId + "</code>");
        assertThat(view.path("lifecycle").asString()).isEqualTo("SUCCEEDED");
        assertThat(page.raw()).contains("console-lifecycle\">SUCCEEDED</span>");
        compared.put("lifecycle", "SUCCEEDED");
        assertThat(page.raw()).contains("journey-model-a").contains("RUN_SUCCEEDED").contains("TOOL_COMPLETED");
        assertThat(page.raw()).contains("data-complete=\"true\"");
        // The step projection (the per-step correlation of event sequences), event count, and usage agree.
        JsonNode steps = admin.get("/api/v1/runs/" + runId + "/steps").json().path("items");
        assertThat(steps.size()).isGreaterThanOrEqualTo(3);
        List<String> stepRanges = new ArrayList<>();
        for (JsonNode step : steps) {
            String range = step.path("firstSequence").asLong() + "\u2013" + step.path("lastSequence").asLong();
            assertThat(page.raw()).as("step " + step.path("name").asString()).contains(range)
                    .contains(step.path("name").asString());
            stepRanges.add(step.path("name").asString() + " " + range);
        }
        Api.Response list = console.page("/console/runs");
        assertThat(list.raw()).contains(runId);
        EVIDENCE.fact(journey, "comparedFields", compared);
        EVIDENCE.fact(journey, "steps", stepRanges);
        EVIDENCE.fact(journey, "eventSequence", view.path("eventSequence").asLong());
        EVIDENCE.transcript("j7-console-run.html", page.raw()
                .replaceAll("X-CSRF-Token&quot;:&quot;[^&]+&quot;", "X-CSRF-Token&quot;:&quot;****&quot;"));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // AC3: kill the server during slow model streaming; the run recovers, without duplicate effects.
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    @Order(8)
    @DisplayName("J8 restart: SIGKILL during model streaming; the run recovers with no duplicate effect")
    void j8KillDuringStreamingRecovers() throws Exception {
        String journey = "j8KillDuringStreamingRecovers";
        List<String> effectsBefore = effects();
        Files.writeString(holdFile, "held");
        String runId = submit(admin, webDeploymentId, "slow restart-1");
        String client = UUID.randomUUID().toString();
        String path = "/api/v1/runs/" + runId + "/stream?cursor=0&client=" + client;
        // Watch until two chunks were durably recorded, then kill the server.
        int[] chunks = {0};
        Api.Stream first = admin.stream(path, null,
                event -> "MODEL_STREAM_CHUNK".equals(event.name()) && ++chunks[0] >= 2,
                line -> EVIDENCE.transcript("j8-restart.sse", line));
        assertThat(chunks[0]).isEqualTo(2);
        JsonNode beforeKill = admin.get("/api/v1/runs/" + runId).json();
        assertThat(beforeKill.path("lifecycle").asString()).isEqualTo("ACTIVE");
        List<JsonNode> eventsBeforeKill = events(admin, runId);
        Instant killedAt = Instant.now();
        long pid = server.pid();
        String killedServer = server.name;
        int exit = server.kill();
        assertThat(exit).as("SIGKILL").isEqualTo(137);
        EVIDENCE.transcript("j8-restart.sse", "# server " + server.name + " (pid " + pid + ") killed with SIGKILL at "
                + killedAt + ", exit " + exit + "\n");
        Files.delete(holdFile);

        server = start(Map.of());
        // The client reconnects as an EventSource does: Last-Event-ID and the same client id.
        Api.Stream second = admin.stream(path, first.lastEventId(), event -> false,
                line -> EVIDENCE.transcript("j8-restart.sse", line));
        assertThat(second.status()).isEqualTo(200);
        assertThat(second.ended()).isTrue();
        List<Long> delivered = new ArrayList<>();
        first.events().stream().filter(event -> event.id() != null).forEach(event -> delivered.add(event.sequence()));
        second.events().stream().filter(event -> event.id() != null).forEach(event -> delivered.add(event.sequence()));
        assertGapless(delivered, 1);

        JsonNode recovered = awaitTerminal(admin, runId);
        List<JsonNode> events = events(admin, runId);
        EVIDENCE.events("j8-restart-events.json", events);
        assertThat(recovered.path("lifecycle").asString()).isEqualTo("SUCCEEDED");
        assertThat(delivered).hasSize(events.size());
        // The history before the kill is unchanged: it is a prefix of the final history.
        assertThat(digests(events).subList(0, eventsBeforeKill.size())).isEqualTo(digests(eventsBeforeKill));
        assertThat(count(events, "RUN_SUCCEEDED")).isOne();
        assertThat(events.stream().filter(event -> TERMINAL_EVENTS.contains(event.path("type").asString())).count())
                .isOne();
        assertThat(count(events, "RUN_RECOVERED")).isPositive();
        assertThat(count(events, "MODEL_REQUESTED")).as("the interrupted exchange is repeated once").isEqualTo(2);
        assertThat(count(events, "MODEL_RESPONSE_RECEIVED")).isOne();
        assertThat(types(events)).doesNotContain("TOOL_REQUESTED", "TOOL_STARTED");
        // The output is the recovered attempt's four-chunk answer, not the interrupted 600-chunk one.
        assertThat(first(events, "RUN_SUCCEEDED").toString()).contains("slow-003").doesNotContain("slow-004");
        assertThat(effects()).as("no external effect, and none duplicated").isEqualTo(effectsBefore);

        Map<String, Object> injection = new LinkedHashMap<>();
        injection.put("runId", runId);
        injection.put("signal", "SIGKILL (Process.destroyForcibly)");
        injection.put("killedServer", killedServer);
        injection.put("killedPid", pid);
        injection.put("killedAt", killedAt.toString());
        injection.put("exitCode", exit);
        injection.put("chunksStreamedBeforeKill", chunks[0]);
        injection.put("runBeforeKill", beforeKill);
        injection.put("eventsBeforeKill", eventsBeforeKill.size());
        injection.put("restartedServer", server.name);
        injection.put("runAfterRecovery", recovered);
        injection.put("eventTypes", types(events));
        injection.put("modelRequests", count(events, "MODEL_REQUESTED"));
        injection.put("ledgerEffectsBefore", effectsBefore);
        injection.put("ledgerEffectsAfter", effects());
        injection.put("safeBoundary", "the executor checkpoints only once the model has answered (RESPONDED): recovery "
                + "repeats the whole exchange; this run calls no tool, and j8bKillAfterAToolRanRecovers measures "
                + "the repeat when one ran (gap, #39)");
        EVIDENCE.json("restart-injection.json", injection);
        EVIDENCE.fact(journey, "restartInjection", "restart-injection.json");
        EVIDENCE.fact(journey, "eventTypes", types(events));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // AC3 effect boundary: a kill after a committed tool effect replays it without invoking the tool again (#39).
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    @Order(9)
    @DisplayName("J8b SIGKILL after a tool ran: recovery replays the durable effect without repeating the tool")
    void j8bKillAfterAToolRanRecovers() throws Exception {
        String journey = "j8bKillAfterAToolRanRecovers";
        Files.writeString(holdFile, "held");
        String runId = submit(admin, webDeploymentId, "toolslow journey_ledger repeat-1");
        String client = UUID.randomUUID().toString();
        String path = "/api/v1/runs/" + runId + "/stream?cursor=0&client=" + client;
        // Watch until the tool completed and two chunks of the following answer were recorded, then kill.
        boolean[] toolDone = {false};
        int[] chunks = {0};
        admin.stream(path, null, event -> {
            if ("TOOL_COMPLETED".equals(event.name())) toolDone[0] = true;
            return toolDone[0] && "MODEL_STREAM_CHUNK".equals(event.name()) && ++chunks[0] >= 2;
        }, line -> EVIDENCE.transcript("j8b-kill-after-tool.sse", line));
        assertThat(toolDone[0]).isTrue();
        assertThat(chunks[0]).isEqualTo(2);
        assertThat(effects().stream().filter("repeat-1"::equals).count()).isOne();
        List<JsonNode> beforeKill = events(admin, runId);
        String killed = server.name;
        int exit = server.kill();
        assertThat(exit).as("SIGKILL").isEqualTo(137);
        Files.delete(holdFile);
        server = start(Map.of());

        JsonNode run = awaitTerminal(admin, runId);
        List<JsonNode> events = events(admin, runId);
        EVIDENCE.events("j8b-kill-after-tool-events.json", events);
        // The run ends once, its history before the crash remains unchanged, and recovery replays the committed
        // durable effect instead of invoking the provider again.
        assertThat(run.path("lifecycle").asString()).isEqualTo("SUCCEEDED");
        assertThat(count(events, "RUN_SUCCEEDED")).isOne();
        assertThat(events.stream().filter(event -> TERMINAL_EVENTS.contains(event.path("type").asString())).count())
                .isOne();
        assertThat(digests(events).subList(0, beforeKill.size())).isEqualTo(digests(beforeKill));
        assertThat(count(events, "RUN_RECOVERED")).isPositive();
        long toolRuns = count(events, "TOOL_STARTED");
        long ledgerEntries = effects().stream().filter("repeat-1"::equals).count();
        toolRepeatObserved = ledgerEntries > 1;
        assertThat(toolRuns).isGreaterThanOrEqualTo(1);
        assertThat(ledgerEntries).isEqualTo(1);
        EVIDENCE.fact(journey, "crash", killed + " killed with SIGKILL (exit " + exit + ") after TOOL_COMPLETED, "
                + "during the answer's stream; recovered by " + server.name);
        EVIDENCE.fact(journey, "eventTypes", types(events));
        EVIDENCE.fact(journey, "measuredToolStarts", toolRuns);
        EVIDENCE.fact(journey, "measuredLedgerEntries", ledgerEntries);
        EVIDENCE.fact(journey, "durableEffectReplay",
                "the tool and external ledger effect each ran once across recovery");
    }

    // ---------------------------------------------------------------------------------------------------------------
    // AC4, denied capability, variant 2: a node limit of zero tool calls refuses the call before the tool runs.
    // AC2: the history recorded before two restarts is unchanged.
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    @Order(10)
    @DisplayName("J9 denied capability by limit, and history unchanged across restarts")
    void j9ToolCallLimitRefusesTheCall() {
        String journey = "j9ToolCallLimitRefusesTheCall";
        server.stop();
        server = start(Map.of("agentworks.runs.limits.max-tool-calls", "0"));
        List<String> before = effects();
        String runId = submit(admin, webDeploymentId, "tool journey_ledger limit-1");
        JsonNode run = awaitTerminal(admin, runId);
        List<JsonNode> events = events(admin, runId);
        EVIDENCE.events("j9-limit-events.json", events);
        assertThat(run.path("lifecycle").asString()).isEqualTo("FAILED");
        assertThat(run.path("terminalCode").asString()).isEqualTo("LIMIT_EXCEEDED");
        assertThat(first(events, "RUN_CREATED").toString()).contains("\"maxToolCalls\":0");
        assertThat(types(events)).doesNotContain("TOOL_STARTED", "TOOL_COMPLETED");
        assertThat(types(events)).endsWith("TOOL_REQUESTED", "LIMIT_EXCEEDED", "RUN_FAILED");
        assertThat(effects()).isEqualTo(before);

        // The golden run's history is byte-for-byte the same after three SIGKILLs and a graceful restart.
        List<JsonNode> golden = events(admin, goldenRun.path("id").asString());
        assertThat(goldenDigests).isNotEmpty().doesNotHaveDuplicates();
        assertThat(count(golden, "CAPABILITY_RESOLVED")).isEqualTo(2);
        assertThat(digests(golden)).isEqualTo(goldenDigests);
        assertThat(admin.get("/api/v1/runs/" + goldenRun.path("id").asString()).json().path("lifecycle").asString())
                .isEqualTo("SUCCEEDED");
        EVIDENCE.fact(journey, "run", run);
        EVIDENCE.fact(journey, "eventTypes", types(events));
        EVIDENCE.fact(journey, "goldenHistoryUnchanged", goldenDigests.size() + " event digests identical");
        EVIDENCE.fact(journey, "gap", "the limit is node-wide configuration, not per agent (#41 AgentSpec limits; "
                + "#39 tool policy)");
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The scripted fake model stays refused outside the loopback local-development gate.
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    @Order(11)
    @DisplayName("J10 the fake model gateway is refused outside the local-development gate")
    void j10FakeGatewayRefusedOutsideTheGate() {
        String journey = "j10FakeGatewayRefusedOutsideTheGate";
        localJourneysFinished = true;
        server.stop();
        Map<String, String> properties = properties(Map.of("agentworks.development.local", "false"));
        ServerProcess.Refusal refusal = ServerProcess.expectRefusal("server-refused",
                ServerProcess.command(serverJar, extensionJar, properties), database.serverEnvironment(), EVIDENCE);
        refusedServerStarts++;
        assertThat(refusal.exitCode()).isNotZero();
        assertThat(refusal.output()).contains("GatewayConfigurationException")
                .contains("ConfiguredGateway bean 'journey'").contains("deterministic-fake");
        EVIDENCE.fact(journey, "exitCode", refusal.exitCode());
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The packaged jar alone invokes the direct adapter under the production profile. The endpoint belongs to the
    // harness process, not the server extension, and speaks the adapter's public OpenAI-compatible HTTP contract.
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    @Order(12)
    @DisplayName("J11 production profile: the packaged jar completes a model run through the direct adapter")
    void j11ProductionProfileInstall() throws Exception {
        String journey = "j11ProductionProfileInstall";
        productionDatabase = new JourneyDatabase().start();
        productionModel = new OpenAiCompatibleStub();
        Path productionToken = EVIDENCE.work("production-bootstrap-token");
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("server.port", "0");
        properties.put("server.address", "0.0.0.0");
        properties.putAll(productionDatabase.serverProperties());
        properties.put("agentworks.node-id", "journey-production");
        properties.put("agentworks.development.local", "false");
        properties.put("agentworks.security.bootstrap.token-file", productionToken.toString());
        properties.put("agentworks.runs.worker.enabled", "true");
        properties.put("agentworks.runs.worker.threads", "1");
        properties.put("agentworks.runs.worker.idle-backoff", "25ms");
        properties.put("agentworks.gateway.gateways.production.type", "openai-compatible");
        properties.put("agentworks.gateway.gateways.production.base-url", productionModel.baseUrl());
        properties.put("agentworks.gateway.gateways.production.models[0].alias", "journey-model");
        properties.put("agentworks.gateway.gateways.production.models[0].model", "journey-provider-model");
        properties.put("agentworks.gateway.gateways.production.capabilities", "STREAMING,USAGE");
        Map<String, String> environment = new LinkedHashMap<>(productionDatabase.serverEnvironment());
        // A fresh random idempotency key, never the published local one; passed through the environment.
        String hmacKey = java.util.Base64.getEncoder().encodeToString(randomBytes(32));
        environment.put("SPRING_APPLICATION_JSON", "{\"agentworks\":{\"idempotency\":{\"active-key-version\":1,"
                + "\"hmac-keys\":{\"1\":\"" + hmacKey + "\"}}}}");
        production = ServerProcess.start("server-production", ServerProcess.productCommand(serverJar, properties),
                environment, EVIDENCE);
        Api anonymous = new Api(() -> production.baseUrl());
        String output = production.output();
        assertThat(output).contains("No active profile set").doesNotContain("JourneyExtension");

        long deadline = System.nanoTime() + Duration.ofMinutes(1).toNanos();
        while (!production.output().contains("A one-time bootstrap token was written to")
                && System.nanoTime() < deadline) {
            ServerProcess.sleep(100);
        }
        assertThat(Files.getPosixFilePermissions(productionToken)).containsExactlyInAnyOrder(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
        assertThat(anonymous.get("/api/v1/agents").status()).isEqualTo(401);
        Api.Response created = anonymous.post("/api/v1/auth/bootstrap", Map.of("token",
                Files.readString(productionToken).strip(), "username", "production-admin", "password", PASSWORD));
        assertThat(created.status()).as(created.raw()).isEqualTo(201);

        Api session = new Api(() -> production.baseUrl());
        Api.Response login = session.post("/api/v1/auth/login", Map.of("username", "production-admin",
                "password", PASSWORD));
        assertThat(login.status()).as(login.raw()).isEqualTo(200);
        String cookie = login.header("Set-Cookie");
        assertThat(cookie).as("the production session cookie").startsWith("__Host-agentworks-session=")
                .contains("Secure").contains("HttpOnly");
        Api.Response token = session.post("/api/v1/tokens", Map.of("name", "production", "role", "ADMIN",
                "expiresInDays", 1));
        assertThat(token.status()).as(token.raw()).isEqualTo(201);
        Api api = new Api(() -> production.baseUrl()).bearer(token.json().path("token").asString());

        // An agent that pins the harness's fake gateway cannot be released here: no such gateway exists.
        String definition = createAgent(api, "production-agent");
        JsonNode faked = publish(api, definition, spec("production-agent", "1.0.0", "reference", "Fake.", false));
        Api.Response refused = api.post("/api/v1/agent-versions/" + faked.path("id").asString() + "/releases",
                Map.of("name", "production-agent-fake"));
        assertThat(refused.status()).as(refused.raw()).isBetween(400, 499);
        assertThat(refused.code()).isEqualTo("GATEWAY_NOT_CONFIGURED");

        // The direct adapter resolves this role at release time; the enabled worker then invokes it from the run pin.
        JsonNode version = publish(api, definition, """
                apiVersion: dvarahq.com/v1alpha1
                kind: Agent
                metadata:
                  name: production-agent
                  version: 1.1.0
                spec:
                  executor:
                    type: spring-ai
                  instructions:
                    system: Answer using the configured production gateway.
                  models:
                    reasoning: {gateway: production, model: journey-model}
                """);
        JsonNode release = release(api, version.path("id").asString(), "production-agent-1");
        String environmentId = api.post("/api/v1/environments", Map.of("name", "production")).json().path("id")
                .asString();
        JsonNode promoted = promote(api, environmentId, "web", release.path("id").asString(),
                "production-promote-" + UUID.randomUUID(), "\"0\"");
        Api.Response submitted = api.post("/api/v1/deployments/" + promoted.path("deploymentId").asString() + "/runs",
                Map.of("input", Map.of("prompt", "prove the production model path")),
                Map.of("Idempotency-Key", "production-run-" + UUID.randomUUID()));
        assertThat(submitted.status()).as(submitted.raw()).isEqualTo(201);
        JsonNode run = submitted.json();
        assertThat(run.path("releaseId").asString()).isEqualTo(release.path("id").asString());
        assertThat(run.path("revisionId").asString()).isEqualTo(promoted.path("revisionId").asString());
        assertThat(run.path("cohortId").asString()).isEqualTo(promoted.path("cohortId").asString());
        Api.Stream stream = api.stream("/api/v1/runs/" + run.path("id").asString() + "/stream?cursor=0&client="
                + UUID.randomUUID(), null, event -> "stream-end".equals(event.name()),
                line -> EVIDENCE.transcript("j11-production-model.sse", line));
        assertThat(stream.status()).isEqualTo(200);
        assertThat(stream.ended()).isTrue();
        JsonNode finished = awaitTerminal(api, run.path("id").asString());
        assertThat(finished.path("lifecycle").asString()).isEqualTo("SUCCEEDED");
        List<JsonNode> events = events(api, run.path("id").asString());
        assertThat(types(events)).contains("MODEL_REQUESTED", "MODEL_STREAM_CHUNK", "MODEL_RESPONSE_RECEIVED",
                "RUN_SUCCEEDED");
        assertThat(stream.events().stream().filter(event -> event.id() != null).map(Api.SseEvent::name).toList())
                .contains("MODEL_REQUESTED", "MODEL_RESPONSE_RECEIVED", "RUN_SUCCEEDED");
        assertThat(productionModel.requests()).isEqualTo(1);
        assertThat(productionModel.lastRequest().path("model").asString()).isEqualTo("journey-provider-model");
        assertThat(productionModel.lastRequest().toString()).contains("prove the production model path");
        assertThat(productionModel.authorization()).as("no credential is configured or sent").isNull();
        assertThat(production.stop()).as("graceful stop").isIn(0, 143);

        EVIDENCE.fact(journey, "command", "java -jar <server jar> (no loader path, no profile), server.address=0.0.0.0, "
                + "worker enabled, direct adapter targeting a harness-owned loopback endpoint, idempotency key random "
                + "and passed in the environment");
        EVIDENCE.fact(journey, "sessionCookie", cookie.replaceFirst("=[^;]*", "=****"));
        EVIDENCE.fact(journey, "fakeGatewayRelease", refused.status() + " " + refused.code());
        EVIDENCE.fact(journey, "run", finished);
        EVIDENCE.fact(journey, "eventTypes", types(events));
        EVIDENCE.fact(journey, "providerRequest", Map.of("model", "journey-provider-model", "stream", true,
                "authorizationHeaderPresent", false, "requests", productionModel.requests()));
    }

    // ---- lifecycle helpers ----------------------------------------------------------------------------------------

    private ServerProcess start(Map<String, String> overrides) {
        serverCount++;
        return ServerProcess.start("server-" + serverCount,
                ServerProcess.command(serverJar, extensionJar, properties(overrides)), database.serverEnvironment(),
                EVIDENCE);
    }

    private Map<String, String> properties(Map<String, String> overrides) {
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("spring.profiles.active", "local");
        properties.put("server.port", "0");
        properties.putAll(database.serverProperties());
        properties.put("agentworks.node-id", "journey-node");
        properties.put("agentworks.security.bootstrap.token-file", tokenFile.toString());
        properties.put("agentworks.runs.worker.enabled", "true");
        properties.put("agentworks.runs.worker.threads", "4");
        properties.put("agentworks.runs.worker.id-prefix", "journey-node");
        // A lease long enough that a CI stall (GC, a busy runner) never lapses it, so no run is recovered unless the
        // journey killed its server, and the exact event counts hold. Recovery after a kill waits out this lease.
        properties.put("agentworks.runs.worker.lease-duration", "30s");
        properties.put("agentworks.runs.worker.idle-backoff", "100ms");
        properties.put("agentworks.spring-ai.chunk-batch-chars", "8");
        properties.put("journey.extension.enabled", "true");
        properties.put("journey.effects-file", effectsFile.toString());
        properties.put("journey.hold-file", holdFile.toString());
        properties.putAll(overrides);
        return properties;
    }

    private String base() {
        return server.baseUrl();
    }

    // ---- API helpers ----------------------------------------------------------------------------------------------

    private static String spec(String name, String version, String modelAlias, String system, boolean ledgerPack) {
        return """
                apiVersion: dvarahq.com/v1alpha1
                kind: Agent
                metadata:
                  name: %s
                  version: %s
                spec:
                  executor:
                    type: spring-ai
                  instructions:
                    system: %s
                  models:
                    reasoning: {gateway: journey, model: %s}
                %s""".formatted(name, version, system, modelAlias, ledgerPack ? """
                  execution:
                    initialIntent: EXECUTE
                    autonomyProfile: SUPERVISED
                  capabilityPacks: [journey-ledger]
                """ : "");
    }

    private static String createAgent(Api api, String name) {
        Api.Response created = api.post("/api/v1/agents", Map.of("name", name));
        assertThat(created.status()).as(created.raw()).isEqualTo(201);
        return created.json().path("id").asString();
    }

    private static JsonNode publish(Api api, String definitionId, String yaml) {
        Api.Response published = api.send("POST", "/api/v1/agents/" + definitionId + "/versions", yaml,
                Map.of("Content-Type", "application/yaml"));
        assertThat(published.status()).as(published.raw()).isEqualTo(201);
        return published.json();
    }

    private static JsonNode release(Api api, String versionId, String name) {
        Api.Response release = api.post("/api/v1/agent-versions/" + versionId + "/releases", Map.of("name", name));
        assertThat(release.status()).as(release.raw()).isEqualTo(201);
        return release.json();
    }

    private static JsonNode promote(Api api, String environmentId, String deployment, String releaseId, String key,
            String ifMatch) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Idempotency-Key", key);
        if (ifMatch != null) headers.put("If-Match", ifMatch);
        Api.Response promoted = api.post("/api/v1/environments/" + environmentId + "/promotions",
                Map.of("deployment", deployment, "releaseId", releaseId, "reason", "journey"), headers);
        assertThat(promoted.status()).as(promoted.raw()).isEqualTo(201);
        assertThat(promoted.json().path("outcome").asString()).isNotEqualTo("DENIED");
        assertThat(promoted.json().path("deploymentId").isNull()).isFalse();
        return promoted.json();
    }

    private static String submit(Api api, String deploymentId, String prompt) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("prompt", prompt);
        // Even the negative unknown-tool journey needs one selected callback so Spring AI enters its tool loop;
        // the scripted model then names a different callback and proves it cannot be resolved into existence.
        if (prompt.startsWith("tool ") || prompt.contains("journey_ledger")) {
            input.put("capabilityDetails", List.of(LEDGER_CAPABILITY));
        }
        Api.Response submitted = api.post("/api/v1/deployments/" + deploymentId + "/runs",
                Map.of("input", input));
        assertThat(submitted.status()).as(submitted.raw()).isEqualTo(201);
        return submitted.json().path("id").asString();
    }

    /** Polls the run's public state until it ends. */
    private static JsonNode awaitTerminal(Api api, String runId) {
        long deadline = System.nanoTime() + RUN_TIMEOUT.toNanos();
        JsonNode run = null;
        while (System.nanoTime() < deadline) {
            run = api.get("/api/v1/runs/" + runId).json();
            if (TERMINAL.contains(run.path("lifecycle").asString())) return run;
            ServerProcess.sleep(100);
        }
        throw new AssertionError("run " + runId + " did not end within " + RUN_TIMEOUT + ": " + run);
    }

    private static void awaitEvents(Api api, String runId, String type, long atLeast) {
        long deadline = System.nanoTime() + RUN_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (count(events(api, runId), type) >= atLeast) return;
            ServerProcess.sleep(100);
        }
        throw new AssertionError("run " + runId + " recorded fewer than " + atLeast + " " + type + " within "
                + RUN_TIMEOUT);
    }

    /** The run's whole history through the verified event pages, checked to be gapless from sequence 1. */
    private static List<JsonNode> events(Api api, String runId) {
        List<JsonNode> events = new ArrayList<>();
        String cursor = "0";
        while (cursor != null) {
            Api.Response page = api.get("/api/v1/runs/" + runId + "/events?limit=200&cursor=" + cursor);
            assertThat(page.status()).as(page.raw()).isEqualTo(200);
            page.json().path("items").forEach(events::add);
            JsonNode next = page.json().path("nextCursor");
            cursor = next.isNull() || next.isMissingNode() ? null : next.asString();
        }
        assertGapless(events.stream().map(event -> event.path("sequence").asLong()).toList(), 1);
        return events;
    }

    private static void assertGapless(List<Long> sequences, long from) {
        assertThat(sequences).isNotEmpty();
        for (int index = 0; index < sequences.size(); index++) {
            assertThat(sequences.get(index)).as("sequence at position " + index).isEqualTo(from + index);
        }
    }

    /** The Console page's labelled rows: each {@code <dt>} label with its {@code <dd>} text, tags removed. */
    static Map<String, String> definitions(String html) {
        Map<String, String> rows = new LinkedHashMap<>();
        java.util.regex.Matcher row = java.util.regex.Pattern.compile("<dt[^>]*>(.*?)</dt>\\s*<dd[^>]*>(.*?)</dd>",
                java.util.regex.Pattern.DOTALL).matcher(html);
        while (row.find()) {
            String label = row.group(1).replaceAll("<[^>]+>", "").strip();
            String value = row.group(2).replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").strip();
            assertThat(rows.putIfAbsent(label, value)).as("label " + label + " appears once").isNull();
        }
        return rows;
    }

    private static List<String> types(List<JsonNode> events) {
        return events.stream().map(event -> event.path("type").asString()).toList();
    }

    private static List<String> digests(List<JsonNode> events) {
        return events.stream().map(event -> event.path("eventDigest").asString()).toList();
    }

    private static long count(List<JsonNode> events, String type) {
        return events.stream().filter(event -> type.equals(event.path("type").asString())).count();
    }

    private static JsonNode first(List<JsonNode> events, String type) {
        return events.stream().filter(event -> type.equals(event.path("type").asString())).findFirst()
                .orElseThrow(() -> new AssertionError("no " + type + " in " + types(events)));
    }

    private List<String> effects() {
        try {
            return Files.exists(effectsFile) ? Files.readAllLines(effectsFile) : List.of();
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private void executeAs(String login, String sql, UUID runId) throws SQLException {
        try (Connection connection = database.connect(login); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, runId);
            statement.executeUpdate();
        }
    }

    private void rememberedPermission(UUID permissionId, long version, UUID environment, boolean revoked)
            throws SQLException {
        try (Connection connection = database.connect("journey_control")) {
            if (version == 1) {
                update(connection, "insert into remembered_permission_identity(permission_id, organization_id, "
                                + "scope_level, scope_id) values (?, ?, 'WORKSPACE', ?)", permissionId,
                        organizationId, workspaceId);
            }
            update(connection, "insert into remembered_permission_rule(permission_id, version, organization_id, "
                            + "scope_level, scope_id, actor_id, capability, resource, action, environment_id, effect, "
                            + "maximum_risk, valid_from, expires_at, revoked_at) values (?, ?, ?, 'WORKSPACE', ?, ?, "
                            + "?, 'journey-ledger', 'write', ?, 'ALLOW', "
                            + "'HIGH', current_timestamp - interval '1 minute', current_timestamp + interval '1 day', "
                            + (revoked ? "current_timestamp)" : "null)"),
                    permissionId, version, organizationId, workspaceId, adminUserId, LEDGER_CAPABILITY, environment);
        }
    }

    private static void update(Connection connection, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) statement.setObject(index + 1, values[index]);
            assertThat(statement.executeUpdate()).isOne();
        }
    }

    private static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        new java.security.SecureRandom().nextBytes(bytes);
        return bytes;
    }

    // ---- artifact identity ----------------------------------------------------------------------------------------

    private Map<String, Object> artifacts() throws Exception {
        Map<String, Object> artifacts = new LinkedHashMap<>();
        artifacts.put("serverJar", serverJar.toAbsolutePath().normalize().toString());
        artifacts.put("serverJarSha256", sha256(serverJar));
        artifacts.put("extensionJar", extensionJar.toAbsolutePath().normalize().toString());
        artifacts.put("extensionJarSha256", sha256(extensionJar));
        List<String> extensionEntries = new ArrayList<>();
        try (JarFile jar = new JarFile(extensionJar.toFile())) {
            jar.stream().map(ZipEntry::getName).filter(name -> !name.endsWith("/")).forEach(extensionEntries::add);
        }
        artifacts.put("extensionEntries", extensionEntries);
        try (JarFile jar = new JarFile(serverJar.toFile())) {
            artifacts.put("serverStartClass", jar.getManifest().getMainAttributes().getValue("Start-Class"));
            List<String> journeyEntries = jar.stream().map(ZipEntry::getName)
                    .filter(name -> name.contains("journey")).toList();
            assertThat(journeyEntries).as("the product jar contains nothing of the harness").isEmpty();
            ZipEntry runtime = jar.stream().filter(entry -> entry.getName().matches("BOOT-INF/lib/agentworks-runtime-.*\\.jar"))
                    .findFirst().orElseThrow();
            Map<String, String> identity;
            try (InputStream nested = jar.getInputStream(runtime)) {
                identity = buildIdentity(nested.readAllBytes());
            }
            artifacts.put("runtimeBuildIdentity", identity);
            // The jar under test must be built from the tree under test: GITHUB_SHA in CI (for a pull request, the
            // merge commit GitHub checked out), git HEAD locally. A stale jar fails here.
            String expected = expectedCommit(identity.get("commit"));
            artifacts.put("expectedCommit", expected);
            assertThat(identity.get("commit")).as("the server jar's stamped commit (a stale jar?)").isEqualTo(expected);
            assertThat(identity.get("clean")).as("the server jar must be built from a clean tree").isEqualTo("true");
        }
        return artifacts;
    }

    private static String expectedCommit(String stampedCommit) throws IOException, InterruptedException {
        String ci = System.getenv("GITHUB_SHA");
        if (ci != null && !ci.isBlank()) return ci;
        Process git;
        try {
            git = new ProcessBuilder("git", "rev-parse", "--verify", "HEAD^{commit}").redirectErrorStream(true)
                    .start();
        } catch (IOException unavailable) {
            return stampedCommit;
        }
        String head = new String(git.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        if (git.waitFor() != 0) return stampedCommit;
        return head;
    }

    private static Map<String, String> buildIdentity(byte[] runtimeJar) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(runtimeJar))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (entry.getName().equals("META-INF/agentworks/agentworks-runtime/build-identity.properties")) {
                    Properties properties = new Properties();
                    properties.load(new java.io.InputStreamReader(zip, StandardCharsets.UTF_8));
                    Map<String, String> identity = new LinkedHashMap<>();
                    properties.stringPropertyNames().stream().sorted()
                            .forEach(name -> identity.put(name, properties.getProperty(name)));
                    return identity;
                }
            }
        }
        return Map.of("stamp", "absent");
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[1 << 16];
            for (int read = in.read(buffer); read > 0; read = in.read(buffer)) digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
