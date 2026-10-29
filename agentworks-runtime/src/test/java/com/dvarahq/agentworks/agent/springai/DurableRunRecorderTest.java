package com.dvarahq.agentworks.agent.springai;

import com.dvarahq.agentworks.runplane.execution.RunEngineException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The recorder's drain and its failure marker (#34, #181). */
class DurableRunRecorderTest {

    // Review 4, item 7 (#34), deterministic since #181 round 2: a write still in progress when the continuation stops
    // recording makes close fail, so the continuation abandons its claim (LOST) instead of racing that write, and every
    // later write is refused as the recorder's own STALE_LEASE.
    @Test
    void aWriteStillInProgressAtCloseFailsTheDrainAndLaterWritesAreRefused() throws Exception {
        RecordingRunContext context = new RecordingRunContext();
        CountDownLatch release = new CountDownLatch(1);
        context.holdWrites = release;
        DurableRunRecorder recorder = new DurableRunRecorder(context, 64);
        Thread stream = Thread.ofVirtual().start(() -> recorder.toolStarted("tool-1", "slow"));
        assertThat(context.writing.await(10, TimeUnit.SECONDS)).isTrue();

        assertThat(recorder.close(Duration.ofMillis(100))).isEqualTo(DurableRunRecorder.Drain.WRITE_IN_PROGRESS);

        release.countDown();
        stream.join(Duration.ofSeconds(10));
        assertThatThrownBy(() -> recorder.toolStarted("tool-2", "late")).isInstanceOfSatisfying(
                RecordingFailedException.class, marked -> assertThat(((RunEngineException) marked.failure()).code())
                        .isEqualTo("STALE_LEASE"));
    }

    @Test
    void aDrainedRecorderClosesCleanly() {
        RecordingRunContext context = new RecordingRunContext();
        DurableRunRecorder recorder = new DurableRunRecorder(context, 64);
        recorder.toolStarted("tool-1", "fast");

        assertThat(recorder.close(Duration.ofMillis(100))).isEqualTo(DurableRunRecorder.Drain.DRAINED);
    }

    // #181 round 2, low: the marker is found at any depth of the cause and suppressed graph, and a cyclic graph ends.
    @Test
    void theMarkerIsFoundAnywhereInTheFailureGraphAndCyclesEnd() {
        RecordingFailedException marker = new RecordingFailedException(new RunEngineException("STALE_LEASE", "x"));
        RuntimeException suppressedDeep = new RuntimeException("suppressed holder");
        suppressedDeep.addSuppressed(marker);
        RuntimeException wrapper = new RuntimeException("cause of the top", suppressedDeep);
        RuntimeException top = new RuntimeException("top");
        top.addSuppressed(new IllegalStateException("unrelated"));
        top.addSuppressed(new IllegalStateException("carrier", wrapper));

        assertThat(SpringAiAgentExecutor.recordingFailure(top)).isSameAs(marker);

        RuntimeException first = new RuntimeException("first");
        RuntimeException second = new RuntimeException("second", first);
        first.addSuppressed(second);
        assertThat(SpringAiAgentExecutor.recordingFailure(first)).isNull();
        assertThat(SpringAiAgentExecutor.recordingFailure(null)).isNull();
    }
}
