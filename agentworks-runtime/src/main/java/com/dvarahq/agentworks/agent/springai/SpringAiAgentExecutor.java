package com.dvarahq.agentworks.agent.springai;

import com.dvarahq.agentworks.agent.AgentExecutor;
import com.dvarahq.agentworks.artifact.ArtifactStore;
import com.dvarahq.agentworks.agent.springai.DvaraAdvisors.RunGuardAdvisor.SuspendedException;
import com.dvarahq.agentworks.agent.springai.ExecutorInput.InvalidInputException;
import com.dvarahq.agentworks.agent.springai.ExecutorInput.StructuredOutput;
import com.dvarahq.agentworks.agent.springai.GatewayChatModel.ModelRoundsExceededException;
import com.dvarahq.agentworks.capability.AgentTool;
import com.dvarahq.agentworks.capability.ToolEffect;
import com.dvarahq.agentworks.capability.ToolId;
import com.dvarahq.agentworks.credential.JitCredentialToolInvoker;
import com.dvarahq.agentworks.context.BasicContextCompiler;
import com.dvarahq.agentworks.context.CompiledContext;
import com.dvarahq.agentworks.context.ContextBudget;
import com.dvarahq.agentworks.context.ContextCacheKey;
import com.dvarahq.agentworks.context.ContextClassification;
import com.dvarahq.agentworks.context.ContextCompilationCache;
import com.dvarahq.agentworks.context.ContextCompilationException;
import com.dvarahq.agentworks.context.ContextCompilationRequest;
import com.dvarahq.agentworks.context.ContextCompiler;
import com.dvarahq.agentworks.context.ContextConstraints;
import com.dvarahq.agentworks.context.ContextDestination;
import com.dvarahq.agentworks.context.ContextItem;
import com.dvarahq.agentworks.context.ContextItemType;
import com.dvarahq.agentworks.context.ContextLoadingPolicy;
import com.dvarahq.agentworks.context.ContextProvenance;
import com.dvarahq.agentworks.context.SlidingWindowCompactionStrategy;
import com.dvarahq.agentworks.domain.lifecycle.AgentRunId;
import com.dvarahq.agentworks.domain.run.PinnedAgentVersion;
import com.dvarahq.agentworks.domain.run.RunInvoker;
import com.dvarahq.agentworks.domain.run.RunPins;
import com.dvarahq.agentworks.domain.security.CredentialRef;
import com.dvarahq.agentworks.gateway.CancellationSignal;
import com.dvarahq.agentworks.gateway.GatewayException;
import com.dvarahq.agentworks.gateway.InvocationCorrelation;
import com.dvarahq.agentworks.gateway.ModelGateway;
import com.dvarahq.agentworks.gateway.ModelRole;
import com.dvarahq.agentworks.gateway.PinnedModel;
import com.dvarahq.agentworks.gateway.ResponseFormat;
import com.dvarahq.agentworks.memory.AgentMemoryStore;
import com.dvarahq.agentworks.memory.KnowledgeProvider;
import com.dvarahq.agentworks.memory.KnowledgeQuery;
import com.dvarahq.agentworks.memory.MemoryActor;
import com.dvarahq.agentworks.memory.MemoryKind;
import com.dvarahq.agentworks.memory.MemoryQuery;
import com.dvarahq.agentworks.memory.MemoryRecord;
import com.dvarahq.agentworks.memory.MemoryScope;
import com.dvarahq.agentworks.memory.MemoryWrite;
import com.dvarahq.agentworks.runplane.execution.RunContext;
import com.dvarahq.agentworks.runplane.execution.RunEngineException;
import com.dvarahq.agentworks.runplane.execution.RunEventCodec;
import com.dvarahq.agentworks.runplane.execution.RunHandler;
import com.dvarahq.agentworks.runplane.execution.RunOutcome;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.reactivestreams.Subscription;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.model.tool.ToolCallLimitExceededException;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Flux;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The production Spring AI executor (issue #34), run by a {@link com.dvarahq.agentworks.runplane.execution.RunWorker}
 * as the {@link RunHandler} of durable runs whose release pins executor type {@value #TYPE}.
 *
 * <p>Stateless and thread-safe (ADR-004): its fields are immutable collaborators and configuration, so concurrent
 * runs of any agents, releases, and tenants share one instance. Everything a continuation needs is built per call from
 * the run's pins, manifest, pinned AgentVersion, recorded invoker, input, and checkpoint: the {@link GatewayChatModel},
 * the {@link DurableRunRecorder}, the advisor chain, the recorded tools, and the cancellation signal.
 *
 * <p>One continuation:
 * <ol>
 *   <li>A run that recovers after its response was checkpointed completes from that checkpoint without invoking the
 *       model again (the restart boundary).</li>
 *   <li>The reasoning model is the one the release pinned ({@code PinnedModelRoles}); the principal is the recorded
 *       invoker; the system instructions come from the pinned AgentVersion.</li>
 *   <li>The exchange streams through Spring AI's {@code ChatClient} over the gateway bridge, with the explicit advisor
 *       order of {@link DvaraAdvisors}. Streamed content is recorded incrementally as durable events.</li>
 *   <li>A requested structured output is validated against its schema; a violation fails the run with
 *       {@code STRUCTURED_OUTPUT_INVALID}, after the model events and before any output is recorded.</li>
 *   <li>The output is checkpointed, then the run completes (or pauses, if its cohort is suspended).</li>
 * </ol>
 *
 * <p>Cancellation is durable: cancelling the run makes the next heartbeat or write find the lease gone, the worker
 * interrupts this continuation, and disposing the stream cancels the model invocation. Nothing further is written, so
 * {@code RUN_CANCELLED} remains the one terminal outcome. Spring AI and Reactor types never leave this package, and
 * Spring AI chat memory is never used: conversation state comes only from durable run state.
 */
public final class SpringAiAgentExecutor implements AgentExecutor, RunHandler,
        com.dvarahq.agentworks.runplane.execution.ExecutorCapabilities.Declaring {
    /**
     * The executor checkpoints only its final response, so a run paused mid-exchange would repeat completed model
     * rounds and tool calls when it resumes: an operator may not pause its active runs (#183) until per-round
     * checkpoints (#44).
     */
    public static final com.dvarahq.agentworks.runplane.execution.ExecutorCapabilities CAPABILITIES =
            new com.dvarahq.agentworks.runplane.execution.ExecutorCapabilities(false);

    @Override
    public com.dvarahq.agentworks.runplane.execution.ExecutorCapabilities capabilities() {
        return CAPABILITIES;
    }

    public static final String TYPE = "spring-ai";
    /**
     * The largest output a run records. It appears in both the checkpoint and {@code RUN_SUCCEEDED}, each bounded by
     * the engine's payload limit, so it leaves room for JSON escaping. Larger outputs need artifact storage (#84).
     */
    public static final int MAX_OUTPUT_BYTES = 96 * 1024;
    /** The step of a response no model call produced, such as a tool's returnDirect result. */
    private static final Object DIRECT = new Object();
    private static final com.dvarahq.agentworks.runplane.execution.RunEventCodec CODEC =
            com.dvarahq.agentworks.runplane.execution.RunEventCodec.standard();

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ModelGateway gateway;
    private final ToolCallbackProvider tools;
    private final CapabilityBindings capabilityTools;
    private final ToolPermissionAuthority permissionAuthority;
    private final ToolInputGuardrail inputGuardrail;
    private final ToolOutputGuardrail outputGuardrail;
    private final JitCredentialToolInvoker credentialInvoker;
    private final ToolInteractionAuthority interactionAuthority;
    private final ArtifactStore artifactStore;
    private final ObservationRegistry observations;
    private final SpringAiExecutorSettings settings;
    private final ContextCompiler contextCompiler;
    private final AgentMemoryStore memoryStore;
    private final KnowledgeProviders knowledgeProviders;

    public SpringAiAgentExecutor(ModelGateway gateway, ToolCallbackProvider tools, ObservationRegistry observations,
            SpringAiExecutorSettings settings) {
        this(gateway, tools, CapabilityToolCallbackProvider.from(), observations, settings, null,
                ToolInputGuardrail.validatedInput(), ToolOutputGuardrail.validatedOutput());
    }

    public SpringAiAgentExecutor(ModelGateway gateway, ToolCallbackProvider tools,
            CapabilityToolCallbackProvider capabilityTools, ObservationRegistry observations,
            SpringAiExecutorSettings settings) {
        this(gateway, tools, capabilityTools, observations, settings, null, ToolInputGuardrail.validatedInput(),
                ToolOutputGuardrail.validatedOutput());
    }

    public SpringAiAgentExecutor(ModelGateway gateway, ToolCallbackProvider tools,
            CapabilityToolCallbackProvider capabilityTools, ObservationRegistry observations,
            SpringAiExecutorSettings settings, ToolPermissionAuthority permissionAuthority) {
        this(gateway, tools, capabilityTools, observations, settings, permissionAuthority,
                ToolInputGuardrail.validatedInput(), ToolOutputGuardrail.validatedOutput());
    }

    public SpringAiAgentExecutor(ModelGateway gateway, ToolCallbackProvider tools,
            CapabilityToolCallbackProvider capabilityTools, ObservationRegistry observations,
            SpringAiExecutorSettings settings, ToolPermissionAuthority permissionAuthority,
            ToolInputGuardrail inputGuardrail) {
        this(gateway, tools, capabilityTools, observations, settings, permissionAuthority, inputGuardrail,
                ToolOutputGuardrail.validatedOutput());
    }

    public SpringAiAgentExecutor(ModelGateway gateway, ToolCallbackProvider tools,
            CapabilityToolCallbackProvider capabilityTools, ObservationRegistry observations,
            SpringAiExecutorSettings settings, ToolPermissionAuthority permissionAuthority,
            ToolInputGuardrail inputGuardrail, ToolOutputGuardrail outputGuardrail) {
        this(gateway, tools, capabilityTools, observations, settings, permissionAuthority, inputGuardrail,
                outputGuardrail, null);
    }

    public SpringAiAgentExecutor(ModelGateway gateway, ToolCallbackProvider tools,
            CapabilityToolCallbackProvider capabilityTools, ObservationRegistry observations,
            SpringAiExecutorSettings settings, ToolPermissionAuthority permissionAuthority,
            ToolInputGuardrail inputGuardrail, ToolOutputGuardrail outputGuardrail,
            JitCredentialToolInvoker credentialInvoker) {
        this(gateway, tools, capabilityTools, observations, settings, permissionAuthority, inputGuardrail,
                outputGuardrail, credentialInvoker, null);
    }

    public SpringAiAgentExecutor(ModelGateway gateway, ToolCallbackProvider tools,
            CapabilityToolCallbackProvider capabilityTools, ObservationRegistry observations,
            SpringAiExecutorSettings settings, ToolPermissionAuthority permissionAuthority,
            ToolInputGuardrail inputGuardrail, ToolOutputGuardrail outputGuardrail,
            JitCredentialToolInvoker credentialInvoker, ToolInteractionAuthority interactionAuthority) {
        this(gateway, tools, capabilityTools, observations, settings, permissionAuthority, inputGuardrail,
                outputGuardrail, credentialInvoker, interactionAuthority, null);
    }

    public SpringAiAgentExecutor(ModelGateway gateway, ToolCallbackProvider tools,
            CapabilityToolCallbackProvider capabilityTools, ObservationRegistry observations,
            SpringAiExecutorSettings settings, ToolPermissionAuthority permissionAuthority,
            ToolInputGuardrail inputGuardrail, ToolOutputGuardrail outputGuardrail,
            JitCredentialToolInvoker credentialInvoker, ToolInteractionAuthority interactionAuthority,
            ArtifactStore artifactStore) {
        this(gateway, tools, capabilityTools, observations, settings, permissionAuthority, inputGuardrail,
                outputGuardrail, credentialInvoker, interactionAuthority, artifactStore,
                new BasicContextCompiler(new ContextCompilationCache(1024), new SlidingWindowCompactionStrategy()));
    }

    public SpringAiAgentExecutor(ModelGateway gateway, ToolCallbackProvider tools,
            CapabilityToolCallbackProvider capabilityTools, ObservationRegistry observations,
            SpringAiExecutorSettings settings, ToolPermissionAuthority permissionAuthority,
            ToolInputGuardrail inputGuardrail, ToolOutputGuardrail outputGuardrail,
            JitCredentialToolInvoker credentialInvoker, ToolInteractionAuthority interactionAuthority,
            ArtifactStore artifactStore, ContextCompiler contextCompiler) {
        this(gateway, tools, capabilityTools, observations, settings, permissionAuthority, inputGuardrail,
                outputGuardrail, credentialInvoker, interactionAuthority, artifactStore, contextCompiler, null,
                List.of());
    }

    public SpringAiAgentExecutor(ModelGateway gateway, ToolCallbackProvider tools,
            CapabilityToolCallbackProvider capabilityTools, ObservationRegistry observations,
            SpringAiExecutorSettings settings, ToolPermissionAuthority permissionAuthority,
            ToolInputGuardrail inputGuardrail, ToolOutputGuardrail outputGuardrail,
            JitCredentialToolInvoker credentialInvoker, ToolInteractionAuthority interactionAuthority,
            ArtifactStore artifactStore, ContextCompiler contextCompiler, AgentMemoryStore memoryStore,
            List<KnowledgeProvider> knowledgeProviders) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.tools = Objects.requireNonNull(tools, "tools");
        Objects.requireNonNull(capabilityTools, "capabilityTools");
        Map<ToolId, CapabilityToolCallback> bindings = new LinkedHashMap<>();
        for (CapabilityToolCallback binding : capabilityTools.getCapabilityToolCallbacks()) {
            Objects.requireNonNull(binding, "capability tool callback");
            if (bindings.putIfAbsent(binding.id(), binding) != null) {
                throw new IllegalArgumentException("duplicate capability callback identity: " + binding.id().value());
            }
        }
        this.capabilityTools = new CapabilityBindings(bindings);
        this.permissionAuthority = permissionAuthority;
        this.inputGuardrail = Objects.requireNonNull(inputGuardrail, "inputGuardrail");
        this.outputGuardrail = Objects.requireNonNull(outputGuardrail, "outputGuardrail");
        this.credentialInvoker = credentialInvoker;
        this.interactionAuthority = interactionAuthority;
        this.artifactStore = artifactStore;
        this.observations = Objects.requireNonNull(observations, "observations");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.contextCompiler = Objects.requireNonNull(contextCompiler, "contextCompiler");
        this.memoryStore = memoryStore;
        this.knowledgeProviders = new KnowledgeProviders(knowledgeProviders);
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public RunOutcome execute(RunContext context) throws Exception {
        try (ToolRunScope scope = new ToolRunScope(context)) {
            return executeScoped(context, scope);
        } catch (RecordingFailedException recording) {
            throw recording.failure();
        } catch (ToolAdapterException refusal) {
            return outcome(refusal);
        }
    }

    private RunOutcome executeScoped(RunContext context, ToolRunScope scope) throws Exception {
        Optional<com.dvarahq.agentworks.domain.agent.compiled.CompiledAgentDefinition> compiledDefinition =
                context.compiledDefinition();
        if (compiledDefinition.isPresent() && !TYPE.equals(compiledDefinition.get().executorType())) {
            return RunOutcome.failed("COMPILED_RELEASE_MISMATCH",
                    "the compiled release does not select the claimed executor");
        }
        Optional<JsonNode> restored = context.checkpointState();
        ExecutorInput input;
        try {
            input = ExecutorInput.from(context.input());
        } catch (InvalidInputException invalid) {
            return RunOutcome.failed(invalid.code(), invalid.getMessage());
        }
        Optional<RunInvoker> invoker = context.invoker();
        if (invoker.isEmpty()) {
            return RunOutcome.failed("INVOKER_REQUIRED", "the run records no authenticated principal to invoke models as");
        }
        if (restored.isPresent() && restored.get().path("phase").isString()
                && "RESPONDED".equals(restored.get().path("phase").stringValue())) {
            try {
                finalizeMemory(context, invoker.orElseThrow(), input.prompt(), restored.get().get("output"));
            } catch (RuntimeException unavailable) {
                return RunOutcome.failed("MEMORY_UNAVAILABLE", "the checkpointed response memory could not be finalized");
            }
            return RunOutcome.completed(restored.get().get("output"));
        }
        PinnedModel model;
        try {
            model = context.manifest().models().role(ModelRole.REASONING);
        } catch (GatewayException unpinned) {
            return RunOutcome.failed("GATEWAY_" + unpinned.code().name(), unpinned.detail());
        }
        Optional<PinnedAgentVersion> version = context.agentVersion();
        if (version.isEmpty()) {
            return RunOutcome.failed("AGENT_VERSION_UNAVAILABLE", "the pinned AgentVersion cannot be read");
        }
        String instructions;
        try {
            instructions = instructions(version.get());
        } catch (JacksonException unreadable) {
            return RunOutcome.failed("AGENT_VERSION_UNAVAILABLE", "the pinned AgentVersion is not readable JSON");
        }
        List<SelectedTool> selectedTools;
        try {
            selectedTools = selectedTools(context, scope);
            instructions = capabilityInstructions(context, instructions);
        } catch (InvalidInputException invalid) {
            return RunOutcome.failed(invalid.code(), invalid.getMessage());
        }

        DurableRunRecorder recorder = new DurableRunRecorder(context, settings.chunkBatchChars());
        CancellationSignal continuation = CancellationSignal.create();
        String userPrompt = input.prompt();
        if (context.manifest().contextPolicy().isPresent()) {
            try {
                CompiledContext compiled = compileContext(context, instructions, input.prompt(),
                        memoryContext(context, invoker.orElseThrow(), input.prompt(), continuation));
                recorder.contextCompiled(compiled);
                instructions = compiled.stablePrefix().isBlank() ? null : compiled.stablePrefix();
                userPrompt = compiled.dynamicContent();
            } catch (ContextCompilationException refused) {
                return RunOutcome.failed(refused.code(), refused.getMessage());
            } catch (com.dvarahq.agentworks.memory.KnowledgeRetrievalException unavailable) {
                return RunOutcome.failed(unavailable.code(), unavailable.getMessage());
            } catch (RuntimeException unavailable) {
                return RunOutcome.failed("MEMORY_UNAVAILABLE", "memory or knowledge retrieval failed");
            }
        }

        ToolResultArtifactBinding artifactBinding = artifactStore == null ? null
                : new ToolResultArtifactBinding(artifactStore, context.pins(), invoker.get());
        ToolCallIdentityRegistry toolCallIdentities = new ToolCallIdentityRegistry(context.pins().id());
        List<ToolCallback> recorded = selectedTools.stream()
                .<ToolCallback>map(tool -> new RecordingToolCallback(tool.callback(), recorder,
                        tool.effect().map(effect -> new ToolPolicyGate(context.pins(), invoker.get().identity(),
                                context.manifest().executionPolicy(), tool.capability(), effect, permissionAuthority))
                                .orElseGet(() -> new ToolPolicyGate(context.pins().workspaceId(),
                                        invoker.get().identity(), context.manifest().executionPolicy())),
                        tool.inputSchema(), new ToolInputGuardrailGate(inputGuardrail, context.pins(),
                                invoker.get().identity(), context.manifest().executionPolicy(),
                                tool.callback().getToolDefinition().name(), tool.capability(), tool.effect()),
                        new ToolOutputGuardrailGate(outputGuardrail, context.pins(), invoker.get().identity(),
                                context.manifest().executionPolicy(), tool.callback().getToolDefinition().name(),
                                tool.capability(), tool.effect()),
                        tool.credentialRef().map(reference -> new ToolCredentialBinding(credentialInvoker, reference,
                                invoker.get().identity(), context.pins().definitionId())).orElse(null),
                        tool.effect().map(effect -> new ToolEffectBinding(context,
                                tool.callback().getToolDefinition().name(), tool.capability(), effect))
                                .orElse(null),
                        tool.effect().filter(ignored -> interactionAuthority != null).map(effect ->
                                new ToolInteractionBinding(context.pins(), invoker.get().identity(),
                                        tool.callback().getToolDefinition().name(), tool.capability(), effect,
                                        interactionAuthority)).orElse(null), artifactBinding, toolCallIdentities)).toList();
        RunPins pins = context.pins();
        GatewayChatModel chatModel = new GatewayChatModel(gateway, model, invoker.get().identity(),
                new InvocationCorrelation(new AgentRunId(pins.id()), pins.traceId()), settings.modelTimeout(),
                continuation, input.structured().map(StructuredOutput::responseFormat).orElse(ResponseFormat.TEXT),
                recorder, recorded, settings.maxModelRounds(), toolCallIdentities);
        // Correlates the exchange in default telemetry by identifiers only; no prompt or output is ever a key value.
        Observation observation = Observation.createNotStarted("agentworks.run.execute", observations)
                .lowCardinalityKeyValue("agentworks.executor", TYPE)
                .highCardinalityKeyValue("agentworks.run.id", pins.id().toString())
                .highCardinalityKeyValue("agentworks.trace.id", pins.traceId())
                .highCardinalityKeyValue("agentworks.release.id", pins.releaseId().toString())
                .highCardinalityKeyValue("agentworks.deployment.id", pins.deploymentId().toString())
                .highCardinalityKeyValue("agentworks.revision.id", pins.revisionId().toString())
                .highCardinalityKeyValue("agentworks.cohort.id", pins.cohortId().toString()).start();
        String content;
        try (Observation.Scope ignored = observation.openScope()) {
            ChatClient client = ChatClient.builder(chatModel, observations, null, null)
                    .defaultAdvisors(DvaraAdvisors.chain(context, continuation, recorder::pauseIfRequested)).build();
            ChatClient.ChatClientRequestSpec request = client.prompt();
            if (instructions != null) request = request.system(instructions);
            request = request.user(userPrompt);
            content = consume(request.stream().chatClientResponse(), continuation);
            recorder.flush();
        } catch (RuntimeException failure) {
            observation.error(failure);
            return outcome(failure);
        } finally {
            RunEngineException abandoned = null;
            try {
                // However the exchange ended, no write of the model stream's thread may follow this continuation's own
                // outcome under the same lease: wait out a write in progress and refuse any later one.
                continuation.cancel();
                // The drain is observed on its own, by outcome, so telemetry (and a test) tells an abandoned drain's
                // cause. It is a child of the exchange's observation, which is stopped only after it (#206, PR #208
                // review), so the drain's span lies within the exchange's.
                Observation drain = Observation.createNotStarted("agentworks.run.drain", observations)
                        .parentObservation(observation)
                        .highCardinalityKeyValue("agentworks.run.id", pins.id().toString()).start();
                abandoned = observeDrain(drain, () -> {
                    DurableRunRecorder.Drain drained = recorder.close(settings.writeDrainTimeout());
                    drain.lowCardinalityKeyValue("agentworks.drain.outcome",
                            drained.name().toLowerCase(java.util.Locale.ROOT));
                    if (drained == DurableRunRecorder.Drain.DRAINED) return null;
                    RunEngineException lost = new RunEngineException("STALE_LEASE",
                            "a stream write did not finish; the claim is abandoned");
                    // An abandoned drain is an error of its observation (#206), so telemetry counts it as one.
                    drain.error(lost);
                    return lost;
                });
            } finally {
                if (abandoned != null && observation.getContext().getError() == null) observation.error(abandoned);
                observation.stop();
            }
            if (abandoned != null) {
                // A stream write is still in flight (or this thread was interrupted): recording is refused from now
                // on, and this claim is abandoned rather than racing that write. The worker treats the stale lease as
                // lost, so the run is recovered from its last checkpoint after the lease lapses.
                throw abandoned;
            }
        }

        Object output;
        if (input.structured().isPresent()) {
            try {
                output = Map.of("type", "json", "value", input.structured().get().validate(content));
            } catch (InvalidInputException invalid) {
                return RunOutcome.failed(invalid.code(), invalid.getMessage());
            }
        } else {
            output = Map.of("type", "text", "content", content);
        }
        if (JSON.writeValueAsBytes(output).length > MAX_OUTPUT_BYTES) {
            return RunOutcome.failed("OUTPUT_TOO_LARGE", "the recorded output would exceed " + MAX_OUTPUT_BYTES + " bytes");
        }
        Map<String, Object> checkpoint = new LinkedHashMap<>();
        checkpoint.put("phase", "RESPONDED");
        checkpoint.put("output", output);
        try {
            // The deepest record of the output is the checkpoint, whose payload nests the state as {"state": ...}; the
            // RUN_SUCCEEDED payload nests it less deeply. Validating the checkpoint's envelope covers both.
            CODEC.canonicalPayload(Map.of("state", checkpoint));
        } catch (RunEngineException unstorable) {
            return RunOutcome.failed(input.structured().isPresent() ? "STRUCTURED_OUTPUT_INVALID" : "OUTPUT_INVALID",
                    "the model output cannot be stored: " + unstorable.code());
        }
        context.checkpoint(checkpoint);
        try {
            finalizeMemory(context, invoker.orElseThrow(), input.prompt(), output);
        } catch (RuntimeException unavailable) {
            throw new RunEngineException("MEMORY_UNAVAILABLE",
                    "the checkpointed response memory could not be finalized", unavailable);
        }
        // A suspended cohort pauses even a finished continuation, as before. The worker treats this checkpoint as a
        // safe boundary for an operator's request (#183): for an executor declared resumable mid-exchange it pauses
        // here, and a resume completes the run from the checkpoint; for this executor, which is not (yet, #44), a
        // leftover request is cleared and the run completes.
        if (context.suspended()) return RunOutcome.paused("COHORT_SUSPENDED");
        return RunOutcome.completed(output);
    }

    /** Runs and always stops the drain observation, recording an operation failure before it propagates (#210). */
    static <T> T observeDrain(Observation drain, java.util.function.Supplier<T> operation) {
        try {
            return operation.get();
        } catch (RuntimeException failure) {
            drain.error(failure);
            throw failure;
        } finally {
            drain.stop();
        }
    }

    /**
     * Consumes the exchange on this thread, one response at a time. An interrupt (the worker stopping this
     * continuation) cancels the continuation, disposes the stream, and is rethrown.
     */
    private static String consume(Flux<ChatClientResponse> responses, CancellationSignal continuation)
            throws InterruptedException {
        StringBuilder content = new StringBuilder();
        long bytes = 0;
        Object step = null;
        try (StreamConsumer consumer = new StreamConsumer()) {
            responses.subscribe(consumer);
            ChatClientResponse next;
            while ((next = consumer.next()) != null) {
                ChatResponse response = next.chatResponse();
                if (response == null || response.getResult() == null) continue;
                if (ToolCallLimitExceededException.FINISH_REASON.equals(
                        response.getResult().getMetadata().getFinishReason())) {
                    throw new OutputRejectedException("TOOL_CALL_LIMIT_EXCEEDED",
                            "the model requested more tool calls than one exchange allows");
                }
                // Text a model streams in a tool-calling round is not the answer: only the last round's text is. A
                // response without a model step (a tool's returnDirect result) is a round of its own.
                Object current = response.getResult().getOutput().getMetadata().get(GatewayChatModel.STEP);
                if (current == null) current = DIRECT;
                if (!current.equals(step)) {
                    step = current;
                    content.setLength(0);
                    bytes = 0;
                }
                String text = response.getResult().getOutput().getText();
                if (text == null) continue;
                content.append(text);
                bytes += text.getBytes(StandardCharsets.UTF_8).length;
                if (bytes > MAX_OUTPUT_BYTES) {
                    // Stop paying for output that could never be recorded; closing the consumer cancels the stream.
                    throw new OutputRejectedException("OUTPUT_TOO_LARGE",
                            "the model output exceeds " + MAX_OUTPUT_BYTES + " bytes; larger outputs need artifact storage");
                }
            }
        } catch (InterruptedException stopped) {
            continuation.cancel();
            throw stopped;
        }
        return content.toString();
    }

    /** Maps a failed exchange to its outcome; engine failures go back to the worker unchanged. */
    private static RunOutcome outcome(RuntimeException failure) {
        // Failures of AgentWorks' own recording (the engine, its store, and their transactions), even wrapped by Spring
        // AI or Reactor, go back to the worker unchanged: it abandons the claim (LOST) on a database or transaction
        // failure, and the run is recovered after the lease lapses, instead of failing terminally. Only failures the
        // recorder marked qualify (#181): the same exception types thrown by a tool are the tool's failure, and end the
        // run typed below, so a deterministic tool failure is never re-executed by recovery. A write that failed while
        // a model failure was being recorded is attached to that failure as suppressed.
        RecordingFailedException recording = recordingFailure(failure);
        if (recording != null) throw recording.failure();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            switch (cause) {
                case ChildRunBlockedException child -> {
                    return RunOutcome.delegated(Map.of("relationshipId", child.relationshipId().toString(),
                            "childRunId", child.childRunId().toString()));
                }
                case GatewayException gateway -> {
                    return RunOutcome.failed("GATEWAY_" + gateway.code().name(), gateway.detail());
                }
                case SuspendedException suspended -> {
                    return RunOutcome.paused(suspended.reason());
                }
                case ModelRoundsExceededException rounds -> {
                    return RunOutcome.failed("MODEL_ROUNDS_EXCEEDED", rounds.getMessage());
                }
                case InvalidInputException invalid -> {
                    return RunOutcome.failed(invalid.code(), invalid.getMessage());
                }
                case OutputRejectedException rejected -> {
                    return RunOutcome.failed(rejected.code(), rejected.getMessage());
                }
                case ToolAdapterException rejected -> {
                    return RunOutcome.failed(rejected.code(), "tool adapter refused the operation");
                }
                case ToolInteractionRequiredException interaction -> {
                    return RunOutcome.interaction(interaction.draft());
                }
                default -> {
                }
            }
        }
        // Anything else is recorded by its type only: an exception message may carry model, tool, or provider text.
        return RunOutcome.failed("EXECUTOR_FAILED", "the exchange failed with " + failure.getClass().getName());
    }

    /** The most throwables {@link #recordingFailure(Throwable)} visits. */
    static final int MAX_FAILURE_GRAPH = 256;

    /**
     * The recorder's marker anywhere in {@code failure}'s graph of causes and suppressed failures, at any depth, or
     * {@code null}: a breadth-first walk that visits each throwable once (so cycles end) and at most
     * {@link #MAX_FAILURE_GRAPH} of them.
     */
    static RecordingFailedException recordingFailure(Throwable failure) {
        java.util.Set<Throwable> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        java.util.ArrayDeque<Throwable> pending = new java.util.ArrayDeque<>();
        if (failure != null) pending.add(failure);
        while (!pending.isEmpty() && seen.size() < MAX_FAILURE_GRAPH) {
            Throwable next = pending.poll();
            if (!seen.add(next)) continue;
            if (next instanceof RecordingFailedException recording) return recording;
            if (next.getCause() != null) pending.add(next.getCause());
            pending.addAll(java.util.Arrays.asList(next.getSuppressed()));
        }
        return null;
    }

    /** The system instructions the pinned AgentVersion authored, if any. */
    static String instructions(PinnedAgentVersion version) {
        JsonNode system = JSON.readTree(version.specJson()).path("spec").path("instructions").path("system");
        return system.isString() && !system.stringValue().isBlank() ? system.stringValue() : null;
    }

    private CompiledContext compileContext(RunContext context, String instructions, String userPrompt,
            MemoryContext memory) {
        var policy = context.manifest().contextPolicy().orElseThrow();
        RunPins pins = context.pins();
        ContextConstraints stableConstraints = new ContextConstraints(pins.workspaceId(), null,
                ContextClassification.INTERNAL, Set.of(ContextDestination.MODEL, ContextDestination.CACHE), false,
                "release");
        ContextConstraints dynamicConstraints = new ContextConstraints(pins.workspaceId(),
                context.invoker().map(invoker -> invoker.identity().delegatedSubjectId().toString()).orElse(null),
                ContextClassification.INTERNAL, Set.of(ContextDestination.MODEL), false, "run");
        List<ContextItem> stable = instructions == null ? List.of() : List.of(new ContextItem("agent-instructions",
                ContextItemType.SYSTEM_INSTRUCTION, instructions, estimateTokens(instructions), 1, 10_000, true,
                ContextLoadingPolicy.ALWAYS, 1,
                new ContextProvenance("agent-version", pins.versionId().toString(),
                        RunEventCodec.sha256(instructions), "release-pinned"), stableConstraints));
        ContextItem input = new ContextItem("run-input", ContextItemType.USER_INPUT, userPrompt,
                estimateTokens(userPrompt), 1, 10_000, true, ContextLoadingPolicy.ALWAYS, 0,
                new ContextProvenance("run", pins.id().toString(), RunEventCodec.sha256(userPrompt), "authenticated"),
                dynamicConstraints);
        StringBuilder dependencies = new StringBuilder(context.manifest().specDigest());
        context.manifest().pinnedSkills().forEach(skill -> dependencies.append('|').append(skill.contentDigest()));
        context.manifest().pinnedCapabilities().forEach(pack -> dependencies.append('|').append(pack.identity()));
        dependencies.append('|').append(memory.dependency());
        ContextCacheKey key = new ContextCacheKey(pins.workspaceId(), pins.releaseId(), pins.revisionId(),
                policy.policyHash(), RunEventCodec.sha256(dependencies.toString()));
        return contextCompiler.compile(new ContextCompilationRequest(pins.workspaceId(), key,
                new ContextBudget(policy.maximumTokens(), Map.of(ContextItemType.USER_INPUT,
                        Math.min(policy.maximumTokens(), input.estimatedTokens()))), stable,
                java.util.stream.Stream.concat(java.util.stream.Stream.of(input), memory.items().stream()).toList(), Set.of(),
                ContextDestination.MODEL, ContextClassification.CONFIDENTIAL, policy.cacheAware(),
                policy.compactionEnabled()));
    }

    private MemoryContext memoryContext(RunContext context, RunInvoker invoker, String prompt,
            CancellationSignal cancellation) {
        var policy = context.manifest().memoryPolicy().orElse(null);
        if (policy == null) return new MemoryContext(List.of(), "memory:none");
        RunPins pins = context.pins();
        UUID session = memoryStore == null ? null
                : memoryStore.logicalSessionId(pins.workspaceId(), pins.id()).orElse(null);
        MemoryScope memoryScope = new MemoryScope(pins.workspaceId(), pins.definitionId(), pins.id(), session);
        MemoryActor actor = new MemoryActor(invoker.identity().userId(), invoker.identity().delegatedSubjectId());
        List<ContextItem> items = new java.util.ArrayList<>();
        StringBuilder dependency = new StringBuilder(policy.policyHash());
        if (memoryStore != null && policy.conversationEnabled() && session != null) {
            List<MemoryRecord> records = memoryStore.read(actor,
                    new MemoryQuery(MemoryKind.CONVERSATION, memoryScope, policy.conversationItems(), true));
            dependency.append("|c:").append(memoryStore.revision(MemoryKind.CONVERSATION, memoryScope));
            records.forEach(record -> items.add(memoryItem(record, ContextItemType.CONVERSATION_MEMORY)));
        }
        if (memoryStore != null && policy.workingEnabled()) {
            List<MemoryRecord> records = memoryStore.read(actor,
                    new MemoryQuery(MemoryKind.WORKING, memoryScope, policy.workingItems(), true));
            dependency.append("|w:").append(memoryStore.revision(MemoryKind.WORKING, memoryScope));
            records.forEach(record -> items.add(memoryItem(record, ContextItemType.WORKING_MEMORY)));
        }
        if (policy.semanticEnabled()) {
            KnowledgeQuery query = new KnowledgeQuery(pins.workspaceId(), invoker.identity().delegatedSubjectId(), prompt,
                    policy.knowledgeItems(), policy.knowledgeTokens(), policy.knowledgeTimeout(),
                    ContextClassification.CONFIDENTIAL);
            int remainingItems = policy.knowledgeItems();
            int remainingTokens = policy.knowledgeTokens();
            for (KnowledgeProvider provider : knowledgeProviders.values()) {
                cancellation.throwIfCancelled();
                if (!provider.ready()) continue;
                List<ContextItem> found = retrieveKnowledge(provider, query, cancellation);
                for (ContextItem item : found) {
                    if (remainingItems == 0 || item.estimatedTokens() > remainingTokens) break;
                    if (!pins.workspaceId().equals(item.constraints().workspaceId())
                            || (item.constraints().subject() != null
                                    && !item.constraints().subject().equals(query.delegatedSubjectId().toString()))
                            || !item.constraints().destinations().contains(ContextDestination.MODEL)
                            || item.constraints().classification().ordinal() > query.maximumClassification().ordinal()) {
                        continue;
                    }
                    items.add(new ContextItem(item.id(), item.type(), item.content(), item.estimatedTokens(),
                            item.relevance(), item.priority(), item.mandatory(), ContextLoadingPolicy.ALWAYS,
                            item.cacheStability(), item.provenance(), item.constraints()));
                    remainingItems--;
                    remainingTokens -= item.estimatedTokens();
                    dependency.append('|').append(provider.id()).append(':').append(item.provenance().contentDigest());
                }
            }
        }
        return new MemoryContext(List.copyOf(items), RunEventCodec.sha256(dependency.toString()));
    }

    private static List<ContextItem> retrieveKnowledge(KnowledgeProvider provider, KnowledgeQuery query,
            CancellationSignal cancellation) {
        if (provider.id() == null || !provider.id().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new IllegalArgumentException("knowledge provider has an invalid id");
        }
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        Future<List<ContextItem>> future = executor.submit(() -> List.copyOf(
                provider.retrieve(query, cancellation::isCancelled)));
        try {
            return future.get(query.timeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            future.cancel(true);
            throw new com.dvarahq.agentworks.memory.KnowledgeRetrievalException("KNOWLEDGE_TIMEOUT",
                    "knowledge retrieval exceeded its pinned timeout");
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new com.dvarahq.agentworks.memory.KnowledgeRetrievalException("KNOWLEDGE_CANCELLED",
                    "knowledge retrieval was interrupted");
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("knowledge retrieval failed", failed.getCause());
        } finally {
            executor.shutdownNow();
        }
    }

    private record KnowledgeProviders(List<KnowledgeProvider> values) {
        private KnowledgeProviders {
            values = List.copyOf(Objects.requireNonNull(values, "values"));
        }
    }

    private static ContextItem memoryItem(MemoryRecord record, ContextItemType type) {
        String content = record.content().orElseThrow();
        return new ContextItem("memory:" + record.id(), type, content, estimateTokens(content), 0.7, 100, false,
                ContextLoadingPolicy.ALWAYS, 0,
                new ContextProvenance("memory", record.id().toString(), record.contentDigest(), "authenticated"),
                new ContextConstraints(record.scope().workspaceId(), null, ContextClassification.CONFIDENTIAL,
                        Set.of(ContextDestination.MODEL), false, record.expiresAt().toString()));
    }

    private void finalizeMemory(RunContext context, RunInvoker invoker, String prompt, Object output) {
        var policy = context.manifest().memoryPolicy().orElse(null);
        if (policy == null || memoryStore == null || (!policy.conversationEnabled() && !policy.workingEnabled())) return;
        RunPins pins = context.pins();
        UUID session = memoryStore.logicalSessionId(pins.workspaceId(), pins.id()).orElse(null);
        MemoryScope memoryScope = new MemoryScope(pins.workspaceId(), pins.definitionId(), pins.id(), session);
        List<MemoryWrite> writes = new java.util.ArrayList<>();
        if (policy.conversationEnabled() && session != null) {
            exchangeWrites(writes, "conversation", MemoryKind.CONVERSATION, memoryScope, pins, prompt, output,
                    policy.conversationRetention());
        }
        if (policy.workingEnabled()) {
            exchangeWrites(writes, "working", MemoryKind.WORKING, memoryScope, pins, prompt, output,
                    policy.workingRetention());
        }
        if (!writes.isEmpty()) memoryStore.commit(new MemoryActor(invoker.identity().userId(),
                invoker.identity().delegatedSubjectId()), writes);
    }

    private static void exchangeWrites(List<MemoryWrite> writes, String prefix, MemoryKind kind, MemoryScope scope,
            RunPins pins, String prompt, Object output, java.time.Duration retention) {
        writes.add(new MemoryWrite("memory:" + prefix + ":user", kind, scope, pins.releaseId(), pins.revisionId(),
                "user", prompt, retention));
        String response;
        try {
            response = JSON.writeValueAsString(output);
        } catch (JacksonException impossible) {
            throw new IllegalArgumentException("memory output is not serializable", impossible);
        }
        writes.add(new MemoryWrite("memory:" + prefix + ":assistant", kind, scope, pins.releaseId(), pins.revisionId(),
                "assistant", response, retention));
    }

    private record MemoryContext(List<ContextItem> items, String dependency) {}

    private static int estimateTokens(String content) {
        return Math.max(1, (content.codePointCount(0, content.length()) + 3) / 4);
    }

    /** Version 1/2 keep legacy callbacks, but delegated children still receive only their explicitly allowed subset. */
    private List<SelectedTool> selectedTools(RunContext context, ToolRunScope scope) {
        java.util.Set<String> delegated = delegatedTools(context.input());
        if (context.manifest().manifestVersion() < 3) return Arrays.stream(tools.getToolCallbacks())
                .filter(callback -> delegated == null || delegated.contains(callback.getToolDefinition().name()))
                .map(callback -> new SelectedTool(callback, callback.getToolDefinition().name(), Optional.empty(),
                        ToolInputSchema.compile(callback.getToolDefinition().inputSchema()), Optional.empty()))
                .toList();
        var resolved = context.resolvedCapabilities().orElseThrow(() -> new InvalidInputException(
                "CAPABILITY_RESOLUTION_REQUIRED", "version-3 releases require a captured capability resolution"));
        Map<ToolId, Optional<ToolEffect>> effects = new LinkedHashMap<>();
        context.manifest().pinnedCapabilities().forEach(pack -> pack.tools().forEach(tool ->
                effects.putIfAbsent(tool.id(), tool.effect())));
        List<SelectedTool> selected = new java.util.ArrayList<>();
        java.util.Set<String> names = new java.util.HashSet<>();
        for (var detail : resolved.disclosure().details()) {
            CapabilityToolCallback binding = capabilityTools.callback(detail.id());
            if (binding == null) {
                throw new InvalidInputException("CAPABILITY_BINDING_UNAVAILABLE",
                        "no runtime callback implements selected capability " + detail.id().value());
            }
            ToolCallback callback = binding.callback();
            if (callback instanceof RunBoundToolCallback scoped) callback = scoped.bind(scope);
            if (delegated != null && !delegated.contains(detail.id().value())
                    && !delegated.contains(callback.getToolDefinition().name())) continue;
            if ((callback instanceof CredentialedToolCallback) != binding.credentialName().isPresent()) {
                throw new InvalidInputException("CAPABILITY_BINDING_CONFLICT", "bound callback changed credential contract");
            }
            String schema = callback.getToolDefinition().inputSchema();
            if (!detail.schemaHash().equals(AgentTool.digest(schema))) {
                throw new InvalidInputException("CAPABILITY_BINDING_SCHEMA_DRIFT",
                        "runtime callback schema does not match selected capability " + detail.id().value());
            }
            if (!names.add(callback.getToolDefinition().name())) {
                throw new InvalidInputException("CAPABILITY_BINDING_CONFLICT",
                        "selected capability callbacks have a duplicate model tool name");
            }
            Optional<CredentialRef> credentialRef = binding.credentialName().map(name -> {
                CredentialRef reference = context.credentialReferences().get(name);
                if (reference == null) throw new InvalidInputException("CREDENTIAL_REFERENCE_REQUIRED",
                        "the pinned deployment does not bind the required tool credential");
                if (credentialInvoker == null) throw new InvalidInputException("CREDENTIAL_PROVIDER_UNAVAILABLE",
                        "the runtime has no JIT credential authority for a credentialed tool");
                return reference;
            });
            selected.add(new SelectedTool(callback, detail.id().value(),
                    effects.getOrDefault(detail.id(), Optional.empty()), ToolInputSchema.compile(schema), credentialRef));
        }
        return List.copyOf(selected);
    }

    private static java.util.Set<String> delegatedTools(JsonNode input) {
        JsonNode value = input == null ? null : input.get("allowedTools");
        if (value == null) return null;
        if (!value.isArray()) throw new InvalidInputException("DELEGATED_TOOLS_INVALID", "allowedTools must be an array");
        java.util.Set<String> result = new java.util.HashSet<>();
        value.forEach(tool -> {
            if (!tool.isString()) throw new InvalidInputException("DELEGATED_TOOLS_INVALID", "allowedTools entries must be strings");
            result.add(tool.stringValue());
        });
        return java.util.Set.copyOf(result);
    }

    private record SelectedTool(ToolCallback callback, String capability, Optional<ToolEffect> effect,
            ToolInputSchema inputSchema, Optional<CredentialRef> credentialRef) {
        private SelectedTool {
            Objects.requireNonNull(callback, "callback");
            Objects.requireNonNull(capability, "capability");
            effect = Objects.requireNonNull(effect, "effect");
            Objects.requireNonNull(inputSchema, "inputSchema");
            credentialRef = Objects.requireNonNull(credentialRef, "credentialRef");
        }
    }

    private record CapabilityBindings(Map<ToolId, CapabilityToolCallback> callbacks) {
        private CapabilityBindings {
            callbacks = Map.copyOf(callbacks);
        }

        private CapabilityToolCallback callback(ToolId id) {
            return callbacks.get(id);
        }
    }

    /** Summary entries enter model context without descriptions or schemas; DETAIL entries arrive as callbacks. */
    private static String capabilityInstructions(RunContext context, String instructions) {
        if (context.manifest().manifestVersion() < 3) return instructions;
        var resolved = context.resolvedCapabilities().orElseThrow(() -> new InvalidInputException(
                "CAPABILITY_RESOLUTION_REQUIRED", "version-3 releases require a captured capability resolution"));
        if (resolved.disclosure().summaries().isEmpty()) return instructions;
        StringBuilder summaries = new StringBuilder("Available capability summaries (schemas are not disclosed):");
        for (var summary : resolved.disclosure().summaries()) {
            summaries.append("\n- ").append(summary.id().value()).append(" [").append(summary.type()).append("] ")
                    .append(summary.summary()).append(" (schema ").append(summary.schemaHash()).append(')');
        }
        return instructions == null ? summaries.toString() : instructions + "\n\n" + summaries;
    }

    /** Delivers the stream to the continuation's thread one element at a time (demand of one: backpressure). */
    private static final class StreamConsumer extends BaseSubscriber<ChatClientResponse> implements AutoCloseable {
        private static final Object DONE = new Object();

        private record Failure(Throwable cause) {}

        private final BlockingQueue<Object> queue = new LinkedBlockingQueue<>();

        @Override
        protected void hookOnSubscribe(Subscription subscription) {
            request(1);
        }

        @Override
        protected void hookOnNext(ChatClientResponse value) {
            queue.add(value);
        }

        @Override
        protected void hookOnComplete() {
            queue.add(DONE);
        }

        @Override
        protected void hookOnError(Throwable failure) {
            queue.add(new Failure(failure));
        }

        /** The next response, or {@code null} at the end; rethrows the stream's failure. */
        ChatClientResponse next() throws InterruptedException {
            Object next = queue.take();
            if (next == DONE) return null;
            if (next instanceof Failure(Throwable cause)) {
                if (cause instanceof RuntimeException runtime) throw runtime;
                if (cause instanceof Error error) throw error;
                throw new IllegalStateException(cause);
            }
            request(1);
            return (ChatClientResponse) next;
        }

        @Override
        public void close() {
            dispose();
        }
    }
}
