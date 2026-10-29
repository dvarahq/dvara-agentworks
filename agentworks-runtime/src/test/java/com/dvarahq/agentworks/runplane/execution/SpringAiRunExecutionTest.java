package com.dvarahq.agentworks.runplane.execution;

import com.dvarahq.agentworks.agent.springai.SpringAiAgentExecutor;
import com.dvarahq.agentworks.agent.springai.SpringAiExecutorSettings;
import com.dvarahq.agentworks.domain.run.PinnedAgentVersion;
import com.dvarahq.agentworks.domain.run.RunEventType;
import com.dvarahq.agentworks.domain.run.RunInvoker;
import com.dvarahq.agentworks.domain.run.RunLifecycle;
import com.dvarahq.agentworks.domain.run.RunLimits;
import com.dvarahq.agentworks.domain.run.RunRecord;
import com.dvarahq.agentworks.gateway.AdapterInvocation;
import com.dvarahq.agentworks.gateway.CapabilityPolicy;
import com.dvarahq.agentworks.gateway.ConfiguredGateway;
import com.dvarahq.agentworks.gateway.GatewayErrorCode;
import com.dvarahq.agentworks.gateway.GatewayEvent;
import com.dvarahq.agentworks.gateway.GatewayRegistry;
import com.dvarahq.agentworks.gateway.ModelGateway;
import com.dvarahq.agentworks.gateway.ModelMessage;
import com.dvarahq.agentworks.gateway.ModelUsage;
import com.dvarahq.agentworks.gateway.ResponseFormat;
import com.dvarahq.agentworks.gateway.ToolCall;
import com.dvarahq.agentworks.gateway.fake.DeterministicFakeGatewayAdapter;
import com.dvarahq.agentworks.gateway.fake.FakeReply;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * Issue #34: the stateless Spring AI executor running durable runs end to end, over the in-memory persistence port:
 * the release-pinned model through Core's gateway, the recorded invoker's identity, durable model and tool events with
 * run, release, deployment, and trace correlation, structured output, cancellation, and the restart boundary.
 */
class SpringAiRunExecutionTest {
    private static final String SPEC = "{\"apiVersion\":\"dvarahq.com/v1alpha1\",\"kind\":\"Agent\",\"metadata\":"
            + "{\"name\":\"agent\",\"version\":\"1.0.0\"},\"spec\":{\"executor\":{\"type\":\"spring-ai\"},"
            + "\"instructions\":{\"system\":\"Answer tersely.\"}}}";
    private static final String DIGEST = "sha256:" + "a".repeat(64);

    private final List<GatewayEvent> gatewayEvents = new CopyOnWriteArrayList<>();

    static final class Calculator {
        final AtomicInteger calls = new AtomicInteger();

        @Tool(name = "add", description = "Adds two integers")
        int add(int left, int right) {
            calls.incrementAndGet();
            return left + right;
        }
    }

    private static EngineHarness harness() {
        return configured(new EngineHarness());
    }

    /** A second tenant in the same store and engine as {@code shared}. */
    private static EngineHarness tenantOf(EngineHarness shared) {
        return configured(new EngineHarness(shared));
    }

    private static EngineHarness configured(EngineHarness harness) {
        harness.store.manifest(harness.release, ReleaseManifestReaderTest.version2(harness.version, "pinned-model"));
        harness.manifestVersion = 2;
        harness.store.version(new PinnedAgentVersion(harness.version, SPEC, DIGEST));
        return harness;
    }

    private static RunInvoker invoker(EngineHarness harness) {
        return new RunInvoker(UUID.randomUUID(), harness.workspace.value(), UUID.randomUUID(), UUID.randomUUID(),
                "api-token");
    }

    private ModelGateway gateway(Function<AdapterInvocation, FakeReply> script) {
        // The live catalog maps the alias to another model: runs must still invoke the model their release pinned.
        var adapter = DeterministicFakeGatewayAdapter.builder("direct").model("coding-premium", "catalog-model")
                .script(script).build();
        return new ModelGateway(GatewayRegistry.of(List.of(new ConfiguredGateway(adapter, CapabilityPolicy.DEFAULT,
                null))), null, null, gatewayEvents::add, Clock.systemUTC());
    }

    private static SpringAiAgentExecutor executor(ModelGateway gateway, Object... tools) {
        return new SpringAiAgentExecutor(gateway, MethodToolCallbackProvider.builder().toolObjects(tools).build(),
                ObservationRegistry.NOOP, new SpringAiExecutorSettings(Duration.ofSeconds(30), 8, 8));
    }

    private static SpringAiAgentExecutor executor(ModelGateway gateway) {
        return new SpringAiAgentExecutor(gateway, () -> new org.springframework.ai.tool.ToolCallback[0],
                ObservationRegistry.NOOP, new SpringAiExecutorSettings(Duration.ofSeconds(30), 8, 8));
    }

    private static UUID submit(EngineHarness harness, Object input, RunInvoker invoker) {
        return submit(harness, RunLimits.UNBOUNDED, input, invoker);
    }

    private static UUID submit(EngineHarness harness, RunLimits limits, Object input, RunInvoker invoker) {
        UUID id = harness.run(limits, input, invoker);
        harness.engine.enqueue(harness.workspace, id, "system:test");
        return id;
    }

