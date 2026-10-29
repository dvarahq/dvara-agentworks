package com.dvarahq.agentworks.agent.springai;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class ToolRunScopeTest {
    @Test void closesAllResourcesInReverseOrderEvenWhenCleanupFails() throws Exception {
        var closed = new ArrayList<Integer>();
        var scope = new ToolRunScope(new RecordingRunContext());
        AutoCloseable first = scope.resource("first", () -> () -> { closed.add(1); throw new Exception("secret-canary"); });
        AutoCloseable reused = scope.resource("first", () -> { throw new AssertionError("duplicate acquisition"); });
        assertThat(reused).isSameAs(first);
        scope.resource("second", () -> () -> { closed.add(2); throw new Exception("second"); });
        assertThatThrownBy(scope::close).isInstanceOf(ToolAdapterException.class)
                .hasMessage("TOOL_RESOURCE_CLEANUP_FAILED").hasNoCause()
                .satisfies(failure -> assertThat(failure.getSuppressed()).isEmpty());
        assertThat(closed).isEqualTo(List.of(2, 1));
        scope.close();
        assertThatThrownBy(() -> scope.resource("new", () -> () -> {})).isInstanceOf(IllegalStateException.class);
        var interrupted = new ToolRunScope(new RecordingRunContext());
        interrupted.resource("interrupt", () -> () -> { throw new InterruptedException("secret-interrupt"); });
        try {
            assertThatThrownBy(interrupted::close).hasMessage("TOOL_RESOURCE_CLEANUP_FAILED").hasNoCause();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
        var recording = new ToolRunScope(new RecordingRunContext());
        var marker = new RecordingFailedException(new IllegalStateException("own recorder failure"));
        recording.resource("recording", () -> () -> { throw marker; });
        assertThatThrownBy(recording::close).isSameAs(marker);
    }
}
