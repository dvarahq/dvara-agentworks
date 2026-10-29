package com.dvarahq.agentworks.testing;

/** Entry point used by {@link AgentTest} classes. */
public final class AgentTests {
    private static final ThreadLocal<AgentFixture> CURRENT = new ThreadLocal<>();
    private AgentTests() {}
    public static AgentFixture agent() {
        AgentFixture fixture = CURRENT.get();
        if (fixture == null) throw new IllegalStateException("agent() requires a test class annotated with @AgentTest");
        return fixture.copy();
    }
    static void bind(AgentFixture fixture) {
        if (CURRENT.get() != null) throw new IllegalStateException("nested AgentTest context");
        CURRENT.set(fixture);
    }
    static void clear() { CURRENT.remove(); }
}
