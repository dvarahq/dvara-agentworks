package com.dvarahq.agentworks.agent.springai;

import com.dvarahq.agentworks.gateway.ModelInvocation;

import java.time.Duration;
import java.util.Objects;

/**
 * Immutable configuration of the Spring AI executor.
 *
 * @param modelTimeout    the deadline of one model invocation, streamed chunks included
 * @param chunkBatchChars streamed content is persisted as {@code MODEL_STREAM_CHUNK} events of at most this many
 *                        characters, so a stream costs a bounded number of durable writes
 * @param maxModelRounds  the most model invocations one continuation may make, tool rounds included
 * @param writeDrainTimeout how long a continuation that ends waits for a durable write already in progress on the
 *                        model stream's thread; when it does not finish in time, the claim is abandoned (LOST) and the
 *                        run is recovered, so the write can never race the continuation's own outcome
 */
public record SpringAiExecutorSettings(Duration modelTimeout, int chunkBatchChars, int maxModelRounds,
        Duration writeDrainTimeout) {
    public static final Duration DEFAULT_WRITE_DRAIN_TIMEOUT = Duration.ofSeconds(30);
    public static final SpringAiExecutorSettings DEFAULTS = new SpringAiExecutorSettings(Duration.ofMinutes(5), 2_048, 16);
    /** One event's delta stays well inside the engine's payload bound. */
    public static final int MAX_CHUNK_BATCH_CHARS = 16_384;

    public SpringAiExecutorSettings {
        Objects.requireNonNull(modelTimeout, "modelTimeout");
        if (modelTimeout.isNegative() || modelTimeout.isZero() || modelTimeout.compareTo(ModelInvocation.MAX_TIMEOUT) > 0) {
            throw new IllegalArgumentException("modelTimeout must be positive and at most " + ModelInvocation.MAX_TIMEOUT);
        }
        if (chunkBatchChars < 1 || chunkBatchChars > MAX_CHUNK_BATCH_CHARS) {
            throw new IllegalArgumentException("chunkBatchChars is 1 to " + MAX_CHUNK_BATCH_CHARS);
        }
        if (maxModelRounds < 1 || maxModelRounds > 256) {
            throw new IllegalArgumentException("maxModelRounds is 1 to 256");
        }
        Objects.requireNonNull(writeDrainTimeout, "writeDrainTimeout");
        if (writeDrainTimeout.isNegative() || writeDrainTimeout.isZero()) {
            throw new IllegalArgumentException("writeDrainTimeout must be positive");
        }
    }

    public SpringAiExecutorSettings(Duration modelTimeout, int chunkBatchChars, int maxModelRounds) {
        this(modelTimeout, chunkBatchChars, maxModelRounds, DEFAULT_WRITE_DRAIN_TIMEOUT);
    }
}
