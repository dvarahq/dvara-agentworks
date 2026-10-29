package com.dvarahq.agentworks.agent.springai;

import com.dvarahq.agentworks.domain.run.RunPins;
import com.dvarahq.agentworks.gateway.CancellationSignal;
import com.dvarahq.agentworks.runplane.execution.DurableRunEngine;
import com.dvarahq.agentworks.runplane.execution.RunContext;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.core.Ordered;

import java.util.List;
import java.util.Objects;

/**
 * The executor's advisor chain, in one explicit order (master §16; regression-tested). A lower order is further out,
 * so it runs first on the way in:
 * <ol>
 *   <li>{@link RunCorrelationAdvisor} ({@link #CORRELATION}): once per exchange, binds the run, release, deployment,
 *       and trace identity into the request context.</li>
 *   <li>{@link ToolCallingAdvisor} ({@link #TOOL_CALLING}): Spring AI's tool loop, over exactly the continuation's
 *       recorded tools; no other tool can be resolved.</li>
 *   <li>{@link RunGuardAdvisor} ({@link #GUARD}): inside the tool loop, so before every model round it stops the
 *       continuation once it is cancelled or its cohort is suspended.</li>
 *   <li>the model: {@link GatewayChatModel}.</li>
 * </ol>
 * No memory advisor is ever part of the chain: conversation state comes only from the run's durable input, events,
 * and checkpoints (ADR-004), never from Spring AI chat memory.
 */
final class DvaraAdvisors {
    static final int CORRELATION = Ordered.HIGHEST_PRECEDENCE + 100;
    static final int TOOL_CALLING = ToolCallingAdvisor.DEFAULT_ORDER;
    static final int GUARD = Ordered.HIGHEST_PRECEDENCE + 400;

    /** Request-context keys the correlation advisor binds. */
    static final String RUN_ID = "agentworks.runId";
    static final String TRACE_ID = "agentworks.traceId";
    static final String RELEASE_ID = "agentworks.releaseId";
    static final String DEPLOYMENT_ID = "agentworks.deploymentId";

    private DvaraAdvisors() {
    }

    /** The chain for one continuation, in execution order. */
    static List<Advisor> chain(RunContext context, CancellationSignal continuation) {
        return chain(context, continuation, context::pauseIfRequested);
    }

    /**
     * The chain, pausing at the guard through {@code pauseIfRequested} (#183): the executor passes its recorder's, so
     * the check is ordered with the continuation's stream writes and its failures are the recorder's own.
     */
    static List<Advisor> chain(RunContext context, CancellationSignal continuation,
            java.util.function.BooleanSupplier pauseIfRequested) {
        ToolCallingAdvisor tools = ToolCallingAdvisor.builder()
                // Only the tools on the request are resolvable: a model cannot name any other tool into existence.
                .toolCallingManager(ToolCallingManager.builder().toolCallbackResolver(name -> null).build())
                .advisorOrder(TOOL_CALLING).build();
        return List.of(new RunCorrelationAdvisor(context.pins()), tools,
                new RunGuardAdvisor(context, continuation, pauseIfRequested));
    }

    /** Binds the run's correlation into the request context, once per exchange. */
    static final class RunCorrelationAdvisor implements BaseAdvisor {
        private final RunPins pins;

        RunCorrelationAdvisor(RunPins pins) {
            this.pins = Objects.requireNonNull(pins, "pins");
        }

        @Override
        public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
            return request.mutate().context(RUN_ID, pins.id().toString()).context(TRACE_ID, pins.traceId())
                    .context(RELEASE_ID, pins.releaseId().toString())
                    .context(DEPLOYMENT_ID, pins.deploymentId().toString()).build();
        }

        @Override
        public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
            return response;
        }

        @Override
        public String getName() {
            return "DvaraRunCorrelationAdvisor";
        }

        @Override
        public int getOrder() {
            return CORRELATION;
        }
    }

    /**
     * Stops a continuation at a safe boundary: before any model round once it is cancelled, its cohort is suspended, or
     * an operator asked for the run to pause (#183). A tool call of the previous round has already completed and been
     * recorded by then, so no effect is interrupted or rolled back.
     */
    static final class RunGuardAdvisor implements BaseAdvisor {
        /** The continuation stopped at a safe boundary to pause the run, for {@link #reason()}. */
        static final class SuspendedException extends RuntimeException {
            private final String reason;

            SuspendedException(String reason) {
                super("the run pauses: " + reason, null, false, false);
                this.reason = reason;
            }

            String reason() {
                return reason;
            }
        }

        private final RunContext context;
        private final CancellationSignal continuation;
        private final java.util.function.BooleanSupplier pauseIfRequested;

        RunGuardAdvisor(RunContext context, CancellationSignal continuation) {
            this(context, continuation, context::pauseIfRequested);
        }

        RunGuardAdvisor(RunContext context, CancellationSignal continuation,
                java.util.function.BooleanSupplier pauseIfRequested) {
            this.context = Objects.requireNonNull(context, "context");
            this.continuation = Objects.requireNonNull(continuation, "continuation");
            this.pauseIfRequested = Objects.requireNonNull(pauseIfRequested, "pauseIfRequested");
        }

        @Override
        public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
            continuation.throwIfCancelled();
            if (context.suspended()) throw new SuspendedException("COHORT_SUSPENDED");
            // Atomic check and pause (#183): a request a resume withdrew leaves the run active and the round proceeds.
            if (pauseIfRequested.getAsBoolean()) throw new SuspendedException(DurableRunEngine.OPERATOR_PAUSE);
            if (!request.context().containsKey(RUN_ID)) {
                throw new IllegalStateException("the run correlation advisor did not run before the guard");
            }
            return request;
        }

        @Override
        public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
            return response;
        }

        @Override
        public String getName() {
            return "DvaraRunGuardAdvisor";
        }

        @Override
        public int getOrder() {
            return GUARD;
        }
    }
}
