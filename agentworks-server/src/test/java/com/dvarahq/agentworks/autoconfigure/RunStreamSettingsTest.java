package com.dvarahq.agentworks.autoconfigure;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Run event stream settings (issue #35): a stream re-checks its session without refreshing its activity time, so
 * startup refuses a maximum stream duration that could outlive the session idle timeout.
 */
class RunStreamSettingsTest {

    @Test
    void theDefaultsEndEveryStreamBeforeItsSessionCouldIdleOut() {
        var settings = AgentWorksLifecycleAutoConfiguration.streamSettings(new MockEnvironment());

        assertThat(settings.maxDuration()).isEqualTo(Duration.ofMinutes(10));
        assertThat(settings.maxStreamsPerWorkspace()).isEqualTo(128);
    }

    @Test
    void aMaximumDurationThatCouldOutliveTheSessionIdleTimeoutFailsStartup() {
        var tooLong = new MockEnvironment().withProperty("agentworks.runs.streams.max-duration", "PT30M");
        var shortSessions = new MockEnvironment().withProperty("agentworks.security.session.idle-timeout", "10m");
        var fits = new MockEnvironment().withProperty("agentworks.runs.streams.max-duration", "PT5M")
                .withProperty("agentworks.security.session.idle-timeout", "10m");

        assertThatThrownBy(() -> AgentWorksLifecycleAutoConfiguration.streamSettings(tooLong))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("max-duration");
        assertThatThrownBy(() -> AgentWorksLifecycleAutoConfiguration.streamSettings(shortSessions))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("idle-timeout");
        assertThat(AgentWorksLifecycleAutoConfiguration.streamSettings(fits).maxDuration())
                .isEqualTo(Duration.ofMinutes(5));
    }

    /**
     * #186: after the stream's {@code reconnect}, the client waits {@code client-retry} before it reconnects and
     * refreshes its session again, so that wait counts too.
     */
    @Test
    void theClientsRetryDelayCountsAgainstTheSessionIdleTimeout() {
        // 8m58s plus the 1m touch interval fits a 10m idle timeout, but not with the 3s client retry on top.
        var withoutRoomForTheRetry = new MockEnvironment().withProperty("agentworks.runs.streams.max-duration", "PT8M58S")
                .withProperty("agentworks.security.session.idle-timeout", "10m");
        var withRoom = new MockEnvironment().withProperty("agentworks.runs.streams.max-duration", "PT8M56S")
                .withProperty("agentworks.security.session.idle-timeout", "10m");

        assertThatThrownBy(() -> AgentWorksLifecycleAutoConfiguration.streamSettings(withoutRoomForTheRetry))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("client-retry");
        assertThat(AgentWorksLifecycleAutoConfiguration.streamSettings(withRoom).maxDuration())
                .isEqualTo(Duration.ofSeconds(536));
    }
}
