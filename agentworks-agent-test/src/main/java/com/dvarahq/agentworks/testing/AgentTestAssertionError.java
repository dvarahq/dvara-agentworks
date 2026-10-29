package com.dvarahq.agentworks.testing;

public final class AgentTestAssertionError extends AssertionError {
    private final AgentTestMismatch mismatch;
    public AgentTestAssertionError(AgentTestMismatch mismatch) {
        super(mismatch.code() + ": expected <" + mismatch.expected() + "> but was <" + mismatch.actual() + ">");
        this.mismatch = mismatch;
    }
    public AgentTestMismatch mismatch() { return mismatch; }
}
