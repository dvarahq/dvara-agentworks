package com.dvarahq.agentworks.agent.springai;

import com.dvarahq.agentworks.gateway.ModelResponse;
import com.dvarahq.agentworks.gateway.ToolCall;
import com.dvarahq.agentworks.replay.ReplayException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GatewayReplayModelExecutorTest {
    @Test
    void refusesANewToolCallWithoutExecutingAnEffect() {
        ModelResponse response = mock(ModelResponse.class);
        when(response.toolCalls()).thenReturn(List.of(new ToolCall("call-1", "live-tool", "{}")));

        assertThatThrownBy(() -> GatewayReplayModelExecutor.refuseLiveEffect(response))
                .isInstanceOfSatisfying(ReplayException.class,
                        failure -> assertThat(failure.code()).isEqualTo("LIVE_EFFECT_FORBIDDEN"));
    }
}
