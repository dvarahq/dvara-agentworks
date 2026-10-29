package com.dvarahq.agentworks.testing;

import org.junit.jupiter.api.extension.*;

/** Binds one isolated fixture to each annotated JUnit invocation. */
public final class AgentTestExtension implements BeforeEachCallback, AfterEachCallback {
    @Override public void beforeEach(ExtensionContext context) {
        AgentTest declaration = context.getRequiredTestClass().getAnnotation(AgentTest.class);
        if (declaration == null) throw new IllegalStateException("@AgentTest is required");
        AgentTests.bind(new AgentFixture(declaration.value(), declaration.seed(), context.getUniqueId()));
    }
    @Override public void afterEach(ExtensionContext context) { AgentTests.clear(); }
}