    /**
     * The continuation's thread once it waits for the model stream's next element, in the executor's stream take, not
     * merely in some wait (#206 low); {@code null}, with the reason in {@code failure}, when it neither started nor waits
     * there within 10 seconds, so a broken precondition fails the test instead of spinning forever.
     */
    private static Thread awaitWaitingForTheStream(java.util.concurrent.atomic.AtomicReference<Thread> handler,
            java.util.concurrent.atomic.AtomicReference<String> failure) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Thread continuation = handler.get();
            if (continuation != null && continuation.getState() == Thread.State.WAITING
                    && java.util.Arrays.stream(continuation.getStackTrace()).anyMatch(frame -> frame.getClassName()
                            .equals(SpringAiAgentExecutor.class.getName() + "$StreamConsumer")
                            && frame.getMethodName().equals("next"))) {
                return continuation;
            }
            Thread.onSpinWait();
        }
        failure.set(handler.get() == null ? "the exchange never started"
                : "the continuation never waited for the stream; it is " + handler.get().getState());
        return null;
    }

    private static RunWorker worker(EngineHarness harness, RunHandler handler) {
        return new RunWorker(harness.engine, handler, "worker-1", harness.scope(), Duration.ofSeconds(30));
    }

    private static List<RunEventType> types(EngineHarness harness, UUID id) {
        return harness.engine.history(harness.workspace, id).stream().map(RunEventCodec.DecodedEvent::type).toList();
    }

    @Test
    void aRunInvokesItsPinnedModelAsItsInvokerAndRecordsCorrelatedDurableEvents() {
        EngineHarness harness = harness();
        List<AdapterInvocation> invocations = new CopyOnWriteArrayList<>();
        ModelGateway gateway = gateway(invocation -> {
            invocations.add(invocation);
            return DeterministicFakeGatewayAdapter.echo(invocation);
        });
        UUID id = submit(harness, Map.of("prompt", "hello durable world"), invoker(harness));

        try (RunWorker worker = worker(harness, executor(gateway))) {
            assertThat(worker.pollOnce()).isEqualTo(RunWorker.Poll.EXECUTED);
        }

        RunRecord done = harness.engine.run(harness.workspace, id);
        assertThat(done.lifecycle()).isEqualTo(RunLifecycle.SUCCEEDED);
        var history = harness.engine.history(harness.workspace, id);
        String content = "echo(pinned-model): hello durable world";
        assertThat(history.getLast().payload().path("output").path("content").stringValue()).isEqualTo(content);
        // The pinned model, not today's catalog entry; the system instructions came from the pinned AgentVersion.
        assertThat(invocations).singleElement().satisfies(invocation -> {
            assertThat(invocation.providerModel()).isEqualTo("pinned-model");
            assertThat(invocation.request().messages().getFirst()).isEqualTo(ModelMessage.system("Answer tersely."));
            assertThat(invocation.plan().stream()).isTrue();
        });
        assertThat(types(harness, id)).containsSubsequence(RunEventType.MODEL_REQUESTED,
                RunEventType.MODEL_STREAM_CHUNK, RunEventType.MODEL_RESPONSE_RECEIVED, RunEventType.CHECKPOINT_CREATED,
                RunEventType.RUN_SUCCEEDED);
        // Streamed content is recorded incrementally, in bounded chunks that concatenate to the output.
        var chunks = history.stream().filter(event -> event.type() == RunEventType.MODEL_STREAM_CHUNK).toList();
        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks.stream().map(event -> event.payload().path("delta").stringValue())
                .reduce("", String::concat)).isEqualTo(content);
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.payload().path("delta").stringValue()).hasSizeLessThanOrEqualTo(8));
        // Every model event carries the run's correlation; one model call shares one step and one span.
        var pins = done.pins();
        var model = history.stream().filter(event -> event.type().name().startsWith("MODEL_")).toList();
        assertThat(model).allSatisfy(event -> {
            assertThat(event.raw().runId()).isEqualTo(id);
            assertThat(event.raw().traceId()).isEqualTo(pins.traceId());
            assertThat(event.raw().releaseId()).isEqualTo(pins.releaseId());
            assertThat(event.raw().deploymentId()).isEqualTo(pins.deploymentId());
            assertThat(event.raw().spanId()).isEqualTo(model.getFirst().raw().spanId());
            assertThat(event.payload().path("stepId").stringValue()).isEqualTo(model.getFirst().payload().path("stepId")
                    .stringValue());
        });
        var response = history.stream().filter(event -> event.type() == RunEventType.MODEL_RESPONSE_RECEIVED)
                .findFirst().orElseThrow();
        assertThat(response.payload().path("providerModel").stringValue()).isEqualTo("pinned-model");
        assertThat(response.payload().path("gatewayId").stringValue()).isEqualTo("direct");
        assertThat(done.summary().usage().modelCalls()).isOne();
        assertThat(done.summary().usage().outputTokens()).isPositive();
        // Prompts are evidenced by digest only.
        var requested = history.stream().filter(event -> event.type() == RunEventType.MODEL_REQUESTED).findFirst()
                .orElseThrow();
        assertThat(requested.raw().payloadJson()).doesNotContain("hello durable world").doesNotContain("Answer tersely");
        assertThat(harness.engine.verify(harness.workspace, id).intact()).isTrue();
    }

    @Test
    void toolCallsRunThroughTheAdvisorLoopAndAreRecordedAsToolEvidence() {
        EngineHarness harness = harness();
        ModelGateway gateway = gateway(invocation -> {
            boolean answered = invocation.request().messages().stream().anyMatch(m -> m.role() == ModelMessage.Role.TOOL);
            if (!answered) {
                assertThat(invocation.request().tools()).extracting(tool -> tool.name()).containsExactly("add");
                return FakeReply.text(List.of(), ModelUsage.reported(3, 1, null, null, null))
                        .withToolCalls(List.of(new ToolCall("call-1", "add", "{\"left\":2,\"right\":3}")));
            }
            String result = invocation.request().messages().stream().filter(m -> m.role() == ModelMessage.Role.TOOL)
                    .findFirst().orElseThrow().content();
            return FakeReply.text(List.of("the sum is ", result), ModelUsage.reported(5, 2, null, null, null));
        });
        UUID id = submit(harness, Map.of("prompt", "add 2 and 3"), invoker(harness));

        try (RunWorker worker = worker(harness, executor(gateway, new Calculator()))) {
            assertThat(worker.pollOnce()).isEqualTo(RunWorker.Poll.EXECUTED);
        }

        RunRecord done = harness.engine.run(harness.workspace, id);
        assertThat(done.lifecycle()).isEqualTo(RunLifecycle.SUCCEEDED);
        assertThat(harness.engine.history(harness.workspace, id).getLast().payload().path("output").path("content")
                .stringValue()).isEqualTo("the sum is 5");
        assertThat(types(harness, id)).containsSubsequence(RunEventType.MODEL_REQUESTED,
                RunEventType.MODEL_RESPONSE_RECEIVED, RunEventType.TOOL_REQUESTED, RunEventType.TOOL_STARTED,
                RunEventType.TOOL_OUTPUT_GUARDRAIL_EVALUATED, RunEventType.TOOL_COMPLETED,
                RunEventType.MODEL_REQUESTED, RunEventType.MODEL_RESPONSE_RECEIVED);
        assertThat(done.summary().usage().modelCalls()).isEqualTo(2);
        assertThat(done.summary().usage().toolCalls()).isOne();
        assertThat(harness.engine.steps(harness.workspace, id)).anySatisfy(step ->
                assertThat(step.name()).isEqualTo("add"));
    }

    @Test
    void invalidToolArgumentsFailBeforePolicyOrTheCallbackWithoutEnteringHistory() {
        EngineHarness harness = harness();
        ModelGateway gateway = gateway(invocation -> FakeReply
                .text(List.of(), ModelUsage.reported(3, 1, null, null, null))
                .withToolCalls(List.of(new ToolCall("call-1", "add",
                        "{\"left\":\"secret-canary\",\"right\":3}"))));
        Calculator calculator = new Calculator();
        UUID id = submit(harness, Map.of("prompt", "invoke add"), invoker(harness));

        try (RunWorker worker = worker(harness, executor(gateway, calculator))) {
            assertThat(worker.pollOnce()).isEqualTo(RunWorker.Poll.EXECUTED);
        }

        RunRecord failed = harness.engine.run(harness.workspace, id);
        assertThat(failed.summary().terminalCode()).isEqualTo("TOOL_INPUT_INVALID");
        assertThat(calculator.calls).hasValue(0);
        assertThat(types(harness, id)).containsSubsequence(RunEventType.TOOL_REQUESTED,
                RunEventType.TOOL_INPUT_SCHEMA_VALIDATED).doesNotContain(RunEventType.TOOL_POLICY_EVALUATED,
                        RunEventType.TOOL_STARTED, RunEventType.TOOL_COMPLETED);
        var validation = harness.engine.history(harness.workspace, id).stream()
                .filter(event -> event.type() == RunEventType.TOOL_INPUT_SCHEMA_VALIDATED).findFirst().orElseThrow();
        assertThat(validation.payload().path("decision").stringValue()).isEqualTo("DENY");
        assertThat(validation.payload().path("reason").stringValue()).isEqualTo("SCHEMA_VIOLATION");
        assertThat(harness.engine.history(harness.workspace, id).toString()).doesNotContain("secret-canary");
    }

    /** A tool during whose call an operator requests the run's pause (#183). */
    static final class PausingCalculator {
        private final Runnable requestPause;
        final java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();

        PausingCalculator(Runnable requestPause) {
            this.requestPause = requestPause;
        }

        @Tool(name = "add", description = "Adds two integers")
        int add(int left, int right) {
            if (calls.incrementAndGet() == 1) requestPause.run();
            return left + right;
        }
    }

    // PR #191 review, medium 3 (the user's decision): the Spring AI executor checkpoints only its final response, so an
    // operator's pause of its active run is refused PAUSE_UNSAFE (nothing written) and the run completes.
    @Test
    void anOperatorPauseOfAnActiveSpringAiRunIsRefusedAsUnsafeAndTheRunCompletes() {
        EngineHarness harness = harness();
        ModelGateway gateway = gateway(invocation -> {
            boolean answered = invocation.request().messages().stream().anyMatch(m -> m.role() == ModelMessage.Role.TOOL);
            if (!answered) {
                return FakeReply.text(List.of(), ModelUsage.reported(3, 1, null, null, null))
                        .withToolCalls(List.of(new ToolCall("call-1", "add", "{\"left\":2,\"right\":3}")));
            }
            return FakeReply.text(List.of("the sum is 5"), ModelUsage.reported(5, 2, null, null, null));
        });
        UUID id = submit(harness, Map.of("prompt", "add 2 and 3"), invoker(harness));
        var refusal = new java.util.concurrent.atomic.AtomicReference<String>();
        var tool = new PausingCalculator(() -> {
            try {
                harness.engine.requestPause(harness.workspace, id, "user:ops", null);
            } catch (RunEngineException refused) {
                refusal.set(refused.code());
            }
        });

        try (RunWorker worker = worker(harness, executor(gateway, tool))) {
            assertThat(worker.pollOnce()).isEqualTo(RunWorker.Poll.EXECUTED);
        }

        assertThat(refusal).hasValue("PAUSE_UNSAFE");
        assertThat(harness.engine.run(harness.workspace, id).lifecycle()).isEqualTo(RunLifecycle.SUCCEEDED);
        assertThat(harness.store.pauseRequests()).isEmpty();
        assertThat(types(harness, id)).doesNotContain(RunEventType.RUN_PAUSED);
    }

    // #183, with the executor declared resumable mid-exchange (as it will be once it checkpoints every round, #44): an
    // operator's pause requested while a tool call is in flight waits for that call: its outcome is recorded, and the
    // run pauses at the next safe boundary, before the next model round, which is never requested. A resume runs the
    // continuation again from its last checkpoint and completes it.
    @Test
    void anOperatorPauseDuringAToolCallTakesEffectBeforeTheNextModelRound() {
        EngineHarness harness = configured(new EngineHarness("spring-ai", ExecutorCapabilities.Registry.of(
                Map.of(SpringAiAgentExecutor.TYPE, new ExecutorCapabilities(true)))));
        var modelCalls = new java.util.concurrent.atomic.AtomicInteger();
        ModelGateway gateway = gateway(invocation -> {
            modelCalls.incrementAndGet();
            boolean answered = invocation.request().messages().stream().anyMatch(m -> m.role() == ModelMessage.Role.TOOL);
            if (!answered) {
                return FakeReply.text(List.of(), ModelUsage.reported(3, 1, null, null, null))
                        .withToolCalls(List.of(new ToolCall("call-1", "add", "{\"left\":2,\"right\":3}")));
            }
            return FakeReply.text(List.of("the sum is 5"), ModelUsage.reported(5, 2, null, null, null));
        });
        UUID id = submit(harness, Map.of("prompt", "add 2 and 3"), invoker(harness));
        var tool = new PausingCalculator(() -> assertThat(harness.engine.requestPause(harness.workspace, id,
                "user:ops", "maintenance").pending()).isTrue());

        try (RunWorker worker = worker(harness, executor(gateway, tool))) {
            assertThat(worker.pollOnce()).isEqualTo(RunWorker.Poll.EXECUTED);
        }

        RunRecord paused = harness.engine.run(harness.workspace, id);
        assertThat(paused.lifecycle()).isEqualTo(RunLifecycle.PAUSED);
        assertThat(paused.control().leaseOwner()).isNull();
        assertThat(modelCalls).hasValue(1);
        List<RunEventType> types = types(harness, id);
        assertThat(types).containsSubsequence(RunEventType.MODEL_REQUESTED, RunEventType.TOOL_STARTED,
                RunEventType.TOOL_OUTPUT_GUARDRAIL_EVALUATED, RunEventType.TOOL_COMPLETED,
                RunEventType.RUN_PAUSED);
        assertThat(types.stream().filter(RunEventType.MODEL_REQUESTED::equals)).hasSize(1);
        var event = harness.engine.history(harness.workspace, id).getLast();
        assertThat(event.payload().path("reason").stringValue()).isEqualTo(DurableRunEngine.OPERATOR_PAUSE);
        assertThat(event.payload().path("requestedBy").stringValue()).isEqualTo("user:ops");

        harness.engine.resumePaused(harness.workspace, id, "user:ops");
        try (RunWorker worker = worker(harness, executor(gateway, tool))) {
            assertThat(worker.pollOnce()).isEqualTo(RunWorker.Poll.EXECUTED);
        }
        assertThat(harness.engine.run(harness.workspace, id).lifecycle()).isEqualTo(RunLifecycle.SUCCEEDED);
        // The executor checkpoints only its final response, so the resumed continuation repeats the exchange: the
        // first round and its tool call run again (documented; tool idempotency is #39).
        assertThat(tool.calls).hasValue(2);
        assertThat(modelCalls).hasValue(3);
        assertThat(harness.engine.verify(harness.workspace, id).intact()).isTrue();
    }

    @Test
    void structuredOutputIsValidatedAndNormalized() {
        EngineHarness harness = harness();
        List<ResponseFormat> formats = new CopyOnWriteArrayList<>();
        List<String> replies = new CopyOnWriteArrayList<>(List.of("{\"answer\": 42}", "the answer is 42",
                "{\"answer\": \"forty-two\"}"));
        ModelGateway gateway = gateway(invocation -> {
            formats.add(invocation.request().responseFormat());
            return FakeReply.text(List.of(replies.removeFirst()), ModelUsage.reported(1, 1, null, null, null));
        });
        Map<String, Object> schema = Map.of("type", "object", "required", List.of("answer"),
                "properties", Map.of("answer", Map.of("type", "integer")));
        Map<String, Object> input = Map.of("prompt", "what is the answer?", "responseFormat",
                Map.of("name", "answer", "schema", schema));
        UUID valid = submit(harness, input, invoker(harness));
        UUID malformed = submit(harness, input, invoker(harness));
        UUID violating = submit(harness, input, invoker(harness));

        try (RunWorker worker = worker(harness, executor(gateway))) {
            for (int run = 0; run < 3; run++) assertThat(worker.pollOnce()).isEqualTo(RunWorker.Poll.EXECUTED);
        }

        assertThat(formats).allSatisfy(format -> assertThat(format.kind()).isEqualTo(ResponseFormat.Kind.JSON_SCHEMA));
        var output = harness.engine.history(harness.workspace, valid).getLast().payload().path("output");
        assertThat(output.path("type").stringValue()).isEqualTo("json");
        assertThat(output.path("value").path("answer").intValue()).isEqualTo(42);
        for (UUID failed : List.of(malformed, violating)) {
            RunRecord run = harness.engine.run(harness.workspace, failed);
            assertThat(run.summary().terminalCode()).isEqualTo("STRUCTURED_OUTPUT_INVALID");
            // The model exchange is evidenced, and no output or checkpoint was recorded for the invalid result.
            assertThat(types(harness, failed)).contains(RunEventType.MODEL_RESPONSE_RECEIVED)
                    .doesNotContain(RunEventType.CHECKPOINT_CREATED);
            assertThat(harness.engine.verify(harness.workspace, failed).intact()).isTrue();
        }
        assertThat(harness.engine.history(harness.workspace, violating).getLast().payload().path("message")
                .stringValue()).contains("type").doesNotContain("forty-two");
    }

    @Test
    void aSchemaThatWouldLoadAResourceIsRefusedBeforeAnyModelCall() {
        EngineHarness harness = harness();
        AtomicInteger calls = new AtomicInteger();
        ModelGateway gateway = gateway(invocation -> {
            calls.incrementAndGet();
            return DeterministicFakeGatewayAdapter.echo(invocation);
        });
        UUID id = submit(harness, Map.of("prompt", "x", "responseFormat", Map.of("name", "remote",
                "schema", Map.of("$ref", "https://attacker.example/schema.json"))), invoker(harness));

        try (RunWorker worker = worker(harness, executor(gateway))) {
            assertThat(worker.pollOnce()).isEqualTo(RunWorker.Poll.EXECUTED);
        }

        assertThat(harness.engine.run(harness.workspace, id).summary().terminalCode()).isEqualTo("INVALID_INPUT");
        assertThat(calls).hasValue(0);
    }

    @Test
    void cancellationStopsStreamingAndLeavesExactlyOneTerminalOutcome() throws Exception {
        EngineHarness harness = harness();
        CountDownLatch streaming = new CountDownLatch(1);
        List<String> chunks = new ArrayList<>();
        for (int index = 0; index < 400; index++) chunks.add("token" + index + " ");
        ModelGateway gateway = gateway(invocation -> {
            streaming.countDown();
            return FakeReply.text(chunks, ModelUsage.reported(1, 400, null, null, null))
                    .withChunkDelay(Duration.ofMillis(20));
        });
        UUID id = submit(harness, Map.of("prompt", "write a lot"), invoker(harness));

        try (RunWorker worker = new RunWorker(harness.engine, executor(gateway), "worker-1", harness.scope(),
                Duration.ofMillis(900))) {
            Future<RunWorker.Poll> poll;
            try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
                poll = pool.submit(worker::pollOnce);
                assertThat(streaming.await(5, TimeUnit.SECONDS)).isTrue();
                Thread.sleep(200);
                harness.engine.cancel(harness.workspace, id, "operator:ops", "no longer needed");
                assertThat(poll.get(10, TimeUnit.SECONDS)).isEqualTo(RunWorker.Poll.LOST);
            }
        }

        RunRecord cancelled = harness.engine.run(harness.workspace, id);
        assertThat(cancelled.lifecycle()).isEqualTo(RunLifecycle.CANCELLED);
        List<RunEventType> types = types(harness, id);
        assertThat(types.stream().filter(type -> type.name().startsWith("RUN_") && type != RunEventType.RUN_CREATED
                && type != RunEventType.RUN_QUEUED && type != RunEventType.RUN_ACTIVATED).toList())
                .containsExactly(RunEventType.RUN_CANCELLED);
        assertThat(types.getLast()).isEqualTo(RunEventType.RUN_CANCELLED);
        assertThat(types).doesNotContain(RunEventType.MODEL_RESPONSE_RECEIVED);
        // Streaming stopped: the model invocation ended cancelled, long before its 400 chunks.
        assertThat(gatewayEvents).anySatisfy(event -> assertThat(event).isInstanceOfSatisfying(
                GatewayEvent.ModelInvocationFailed.class,
                failed -> assertThat(failed.code()).isEqualTo(GatewayErrorCode.CANCELLED)));
        long recorded = harness.engine.history(harness.workspace, id).stream()
                .filter(event -> event.type() == RunEventType.MODEL_STREAM_CHUNK).count();
        assertThat(recorded).isLessThan(400);
        assertThat(harness.engine.verify(harness.workspace, id).intact()).isTrue();
    }

    @Test
    void aRecoveredRunCompletesFromItsCheckpointWithoutInvokingTheModelAgain() {
        EngineHarness harness = harness();
        AtomicInteger calls = new AtomicInteger();
        ModelGateway gateway = gateway(invocation -> {
            calls.incrementAndGet();
            return DeterministicFakeGatewayAdapter.echo(invocation);
        });
        SpringAiAgentExecutor executor = executor(gateway);
        UUID id = submit(harness, Map.of("prompt", "once only"), invoker(harness));
        AtomicInteger attempts = new AtomicInteger();
        RunHandler crashingOnce = context -> {
            RunOutcome outcome = executor.execute(context);
            if (attempts.incrementAndGet() == 1) {
                // The worker process dies after the response was checkpointed but before the run completed.
                throw new org.springframework.dao.DataAccessResourceFailureException("node lost");
            }
            return outcome;
        };

        try (RunWorker worker = worker(harness, crashingOnce)) {
            assertThat(worker.pollOnce()).isEqualTo(RunWorker.Poll.LOST);
            harness.store.advance(Duration.ofMinutes(1));
            assertThat(worker.pollOnce()).isEqualTo(RunWorker.Poll.EXECUTED);
        }

        assertThat(calls).hasValue(1);
        RunRecord done = harness.engine.run(harness.workspace, id);
        assertThat(done.lifecycle()).isEqualTo(RunLifecycle.SUCCEEDED);
        assertThat(types(harness, id)).contains(RunEventType.RUN_RECOVERED, RunEventType.CHECKPOINT_RESTORED);
        assertThat(harness.engine.history(harness.workspace, id).getLast().payload().path("output").path("content")
                .stringValue()).isEqualTo("echo(pinned-model): once only");
    }

    @Test
    void concurrentRunsOfTwoTenantsShareOneExecutorWithoutLeakage() throws Exception {
        EngineHarness first = harness();
        EngineHarness second = tenantOf(first);
        ModelGateway gateway = gateway(invocation -> {
            try {
                Thread.sleep(5);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return DeterministicFakeGatewayAdapter.echo(invocation);
        });
        SpringAiAgentExecutor shared = executor(gateway, new Calculator());
        Map<UUID, String> expected = new java.util.concurrent.ConcurrentHashMap<>();
        Map<UUID, EngineHarness> owner = new java.util.concurrent.ConcurrentHashMap<>();
        for (EngineHarness harness : List.of(first, second)) {
            RunInvoker invoker = invoker(harness);
            for (int index = 0; index < 6; index++) {
                String prompt = "tenant " + harness.workspace.value() + " prompt " + index;
                UUID id = submit(harness, Map.of("prompt", prompt), invoker);
                expected.put(id, "echo(pinned-model): " + prompt);
                owner.put(id, harness);
            }
        }

        try (ExecutorService pool = Executors.newFixedThreadPool(4)) {
            List<Future<?>> loops = new ArrayList<>();
            for (int thread = 0; thread < 4; thread++) {
                String name = "worker-" + thread;
                loops.add(pool.submit(() -> {
                    // One store, one engine, and workers that claim across every workspace.
                    try (RunWorker worker = new RunWorker(first.engine, shared, name,
                            com.dvarahq.agentworks.domain.run.ClaimScope.any(ReleaseManifestReader.SUPPORTED_VERSIONS),
                            Duration.ofSeconds(30))) {
                        while (worker.pollOnce() != RunWorker.Poll.IDLE) {
                            // keep draining this tenant
                        }
                    }
                    return null;
                }));
            }
            for (Future<?> loop : loops) loop.get(60, TimeUnit.SECONDS);
        }

        expected.forEach((id, content) -> {
            EngineHarness harness = owner.get(id);
            RunRecord run = harness.engine.run(harness.workspace, id);
            assertThat(run.lifecycle()).isEqualTo(RunLifecycle.SUCCEEDED);
            var history = harness.engine.history(harness.workspace, id);
            assertThat(history.getLast().payload().path("output").path("content").stringValue()).isEqualTo(content);
            assertThat(history).allSatisfy(event -> {
                assertThat(event.raw().runId()).isEqualTo(id);
                assertThat(event.raw().workspaceId()).isEqualTo(harness.workspace.value());
                assertThat(event.raw().traceId()).isEqualTo(run.pins().traceId());
            });
            assertThat(history.stream().filter(event -> event.type() == RunEventType.MODEL_STREAM_CHUNK)
                    .map(event -> event.payload().path("delta").stringValue()).reduce("", String::concat))
                    .isEqualTo(content);
        });
    }

    // Review: text a model streams in a tool-calling round is not the answer.
    @Test
    void onlyTheLastRoundsTextIsTheOutputEvenWithStructuredOutput() {
        EngineHarness harness = harness();
        ModelGateway gateway = gateway(invocation -> {
            boolean answered = invocation.request().messages().stream().anyMatch(m -> m.role() == ModelMessage.Role.TOOL);
            if (!answered) {
                return FakeReply.text(List.of("I'll ", "use the calculator. "), ModelUsage.reported(3, 2, null, null, null))
                        .withToolCalls(List.of(new ToolCall("call-1", "add", "{\"left\":2,\"right\":3}")));
            }
            return FakeReply.text(List.of("{\"answer\": ", "5}"), ModelUsage.reported(5, 2, null, null, null));
        });
        Map<String, Object> schema = Map.of("type", "object", "required", List.of("answer"),
                "properties", Map.of("answer", Map.of("type", "integer")));
        UUID id = submit(harness, Map.of("prompt", "add 2 and 3", "responseFormat", Map.of("name", "sum",
                "schema", schema)), invoker(harness));

        try (RunWorker worker = worker(harness, executor(gateway, new Calculator()))) {
            assertThat(worker.pollOnce()).isEqualTo(RunWorker.Poll.EXECUTED);
        }

        RunRecord done = harness.engine.run(harness.workspace, id);
        assertThat(done.lifecycle()).as(done.summary().terminalCode()).isEqualTo(RunLifecycle.SUCCEEDED);
        assertThat(harness.engine.history(harness.workspace, id).getLast().payload().path("output").path("value")
                .path("answer").intValue()).isEqualTo(5);
    }

    // Review: the model-call limit refuses a call before the provider is contacted, not one paid call late.
    @Test
    void theModelCallLimitIsEnforcedBeforeTheProviderIsContacted() {
        EngineHarness harness = harness();
        AtomicInteger calls = new AtomicInteger();
        ModelGateway gateway = gateway(invocation -> {
            calls.incrementAndGet();
            boolean answered = invocation.request().messages().stream().anyMatch(m -> m.role() == ModelMessage.Role.TOOL);
            return answered ? FakeReply.text(List.of("done"), ModelUsage.reported(1, 1, null, null, null))
                    : FakeReply.text(List.of(), ModelUsage.reported(1, 1, null, null, null))
                            .withToolCalls(List.of(new ToolCall("c1", "add", "{\"left\":1,\"right\":1}")));
        });
        UUID none = submit(harness, new RunLimits(null, 0L, null, null, null), Map.of("prompt", "x"), invoker(harness));
        UUID one = submit(harness, new RunLimits(null, 1L, null, null, null), Map.of("prompt", "x"), invoker(harness));

        try (RunWorker worker = worker(harness, executor(gateway, new Calculator()))) {
            assertThat(worker.pollOnce()).isEqualTo(RunWorker.Poll.EXECUTED);
            assertThat(calls).hasValue(0);
            assertThat(worker.pollOnce()).isEqualTo(RunWorker.Poll.EXECUTED);
        }

        assertThat(calls).hasValue(1);
        for (UUID id : List.of(none, one)) {
            RunRecord failed = harness.engine.run(harness.workspace, id);
            assertThat(failed.summary().terminalCode()).isEqualTo("LIMIT_EXCEEDED");
            assertThat(failed.summary().usage().modelCalls()).isLessThanOrEqualTo(id.equals(none) ? 1 : 2);
        }
    }

    // Review: an output too large to record stops the stream early instead of failing after the whole exchange.
    @Test
    void anOversizedOutputStopsTheStreamAndFailsTyped() {
        EngineHarness harness = harness();
        List<String> chunks = new ArrayList<>();
        for (int index = 0; index < 400; index++) chunks.add("x".repeat(1024));
        ModelGateway gateway = gateway(invocation -> FakeReply.text(chunks, ModelUsage.reported(1, 400, null, null, null))
                .withChunkDelay(Duration.ofMillis(1)));
        UUID id = submit(harness, Map.of("prompt", "write a book"), invoker(harness));
        SpringAiAgentExecutor executor = new SpringAiAgentExecutor(gateway,
                () -> new org.springframework.ai.tool.ToolCallback[0], ObservationRegistry.NOOP,
                new SpringAiExecutorSettings(Duration.ofSeconds(30), 16_384, 8));

        try (RunWorker worker = worker(harness, executor)) {
            assertThat(worker.pollOnce()).isEqualTo(RunWorker.Poll.EXECUTED);
        }

        RunRecord failed = harness.engine.run(harness.workspace, id);
        assertThat(failed.summary().terminalCode()).isEqualTo("OUTPUT_TOO_LARGE");
        long recorded = harness.engine.history(harness.workspace, id).stream()
                .filter(event -> event.type() == RunEventType.MODEL_STREAM_CHUNK)
                .mapToLong(event -> event.payload().path("delta").stringValue().length()).sum();
        assertThat(recorded).isLessThan(400L * 1024);
        assertThat(types(harness, id)).doesNotContain(RunEventType.CHECKPOINT_CREATED);
    }

    // Review: Spring AI's tool-call limit ends the run as a failure, never as a successful answer.
    @Test
    void exceedingTheToolCallLimitFailsTheRun() {
        EngineHarness harness = harness();
        List<ToolCall> calls = new ArrayList<>();
        for (int index = 0; index < 41; index++) {
            calls.add(new ToolCall("c" + index, "add", "{\"left\":1,\"right\":" + index + "}"));
        }
        ModelGateway gateway = gateway(invocation -> FakeReply.text(List.of(), ModelUsage.reported(1, 1, null, null, null))
                .withToolCalls(calls));
        UUID id = submit(harness, Map.of("prompt", "add a lot"), invoker(harness));

        try (RunWorker worker = worker(harness, executor(gateway, new Calculator()))) {
            assertThat(worker.pollOnce()).isEqualTo(RunWorker.Poll.EXECUTED);
        }

        assertThat(harness.engine.run(harness.workspace, id).summary().terminalCode())
                .isEqualTo("TOOL_CALL_LIMIT_EXCEEDED");
    }

    private static SpringAiAgentExecutor batching(ModelGateway gateway, int chunkBatchChars,
            org.springframework.ai.tool.ToolCallback... tools) {
        return new SpringAiAgentExecutor(gateway, () -> tools.clone(), ObservationRegistry.NOOP,
                new SpringAiExecutorSettings(Duration.ofSeconds(30), chunkBatchChars, 8));
    }

    private static RunRecord runOnce(EngineHarness harness, SpringAiAgentExecutor executor, UUID id) {
        try (RunWorker worker = worker(harness, executor)) {
            assertThat(worker.pollOnce()).isEqualTo(RunWorker.Poll.EXECUTED);
        }
        return harness.engine.run(harness.workspace, id);
    }

    // Review 3, item 1: a batch boundary never splits a surrogate pair, inside one delta or across two.
    @Test
    void emojiStraddlingBatchAndChunkBoundariesAreStoredWhole() {
        EngineHarness harness = harness();
        List<String> chunks = List.of("ab😀", "😀c\uD83D", "\uDE00😀😀d");
        String content = String.join("", chunks);
        ModelGateway gateway = gateway(invocation -> FakeReply.text(chunks, ModelUsage.reported(1, 3, null, null, null)));
        UUID id = submit(harness, Map.of("prompt", "emoji"), invoker(harness));

        RunRecord done = runOnce(harness, batching(gateway, 3), id);

        assertThat(done.lifecycle()).as(String.valueOf(done.summary().terminalCode())).isEqualTo(RunLifecycle.SUCCEEDED);
        var history = harness.engine.history(harness.workspace, id);
        assertThat(history.getLast().payload().path("output").path("content").stringValue()).isEqualTo(content);
        var deltas = history.stream().filter(event -> event.type() == RunEventType.MODEL_STREAM_CHUNK)
                .map(event -> event.payload().path("delta").stringValue()).toList();
        assertThat(String.join("", deltas)).isEqualTo(content);
        assertThat(deltas).allSatisfy(delta -> {
            assertThat(Character.isLowSurrogate(delta.charAt(0))).isFalse();
            assertThat(Character.isHighSurrogate(delta.charAt(delta.length() - 1))).isFalse();
        });
    }

    // Review 3, item 7: text that could never be stored ends the exchange with a typed failure, early.
    @Test
    void unstorableModelTextFailsTypedRatherThanAsAGenericPayloadError() {
        EngineHarness harness = harness();
        Map<String, List<String>> replies = Map.of("nul", List.of("a\u0000b", " more"), "lone", List.of("tail \uD83D"),
                "json", List.of("{\"answer\": \"a\\u0000b\"}"));
        ModelGateway gateway = gateway(invocation -> {
            String prompt = invocation.request().messages().getLast().content();
            return FakeReply.text(replies.get(prompt), ModelUsage.reported(1, 1, null, null, null));
        });
        UUID nul = submit(harness, Map.of("prompt", "nul"), invoker(harness));
        UUID lone = submit(harness, Map.of("prompt", "lone"), invoker(harness));
        UUID json = submit(harness, Map.of("prompt", "json", "responseFormat", Map.of("name", "a", "schema",
                Map.of("type", "object", "properties", Map.of("answer", Map.of("type", "string"))))), invoker(harness));

        try (RunWorker worker = worker(harness, executor(gateway))) {
            for (int run = 0; run < 3; run++) assertThat(worker.pollOnce()).isEqualTo(RunWorker.Poll.EXECUTED);
        }

        assertThat(harness.engine.run(harness.workspace, nul).summary().terminalCode()).isEqualTo("OUTPUT_INVALID");
        assertThat(harness.engine.run(harness.workspace, lone).summary().terminalCode()).isEqualTo("OUTPUT_INVALID");
        assertThat(harness.engine.run(harness.workspace, json).summary().terminalCode())
                .isEqualTo("STRUCTURED_OUTPUT_INVALID");
        assertThat(types(harness, nul)).doesNotContain(RunEventType.MODEL_RESPONSE_RECEIVED);
        // Review 4, item 8: half a surrogate pair left when the stream ends is dropped from the stored chunks, so the
        // buffered text and the model's final event are still recorded before the typed failure.
        assertThat(types(harness, lone)).contains(RunEventType.MODEL_STREAM_CHUNK,
                RunEventType.MODEL_RESPONSE_RECEIVED);
        assertThat(harness.engine.history(harness.workspace, lone).stream()
                .filter(event -> event.type() == RunEventType.MODEL_STREAM_CHUNK)
                .map(event -> event.payload().path("delta").stringValue()).reduce("", String::concat)).isEqualTo("tail ");
    }

    // #181 low 3: the response's digest and length describe the text the stored chunks carry, so the chunks verify
    // against them even when half a surrogate pair was dropped from the end of the stream.
    @Test
    void theResponseDigestMatchesTheStoredChunksWhenADanglingSurrogateIsDropped() {
        EngineHarness harness = harness();
        ModelGateway gateway = gateway(invocation -> FakeReply.text(List.of("tail \uD83D"),
                ModelUsage.reported(1, 1, null, null, null)));
        UUID lone = submit(harness, Map.of("prompt", "lone"), invoker(harness));

        runOnce(harness, executor(gateway), lone);

        var history = harness.engine.history(harness.workspace, lone);
        String chunks = history.stream().filter(event -> event.type() == RunEventType.MODEL_STREAM_CHUNK)
                .map(event -> event.payload().path("delta").stringValue()).reduce("", String::concat);
        var response = history.stream().filter(event -> event.type() == RunEventType.MODEL_RESPONSE_RECEIVED)
                .findFirst().orElseThrow().payload();
        assertThat(chunks).isEqualTo("tail ");
        assertThat(response.path("contentLength").intValue()).isEqualTo(chunks.length());
        assertThat(response.path("contentDigest").stringValue())
                .isEqualTo(com.dvarahq.agentworks.runplane.execution.RunEventCodec.sha256(chunks));
    }

    // Review 4, item 6: the storable-output check validates the checkpoint's own envelope, so output nested exactly
    // one level too deep for the checkpoint fails typed instead of as a generic payload error at checkpoint time.
    @Test
    void outputTooDeepForItsCheckpointFailsTypedAtTheDepthBoundary() {
        // The deepest nesting that fits in {"output": ...} but not in the checkpoint's {"state": {"output": ...}}.
        int depth = 1;
        while (storable(Map.of("output", Map.of("type", "json", "value", nested(depth + 1))))) depth++;
        assertThat(storable(Map.of("state", Map.of("phase", "RESPONDED", "output",
                Map.of("type", "json", "value", nested(depth)))))).isFalse();
        String reply = tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(nested(depth));
        EngineHarness harness = harness();
        ModelGateway gateway = gateway(invocation -> FakeReply.text(List.of(reply), ModelUsage.reported(1, 1, null, null, null)));
        UUID id = submit(harness, Map.of("prompt", "deep", "responseFormat", Map.of("name", "deep", "schema",
                Map.of("type", "object"))), invoker(harness));

        RunRecord failed = runOnce(harness, executor(gateway), id);

        assertThat(failed.summary().terminalCode()).isEqualTo("STRUCTURED_OUTPUT_INVALID");
        assertThat(types(harness, id)).doesNotContain(RunEventType.CHECKPOINT_CREATED);
    }

    private static Map<String, Object> nested(int depth) {
        Map<String, Object> value = Map.of();
        for (int level = 1; level < depth; level++) value = Map.of("a", value);
        return value;
    }

    private static boolean storable(Map<String, Object> payload) {
        try {
            RunEventCodec.standard().canonicalPayload(payload);
            return true;
        } catch (RunEngineException tooDeep) {
            return false;
        }
    }

    // Review 4, item 8: a model call that fails while half a surrogate pair is buffered still records its failure.
    @Test
    void aModelFailureWithHalfASurrogatePairBufferedIsRecordedWithItsChunks() {
        EngineHarness harness = harness();
        // The first chunk ends inside a pair; the call times out before the chunk that would complete it arrives.
        ModelGateway gateway = gateway(invocation -> FakeReply.text(List.of("ab\uD83D", "\uDE00 and more"),
                ModelUsage.reported(1, 2, null, null, null)).withChunkDelay(Duration.ofMillis(300)));
        UUID id = submit(harness, Map.of("prompt", "slow"), invoker(harness));
        SpringAiAgentExecutor executor = new SpringAiAgentExecutor(gateway,
                () -> new org.springframework.ai.tool.ToolCallback[0], ObservationRegistry.NOOP,
                new SpringAiExecutorSettings(Duration.ofMillis(450), 64, 8));

        RunRecord failed = runOnce(harness, executor, id);

        assertThat(failed.summary().terminalCode()).isEqualTo("GATEWAY_PARTIAL_STREAM");
        var history = harness.engine.history(harness.workspace, id);
        assertThat(history.stream().map(RunEventCodec.DecodedEvent::type)).contains(RunEventType.MODEL_FAILED);
        assertThat(history.stream().filter(event -> event.type() == RunEventType.MODEL_STREAM_CHUNK)
                .map(event -> event.payload().path("delta").stringValue()).reduce("", String::concat)).isEqualTo("ab");
    }

    // Review 3, item 3: a stream write in flight when the exchange ends early never displaces the typed outcome.
    @Test
    void anEarlyEndWaitsForTheStreamsWriteSoTheTypedFailureIsRecorded() {
        EngineHarness harness = harness();
        // Every durable write from the model stream holds the store for a while, as a slow database would.
        var slowStreamWrites = new DurableRunEngine(new QuarantineDelegating(harness.store) {
            @Override
            public <T> T inTransaction(com.dvarahq.agentworks.domain.tenant.WorkspaceId workspace,
                    java.util.function.Function<com.dvarahq.agentworks.domain.run.RunTransaction, T> work) {
                return harness.store.inTransaction(workspace, tx -> {
                    T result = work.apply(tx);
                    // Streamed chunks are recorded on the gateway adapter's thread.
                    if (Thread.currentThread().getName().startsWith("agentworks-gateway-")) {
                        try {
                            Thread.sleep(150);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    return result;
                });
            }

            @Override
            public <T> java.util.Optional<T> claimNext(com.dvarahq.agentworks.domain.run.ClaimScope scope,
                    java.util.function.BiFunction<RunRecord, com.dvarahq.agentworks.domain.run.RunTransaction, T> work) {
                return harness.store.claimNext(scope, work);
            }
        }, RunEventCodec.standard(), new ReleaseManifestReader(), new com.dvarahq.agentworks.storage.lifecycle.LifecycleRetry(1));
        List<String> chunks = new ArrayList<>();
        for (int index = 0; index < 20; index++) chunks.add("y".repeat(8_192));
        ModelGateway gateway = gateway(invocation -> FakeReply.text(chunks, ModelUsage.reported(1, 20, null, null, null)));
        UUID id = submit(harness, Map.of("prompt", "too much"), invoker(harness));

        try (RunWorker worker = new RunWorker(slowStreamWrites, batching(gateway, 8_192), "worker-1", harness.scope(),
                Duration.ofSeconds(30))) {
            assertThat(assertTimeoutPreemptively(Duration.ofSeconds(30), worker::pollOnce)).isEqualTo(RunWorker.Poll.EXECUTED);
        }

        RunRecord failed = harness.engine.run(harness.workspace, id);
        assertThat(failed.lifecycle()).isEqualTo(RunLifecycle.FAILED);
        assertThat(failed.summary().terminalCode()).isEqualTo("OUTPUT_TOO_LARGE");
    }

    // Review 4, item 7, deterministic since #181 round 2: the model stream may reach its next chunk only after the
    // continuation has seen the output limit (and stopped the stream). The outcome is then always the typed
    // OUTPUT_TOO_LARGE: either the stream's next write is refused by the cancellation, or the write already past its
    // cancellation check completes within the drain wait. The undrained case (a write slower than writeDrainTimeout,
    // which abandons the claim) is proven at the recorder,
    // DurableRunRecorderTest.aWriteStillInProgressAtCloseFailsTheDrainAndLaterWritesAreRefused.
    @Test
    void aStreamReachingItsNextChunkOnlyAfterTheLimitIsSeenEndsTypedOutputTooLarge() throws Exception {
        EngineHarness harness = harness();
        CountDownLatch limitSeen = new CountDownLatch(1);
        AtomicInteger streamWrites = new AtomicInteger();
        int limitChunks = SpringAiAgentExecutor.MAX_OUTPUT_BYTES / 8_192;
        var gatedStreamWrites = new DurableRunEngine(new QuarantineDelegating(harness.store) {
            @Override
            public <T> T inTransaction(com.dvarahq.agentworks.domain.tenant.WorkspaceId workspace,
                    java.util.function.Function<com.dvarahq.agentworks.domain.run.RunTransaction, T> work) {
                // Chunk writes run on the gateway adapter's thread; every one after the chunk that crosses the output
                // limit waits until the continuation has seen the limit.
                if (Thread.currentThread().getName().startsWith("agentworks-gateway-")
                        && streamWrites.incrementAndGet() > limitChunks + 1) {
                    try {
                        assertThat(limitSeen.await(10, TimeUnit.SECONDS)).isTrue();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
                return harness.store.inTransaction(workspace, work);
            }

            @Override
            public <T> java.util.Optional<T> claimNext(com.dvarahq.agentworks.domain.run.ClaimScope scope,
                    java.util.function.BiFunction<RunRecord, com.dvarahq.agentworks.domain.run.RunTransaction, T> work) {
                return harness.store.claimNext(scope, work);
            }
        }, RunEventCodec.standard(), new ReleaseManifestReader(), new com.dvarahq.agentworks.storage.lifecycle.LifecycleRetry(1));
        // The continuation has seen the limit (and stopped the stream) once its recorder's drain starts.
        ObservationRegistry observations = ObservationRegistry.create();
        observations.observationConfig().observationHandler(new io.micrometer.observation.ObservationHandler<>() {
            @Override
            public boolean supportsContext(io.micrometer.observation.Observation.Context context) {
                return true;
            }

            @Override
            public void onStart(io.micrometer.observation.Observation.Context context) {
                // The drain starts once the exchange ended and its stream was cancelled (PR #208 review: the
                // exchange's own observation now stops after the drain).
                if ("agentworks.run.drain".equals(context.getName())) limitSeen.countDown();
            }
        });
        List<String> chunks = new ArrayList<>();
        for (int index = 0; index < limitChunks + 8; index++) chunks.add("y".repeat(8_192));
        ModelGateway gateway = gateway(invocation -> FakeReply.text(chunks, ModelUsage.reported(1, chunks.size(), null,
                null, null)));
        UUID id = submit(harness, Map.of("prompt", "too much"), invoker(harness));
        SpringAiAgentExecutor executor = new SpringAiAgentExecutor(gateway,
                () -> new org.springframework.ai.tool.ToolCallback[0], observations,
                new SpringAiExecutorSettings(Duration.ofSeconds(30), 8_192, 8, Duration.ofSeconds(5)));

        try (RunWorker worker = new RunWorker(gatedStreamWrites, executor, "worker-1", harness.scope(),
                Duration.ofSeconds(30))) {
            assertThat(assertTimeoutPreemptively(Duration.ofSeconds(30), worker::pollOnce))
                    .isEqualTo(RunWorker.Poll.EXECUTED);
        }

        RunRecord failed = harness.engine.run(harness.workspace, id);
        assertThat(failed.lifecycle()).isEqualTo(RunLifecycle.FAILED);
        assertThat(failed.summary().terminalCode()).isEqualTo("OUTPUT_TOO_LARGE");
        assertThat(harness.engine.verify(harness.workspace, id).intact()).isTrue();
    }

    // #185 low (the ISSUE-34 drain evidence), end to end through the executor and the worker: when the continuation
    // cannot drain its recorder (DurableRunRecorder.close returns false), it records no outcome and the worker abandons
    // the claim (LOST); the run stays ACTIVE for recovery. The drain fails deterministically here because the handler's
    // thread is interrupted as the exchange ends, one of the two documented causes. DurableRunRecorderTest proves the
    // other, a write still in progress, at the recorder. An earlier version held a stream write that started after
    // the output limit was seen; whether that write starts before the stream is cancelled is a race, so it was flaky.
    @Test
    void aContinuationThatCannotDrainItsRecorderLeavesTheRunForRecovery() throws Exception {
        EngineHarness harness = harness();
        // The drain's observation starts on the handler's thread right before the recorder is drained (PR #208 review:
        // the exchange's own observation now stops after the drain).
        ObservationRegistry observations = ObservationRegistry.create();
        observations.observationConfig().observationHandler(new io.micrometer.observation.ObservationHandler<>() {
            @Override
            public boolean supportsContext(io.micrometer.observation.Observation.Context context) {
                return true;
            }

            @Override
            public void onStart(io.micrometer.observation.Observation.Context context) {
                if ("agentworks.run.drain".equals(context.getName())) Thread.currentThread().interrupt();
            }
        });
        ModelGateway gateway = gateway(invocation -> FakeReply.text(List.of("an ", "answer"),
                ModelUsage.reported(1, 2, null, null, null)));
        UUID id = submit(harness, Map.of("prompt", "hello"), invoker(harness));
        SpringAiAgentExecutor executor = new SpringAiAgentExecutor(gateway,
                () -> new org.springframework.ai.tool.ToolCallback[0], observations,
                new SpringAiExecutorSettings(Duration.ofSeconds(30), 8_192, 8, Duration.ofSeconds(5)));

        RunWorker.Poll poll;
        try (RunWorker worker = new RunWorker(harness.engine, executor, "worker-1", harness.scope(),
                Duration.ofSeconds(30))) {
            poll = assertTimeoutPreemptively(Duration.ofSeconds(30), worker::pollOnce);
        }

        assertThat(poll).isEqualTo(RunWorker.Poll.LOST);
        RunRecord left = harness.engine.run(harness.workspace, id);
        assertThat(left.lifecycle()).isEqualTo(RunLifecycle.ACTIVE);
        assertThat(types(harness, id)).contains(RunEventType.MODEL_RESPONSE_RECEIVED)
                .doesNotContain(RunEventType.RUN_FAILED, RunEventType.RUN_SUCCEEDED);
    }

    // #201 low 7, end to end: the drain's other cause, a stream write still in progress when the exchange ends, also
    // leaves the run for recovery. The model stream's first write is held in the store; only then is the continuation
    // ended (its thread, named by the executor's own observation, is interrupted while it waits for the stream, which
    // clears the interrupt), so its drain waits for that write, gives up, and the worker abandons the claim (LOST). The
    // executor's drain observation reports why the drain ended (#201 review, low 10); the write is released only after
    // the poll returned, so the order is fixed, not timed.
    @Test
    void aStreamWriteStillInProgressWhenTheExchangeEndsLeavesTheRunForRecovery() throws Exception {
        EngineHarness harness = harness();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicInteger streamWrites = new AtomicInteger();
        var handler = new java.util.concurrent.atomic.AtomicReference<Thread>();
        var drains = java.util.Collections.synchronizedList(new ArrayList<String>());
        var drainParents = java.util.Collections.synchronizedList(new ArrayList<String>());
        var drainErrors = java.util.Collections.synchronizedList(new ArrayList<String>());
        var parkFailure = new java.util.concurrent.atomic.AtomicReference<String>();
        var stopped = java.util.Collections.newSetFromMap(
                new java.util.concurrent.ConcurrentHashMap<io.micrometer.observation.Observation.ContextView, Boolean>());
        var drainParentsRunning = java.util.Collections.synchronizedList(new ArrayList<Boolean>());
        var executeErrors = java.util.Collections.synchronizedList(new ArrayList<String>());
        ObservationRegistry observations = ObservationRegistry.create();
        observations.observationConfig().observationHandler(new io.micrometer.observation.ObservationHandler<>() {
            @Override
            public boolean supportsContext(io.micrometer.observation.Observation.Context context) {
                return true;
            }

            @Override
            public void onStart(io.micrometer.observation.Observation.Context context) {
                // The exchange starts on the handler's thread.
                if ("agentworks.run.execute".equals(context.getName())) handler.set(Thread.currentThread());
                // PR #208 review: the drain starts while the exchange's observation is still running.
                if ("agentworks.run.drain".equals(context.getName())) {
                    drainParentsRunning.add(context.getParentObservation() != null
                            && !stopped.contains(context.getParentObservation().getContextView()));
                }
            }

            @Override
            public void onStop(io.micrometer.observation.Observation.Context context) {
                stopped.add(context);
                if ("agentworks.run.execute".equals(context.getName())) {
                    executeErrors.add(context.getError() instanceof RunEngineException engine ? engine.code() : "none");
                }
                if ("agentworks.run.drain".equals(context.getName())) {
                    drains.add(context.getLowCardinalityKeyValue("agentworks.drain.outcome").getValue());
                    // #206 low: the drain belongs to the exchange's trace, and an abandoned drain is an error.
                    var parent = context.getParentObservation();
                    drainParents.add(parent == null ? "none" : parent.getContextView().getName());
                    drainErrors.add(context.getError() == null ? "none"
                            : context.getError() instanceof RunEngineException engine ? engine.code() : "other");
                }
            }
        });
        var heldStreamWrite = new DurableRunEngine(new QuarantineDelegating(harness.store) {
            @Override
            public <T> T inTransaction(com.dvarahq.agentworks.domain.tenant.WorkspaceId workspace,
                    java.util.function.Function<com.dvarahq.agentworks.domain.run.RunTransaction, T> work) {
                if (!Thread.currentThread().getName().startsWith("agentworks-gateway-")
                        || streamWrites.incrementAndGet() > 1) {
                    return harness.store.inTransaction(workspace, work);
                }
                // The first write of the model stream, made under the recorder's write lock: end the exchange while it
                // is in progress, once the handler waits for the stream, then hold the write until the poll returned.
                Thread continuation = awaitWaitingForTheStream(handler, parkFailure);
                if (continuation == null) {
                    // The precondition failed: write without holding, so the poll ends and the reason is reported.
                    try {
                        return harness.store.inTransaction(workspace, work);
                    } finally {
                        finished.countDown();
                    }
                }
                continuation.interrupt();
                boolean interrupted = false;
                while (true) {
                    try {
                        release.await();
                        break;
                    } catch (InterruptedException cancelled) {
                        // Stopping the stream may interrupt this thread; the write stays held regardless.
                        interrupted = true;
                    }
                }
                try {
                    return harness.store.inTransaction(workspace, work);
                } finally {
                    finished.countDown();
                    if (interrupted) Thread.currentThread().interrupt();
                }
            }

            @Override
            public <T> java.util.Optional<T> claimNext(com.dvarahq.agentworks.domain.run.ClaimScope scope,
                    java.util.function.BiFunction<RunRecord, com.dvarahq.agentworks.domain.run.RunTransaction, T> work) {
                return harness.store.claimNext(scope, work);
            }
        }, RunEventCodec.standard(), new ReleaseManifestReader(), new com.dvarahq.agentworks.storage.lifecycle.LifecycleRetry(1));
        ModelGateway gateway = gateway(invocation -> FakeReply.text(List.of("an ", "answer"),
                ModelUsage.reported(1, 2, null, null, null)));
        UUID id = submit(harness, Map.of("prompt", "hello"), invoker(harness));
        SpringAiAgentExecutor executor = new SpringAiAgentExecutor(gateway,
                () -> new org.springframework.ai.tool.ToolCallback[0], observations,
                new SpringAiExecutorSettings(Duration.ofSeconds(30), 1, 8, Duration.ofMillis(200)));

        RunWorker.Poll poll;
        try (RunWorker worker = new RunWorker(heldStreamWrite, executor, "drain-worker", harness.scope(),
                Duration.ofSeconds(30))) {
            poll = assertTimeoutPreemptively(Duration.ofSeconds(30), worker::pollOnce);
        } finally {
            release.countDown();
        }
        assertThat(finished.await(10, TimeUnit.SECONDS)).isTrue();

        assertThat(parkFailure).hasValue(null);
        assertThat(poll).isEqualTo(RunWorker.Poll.LOST);
        // The drain ended because the held write was still in progress, not because of the interrupt.
        assertThat(drains).containsExactly("write_in_progress");
        assertThat(drainParents).containsExactly("agentworks.run.execute");
        assertThat(drainErrors).containsExactly("STALE_LEASE");
        assertThat(drainParentsRunning).containsExactly(true);
        assertThat(executeErrors).containsExactly("STALE_LEASE");
        RunRecord left = harness.engine.run(harness.workspace, id);
        assertThat(left.lifecycle()).isEqualTo(RunLifecycle.ACTIVE);
        assertThat(types(harness, id)).doesNotContain(RunEventType.MODEL_RESPONSE_RECEIVED, RunEventType.RUN_FAILED,
                RunEventType.RUN_SUCCEEDED);
    }

    // Review 4, item 3: a database failure while a streamed chunk is recorded leaves the run for recovery (the claim is
    // lost), however Spring AI and Reactor wrap it; it never fails the run as an executor failure.
    @Test
    void aDatabaseFailureWhileRecordingAChunkLeavesTheRunForRecovery() {
        EngineHarness harness = harness();
        var failures = new AtomicInteger();
        var failingOnce = new DurableRunEngine(new QuarantineDelegating(harness.store) {
            @Override
            public <T> T inTransaction(com.dvarahq.agentworks.domain.tenant.WorkspaceId workspace,
                    java.util.function.Function<com.dvarahq.agentworks.domain.run.RunTransaction, T> work) {
                if (Thread.currentThread().getName().startsWith("agentworks-gateway-") && failures.getAndIncrement() == 0) {
                    throw new org.springframework.dao.DataAccessResourceFailureException("connection lost");
                }
                return harness.store.inTransaction(workspace, work);
            }

            @Override
            public <T> java.util.Optional<T> claimNext(com.dvarahq.agentworks.domain.run.ClaimScope scope,
                    java.util.function.BiFunction<RunRecord, com.dvarahq.agentworks.domain.run.RunTransaction, T> work) {
                return harness.store.claimNext(scope, work);
            }
        }, RunEventCodec.standard(), new ReleaseManifestReader(), new com.dvarahq.agentworks.storage.lifecycle.LifecycleRetry(1));
        ModelGateway gateway = gateway(DeterministicFakeGatewayAdapter::echo);
        UUID id = submit(harness, Map.of("prompt", "recover me"), invoker(harness));

        try (RunWorker worker = new RunWorker(failingOnce, batching(gateway, 4), "worker-1", harness.scope(),
                Duration.ofSeconds(30))) {
            assertThat(worker.pollOnce()).isEqualTo(RunWorker.Poll.LOST);
            assertThat(harness.engine.run(harness.workspace, id).lifecycle()).isEqualTo(RunLifecycle.ACTIVE);
            harness.store.advance(Duration.ofMinutes(1));
            assertThat(worker.pollOnce()).isEqualTo(RunWorker.Poll.EXECUTED);
        }

        assertThat(failures.get()).isGreaterThan(0);
        RunRecord done = harness.engine.run(harness.workspace, id);
        assertThat(done.lifecycle()).as(String.valueOf(done.summary().terminalCode())).isEqualTo(RunLifecycle.SUCCEEDED);
        assertThat(types(harness, id)).contains(RunEventType.RUN_RECOVERED).doesNotContain(RunEventType.RUN_FAILED);
    }

    // Review 3, item 6: a tool's returnDirect result is the output on its own, never appended to earlier text.
    @Test
    void aReturnDirectToolResultIsTheWholeOutput() {
        EngineHarness harness = harness();
        ModelGateway gateway = gateway(invocation -> FakeReply.text(List.of("Let me ", "check. "),
                ModelUsage.reported(1, 2, null, null, null))
                .withToolCalls(List.of(new ToolCall("c1", "lookup", "{\"key\":\"answer\"}"))));
        UUID id = submit(harness, Map.of("prompt", "look it up"), invoker(harness));

        RunRecord done = runOnce(harness, executor(gateway, new DirectLookup()), id);

        assertThat(done.lifecycle()).as(String.valueOf(done.summary().terminalCode())).isEqualTo(RunLifecycle.SUCCEEDED);
        String content = harness.engine.history(harness.workspace, id).getLast().payload().path("output")
                .path("content").stringValue();
        assertThat(content).contains("forty-two").doesNotContain("Let me").doesNotContain("check.");
    }

    // Review 4, item 5: a tool that overflows its stack is recorded as TOOL_FAILED and ends the exchange with a typed
    // failure; the continuation always receives a terminal signal and the worker keeps polling.
    @Test
    void aToolThatOverflowsItsStackIsRecordedAndFailsTheRunTyped() {
        EngineHarness harness = harness();
        ModelGateway gateway = gateway(invocation -> FakeReply.text(List.of(), ModelUsage.reported(1, 1, null, null, null))
                .withToolCalls(List.of(new ToolCall("c1", "recurse", "{\"depth\":0}"))));
        UUID id = submit(harness, Map.of("prompt", "recurse"), invoker(harness));
        UUID next = submit(harness, Map.of("prompt", "then this"), invoker(harness));

        try (RunWorker worker = worker(harness, executor(gateway, new Recursive()))) {
            assertThat(assertTimeoutPreemptively(Duration.ofSeconds(30), worker::pollOnce))
                    .isEqualTo(RunWorker.Poll.EXECUTED);
        }

        RunRecord failed = harness.engine.run(harness.workspace, id);
        assertThat(failed.summary().terminalCode()).isEqualTo("TOOL_FAILED");
        var history = harness.engine.history(harness.workspace, id);
        assertThat(history.stream().filter(event -> event.type() == RunEventType.TOOL_FAILED)).singleElement()
                .satisfies(event -> assertThat(event.payload().path("error").stringValue())
                        .isEqualTo(StackOverflowError.class.getName()));
        assertThat(history.getLast().payload().path("message").stringValue())
                .contains(StackOverflowError.class.getName());
        assertThat(harness.engine.run(harness.workspace, next).lifecycle()).isEqualTo(RunLifecycle.QUEUED);
    }

    static final class Recursive {
        @Tool(name = "recurse", description = "Recurses without end")
        int recurse(int depth) {
            return recurse(depth + 1) + 1;
        }
    }

    static final class DirectLookup {
        @Tool(name = "lookup", description = "Looks up a value", returnDirect = true)
        String lookup(String key) {
            return "forty-two";
        }
    }

    // Review 3, item 9: an unmapped failure is recorded by its type only, never by its message text.
    @Test
    void anUnmappedFailureIsRecordedByTypeWithoutItsMessage() {
        EngineHarness harness = harness();
        var leaking = new org.springframework.ai.tool.ToolCallback() {
            @Override
            public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
                return org.springframework.ai.tool.definition.ToolDefinition.builder().name("leak").description("d")
                        .inputSchema("{\"type\":\"object\"}").build();
            }

            @Override
            public String call(String toolInput) {
                throw new IllegalStateException("secret provider text from a tool");
            }
        };
        ModelGateway gateway = gateway(invocation -> FakeReply.text(List.of(), ModelUsage.reported(1, 1, null, null, null))
                .withToolCalls(List.of(new ToolCall("c1", "leak", "{}"))));
        UUID id = submit(harness, Map.of("prompt", "leak"), invoker(harness));

        RunRecord failed = runOnce(harness, batching(gateway, 64, leaking), id);

        assertThat(failed.summary().terminalCode()).isEqualTo("EXECUTOR_FAILED");
        var last = harness.engine.history(harness.workspace, id).getLast();
        assertThat(last.payload().path("message").stringValue()).contains(IllegalStateException.class.getName());
        assertThat(last.raw().payloadJson()).doesNotContain("secret");
    }

    // #181 medium 1: a tool's own database exception is the tool's failure, not infrastructure. The run fails once,
    // typed and by class name only, instead of being abandoned for recovery and re-running the tool forever.
    @Test
    void aToolsOwnDatabaseExceptionFailsTheRunOnceInsteadOfLeavingItForRecovery() {
        EngineHarness harness = harness();
        AtomicInteger invocations = new AtomicInteger();
        var failingTool = new org.springframework.ai.tool.ToolCallback() {
            @Override
            public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
                return org.springframework.ai.tool.definition.ToolDefinition.builder().name("lookup").description("d")
                        .inputSchema("{\"type\":\"object\"}").build();
            }

            @Override
            public String call(String toolInput) {
                invocations.incrementAndGet();
                throw new org.springframework.dao.EmptyResultDataAccessException("no row for secret key", 1);
            }
        };
        ModelGateway gateway = gateway(invocation -> FakeReply.text(List.of(), ModelUsage.reported(1, 1, null, null, null))
                .withToolCalls(List.of(new ToolCall("c1", "lookup", "{}"))));
        UUID id = submit(harness, Map.of("prompt", "look it up"), invoker(harness));

        try (RunWorker worker = worker(harness, batching(gateway, 64, failingTool))) {
            assertThat(worker.pollOnce()).isEqualTo(RunWorker.Poll.EXECUTED);
            harness.store.advance(Duration.ofMinutes(5));
            assertThat(worker.pollOnce()).isEqualTo(RunWorker.Poll.IDLE);
        }

        RunRecord failed = harness.engine.run(harness.workspace, id);
        assertThat(failed.lifecycle()).isEqualTo(RunLifecycle.FAILED);
        assertThat(failed.summary().terminalCode()).isIn("TOOL_FAILED", "EXECUTOR_FAILED");
        assertThat(invocations).hasValue(1);
        var history = harness.engine.history(harness.workspace, id);
        assertThat(history.stream().filter(event -> event.type() == RunEventType.TOOL_FAILED)).singleElement()
                .satisfies(event -> assertThat(event.payload().path("error").stringValue())
                        .isEqualTo(org.springframework.dao.EmptyResultDataAccessException.class.getName()));
        assertThat(types(harness, id)).doesNotContain(RunEventType.RUN_RECOVERED);
        assertThat(history.getLast().raw().payloadJson()).doesNotContain("secret");
    }

    // #181 medium 1: a database failure of AgentWorks' own recorder while a tool's evidence is written still leaves
    // the run for recovery (LOST), and the recovered run completes.
    @Test
    void aRecorderDatabaseFailureAroundAToolStillLeavesTheRunForRecovery() {
        EngineHarness harness = harness();
        var failures = new AtomicInteger();
        var failingOnce = new DurableRunEngine(new QuarantineDelegating(harness.store) {
            @Override
            public <T> T inTransaction(com.dvarahq.agentworks.domain.tenant.WorkspaceId workspace,
                    java.util.function.Function<com.dvarahq.agentworks.domain.run.RunTransaction, T> work) {
                // The first write of a tool's start fails, as a lost connection would.
                boolean toolStart = java.util.Arrays.stream(Thread.currentThread().getStackTrace())
                        .anyMatch(frame -> frame.getMethodName().equals("toolStarted"));
                if (toolStart && failures.getAndIncrement() == 0) {
                    throw new org.springframework.dao.DataAccessResourceFailureException("connection lost");
                }
                return harness.store.inTransaction(workspace, work);
            }

            @Override
            public <T> java.util.Optional<T> claimNext(com.dvarahq.agentworks.domain.run.ClaimScope scope,
                    java.util.function.BiFunction<RunRecord, com.dvarahq.agentworks.domain.run.RunTransaction, T> work) {
                return harness.store.claimNext(scope, work);
            }
        }, RunEventCodec.standard(), new ReleaseManifestReader(), new com.dvarahq.agentworks.storage.lifecycle.LifecycleRetry(1));
        ModelGateway gateway = gateway(invocation -> {
            boolean answered = invocation.request().messages().stream().anyMatch(m -> m.role() == ModelMessage.Role.TOOL);
            if (!answered) {
                return FakeReply.text(List.of(), ModelUsage.reported(3, 1, null, null, null))
                        .withToolCalls(List.of(new ToolCall("call-1", "add", "{\"left\":2,\"right\":3}")));
            }
            return FakeReply.text(List.of("done"), ModelUsage.reported(5, 2, null, null, null));
        });
        UUID id = submit(harness, Map.of("prompt", "add 2 and 3"), invoker(harness));

        try (RunWorker worker = new RunWorker(failingOnce, executor(gateway, new Calculator()), "worker-1",
                harness.scope(), Duration.ofSeconds(30))) {
            assertThat(worker.pollOnce()).isEqualTo(RunWorker.Poll.LOST);
            assertThat(harness.engine.run(harness.workspace, id).lifecycle()).isEqualTo(RunLifecycle.ACTIVE);
            harness.store.advance(Duration.ofMinutes(1));
            assertThat(worker.pollOnce()).isEqualTo(RunWorker.Poll.EXECUTED);
        }

        assertThat(failures.get()).isPositive();
        RunRecord done = harness.engine.run(harness.workspace, id);
        assertThat(done.lifecycle()).as(String.valueOf(done.summary().terminalCode())).isEqualTo(RunLifecycle.SUCCEEDED);
        assertThat(types(harness, id)).contains(RunEventType.RUN_RECOVERED).doesNotContain(RunEventType.RUN_FAILED);
    }

    // Review 3, item 5, and review 4, items 1 and 2: a node without a run's executor type never selects it, so it is
    // neither refused nor quarantined; a capable node runs it.
    @Test
    void aNodeWithoutTheRunsExecutorTypeNeverClaimsIt() {
        EngineHarness harness = harness();
        UUID id = submit(harness, Map.of("prompt", "x"), invoker(harness));
        AtomicInteger calls = new AtomicInteger();

        try (RunWorker incapable = new RunWorker(harness.engine, context -> {
            calls.incrementAndGet();
            return RunOutcome.completed(null);
        }, "remote-node", harness.scope().executing(java.util.Set.of("remote-http")), Duration.ofSeconds(30))) {
            assertThat(incapable.pollOnce()).isEqualTo(RunWorker.Poll.IDLE);
            assertThat(incapable.pollOnce()).isEqualTo(RunWorker.Poll.IDLE);
            assertThat(harness.quarantined()).isEmpty();
        }
        assertThat(calls).hasValue(0);
        RunRecord untouched = harness.engine.run(harness.workspace, id);
        assertThat(untouched.lifecycle()).isEqualTo(RunLifecycle.QUEUED);
        assertThat(types(harness, id)).doesNotContain(RunEventType.RUN_ACTIVATED);

        try (RunWorker capable = new RunWorker(harness.engine, executor(gateway(DeterministicFakeGatewayAdapter::echo)),
                "spring-node", harness.scope().executing(java.util.Set.of("spring-ai")), Duration.ofSeconds(30))) {
            assertThat(capable.pollOnce()).isEqualTo(RunWorker.Poll.EXECUTED);
        }
        assertThat(harness.engine.run(harness.workspace, id).lifecycle()).isEqualTo(RunLifecycle.SUCCEEDED);
    }

    // Review 4, items 1 and 2: more than a thousand older runs of another executor type never delay a node's own.
    @Test
    void aNodeSelectsItsOwnRunBehindMoreThanAThousandOlderRunsOfAnotherExecutorType() {
        EngineHarness harness = harness();
        EngineHarness foreign = new EngineHarness(harness);
        foreign.store.manifest(foreign.release, EngineHarness.manifest(foreign.version)
                .replace("\"type\":\"spring-ai\"", "\"type\":\"remote-http\""));
        List<UUID> older = new ArrayList<>();
        for (int index = 0; index < 1_100; index++) older.add(submit(foreign, Map.of("prompt", "x"), null));
        UUID own = submit(harness, Map.of("prompt", "mine"), invoker(harness));
        var springNode = com.dvarahq.agentworks.domain.run.ClaimScope.any(ReleaseManifestReader.SUPPORTED_VERSIONS)
                .executing(java.util.Set.of("spring-ai"));

        try (RunWorker worker = new RunWorker(harness.engine, executor(gateway(DeterministicFakeGatewayAdapter::echo)),
                "spring-node", springNode, Duration.ofSeconds(30))) {
            assertThat(worker.pollOnce()).isEqualTo(RunWorker.Poll.EXECUTED);
            assertThat(worker.pollOnce()).isEqualTo(RunWorker.Poll.IDLE);
            assertThat(harness.quarantined()).isEmpty();
        }

        assertThat(harness.engine.run(harness.workspace, own).lifecycle()).isEqualTo(RunLifecycle.SUCCEEDED);
        assertThat(older).allSatisfy(id -> assertThat(foreign.engine.run(foreign.workspace, id).lifecycle())
                .isEqualTo(RunLifecycle.QUEUED));
    }

    @Test
    void aRunWithoutAnInvokerOrPinnedVersionFailsClosedWithoutInvokingTheModel() {
        EngineHarness harness = harness();
        AtomicInteger calls = new AtomicInteger();
        ModelGateway gateway = gateway(invocation -> {
            calls.incrementAndGet();
            return DeterministicFakeGatewayAdapter.echo(invocation);
        });
        UUID anonymous = submit(harness, Map.of("prompt", "x"), null);
        EngineHarness unversioned = new EngineHarness();
        unversioned.store.manifest(unversioned.release, ReleaseManifestReaderTest.version2(unversioned.version, "m"));
        unversioned.manifestVersion = 2;
        UUID noVersion = submit(unversioned, Map.of("prompt", "x"), invoker(unversioned));
        EngineHarness legacy = new EngineHarness();
        legacy.store.version(new PinnedAgentVersion(legacy.version, SPEC, DIGEST));
        UUID unresolved = submit(legacy, Map.of("prompt", "x"), invoker(legacy));

        for (var harnessAndRun : List.of(Map.entry(harness, anonymous), Map.entry(unversioned, noVersion),
                Map.entry(legacy, unresolved))) {
            try (RunWorker worker = worker(harnessAndRun.getKey(), executor(gateway))) {
                assertThat(worker.pollOnce()).isEqualTo(RunWorker.Poll.EXECUTED);
            }
        }

        assertThat(harness.engine.run(harness.workspace, anonymous).summary().terminalCode())
                .isEqualTo("INVOKER_REQUIRED");
        assertThat(unversioned.engine.run(unversioned.workspace, noVersion).summary().terminalCode())
                .isEqualTo("AGENT_VERSION_UNAVAILABLE");
        // A version 1 release never resolved its model roles: it cannot invoke one now.
        assertThat(legacy.engine.run(legacy.workspace, unresolved).summary().terminalCode())
                .isEqualTo("GATEWAY_MODEL_ROLE_UNRESOLVED");
        assertThat(calls).hasValue(0);
    }

    @Test
    void anAgentVersionThatDoesNotMatchTheManifestIsRefusedAtClaim() {
        EngineHarness harness = harness();
        harness.store.version(new PinnedAgentVersion(harness.version, SPEC, "sha256:" + "b".repeat(64)));
        UUID id = submit(harness, Map.of("prompt", "x"), invoker(harness));

        try (RunWorker worker = worker(harness, executor(gateway(DeterministicFakeGatewayAdapter::echo)))) {
            assertThat(worker.pollOnce()).isEqualTo(RunWorker.Poll.REFUSED);
        }
        assertThat(harness.engine.run(harness.workspace, id).lifecycle()).isEqualTo(RunLifecycle.QUEUED);
    }

    @Test
    void aRecordedInvokerFromAnotherWorkspaceIsRefused() {
        EngineHarness harness = harness();
        RunInvoker foreign = new RunInvoker(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "session");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> harness.run(RunLimits.UNBOUNDED, "x", foreign))
                .isInstanceOfSatisfying(RunEngineException.class, refused ->
                        assertThat(refused.code()).isEqualTo("INVALID_EVENT"));
    }

    @Test
    void anUnknownExecutorTypePausesTheRunInsteadOfSubstitutingAnotherExecutor() throws Exception {
        EngineHarness harness = harness();
        UUID id = submit(harness, Map.of("prompt", "x"), invoker(harness));
        AtomicInteger calls = new AtomicInteger();
        ExecutorRouter router = new ExecutorRouter(Map.of("remote-http", context -> {
            calls.incrementAndGet();
            return RunOutcome.completed(null);
        }));

        try (RunWorker worker = worker(harness, router)) {
            assertThat(worker.pollOnce()).isEqualTo(RunWorker.Poll.EXECUTED);
        }

        RunRecord paused = harness.engine.run(harness.workspace, id);
        assertThat(paused.lifecycle()).isEqualTo(RunLifecycle.PAUSED);
        assertThat(harness.engine.history(harness.workspace, id).getLast().payload().path("reason").stringValue())
                .isEqualTo(ExecutorRouter.UNAVAILABLE);
        assertThat(calls).hasValue(0);
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> assertThat(router.types()).containsExactly("remote-http"));
    }
}
