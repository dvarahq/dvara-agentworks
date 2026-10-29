package com.dvarahq.agentworks.testing;

import com.dvarahq.agentworks.testing.fake.FakeModel;
import java.math.BigDecimal;
import java.util.*;

/** Minimal model-only runtime useful for smoke tests; production runtimes plug in through {@link AgentTestRuntime}. */
public final class DefaultFakeRuntime implements AgentTestRuntime {
    @Override public AgentTestResult execute(AgentTestInvocation invocation) {
        FakeModel.Reply reply = invocation.fakes().model().invoke(new FakeModel.Request(invocation.userMessage(), invocation.seed()));
        var event = new AgentTestEvent(1, invocation.fakes().clock().instant(), AgentTestEvent.Type.MODEL, "response", Map.of("fake", true));
        var output = new AgentTestEvent(2, invocation.fakes().clock().instant(), AgentTestEvent.Type.OUTPUT, "answer", Map.of());
        return new AgentTestResult(AgentTestStatus.SUCCEEDED, reply.answer(), null,
                new AgentTestUsage(reply.inputTokens(), reply.outputTokens(), 0, BigDecimal.ZERO), List.of(event, output), invocation.fakes().versions());
    }
}
